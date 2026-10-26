package org.watchersarchive.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.regex.Pattern

/**
 * GetComics metadata adapter. It reads public RSS/WordPress metadata and article
 * HTML only. It never follows a download host or claims that an article has a
 * stable, authorized, Range-capable file.
 */
class GetComicsCatalogService(
    private val now: () -> String = { Instant.now().toString() },
) {
    suspend fun fetchWordPressPosts(
        baseUrl: String = "https://getcomics.org",
        categoryId: Int = 3,
        page: Int = 1,
        perPage: Int = 20,
    ): List<CatalogSourceRecord> = withContext(Dispatchers.IO) {
        require(perPage in 1..100)
        val endpoint = "$baseUrl/wp-json/wp/v2/posts?categories=$categoryId&page=$page&per_page=$perPage" +
            "&_fields=id,date_gmt,modified_gmt,link,title,excerpt,categories,content"
        parseWordPressPosts(getText(endpoint), endpoint)
    }

    suspend fun fetchRss(feedUrl: String = "https://getcomics.org/cat/marvel/feed/") =
        withContext(Dispatchers.IO) { parseRss(getText(feedUrl), feedUrl) }

    fun parseWordPressPosts(json: String, endpoint: String, discoveredAt: String = now()): List<CatalogSourceRecord> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val post = array.optJSONObject(index) ?: return@mapNotNull null
            val id = post.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = htmlText(post.optJSONObject("title")?.optString("rendered").orEmpty())
            val excerpt = htmlText(post.optJSONObject("excerpt")?.optString("rendered").orEmpty())
            val content = post.optJSONObject("content")?.optString("rendered").orEmpty()
            val cover = firstImageUrl(content)
            val categories = post.optJSONArray("categories").toStringList()
            CatalogSourceRecord(
                source = "getcomics",
                externalId = "post:$id",
                canonicalUrl = post.optString("link", endpoint),
                title = title.ifBlank { "GetComics article $id" },
                synopsis = excerpt.ifBlank { null },
                coverUrl = cover,
                sourcePublishedAt = post.optString("date_gmt").takeIf { it.isNotBlank() },
                sourceModifiedAt = post.optString("modified_gmt").takeIf { it.isNotBlank() },
                firstDiscoveredAt = discoveredAt,
                categories = categories,
                yearLabel = findLabel(excerpt, "year"),
                sizeLabel = findLabel(excerpt, "size"),
                formatLabel = findFormat(content),
                confidence = SourceConfidence.MEDIUM,
            )
        }
    }

    fun parseRss(xml: String, feedUrl: String, discoveredAt: String = now()): List<CatalogSourceRecord> {
        val itemPattern = Pattern.compile("(?is)<item\\b[^>]*>(.*?)</item>")
        val matcher = itemPattern.matcher(xml)
        val output = mutableListOf<CatalogSourceRecord>()
        while (matcher.find()) {
            val item = matcher.group(1)
            val link = xmlTag(item, "link") ?: continue
            val guid = xmlTag(item, "guid") ?: link
            val title = htmlText(xmlTag(item, "title").orEmpty())
            val description = htmlText(xmlTag(item, "description").orEmpty())
            output += CatalogSourceRecord(
                source = "getcomics",
                externalId = "rss:$guid",
                canonicalUrl = link,
                title = title,
                synopsis = description.ifBlank { null },
                coverUrl = firstImageUrl(xmlTag(item, "description").orEmpty()),
                sourcePublishedAt = xmlTag(item, "pubDate"),
                sourceModifiedAt = null,
                firstDiscoveredAt = discoveredAt,
                categories = Pattern.compile("(?is)<category[^>]*>(.*?)</category>").matcher(item).let { categories ->
                    buildList { while (categories.find()) add(htmlText(categories.group(1))) }
                },
                yearLabel = findLabel(description, "year"),
                sizeLabel = findLabel(description, "size"),
                formatLabel = findFormat(item),
                confidence = SourceConfidence.LOW,
            )
        }
        return output
    }

    /**
     * Inspects article HTML without opening a download host. A candidate is never
     * marked READY until a separate, authorized file probe proves format, length,
     * ETag and Range behavior.
     */
    fun inspectArticleHtml(articleUrl: String, articleHtml: String): SourceAssetInspection {
        val recordId = "article:" + articleUrl.hashCode().toUInt().toString(16)
        val links = Regex("(?i)href\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']").findAll(articleHtml)
            .map { it.groupValues[1] }
            .filter { it.contains("/dls/") || knownHost(it) }
            .distinct()
            .toList()
        val assets = links.mapIndexed { index, candidate ->
            val uri = runCatching { URI(candidate) }.getOrNull()
            val path = uri?.path.orEmpty()
            val format = Regex("(?i)\\.(cbz|zip|cbr|rar)(?:$|[?#])").find(path)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
            val host = uri?.host
            val status = when {
                candidate.contains("/dls/") -> SourceAssetStatus.NEEDS_USER_ACTION
                format == "CBR" || format == "RAR" -> SourceAssetStatus.UNSUPPORTED_FORMAT
                else -> SourceAssetStatus.METADATA_ONLY
            }
            SourceAsset(
                id = "$recordId:asset:$index",
                sourceRecordId = recordId,
                host = host,
                candidateUrl = candidate,
                fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() },
                declaredFormat = format,
                declaredSize = findLabel(htmlText(articleHtml), "size"),
                status = status,
                reason = when (status) {
                    SourceAssetStatus.NEEDS_USER_ACTION -> "GetComics 中间路由，需用户正常操作；未取得稳定文件地址。"
                    SourceAssetStatus.UNSUPPORTED_FORMAT -> "当前核心读取器只验证 ZIP/CBZ，未启用 CBR/RAR。"
                    else -> "文章候选链接，尚未验证文件响应、授权和 Range。"
                },
                checkedAt = now(),
            )
        }
        return SourceAssetInspection(articleUrl, assets, false)
    }

    private fun getText(endpoint: String): String {
        val uri = URI(endpoint)
        require(uri.scheme == "https" || uri.scheme == "http")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "WatchersArchive-Metadata/1.0")
        return connection.inputStream.use { input ->
            if (connection.responseCode !in 200..299) throw IOException("Metadata HTTP ${connection.responseCode}")
            val bytes = input.readBytesLimited(2 * 1024 * 1024)
            bytes.toString(Charsets.UTF_8)
        }.also { connection.disconnect() }
    }

    private fun knownHost(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase(Locale.ROOT)
        host.endsWith("vikingfile.com") || host.endsWith("pixeldrain.com") ||
            host.endsWith("datanodes.to") || host.endsWith("terabox.com")
    }.getOrDefault(false)

    private fun htmlText(value: String): String = value
        .replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    private fun firstImageUrl(html: String): String? = Regex("(?i)<img[^>]+src=[\\\"']([^\\\"']+)").find(html)?.groupValues?.get(1)

    private fun findFormat(text: String): String? = Regex("(?i)\\b(CBZ|ZIP|CBR|RAR)\\b").find(text)?.groupValues?.get(1)?.uppercase(Locale.ROOT)

    private fun findLabel(text: String, label: String): String? = Regex("(?i)\\b$label\\s*[:\\-]\\s*([^,;|]+)").find(text)?.groupValues?.get(1)?.trim()

    private fun xmlTag(value: String, tag: String): String? = Regex("(?is)<$tag(?:\\s[^>]*)?>(.*?)</$tag>").find(value)?.groupValues?.get(1)?.trim()

    private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Metadata response exceeds limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

