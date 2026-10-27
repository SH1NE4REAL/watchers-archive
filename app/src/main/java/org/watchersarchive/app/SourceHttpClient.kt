package org.watchersarchive.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Bounded metadata HTTP only; this client never follows download-host workflows. */
internal object SourceHttpClient {
    suspend fun text(url: String, maximumBytes: Int = 4 * 1024 * 1024): String {
        var current = safeSourceUrl(url) ?: throw IOException("来源地址无效")
        repeat(6) { redirects ->
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "WatchersArchive-Metadata/0.4")
            val cancellation = currentCoroutineContext()[Job]?.onSourceCancellation { connection.disconnect() }
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirects == 5) throw IOException("来源重定向过多")
                    val target = connection.getHeaderField("Location")
                        ?: throw IOException("来源重定向缺少地址")
                    val next = safeSourceUrl(target, current) ?: throw IOException("来源重定向地址无效")
                    if (current.startsWith("https:") && !next.startsWith("https:")) throw IOException("拒绝不安全的来源重定向")
                    current = next
                    return@repeat
                }
                if (status !in 200..299) throw IOException("来源暂不可用（HTTP $status）")
                val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
                if (declared != null && declared > maximumBytes) throw IOException("来源页面超出读取上限")
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val block = ByteArray(8192)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        if (output.size() > maximumBytes - count) throw IOException("来源页面超出读取上限")
                        output.write(block, 0, count)
                    }
                    output.toByteArray().toString(Charsets.UTF_8)
                }
            } finally {
                cancellation?.dispose()
                connection.disconnect()
            }
        }
        throw IOException("来源重定向过多")
    }
}

@OptIn(InternalCoroutinesApi::class)
internal fun Job.onSourceCancellation(action: () -> Unit): DisposableHandle =
    invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) action()
    }

internal fun safeSourceUrl(value: String?, base: String? = null): String? = runCatching {
    if (value.isNullOrBlank()) return@runCatching null
    val uri = if (base == null) URI(value.trim()) else URI(base).resolve(value.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
    val isLoopback = host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
    if ((scheme != "https" && !(scheme == "http" && isLoopback)) || uri.userInfo != null) return@runCatching null
    uri.normalize().toASCIIString()
}.getOrNull()

internal fun normalizedSourceDate(value: String?): String? {
    val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(clean).toString() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(clean).toInstant().toString() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(clean, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrNull()
        ?: runCatching { Instant.parse(clean + "Z").toString() }.getOrNull()
}

internal fun sourceText(html: String): String = Jsoup.parseBodyFragment(html).apply { select("script,style").remove() }.text().trim()
