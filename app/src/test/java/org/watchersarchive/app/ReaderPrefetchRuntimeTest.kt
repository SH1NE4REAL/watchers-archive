package org.watchersarchive.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPrefetchRuntimeTest {
    @Test fun nextPageComesFromPreviewCacheWithoutNetworkOrPrematureProgress() = runBlocking {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
        val archive = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            repeat(3) { zip.putNextEntry(ZipEntry("page-$it.png")); zip.write(png); zip.closeEntry() }
        } }.toByteArray()
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val workers = Executors.newCachedThreadPool()
        val accept = Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                workers.submit { client.use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    input.readLine() ?: return@use
                    var range = ""
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                    }
                    val values = range.removePrefix("bytes=").split('-')
                    val start = values[0].toInt(); val end = values[1].toInt(); val length = end - start + 1
                    requests.incrementAndGet()
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 206 Partial Content\r\nETag: \"fixture-v1\"\r\nContent-Range: bytes $start-$end/${archive.size}\r\nContent-Length: $length\r\nConnection: close\r\n\r\n").toByteArray())
                    runCatching { output.write(archive, start, length); output.flush() }
                } }
            }
        }.apply { isDaemon = true; start() }
        val repository = AndroidRepository(ApplicationProvider.getApplicationContext())
        val old = repository.prefetchEnabled
        var session: ReaderSession? = null
        var first: ReaderPage? = null
        var next: ReaderPage? = null
        try {
            repository.prefetchEnabled = true
            val comic = DemoComic("prefetch-${UUID.randomUUID()}", "Generated test", "", "fixture.cbz", sourceUrl = "http://127.0.0.1:${server.localPort}/fixture.cbz")
            session = repository.open(comic)
            first = session.loadPage(0)
            val used = repository.cacheSnapshot().committedBytes
            session.prefetchPage(1)
            withTimeout(5_000) { while (repository.cacheSnapshot().committedBytes <= used) delay(10) }
            assertEquals("Prefetch must not mark page 2 read", 0, repository.lastPage(comic.id))
            val count = requests.get()
            next = session.loadPage(1)
            assertTrue(next.fromCache)
            assertEquals(0L, next.transferredBytes)
            assertEquals("A warmed next page needs no extra Range request", count, requests.get())
            assertEquals(1, repository.lastPage(comic.id))
            first.release(); next.release(); session.close()
            assertEquals(0, repository.cacheSnapshot().pinnedEntries)
        } finally {
            first?.release?.invoke(); next?.release?.invoke(); session?.close(); repository.prefetchEnabled = old
            server.close(); accept.join(1000); workers.shutdownNow()
        }
    }
}
