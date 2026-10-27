package org.watchersarchive.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * Resolves normal source redirects, never CAPTCHA, form submissions, login or an
 * advertising interstitial. Header probes never open a file response body.
 */
internal class SourceResolver(
    private val probe: suspend (String) -> SourceHeaderReply = ::probeSourceHeaders,
    private val metadata: suspend (String) -> String = { SourceHttpClient.text(it, 64 * 1024) },
) {
    suspend fun resolve(asset: SourceAsset): SourceAsset {
        var current = safeSourceUrl(asset.candidateUrl)
            ?: return asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "文件来源地址无效。")
        val visited = mutableSetOf<String>()
        repeat(6) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(current)) return asset.copy(status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "来源链接发生循环跳转，请稍后再试。")
            val uri = URI(current)
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            // Public endpoint documented by Pixeldrain's own API client:
            // https://github.com/Fornaxian/pixeldrain_api_client/blob/master/pixelapi/file.go
            val pixelId = if (host == "pixeldrain.com" || host == "www.pixeldrain.com")
                Regex("^/u/([A-Za-z0-9_-]{4,64})/?$").matchEntire(uri.path.orEmpty())?.groupValues?.get(1) else null
            if (pixelId != null) {
                try {
                    val info = JSONObject(metadata("https://pixeldrain.com/api/file/$pixelId/info"))
                    if (info.optString("id") != pixelId || !info.optBoolean("can_download", false)) {
                        return asset.copy(status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "Pixeldrain 当前未允许公开下载，请在来源页查看。")
                    }
                    val availability = info.optString("availability")
                    if (availability.isNotBlank() && availability != "available") {
                        return asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "Pixeldrain 文件当前不可用。")
                    }
                    return asset.copy(candidateUrl = "https://pixeldrain.com/api/file/$pixelId", host = "pixeldrain.com",
                        fileName = info.optString("name").takeIf(String::isNotBlank),
                        declaredSize = info.optLong("size").takeIf { size -> size > 0 }?.toString(),
                        status = SourceAssetStatus.METADATA_ONLY, reason = "已取得来源允许的公开文件地址，正在检查漫画内容。")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    return asset.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = "暂时无法连接 Pixeldrain，请稍后重试。")
                }
            }
            if (!uri.path.orEmpty().startsWith("/dls/")) {
                if (isKnownLanding(uri)) return asset.copy(candidateUrl = current, host = host,
                    status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "该下载来源需要在其正常页面完成操作，暂不能直接在线阅读。")
                return asset.copy(candidateUrl = current, host = host, fileName = uri.path.substringAfterLast('/').takeIf(String::isNotBlank),
                    status = SourceAssetStatus.METADATA_ONLY, reason = "正在检查来源文件。")
            }
            val reply = try { probe(current) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                return asset.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = "暂时无法连接下载来源。")
            }
            if (reply.status in setOf(301, 302, 303, 307, 308)) {
                val next = safeSourceUrl(reply.location, current)
                    ?: return asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "来源返回了无效或不安全的跳转。")
                if (current.startsWith("https:") && !next.startsWith("https:")) {
                    return asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "来源跳转不安全，已停止读取。")
                }
                current = next
                return@repeat
            }
            return when (reply.status) {
                206 -> asset.copy(candidateUrl = current, host = host, status = SourceAssetStatus.METADATA_ONLY,
                    reason = "已取得文件响应，正在检查漫画内容。")
                200 -> asset.copy(status = if (reply.contentType.orEmpty().contains("html", true)) SourceAssetStatus.NEEDS_USER_ACTION else SourceAssetStatus.RANGE_UNSUPPORTED,
                    reason = if (reply.contentType.orEmpty().contains("html", true)) "该来源仍需在网页中完成操作，暂不能直接在线阅读。" else "此来源不支持按页读取，未下载完整文件。")
                401, 403 -> asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "该下载来源拒绝了访问，请稍后重试或选择其他条目。")
                404, 410 -> asset.copy(status = SourceAssetStatus.EXPIRED, reason = "该下载链接已失效。")
                429 -> asset.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = "来源请求过于频繁，请稍后重试。")
                else -> asset.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = "下载来源暂时不可用（HTTP ${reply.status}）。")
            }
        }
        return asset.copy(status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "来源跳转次数过多，暂不能直接在线阅读。")
    }

    private fun isKnownLanding(uri: URI): Boolean {
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val hasArchiveExtension = Regex("(?i)\\.(cbz|zip|cbr|rar)$").containsMatchIn(uri.path.orEmpty())
        if (hasArchiveExtension) return false
        return listOf("vikingfile.com", "vik1ngfile.site", "datanodes.to", "terabox.com", "1024terabox.com")
            .any { host == it || host.endsWith(".$it") }
    }
}

internal data class SourceHeaderReply(val status: Int, val location: String? = null, val contentType: String? = null)

private suspend fun probeSourceHeaders(url: String): SourceHeaderReply {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Range", "bytes=0-0")
    connection.setRequestProperty("Accept-Encoding", "identity")
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; WatchersArchive/0.4)")
    val cancellation = currentCoroutineContext()[Job]?.onSourceCancellation { connection.disconnect() }
    try {
        currentCoroutineContext().ensureActive()
        return SourceHeaderReply(connection.responseCode, connection.getHeaderField("Location"), connection.getHeaderField("Content-Type"))
    } finally {
        cancellation?.dispose()
        connection.disconnect()
    }
}
