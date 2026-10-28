package org.watchersarchive.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * Resolves normal source redirects, never CAPTCHA, form submissions, login or an
 * advertising interstitial. Header probes never open a file response body.
 */
internal class SourceResolver(
    private val probe: suspend (String) -> SourceHeaderReply = ::probeSourceHeaders,
    private val metadata: suspend (String) -> String = { SourceHttpClient.text(it, 64 * 1024) },
    private val page: suspend (String) -> String = { SourceHttpClient.text(it, 512 * 1024) },
) {
    suspend fun resolve(asset: SourceAsset): SourceAsset {
        var current = safeSourceUrl(asset.candidateUrl)
            ?: return asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "文件来源地址无效。")
        val visited = mutableSetOf<String>()
        var resolvedName = asset.fileName?.takeIf { archiveFormat(it) != null }
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
                    val payload = metadata("https://pixeldrain.com/api/file/$pixelId/info")
                    require(payload.length <= 64 * 1024 && payload.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
                    val info = JSONObject(payload)
                    if (info.optString("id") != pixelId) {
                        return asset.copy(status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "Pixeldrain 未返回对应文件的信息，请在来源页确认。")
                    }
                    if (!info.optBoolean("can_download", false)) {
                        val explanation = listOf("message", "error", "value", "availability_message").joinToString(" ") { info.optString(it) }.lowercase(Locale.ROOT)
                        val quota = listOf("download_limit", "transfer_limit", "bandwidth_limit", "download limit", "transfer limit", "daily limit").any(explanation::contains)
                        return asset.copy(status = SourceAssetStatus.NEEDS_USER_ACTION,
                            reason = if (quota) "Pixeldrain 提示当前传输额度受限，请等待额度恢复或使用来源允许的方式。"
                            else "Pixeldrain 当前未允许公开下载，请在来源页查看。")
                    }
                    val availability = info.optString("availability")
                    if (availability.isNotBlank() && availability != "available") {
                        return asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "Pixeldrain 文件当前不可用。")
                    }
                    // A public file can be speed limited without download permission being denied.
                    val name = cleanFileName(info.optString("name"))
                    return asset.copy(candidateUrl = "https://pixeldrain.com/api/file/$pixelId", host = "pixeldrain.com",
                        fileName = name, declaredFormat = name?.let(::archiveFormat) ?: asset.declaredFormat,
                        declaredSize = info.optLong("size").takeIf { size -> size > 0 }?.toString(),
                        status = SourceAssetStatus.METADATA_ONLY, reason = "已取得来源允许的公开文件地址，正在检查漫画内容。")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    return asset.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = "暂时无法连接 Pixeldrain，请稍后重试。")
                }
            }
            if (isKnownLanding(uri)) return asset.copy(candidateUrl = current, host = host,
                status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "该下载来源需要在其正常页面完成操作，请选择其他来源或在浏览器下载后导入。")
            val reply = try { probe(current) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                return asset.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = "暂时无法连接下载来源。")
            }
            if (reply.status in setOf(301, 302, 303, 307, 308)) {
                val next = safeSourceUrl(reply.location, current)
                    ?: return asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "来源返回了无效或不安全的跳转。")
                if (uri.scheme.equals("https", true) && !URI(next).scheme.equals("https", true)) {
                    return asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "来源跳转不安全，已停止读取。")
                }
                current = next
                return@repeat
            }
            if (reply.status in setOf(200, 206) && isMediaFireShare(uri) && (isHtml(reply.contentType) || reply.contentType.isNullOrBlank())) {
                val html = try { page(current) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    return asset.copy(candidateUrl = current, host = host,
                        status = SourceAssetStatus.TRANSIENT_ERROR, reason = "暂时无法读取 MediaFire 的公开下载页面。")
                }
                if (html.length > 512 * 1024 || html.toByteArray(Charsets.UTF_8).size > 512 * 1024) {
                    return asset.copy(status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "MediaFire 页面超出读取上限，请在来源页查看。")
                }
                val document = Jsoup.parse(html, current)
                val gated = document.select("#challenge-form,#cf-challenge-running,.g-recaptcha,.h-captcha,[data-sitekey],iframe[src*=recaptcha],iframe[src*=hcaptcha]").isNotEmpty() ||
                    Regex("(?i)verify (?:that )?you are human|checking your browser|sign in to continue|login required|premium (?:account )?required")
                        .containsMatchIn(document.title() + " " + document.select("main,h1,h2").text())
                if (gated) return asset.copy(candidateUrl = current, host = host,
                    status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "MediaFire 需要登录、验证或其他正常网页操作，请选择其他来源。")
                val button = document.selectFirst("a#downloadButton[href]")
                val direct = button?.attr("href")?.let { safeSourceUrl(it, current) }
                val directHost = direct?.let { URI(it).host.orEmpty().lowercase(Locale.ROOT) }
                if (direct == null || directHost == null || !directHost.endsWith(".mediafire.com") ||
                    !directHost.substringBefore('.').startsWith("download") || isMediaFireShare(URI(direct))) {
                    return asset.copy(candidateUrl = current, host = host,
                        status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "MediaFire 当前没有可直接访问的公开文件按钮，请在来源页查看。")
                }
                resolvedName = cleanFileName(button.attr("download")) ?: resolvedName
                current = direct
                return@repeat
            }
            if (reply.status in setOf(200, 206) && isHtml(reply.contentType)) return asset.copy(candidateUrl = current, host = host,
                status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "来源返回的是网页，请选择其他来源或在浏览器完成正常操作。")
            val name = dispositionFileName(reply.contentDisposition) ?: resolvedName ?: uri.path.substringAfterLast('/').let(::decodedFileName)
            val format = name?.let(::archiveFormat) ?: asset.declaredFormat
            val type = reply.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
            val fileType = type in setOf("application/octet-stream", "binary/octet-stream", "application/zip", "application/x-zip-compressed", "application/vnd.rar", "application/x-rar-compressed", "application/x-cbz", "application/x-cbr")
            if (reply.status == 200 && !fileType && format == null) return asset.copy(candidateUrl = current, host = host,
                status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "来源尚未提供可确认的漫画文件，请选择其他来源。")
            val size = Regex("(?i)^bytes\\s+\\d+-\\d+/(\\d+)$").matchEntire(reply.contentRange?.trim().orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
                ?: reply.contentLength?.takeIf { reply.status == 200 && it > 0 }
            return when (reply.status) {
                206 -> asset.copy(candidateUrl = current, host = host, fileName = name, declaredFormat = format,
                    declaredSize = size?.toString() ?: asset.declaredSize, status = SourceAssetStatus.METADATA_ONLY,
                    reason = "已取得文件响应，正在检查漫画内容。")
                200 -> asset.copy(candidateUrl = current, host = host, fileName = name, declaredFormat = format,
                    declaredSize = size?.toString() ?: asset.declaredSize, status = SourceAssetStatus.RANGE_UNSUPPORTED,
                    reason = "来源提供整文件响应，可尝试完整离线下载；暂不支持直接按页阅读。")
                401, 403 -> asset.copy(status = SourceAssetStatus.UNAUTHORIZED, reason = "该下载来源拒绝了访问，请稍后重试或选择其他条目。")
                404, 410 -> asset.copy(status = SourceAssetStatus.EXPIRED, reason = "该下载链接已失效。")
                429 -> asset.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = "来源请求过于频繁，请稍后重试。")
                else -> asset.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = "下载来源暂时不可用（HTTP ${reply.status}）。")
            }
        }
        return asset.copy(status = SourceAssetStatus.NEEDS_USER_ACTION, reason = "来源跳转次数过多，暂不能直接在线阅读。")
    }

    private fun isMediaFireShare(uri: URI): Boolean {
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        return host in setOf("mediafire.com", "www.mediafire.com") &&
            listOf("/file/", "/file_premium/", "/download/").any { uri.path.orEmpty().startsWith(it) }
    }

    private fun isKnownLanding(uri: URI): Boolean {
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val hasArchiveExtension = Regex("(?i)\\.(cbz|zip|cbr|rar)$").containsMatchIn(uri.path.orEmpty())
        if (hasArchiveExtension) return false
        return listOf("vikingfile.com", "vik1ngfile.site", "datanodes.to", "terabox.com", "1024terabox.com", "mega.nz", "mega.co.nz", "we.tl", "wetransfer.com", "drive.google.com", "gofile.io")
            .any { host == it || host.endsWith(".$it") }
    }

    private fun isHtml(type: String?) = type.orEmpty().let { it.contains("html", true) || it.contains("xhtml", true) }
    private fun archiveFormat(name: String): String? = Regex("(?i)\\.(cbz|zip|cbr|rar)$").find(name)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    private fun cleanFileName(name: String): String? = name.trim().replace('\\', '/').substringAfterLast('/')
        .takeIf { it.isNotBlank() && it.length <= 240 && it.none { c -> c.isISOControl() } }
    private fun decodedFileName(name: String): String? = cleanFileName(runCatching {
        URLDecoder.decode(name.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(name))
    private fun dispositionFileName(value: String?): String? {
        if (value == null || value.length > 4096) return null
        Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)").find(value)?.groupValues?.get(1)?.trim()?.let { extended ->
            Regex("(?i)^utf-8'[^']*'(.*)$").matchEntire(extended)?.groupValues?.get(1)?.let { encoded ->
                decodedFileName(encoded)?.let { return it }
            }
        }
        return Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))")
            .find(value)?.let { match -> cleanFileName(match.groupValues[1].ifEmpty { match.groupValues[2] }) }
    }
}

internal data class SourceHeaderReply(val status: Int, val location: String? = null, val contentType: String? = null,
    val contentLength: Long? = null, val contentDisposition: String? = null, val contentRange: String? = null)

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
        // Never open the response body, even when a multi-GB response ignores Range.
        return SourceHeaderReply(connection.responseCode, connection.getHeaderField("Location"), connection.getHeaderField("Content-Type"),
            connection.getHeaderField("Content-Length")?.toLongOrNull(), connection.getHeaderField("Content-Disposition"), connection.getHeaderField("Content-Range"))
    } finally {
        cancellation?.dispose()
        connection.disconnect()
    }
}
