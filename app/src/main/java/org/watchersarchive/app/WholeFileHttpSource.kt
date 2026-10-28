package org.watchersarchive.app

import org.watchersarchive.core.ArchiveByteSource
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CancellationToken
import org.watchersarchive.core.ReaderLimits
import org.watchersarchive.core.RemoteIdentity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Only the user's explicit whole-book operation can consume an HTTP 200 body. */
interface OfflineWholeFileTransfer {
    fun transferWhole(token: CancellationToken, sink: (ByteArray, Int, Int) -> Unit)
}

/** A non-resumable, bounded-memory transfer of one previously probed file. */
class WholeFileHttpSource(
    private val spec: OfflineDownloadSpec,
    private val limits: ReaderLimits = ReaderLimits(),
    private val bufferBytes: Int = 64 * 1024,
) : ArchiveByteSource, OfflineWholeFileTransfer {
    private class SinkFailure(cause: Throwable) : RuntimeException(cause)
    init {
        require(spec.mode == OfflineTransferMode.FULL)
        require(bufferBytes in 512..(64 * 1024))
        require(spec.length in 22..limits.maxRemoteBytes)
    }

    override fun discover(token: CancellationToken): RemoteIdentity {
        token.throwIfCancelled()
        // This identity came from the bounded candidate probe. The real GET
        // below checks length/type and, if supplied, the remote strong ETag again.
        return RemoteIdentity(spec.url, spec.length, spec.eTag, null)
    }

    override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
        token.throwIfCancelled()
        throw ArchiveException(ArchiveError.RANGE_NOT_SUPPORTED, "此来源只能完整下载后离线阅读")
    }

    override fun transferWhole(token: CancellationToken, sink: (ByteArray, Int, Int) -> Unit) {
        var current = URL(spec.url)
        repeat(6) { redirects ->
            token.throwIfCancelled()
            if (current.protocol !in setOf("http", "https") || current.userInfo != null || current.ref != null) {
                throw ArchiveException(ArchiveError.HTTP_ERROR, "文件地址无效")
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.connectTimeout = limits.connectTimeoutMillis
            connection.readTimeout = limits.readTimeoutMillis
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; WatchersArchive/0.8)")
            if (spec.verifyRemoteETag) connection.setRequestProperty("If-Match", spec.eTag)
            val registration = token.onCancel { connection.disconnect() }
            try {
                token.throwIfCancelled()
                val status = connection.responseCode
                token.throwIfCancelled()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirects == 5) throw ArchiveException(ArchiveError.HTTP_ERROR, "来源重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw ArchiveException(ArchiveError.HTTP_ERROR, "文件跳转缺少地址")
                    val next = URL(current, location)
                    if (current.protocol == "https" && next.protocol != "https") {
                        throw ArchiveException(ArchiveError.HTTP_ERROR, "拒绝不安全的文件跳转")
                    }
                    current = next
                    return@repeat
                }
                if (status == 412) throw ArchiveException(ArchiveError.OBJECT_CHANGED, "下载来源文件已变化")
                if (status != 200) throw ArchiveException(ArchiveError.HTTP_ERROR, "该来源暂时无法下载（HTTP $status）")
                val encoding = connection.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
                    throw ArchiveException(ArchiveError.INVALID_LENGTH, "下载响应内容编码不受支持")
                }
                val type = connection.getHeaderField("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                if (type.startsWith("text/") || type.contains("html") || type.contains("json") || type.contains("xml")) {
                    throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "来源返回了网页或验证信息，未保存为漫画")
                }
                if (connection.getHeaderField("Content-Length")?.toLongOrNull() != spec.length) {
                    throw ArchiveException(ArchiveError.OBJECT_CHANGED, "下载来源文件大小已变化，请重新取得来源")
                }
                if (spec.verifyRemoteETag) {
                    val tag = connection.getHeaderField("ETag")?.trim()
                    if (tag == null || !strongTag(tag)) throw ArchiveException(ArchiveError.IDENTITY_REQUIRED, "来源没有返回已验证的文件版本")
                    if (tag != spec.eTag) throw ArchiveException(ArchiveError.OBJECT_CHANGED, "下载来源文件版本已变化")
                }
                val buffer = ByteArray(minOf(bufferBytes.toLong(), spec.length).toInt())
                var consumed = 0L
                connection.inputStream.use { input ->
                    // Inspect a small prefix before exposing it to the provisional
                    // file sink. A mislabeled HTML quota/error page is not a comic.
                    val prefixSize = minOf(512L, spec.length).toInt()
                    var prefixRead = 0
                    while (prefixRead < prefixSize) {
                        token.throwIfCancelled()
                        val count = input.read(buffer, prefixRead, prefixSize - prefixRead)
                        if (count < 0) throw ArchiveException(ArchiveError.INVALID_LENGTH, "完整下载被截断")
                        if (count == 0) continue
                        prefixRead += count
                    }
                    inspectPrefix(buffer, prefixRead)
                    emit(sink, buffer, prefixRead)
                    consumed += prefixRead
                    while (consumed < spec.length) {
                        token.throwIfCancelled()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), spec.length - consumed).toInt())
                        if (count < 0) throw ArchiveException(ArchiveError.INVALID_LENGTH, "完整下载被截断")
                        if (count == 0) continue
                        token.throwIfCancelled()
                        emit(sink, buffer, count)
                        consumed += count
                    }
                    token.throwIfCancelled()
                    if (input.read() != -1) throw ArchiveException(ArchiveError.INVALID_LENGTH, "下载响应超过已确认的文件大小")
                }
                token.throwIfCancelled()
                return
            } catch (failure: SinkFailure) {
                throw checkNotNull(failure.cause)
            } catch (failure: ArchiveException) {
                if (token.isCancelled && failure.code != ArchiveError.CANCELLED) {
                    throw ArchiveException(ArchiveError.CANCELLED, "下载已取消", failure)
                }
                throw failure
            } catch (failure: IOException) {
                throw ArchiveException(if (token.isCancelled) ArchiveError.CANCELLED else ArchiveError.NETWORK,
                    if (token.isCancelled) "下载已取消" else "文件下载连接中断", failure)
            } finally {
                registration.close()
                connection.disconnect()
            }
        }
        throw ArchiveException(ArchiveError.HTTP_ERROR, "来源重定向次数过多")
    }

    private fun emit(sink: (ByteArray, Int, Int) -> Unit, bytes: ByteArray, length: Int) {
        try { sink(bytes, 0, length) } catch (failure: Throwable) { throw SinkFailure(failure) }
    }

    private fun inspectPrefix(bytes: ByteArray, count: Int) {
        checkDownloadArchiveHeader(bytes.copyOfRange(0, minOf(count, 32)))
        val text = String(bytes, 0, count, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t').lowercase(Locale.ROOT)
        if (text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("<head") || text.startsWith("<body") || text.startsWith("{\"error\"")) {
            throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "来源返回了网页或错误信息，未保存为漫画")
        }
        val rar5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)
        if (count >= rar5.size && rar5.indices.all { bytes[it] == rar5[it] }) {
            throw ArchiveException(ArchiveError.UNSUPPORTED_ARCHIVE, "当前版本暂不支持 RAR5 漫画")
        }
    }

    private fun strongTag(tag: String): Boolean = tag.length >= 2 && tag.first() == '"' && tag.last() == '"' &&
        tag.substring(1, tag.lastIndex).all { it.code >= 0x21 && it != '"' && it.code != 0x7f }
}
