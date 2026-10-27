package org.watchersarchive.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The only network primitive used by the ZIP reader. It never consumes a 200 response body. */
class HttpRangeSource @JvmOverloads constructor(
    private val url: String,
    private val limits: ReaderLimits = ReaderLimits(),
    private val observer: (RangeTrace) -> Unit = {},
) : ArchiveByteSource {
    private data class Response(val bytes: ByteArray, val identity: RemoteIdentity)

    override fun discover(token: CancellationToken): RemoteIdentity =
        fetch(url, null, 0, 1, token).identity

    override fun readRange(
        identity: RemoteIdentity,
        start: Long,
        length: Int,
        token: CancellationToken,
    ): ByteArray {
        if (start < 0 || length <= 0 || start > identity.length - length.toLong()) {
            throw ArchiveException(ArchiveError.INVALID_LENGTH, "字节范围超出文件边界")
        }
        return fetch(identity.url, identity, start, length, token).bytes
    }

    private fun fetch(
        initialUrl: String,
        expected: RemoteIdentity?,
        start: Long,
        length: Int,
        token: CancellationToken,
    ): Response {
        if (length <= 0 || length > limits.maxRangeBytes || start < 0 || start > Long.MAX_VALUE - length) {
            throw ArchiveException(ArchiveError.LIMIT_EXCEEDED, "单次范围请求超出限制")
        }
        val end = start + length - 1
        var currentUrl = URL(initialUrl)
        repeat(6) { redirectCount ->
            token.throwIfCancelled()
            if (currentUrl.protocol !in setOf("http", "https") || currentUrl.userInfo != null) {
                throw ArchiveException(ArchiveError.HTTP_ERROR, "仅允许不含凭据的 HTTP/HTTPS 地址")
            }
            val requestUrl = currentUrl.toExternalForm()
            val connection = currentUrl.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = limits.connectTimeoutMillis
            connection.readTimeout = limits.readTimeoutMillis
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=$start-$end")
            connection.setRequestProperty("User-Agent", "WatchersArchive-Phase1/1.0")
            expected?.let { connection.setRequestProperty("If-Match", it.eTag) }
            var status: Int? = null
            var contentRange: String? = null
            var received = 0L
            var outcome = "未完成"
            val registration = token.onCancel { connection.disconnect() }
            try {
                token.throwIfCancelled()
                status = connection.responseCode
                contentRange = connection.getHeaderField("Content-Range")
                token.throwIfCancelled()
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirectCount == 5) throw ArchiveException(ArchiveError.HTTP_ERROR, "重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw ArchiveException(ArchiveError.HTTP_ERROR, "重定向缺少 Location")
                    val redirected = URL(currentUrl, location)
                    if (currentUrl.protocol == "https" && redirected.protocol != "https") {
                        throw ArchiveException(ArchiveError.HTTP_ERROR, "拒绝 HTTPS 降级重定向")
                    }
                    currentUrl = redirected
                    outcome = "重定向；未读取正文"
                    return@repeat
                }
                if (status == 412) throw ArchiveException(ArchiveError.OBJECT_CHANGED, "远程文件版本已改变，请重新打开")
                if (status == 200) throw ArchiveException(ArchiveError.RANGE_NOT_SUPPORTED, "来源忽略 Range；已拒绝完整响应")
                if (status == 416) throw ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "来源拒绝了请求范围")
                if (status != 206) throw ArchiveException(ArchiveError.HTTP_ERROR, "范围请求返回 HTTP $status")
                val encoding = connection.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) {
                    throw ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "范围响应使用了外层内容编码")
                }
                val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
                    .matchEntire(contentRange?.trim() ?: "")
                    ?: throw ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "缺少有效 Content-Range")
                val responseStart = match.groupValues[1].toLongOrNull()
                val responseEnd = match.groupValues[2].toLongOrNull()
                val total = match.groupValues[3].toLongOrNull()
                if (responseStart != start || responseEnd != end || total == null || total <= end ||
                    total > limits.maxRemoteBytes || (expected != null && total != expected.length)) {
                    throw ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "Content-Range 与请求或文件长度不一致")
                }
                val declaredLength = connection.getHeaderField("Content-Length")
                if (declaredLength != null && declaredLength.toLongOrNull() != length.toLong()) {
                    throw ArchiveException(ArchiveError.INVALID_LENGTH, "范围响应 Content-Length 不正确")
                }
                val tag = connection.getHeaderField("ETag")?.trim()
                if (tag == null || !isStrongTag(tag)) {
                    throw ArchiveException(ArchiveError.IDENTITY_REQUIRED, "缺少强 ETag，暂不能安全分页读取")
                }
                if (expected != null && tag != expected.eTag) {
                    throw ArchiveException(ArchiveError.OBJECT_CHANGED, "远程 ETag 已改变，请重新打开")
                }
                val identity = RemoteIdentity(currentUrl.toExternalForm(), total, tag, connection.getHeaderField("Last-Modified"))
                val bytes = ByteArray(length)
                // Deliberately obtain the stream only after status, range, size and identity validation.
                connection.inputStream.use { input ->
                    while (received < length) {
                        token.throwIfCancelled()
                        val count = input.read(bytes, received.toInt(), minOf(16 * 1024, length - received.toInt()))
                        if (count < 0) throw ArchiveException(ArchiveError.INVALID_LENGTH, "范围响应被截断 ($received/$length)")
                        if (count == 0) continue
                        received += count
                    }
                    token.throwIfCancelled()
                    if (input.read() != -1) {
                        received++
                        throw ArchiveException(ArchiveError.INVALID_LENGTH, "范围响应超过请求长度")
                    }
                }
                token.throwIfCancelled()
                outcome = "成功"
                return Response(bytes, identity)
            } catch (e: ArchiveException) {
                val translated = if (token.isCancelled && e.code != ArchiveError.CANCELLED)
                    ArchiveException(ArchiveError.CANCELLED, "读取已取消", e) else e
                outcome = "${translated.code}: ${translated.message}"
                throw translated
            } catch (e: IOException) {
                val translated = ArchiveException(
                    if (token.isCancelled) ArchiveError.CANCELLED else ArchiveError.NETWORK,
                    if (token.isCancelled) "读取已取消" else "范围请求失败：${e.message}", e,
                )
                outcome = "${translated.code}: ${translated.message}"
                throw translated
            } finally {
                registration.close()
                connection.disconnect()
                runCatching { observer(RangeTrace(requestUrl, start, end, status, contentRange, received, outcome)) }
            }
        }
        throw ArchiveException(ArchiveError.HTTP_ERROR, "重定向次数过多")
    }

    private fun isStrongTag(tag: String): Boolean = tag.length >= 2 && tag.first() == '"' &&
        tag.last() == '"' && tag.drop(1).dropLast(1).all { it.code >= 0x21 && it != '"' && it.code != 0x7f }
}
