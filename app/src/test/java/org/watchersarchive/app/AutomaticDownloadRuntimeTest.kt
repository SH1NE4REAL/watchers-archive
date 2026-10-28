package org.watchersarchive.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticDownloadRuntimeTest {
    @Test fun repositorySkipsQuotaAndHtmlThenDownloadsNormal200FileAndReadsItWithoutNetwork() = runBlocking {
        val fixture = Fixture()
        val repository = AndroidRepository(ApplicationProvider.getApplicationContext<Context>())
        repository.cacheSnapshot()
        val state = ReflectionHelpers.getField<Any>(repository, "state")
        val metadata = ReflectionHelpers.getField<CatalogMetadataStore>(state, "catalogMetadata")
        val store = ReflectionHelpers.getField<OfflineDownloadStore>(state, "offlineStore")
        val id = "auto-runtime:${UUID.randomUUID()}"
        val record = CatalogSourceRecord("getcomics", id, "${fixture.base}/article", "Automatic source test", null, null,
            null, null, "2026-10-28T00:00:00Z", emptyList(), null, null, "CBZ", SourceConfidence.MEDIUM)
        metadata.upsertAll(listOf(record))
        store.enqueue(OfflineDownloadSpec(id, record.title, record.canonicalUrl, "", 0,
            mode = OfflineTransferMode.DISCOVER, verifyRemoteETag = false))
        val progress = CopyOnWriteArrayList<OfflineDownloadStatus>()
        try {
            repository.runOfflineDownload(id) { progress += it }
            val completed = checkNotNull(store.completed(id))
            assertEquals(OfflineTransferMode.FULL, completed.spec.mode)
            assertArrayEquals(fixture.archive, completed.file.readBytes())
            assertTrue(fixture.requests.any { it.contains("/quota.cbz") })
            assertTrue(fixture.requests.any { it.contains("/web.cbz") })
            assertTrue(fixture.requests.any { it == "/book.cbz FULL" })
            assertTrue(progress.any { it.message?.contains("来源") == true })
            val beforeClose = fixture.requests.size
            fixture.close()
            val comic = repository.prepareSourceReading(record)
            assertTrue(comic.sourceUrl!!.startsWith("file:"))
            val session = repository.open(comic)
            try {
                assertEquals(3, session.totalPages)
                val page = session.loadPage(1)
                assertEquals(1, page.bitmap.width)
                assertEquals(1, page.bitmap.height)
                page.release()
            } finally { session.close() }
            assertEquals(beforeClose, fixture.requests.size)
        } finally {
            fixture.close()
            store.remove(id)
        }
    }

    private class Fixture : Closeable {
        private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
        val archive = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            repeat(3) { zip.putNextEntry(ZipEntry("page-$it.png")); zip.write(png); zip.closeEntry() }
        } }.toByteArray()
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        private val workers = Executors.newCachedThreadPool()
        private val accepting = Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                workers.submit { runCatching { client.use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    var range = false
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; if (line.startsWith("Range:", true)) range = true }
                    requests += "$path ${if (range) "PROBE" else "FULL"}"
                    val article = "<article id='post-1'><a href='$base/quota.cbz'>Mirror 1</a><a href='$base/web.cbz'>Mirror 2</a><a href='$base/book.cbz'>Mirror 3</a></article>".toByteArray()
                    val body = when (path) { "/article" -> article; "/book.cbz" -> archive; else -> "<html>Source unavailable</html>".toByteArray() }
                    val type = if (path == "/book.cbz") "application/zip" else "text/html"
                    val code = if (path == "/quota.cbz") "429 Too Many Requests" else "200 OK"
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    // A 200 probe supplies headers only: the client must not read it.
                    if (!range) output.write(body)
                    output.flush()
                } } }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { if (!server.isClosed) server.close(); accepting.join(1_000); workers.shutdownNow() }
    }
}
