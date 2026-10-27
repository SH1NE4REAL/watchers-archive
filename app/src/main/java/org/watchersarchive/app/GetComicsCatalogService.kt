package org.watchersarchive.app

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CancellationToken
import org.watchersarchive.core.HttpRangeSource
import org.watchersarchive.core.ReaderLimits
import org.watchersarchive.core.ComicArchiveReaders
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/** Public article metadata and bounded file validation. No login/CAPTCHA/download-page bypass. */
class GetComicsCatalogService(
    private val now: () -> String = { Instant.now().toString() },
    private val decodeImage: (ByteArray) -> Boolean = ::decodeProbeImage,
    private val fetchText: suspend (String) -> String = { SourceHttpClient.text(it) },
    private val onValidatedPage: (org.watchersarchive.core.ArchiveIndex, ByteArray) -> Unit = { _, _ -> },
    private val resolveAsset: suspend (SourceAsset) -> SourceAsset = { SourceResolver().resolve(it) },
) {
    suspend fun fetchWordPressPosts(
        baseUrl: String = "https://getcomics.org",
        categoryId: Int = 3,
        page: Int = 1,
        perPage: Int = 20,
        search: String? = null,
    ): List<CatalogSourceRecord> = withContext(Dispatchers.IO) {
        require(perPage in 1..100 && page >= 1 && categoryId > 0)
        val endpoint = "${baseUrl.trimEnd('/')}/wp-json/wp/v2/posts?categories=$categoryId&page=$page&per_page=$perPage" +
            "&_fields=id,date_gmt,modified_gmt,link,title,excerpt,categories,content,yoast_head_json" +
            search?.trim()?.takeIf { it.isNotBlank() }?.let { "&search=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        parseWordPressPosts(fetchText(endpoint), endpoint)
    }

    suspend fun fetchRss(feedUrl: String = "https://getcomics.org/cat/marvel/feed/"): List<CatalogSourceRecord> =
        withContext(Dispatchers.IO) { parseRss(fetchText(feedUrl), feedUrl) }

    suspend fun enrichCover(record: CatalogSourceRecord): CatalogSourceRecord = withContext(Dispatchers.IO) {
        if (!record.coverUrl.isNullOrBlank()) return@withContext record
        try {
            val document = Jsoup.parse(fetchText(record.canonicalUrl), record.canonicalUrl)
            record.copy(coverUrl = findImage(document, record.canonicalUrl))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            record
        }
    }

    fun parseWordPressPosts(json: String, endpoint: String, discoveredAt: String = now()): List<CatalogSourceRecord> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val post = array.optJSONObject(index) ?: return@mapNotNull null
            val id = post.opt("id")?.toString()?.takeIf { it.toLongOrNull() != null } ?: return@mapNotNull null
            val link = safeSourceUrl(post.text("link"), endpoint) ?: return@mapNotNull null
            val title = sourceText(post.optJSONObject("title")?.text("rendered").orEmpty()).ifBlank { "GetComics article $id" }
            val excerptHtml = post.optJSONObject("excerpt")?.text("rendered").orEmpty()
            val content = post.optJSONObject("content")?.text("rendered").orEmpty()
            val excerpt = sourceText(excerptHtml)
            val contentText = sourceText(content)
            val yoast = post.optJSONObject("yoast_head_json")
            val coverFromYoast = yoast?.optJSONArray("og_image")?.optJSONObject(0)?.text("url")
            val cover = safeSourceUrl(coverFromYoast, link)
                ?: findImage(Jsoup.parse(content, link), link)
                ?: findImage(Jsoup.parse(excerptHtml, link), link)
            CatalogSourceRecord(
                source = "getcomics", externalId = "post:$id", canonicalUrl = link, title = title,
                synopsis = synopsis(excerpt).ifBlank { synopsis(contentText) }.ifBlank { null },
                coverUrl = cover,
                sourcePublishedAt = normalizedSourceDate(post.text("date_gmt")),
                sourceModifiedAt = normalizedSourceDate(post.text("modified_gmt")),
                firstDiscoveredAt = discoveredAt,
                categories = post.optJSONArray("categories")?.let { cats -> (0 until cats.length()).map { cats.opt(it).toString() } }.orEmpty(),
                yearLabel = yearLabel("$excerpt $contentText") ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1),
                sizeLabel = sizeLabel("$excerpt $contentText"),
                formatLabel = formatLabel(contentText), confidence = SourceConfidence.MEDIUM,
            )
        }
    }

    fun parseRss(xml: String, feedUrl: String, discoveredAt: String = now()): List<CatalogSourceRecord> {
        val document = Jsoup.parse(xml, feedUrl, Parser.xmlParser())
        return document.getElementsByTag("item").mapNotNull { item ->
            fun field(name: String): String = item.getElementsByTag(name).firstOrNull()?.text().orEmpty()
            val link = safeSourceUrl(field("link"), feedUrl) ?: return@mapNotNull null
            val guid = field("guid")
            val postId = field("post-id").ifBlank { field("wp:post-id") }.takeIf { it.toLongOrNull() != null }
                ?: Regex("[?&]p=(\\d+)").find(guid)?.groupValues?.get(1)
            val descriptionHtml = field("description").ifBlank { field("content:encoded") }
            val description = sourceText(descriptionHtml)
            val title = sourceText(field("title"))
            CatalogSourceRecord(
                source = "getcomics", externalId = postId?.let { "post:$it" } ?: "article:$link", canonicalUrl = link,
                title = title, synopsis = synopsis(description).ifBlank { null },
                coverUrl = findImage(Jsoup.parse(descriptionHtml, link), link),
                sourcePublishedAt = normalizedSourceDate(field("pubDate")), sourceModifiedAt = null,
                firstDiscoveredAt = discoveredAt,
                categories = item.getElementsByTag("category").map { sourceText(it.text()) },
                yearLabel = yearLabel(description) ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1),
                sizeLabel = sizeLabel(description), formatLabel = formatLabel(description), confidence = SourceConfidence.LOW,
            )
        }
    }

    fun inspectArticleHtml(articleUrl: String, articleHtml: String): SourceAssetInspection {
        val article = safeSourceUrl(articleUrl) ?: return SourceAssetInspection(articleUrl, emptyList(), false)
        val document = Jsoup.parse(articleHtml, article)
        val postId = document.selectFirst("article[id^=post-]")?.id()?.removePrefix("post-")?.takeIf { it.toLongOrNull() != null }
        val recordId = postId?.let { "post:$it" } ?: "article:$article"
        val links = document.select("a[href]").mapNotNull { safeSourceUrl(it.attr("href"), article) }
            .filter { candidate -> isIntermediate(candidate) || knownHost(candidate) || declaredFormat(candidate) != null }
            .distinct()
        val assets = links.map { candidate ->
            val uri = URI(candidate)
            val format = declaredFormat(candidate)
            val gated = knownHost(candidate) && format == null && !isPixeldrainShare(candidate)
            val status = when {
                gated -> SourceAssetStatus.NEEDS_USER_ACTION
                else -> SourceAssetStatus.METADATA_ONLY
            }
            SourceAsset(
                id = "$recordId:asset:${stableHash(candidate)}", sourceRecordId = recordId, host = uri.host,
                candidateUrl = candidate, fileName = uri.path.substringAfterLast('/').takeIf { it.isNotBlank() },
                declaredFormat = format, declaredSize = sizeLabel(document.text()), status = status,
                reason = when (status) {
                    SourceAssetStatus.NEEDS_USER_ACTION -> "来源需要在其正常页面完成操作，尚无可直接读取的文件。"
                    else -> "正在等待来源解析和文件内容检查。"
                }, checkedAt = now(),
            )
        }
        return SourceAssetInspection(articleUrl, assets, false)
    }

    suspend fun inspectArticle(articleUrl: String): SourceAssetInspection = withContext(Dispatchers.IO) {
        inspectArticleHtml(articleUrl, fetchText(articleUrl))
    }

    /** READY proves ZIP/RAR4 index + first page + CRC + image decode, not merely one byte. */
    suspend fun validateAsset(asset: SourceAsset): SourceAsset = withContext(Dispatchers.IO) {
        val checked = now()
        val resolved = resolveAsset(asset)
        fun result(status: SourceAssetStatus, message: String) = resolved.copy(
            status = status, reason = message, checkedAt = checked,
            verifiedUrl = null, verifiedETag = null, verifiedLength = null, verifiedPageCount = null,
        )
        if (resolved.status in setOf(SourceAssetStatus.NEEDS_USER_ACTION, SourceAssetStatus.UNAUTHORIZED,
                SourceAssetStatus.EXPIRED, SourceAssetStatus.TRANSIENT_ERROR, SourceAssetStatus.RANGE_UNSUPPORTED)) {
            return@withContext result(resolved.status, resolved.reason)
        }
        val url = safeSourceUrl(resolved.candidateUrl)
            ?: return@withContext result(SourceAssetStatus.UNAUTHORIZED, "文件地址不安全或无效。")
        val token = CancellationToken()
        val cancellation = currentCoroutineContext()[Job]?.onSourceCancellation { token.cancel() }
        try {
            currentCoroutineContext().ensureActive()
            val limits = ReaderLimits(maxPageBytes = 16 * 1024 * 1024, maxCompressedPageBytes = 16 * 1024 * 1024)
            val source = HttpRangeSource(url, limits)
            val identity = source.discover(token)
            val prefix = source.readRange(identity, 0, minOf(8L, identity.length).toInt(), token)
            val rar4Signature = prefix.size >= 7 && prefix.copyOfRange(0, 7).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00))
            // Some valid ZIP64/SFX archives have bytes before the first local
            // header. The factory checks a bounded EOCD tail before rejecting
            // unknown magic; the strict parser still rejects HTML or bad data.
            ComicArchiveReaders.create(source, limits, token).use { reader ->
                val index = reader.open(token)
                if (index.identity.eTag != identity.eTag || index.identity.length != identity.length) {
                    return@withContext result(SourceAssetStatus.EXPIRED, "来源文件已变化，请重新读取详情。")
                }
                val firstPage = reader.readPage(index, 0, token)
                if (!decodeImage(firstPage)) return@withContext result(SourceAssetStatus.UNSUPPORTED_FORMAT, "文件中的页面无法解码。")
                currentCoroutineContext().ensureActive()
                onValidatedPage(index, firstPage)
                resolved.copy(
                    status = SourceAssetStatus.READY, reason = "文件目录和首张页面已验证。", checkedAt = checked,
                    declaredFormat = if (rar4Signature) "CBR" else "CBZ", verifiedUrl = index.identity.url, verifiedETag = index.identity.eTag,
                    verifiedLength = index.identity.length, verifiedPageCount = index.pages.size,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ArchiveException) {
            currentCoroutineContext().ensureActive()
            when (error.code) {
                ArchiveError.RANGE_NOT_SUPPORTED, ArchiveError.INVALID_CONTENT_RANGE -> result(SourceAssetStatus.RANGE_UNSUPPORTED, "此来源暂不支持按页读取。")
                ArchiveError.UNSUPPORTED_ARCHIVE, ArchiveError.UNSUPPORTED_COMPRESSION, ArchiveError.UNSUPPORTED_IMAGE,
                ArchiveError.CRC_MISMATCH, ArchiveError.CORRUPT_ARCHIVE, ArchiveError.LIMIT_EXCEEDED -> result(SourceAssetStatus.UNSUPPORTED_FORMAT, error.message ?: "此文件格式、页面大小或完整性不符合当前阅读要求。")
                ArchiveError.OBJECT_CHANGED -> result(SourceAssetStatus.EXPIRED, "来源文件已变化，请稍后重试。")
                ArchiveError.IDENTITY_REQUIRED -> result(SourceAssetStatus.METADATA_ONLY, "此来源没有可靠的文件版本标识，暂不能在线阅读。")
                else -> result(SourceAssetStatus.TRANSIENT_ERROR, "暂时无法读取文件，请稍后重试。")
            }
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            result(SourceAssetStatus.TRANSIENT_ERROR, "暂时无法连接文件来源。")
        } finally {
            cancellation?.dispose()
        }
    }

    private fun findImage(document: org.jsoup.nodes.Document, base: String): String? {
        val og = document.selectFirst("meta[property=og:image],meta[name=og:image]")?.attr("content")
        safeSourceUrl(og, base)?.let { return it }
        val image = document.selectFirst("article img, .post-content img, .entry-content img, img") ?: return null
        return sequenceOf("data-src", "data-lazy-src", "src").mapNotNull { safeSourceUrl(image.attr(it), base) }.firstOrNull()
    }

    private fun declaredFormat(url: String): String? = Regex("(?i)\\.(cbz|zip|cbr|rar)$").find(URI(url).path.orEmpty())?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    private fun knownHost(url: String): Boolean {
        val host = URI(url).host.orEmpty().lowercase(Locale.ROOT)
        return listOf("vikingfile.com", "vik1ngfile.site", "pixeldrain.com", "datanodes.to", "terabox.com").any { host == it || host.endsWith(".$it") }
    }
    private fun isIntermediate(url: String): Boolean = URI(url).path.orEmpty().startsWith("/dls/")
    private fun isPixeldrainShare(url: String): Boolean = URI(url).let { uri ->
        uri.host?.lowercase(Locale.ROOT) in setOf("pixeldrain.com", "www.pixeldrain.com") && uri.path.orEmpty().startsWith("/u/")
    }
    private fun sizeLabel(text: String): String? = Regex("(?i)\\bSize\\s*[:：-]\\s*(\\d+(?:[.,]\\d+)?\\s*(?:[KMGT]i?B|bytes))\\b").find(text)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")
    private fun yearLabel(text: String): String? = Regex("(?i)\\bYear\\s*[:：-]\\s*(\\d{4}(?:\\s*[–-]\\s*\\d{4})?)\\b").find(text)?.groupValues?.get(1)
    private fun formatLabel(text: String): String? = Regex("(?i)\\b(CBZ|ZIP|CBR|RAR)\\b").find(text)?.value?.uppercase(Locale.ROOT)
    private fun synopsis(text: String): String = text
        .replace(Regex("(?i)\\bYear\\s*[:：-]\\s*\\d{4}(?:\\s*[–-]\\s*\\d{4})?\\s*[|•]?"), " ")
        .replace(Regex("(?i)\\bSize\\s*[:：-]\\s*(?:\\d+(?:[.,]\\d+)?\\s*(?:[KMGT]i?B|bytes)|-\\s*MB)\\s*[|•]?"), " ")
        .replace(Regex("\\s+"), " ").trim()
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun stableHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}

private fun decodeProbeImage(bytes: ByteArray): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth.toLong()
    val height = bounds.outHeight.toLong()
    if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384 || width * height > 20_000_000) return false
    var sample = 1
    while (((width + sample - 1) / sample) * ((height + sample - 1) / sample) > 2_000_000) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
    bitmap.recycle()
    return true
}
