package org.watchersarchive.app

import org.watchersarchive.core.ArchiveByteSource
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CancellationToken
import org.watchersarchive.core.HttpRangeSource
import org.watchersarchive.core.RangeTrace
import org.watchersarchive.core.ReaderLimits
import org.watchersarchive.core.RemoteIdentity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Explicit offline downloads can stream a larger range without allocating its body. */
interface OfflineRangeTransfer {
    val maxTransferBytes: Int

    /** The sink is provisional until this method returns after exact-length validation. */
    fun transferRange(
        identity: RemoteIdentity,
        start: Long,
        length: Int,
        token: CancellationToken,
        sink: (bytes: ByteArray, offset: Int, count: Int) -> Unit,
    )
}

/**
 * Only the explicit whole-book downloader uses the streaming method. Ordinary
 * ArchiveByteSource reads retain the reader's original maxRangeBytes boundary.
 */
open class StreamingHttpRangeSource(
    private val url: String,
    private val limits: ReaderLimits = ReaderLimits(),
    override val maxTransferBytes: Int = 16 * 1024 * 1024,
    private val bufferBytes: Int = 64 * 1024,
    private val observer: (RangeTrace) -> Unit = {},
) : ArchiveByteSource, OfflineRangeTransfer {
    private val bounded = HttpRangeSource(url, limits, observer)
    private class SinkFailure(cause: Throwable) : RuntimeException(cause)

    init {
        require(maxTransferBytes in 1..(16 * 1024 * 1024))
        require(bufferBytes in 1..(64 * 1024))
    }

    override fun discover(token: CancellationToken): RemoteIdentity = bounded.discover(token)

    override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray =
        bounded.readRange(identity, start, length, token)

    override fun transferRange(
        identity: RemoteIdentity,
        start: Long,
        length: Int,
        token: CancellationToken,
        sink: (bytes: ByteArray, offset: Int, count: Int) -> Unit,
    ) {
        if (length !in 1..maxTransferBytes || start < 0 || start > identity.length - length.toLong() ||
            identity.length < 22 || identity.length > limits.maxRemoteBytes) {
            throw ArchiveException(ArchiveError.LIMIT_EXCEEDED, "离线下载范围超出限制")
        }
        if (!strongTag(identity.eTag)) throw ArchiveException(ArchiveError.IDENTITY_REQUIRED, "来源缺少可靠的版本标识")
        val end = start + length - 1L
        var current = URL(identity.url)
        repeat(6) { redirects ->
            token.throwIfCancelled()
            if (current.protocol !in setOf("http", "https") || current.userInfo != null) {
                throw ArchiveException(ArchiveError.HTTP_ERROR, "仅允许不含凭据的 HTTP/HTTPS 地址")
            }
            val requestedUrl = current.toExternalForm()
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.connectTimeout = limits.connectTimeoutMillis
            connection.readTimeout = limits.readTimeoutMillis
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=$start-$end")
            connection.setRequestProperty("If-Match", identity.eTag)
            connection.setRequestProperty("User-Agent", "WatchersArchive-Phase1/1.0")
            val cancellation = token.onCancel { connection.disconnect() }
            var status: Int? = null
            var range: String? = null
            var consumed = 0L
            var outcome = "未完成"
            try {
                token.throwIfCancelled()
                status = connection.responseCode
                range = connection.getHeaderField("Content-Range")
                token.throwIfCancelled()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirects == 5) throw ArchiveException(ArchiveError.HTTP_ERROR, "重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw ArchiveException(ArchiveError.HTTP_ERROR, "重定向缺少地址")
                    val next = URL(current, location)
                    if (current.protocol == "https" && next.protocol != "https") {
                        throw ArchiveException(ArchiveError.HTTP_ERROR, "拒绝 HTTPS 降级重定向")
                    }
                    current = next
                    outcome = "重定向；未读取正文"
                    return@repeat
                }
                if (status == 412) throw ArchiveException(ArchiveError.OBJECT_CHANGED, "下载来源文件已变化")
                // Never obtain an inputStream for an ignored Range/full response.
                if (status == 200) throw ArchiveException(ArchiveError.RANGE_NOT_SUPPORTED, "来源忽略 Range，已拒绝完整响应")
                if (status == 416) throw ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "来源拒绝了下载范围")
                if (status != 206) throw ArchiveException(ArchiveError.HTTP_ERROR, "下载范围暂时不可用")
                val encoding = connection.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
                    throw ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "下载响应使用了外层内容编码")
                }
                val parsed = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
                    .matchEntire(range?.trim().orEmpty())
                    ?: throw ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "缺少有效下载范围")
                val responseStart = parsed.groupValues[1].toLongOrNull()
                val responseEnd = parsed.groupValues[2].toLongOrNull()
                val total = parsed.groupValues[3].toLongOrNull()
                if (responseStart != start || responseEnd != end || total == null || total != identity.length || total <= end) {
                    throw ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "下载范围或文件长度不一致")
                }
                connection.getHeaderField("Content-Length")?.let { declared ->
                    if (declared.toLongOrNull() != length.toLong()) {
                        throw ArchiveException(ArchiveError.INVALID_LENGTH, "下载范围长度不正确")
                    }
                }
                val tag = connection.getHeaderField("ETag")?.trim()
                if (tag == null || !strongTag(tag)) throw ArchiveException(ArchiveError.IDENTITY_REQUIRED, "来源缺少可靠的版本标识")
                if (tag != identity.eTag) throw ArchiveException(ArchiveError.OBJECT_CHANGED, "下载来源文件已变化")
                val buffer = ByteArray(minOf(bufferBytes, length))
                // All headers and the immutable version are checked before any
                // archive body byte is exposed to the provisional file sink.
                connection.inputStream.use { input ->
                    while (consumed < length) {
                        token.throwIfCancelled()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - consumed).toInt())
                        if (count < 0) throw ArchiveException(ArchiveError.INVALID_LENGTH, "下载范围被截断")
                        if (count == 0) continue
                        consumed += count
                        try { sink(buffer, 0, count) }
                        catch (failure: Throwable) { throw SinkFailure(failure) }
                    }
                    token.throwIfCancelled()
                    if (input.read() != -1) {
                        consumed++
                        throw ArchiveException(ArchiveError.INVALID_LENGTH, "下载范围超过预期长度")
                    }
                }
                token.throwIfCancelled()
                outcome = "成功"
                return
            } catch (failure: SinkFailure) {
                // A disk/write/progress callback failure is not a retryable
                // network error. Preserve it for the engine's rollback path.
                outcome = "本地写入中止"
                throw checkNotNull(failure.cause)
            } catch (failure: ArchiveException) {
                val translated = if (token.isCancelled && failure.code != ArchiveError.CANCELLED)
                    ArchiveException(ArchiveError.CANCELLED, "下载已取消", failure) else failure
                outcome = translated.code.toString()
                throw translated
            } catch (failure: IOException) {
                val translated = ArchiveException(if (token.isCancelled) ArchiveError.CANCELLED else ArchiveError.NETWORK,
                    if (token.isCancelled) "下载已取消" else "下载连接中断", failure)
                outcome = translated.code.toString()
                throw translated
            } finally {
                cancellation.close()
                connection.disconnect()
                runCatching { observer(RangeTrace(requestedUrl, start, end, status, range, consumed, outcome)) }
            }
        }
        throw ArchiveException(ArchiveError.HTTP_ERROR, "来源重定向次数过多")
    }

    private fun strongTag(tag: String) = tag.length >= 2 && tag.first() == '"' && tag.last() == '"' &&
        tag.substring(1, tag.lastIndex).all { it.code >= 0x21 && it != '"' && it.code != 0x7f }
}
