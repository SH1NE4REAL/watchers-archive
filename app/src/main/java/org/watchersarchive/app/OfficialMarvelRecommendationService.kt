package org.watchersarchive.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale

/** Official-source guide and character metadata. It never infers story order. */
class OfficialMarvelRecommendationService(
    private val now: () -> String = { Instant.now().toString() },
) {
    suspend fun fetchGuide(url: String): OfficialGuide = withContext(Dispatchers.IO) {
        parseGuide(url, getText(url))
    }

    fun parseGuide(url: String, html: String, fetchedAt: String = now()): OfficialGuide {
        val title = firstMatch(html, "(?is)<h1[^>]*>(.*?)</h1>")?.clean() ?: "Marvel Official Guide"
        val description = firstMatch(html, "(?is)<meta[^>]+name=[\\\"']description[\\\"'][^>]+content=[\\\"']([^\\\"']+)")?.clean()
        val issuePattern = Regex("(?i)href=[\\\"']([^\\\"']*/comics/issue/([0-9]+)/[^\\\"']*)[\\\"']")
        val issues = issuePattern.findAll(html).distinctBy { it.groupValues[2] }.mapIndexed { index, match ->
            OfficialGuideIssue(index + 1, match.groupValues[2], absoluteUrl(url, match.groupValues[1]), "Official guide issue link")
        }.toList()
        val sectionTitles = Regex("(?is)<h2[^>]*>(.*?)</h2>").findAll(html).map { it.groupValues[1].clean() }.filter { it.isNotBlank() }.toList()
        val sections = if (sectionTitles.isEmpty()) listOf(OfficialGuideSection(1, "Official guide order", issues)) else
            sectionTitles.mapIndexed { index, section -> OfficialGuideSection(index + 1, section, if (index == 0) issues else emptyList()) }
        val externalId = Regex("/guides/([0-9]+)/").find(url)?.groupValues?.get(1)
        return OfficialGuide(externalId, title, description, url, fetchedAt, sections)
    }

    suspend fun fetchCharacter(url: String): OfficialCharacterProfile = withContext(Dispatchers.IO) {
        parseCharacter(url, getText(url))
    }

    fun parseCharacter(url: String, html: String, fetchedAt: String = now()): OfficialCharacterProfile {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val name = firstMatch(html, "(?is)<h1[^>]*>(.*?)</h1>")?.clean() ?: slug
        val overview = firstMatch(html, "(?is)<meta[^>]+name=[\\\"']description[\\\"'][^>]+content=[\\\"']([^\\\"']+)")?.clean()
        val related = Regex("(?i)href=[\\\"']([^\\\"']*/characters/[^\\\"']+)[\\\"']").findAll(html)
            .map { absoluteUrl(url, it.groupValues[1]) }.distinct().toList()
        return OfficialCharacterProfile(slug, name, url, overview, emptyList(), related, fetchedAt)
    }

    private fun getText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "WatchersArchive-OfficialMetadata/1.0")
        return connection.inputStream.use { input ->
            if (connection.responseCode !in 200..299) throw IOException("Official metadata HTTP ${connection.responseCode}")
            input.readBytesLimited(4 * 1024 * 1024).toString(Charsets.UTF_8)
        }.also { connection.disconnect() }
    }

    private fun firstMatch(value: String, pattern: String): String? = Regex(pattern).find(value)?.groupValues?.getOrNull(1)
    private fun absoluteUrl(base: String, value: String): String = if (value.startsWith("http")) value else URL(URL(base), value).toString()
    private fun String.clean(): String = replace(Regex("(?is)<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
        while (true) { val count = read(buffer); if (count < 0) break; total += count; if (total > limit) throw IOException("Official metadata response exceeds limit"); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}

