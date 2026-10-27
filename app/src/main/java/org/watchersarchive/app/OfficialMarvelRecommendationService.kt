package org.watchersarchive.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.time.Instant

/** Parse only links actually present in official guide content; never invent prerequisite issues. */
class OfficialMarvelRecommendationService(
    private val now: () -> String = { Instant.now().toString() },
    private val fetchText: suspend (String) -> String = { SourceHttpClient.text(it) },
) {
    suspend fun fetchGuide(url: String): OfficialGuide = withContext(Dispatchers.IO) {
        requireOfficialUrl(url)
        parseGuide(url, fetchText(url))
    }

    fun parseGuide(url: String, html: String, fetchedAt: String = now()): OfficialGuide {
        val document = Jsoup.parse(html, url)
        val content = document.selectFirst("main") ?: document.selectFirst("article") ?: document.body()
        content.select("nav,header,footer,script,style,[role=navigation],.related-content,.relatedContent").remove()
        val title = content.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: throw IOException("官方指南页面暂不可解析")
        val description = document.selectFirst("meta[name=description],meta[property=og:description]")
            ?.attr("content")?.let(::sourceText)?.takeIf { it.isNotBlank() }
        val sections = mutableListOf<OfficialGuideSection>()
        var sectionTitle = "指南列出的漫画"
        var issues = mutableListOf<OfficialGuideIssue>()
        val seen = mutableSetOf<String>()
        fun flush() {
            if (issues.isNotEmpty()) {
                sections += OfficialGuideSection(sections.size + 1, sectionTitle, issues.toList())
                issues = mutableListOf()
            }
        }
        // DOM order plus the nearest preceding content heading preserve the guide's
        // displayed grouping. A role/related navigation link cannot create an issue.
        content.getAllElements().forEach { element ->
            if (element.tagName() in setOf("h2", "h3")) {
                val heading = element.text().trim()
                if (heading.isNotEmpty()) { flush(); sectionTitle = heading }
            }
            if (element.tagName() != "a") return@forEach
            val linked = safeSourceUrl(element.attr("href"), url) ?: return@forEach
            val host = URI(linked).host.orEmpty().lowercase()
            if (host != "marvel.com" && host != "www.marvel.com") return@forEach
            val id = Regex("/comics/issue/(\\d+)(?:/|$)").find(URI(linked).path.orEmpty())?.groupValues?.get(1) ?: return@forEach
            if (!seen.add(id)) return@forEach
            issues += OfficialGuideIssue(
                order = issues.size + 1,
                issueExternalId = id,
                sourceUrl = linked,
                sourceEvidence = "Marvel 官方指南「$title」/「$sectionTitle」显示顺序；不是故事内部时间线。",
            )
        }
        flush()
        if (sections.isEmpty()) throw IOException("官方指南尚未返回可核实的漫画清单")
        val externalId = Regex("/guides/(\\d+)(?:/|$)").find(url)?.groupValues?.get(1)
        return OfficialGuide(externalId, title, description, url, fetchedAt, sections)
    }

    suspend fun fetchCharacter(url: String): OfficialCharacterProfile = withContext(Dispatchers.IO) {
        requireOfficialUrl(url)
        parseCharacter(url, fetchText(url))
    }

    fun parseCharacter(url: String, html: String, fetchedAt: String = now()): OfficialCharacterProfile {
        val document = Jsoup.parse(html, url)
        val content = document.selectFirst("main") ?: document.selectFirst("article") ?: document.body()
        content.select("nav,header,footer,script,style,[role=navigation]").remove()
        val name = content.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: throw IOException("官方角色页面暂不可解析")
        val overview = document.selectFirst("meta[name=description],meta[property=og:description]")?.attr("content")
            ?.let(::sourceText)?.takeIf { it.isNotBlank() }
        val related = content.select("a[href]").mapNotNull { anchor ->
            safeSourceUrl(anchor.attr("href"), url)?.takeIf { target ->
                URI(target).host.orEmpty() in setOf("marvel.com", "www.marvel.com") && URI(target).path.orEmpty().startsWith("/characters/")
            }
        }.filter { it.trimEnd('/') != url.trimEnd('/') }.distinct()
        val slug = Regex("/characters/([^/]+)").find(URI(url).path.orEmpty())?.groupValues?.get(1)
            ?: throw IOException("官方角色地址无效")
        return OfficialCharacterProfile(slug, name, url, overview, emptyList(), related, fetchedAt)
    }

    private fun requireOfficialUrl(url: String) {
        val parsed = safeSourceUrl(url)?.let(::URI)
        require(parsed?.scheme == "https" && parsed.host.orEmpty() in setOf("www.marvel.com", "marvel.com")) { "仅支持 Marvel 官方 HTTPS 页面" }
    }
}
