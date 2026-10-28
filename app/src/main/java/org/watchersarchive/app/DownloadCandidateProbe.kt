package org.watchersarchive.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.ReaderLimits

/**
 * Chooses an explicit offline transfer strategy without requiring online-reader
 * readiness. A 200 response is inspected by headers and immediately closed; its
 * body is consumed only by the separately requested whole-file download.
 */
internal class DownloadCandidateProbe(private val limits: ReaderLimits = ReaderLimits()) {
    suspend fun probe(record: CatalogSourceRecord, asset: SourceAsset): OfflineDownloadSpec {
        var current = safeSourceUrl(asset.candidateUrl)?.takeIf { it.length <= 16_384 }
            ?: throw ComicSourceException(SourceAssetStatus.UNAUTHORIZED, "下载来源地址无效。")
        repeat(6) { redirects ->
            currentCoroutineContext().ensureActive()
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.connectTimeout = limits.connectTimeoutMillis
            connection.readTimeout = limits.readTimeoutMillis
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; WatchersArchive/0.4)")
            val cancellation = currentCoroutineContext()[Job]?.onSourceCancellation { connection.disconnect() }
            try {
                val status = connection.responseCode
                currentCoroutineContext().ensureActive()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirects == 5) throw ComicSourceException(SourceAssetStatus.NEEDS_USER_ACTION, "下载来源跳转过多，请在来源页确认。")
                    val next = safeSourceUrl(connection.getHeaderField("Location"), current)
                        ?: throw ComicSourceException(SourceAssetStatus.UNAUTHORIZED, "来源返回了无效的下载地址。")
                    if (current.startsWith("https:") && !next.startsWith("https:")) {
                        throw ComicSourceException(SourceAssetStatus.UNAUTHORIZED, "下载来源返回了不安全的跳转。")
                    }
                    current = next
                    return@repeat
                }
                when (status) {
                    401, 403 -> throw ComicSourceException(SourceAssetStatus.UNAUTHORIZED, "这个来源暂未允许下载，请在来源页确认。")
                    404, 410 -> throw ComicSourceException(SourceAssetStatus.EXPIRED, "这个下载链接已失效，正在尝试其他来源。")
                    429 -> throw ComicSourceException(SourceAssetStatus.TRANSIENT_ERROR, "这个来源暂时限流或额度不足，正在尝试其他来源。")
                }
                if (status != 200 && status != 206) throw ComicSourceException(SourceAssetStatus.TRANSIENT_ERROR, "这个下载来源暂时不可用。")
                val encoding = connection.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
                    throw ComicSourceException(SourceAssetStatus.UNSUPPORTED_FORMAT, "来源没有提供原始漫画文件。")
                }
                if (looksLikePage(connection.getHeaderField("Content-Type"))) {
                    throw ComicSourceException(SourceAssetStatus.NEEDS_USER_ACTION, "这个来源仍是网页，需要在来源页完成操作。")
                }
                val length = if (status == 206) {
                    val range = Regex("bytes 0-0/([0-9]+)", RegexOption.IGNORE_CASE)
                        .matchEntire(connection.getHeaderField("Content-Range")?.trim().orEmpty())
                        ?: throw ComicSourceException(SourceAssetStatus.RANGE_UNSUPPORTED, "来源返回的文件范围不正确。")
                    val total = range.groupValues[1].toLongOrNull()
                        ?: throw ComicSourceException(SourceAssetStatus.UNSUPPORTED_FORMAT, "来源的文件大小无效。")
                    connection.getHeaderField("Content-Length")?.let {
                        if (it.toLongOrNull() != 1L) throw ComicSourceException(SourceAssetStatus.RANGE_UNSUPPORTED, "来源返回的下载长度不正确。")
                    }
                    checkedLength(total)
                    // Read no archive payload before all response bounds pass.
                    connection.inputStream.use { input ->
                        if (input.read() == -1 || input.read() != -1) {
                            throw ComicSourceException(SourceAssetStatus.TRANSIENT_ERROR, "来源的文件探测响应不完整。")
                        }
                    }
                    total
                } else {
                    // Deliberately never open inputStream for this 200 probe.
                    checkedLength(connection.getHeaderField("Content-Length")?.toLongOrNull())
                }
                currentCoroutineContext().ensureActive()
                val remoteTag = connection.getHeaderField("ETag")?.trim()?.takeIf(::strongTag)
                val rangeReady = status == 206 && remoteTag != null
                val filename = downloadName(connection.getHeaderField("Content-Disposition"))
                    ?: asset.fileName?.takeIf { it.isNotBlank() }
                    ?: URI(current).path.substringAfterLast('/').takeIf { it.isNotBlank() }
                return OfflineDownloadSpec(record.externalId, record.title, current,
                    remoteTag ?: "\"download-${UUID.randomUUID()}\"", length, filename,
                    if (rangeReady) OfflineTransferMode.RANGE else OfflineTransferMode.FULL,
                    remoteTag != null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (source: ComicSourceException) {
                throw source
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw ArchiveException(ArchiveError.NETWORK, "暂时无法连接这个下载来源。", failure)
            } finally {
                cancellation?.dispose()
                connection.disconnect()
            }
        }
        throw ComicSourceException(SourceAssetStatus.NEEDS_USER_ACTION, "下载来源跳转过多。")
    }

    private fun checkedLength(length: Long?): Long {
        if (length == null || length !in 22..limits.maxRemoteBytes) {
            throw ComicSourceException(SourceAssetStatus.UNSUPPORTED_FORMAT, "来源未提供可确认的漫画文件大小。")
        }
        return length
    }

    private fun looksLikePage(type: String?): Boolean = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.let {
        it.startsWith("text/") || it.contains("html") || it.contains("json") || it.contains("xml")
    } ?: false

    private fun strongTag(value: String): Boolean = value.length >= 2 && value.first() == '"' && value.last() == '"' &&
        value.substring(1, value.lastIndex).all { it.code >= 0x21 && it != '"' && it.code != 0x7f }

    private fun downloadName(disposition: String?): String? {
        if (disposition == null) return null
        val extended = Regex("(?i)filename\\*\\s*=\\s*UTF-8''([^;]+)").find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
        val plain = Regex("(?i)filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))").find(disposition)
            ?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.trim() }
        val name = extended?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() } ?: plain
        return name?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf {
            it.isNotBlank() && it.length <= 512 && it.none { character -> character.isISOControl() }
        }
    }
}
