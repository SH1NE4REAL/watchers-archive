package org.watchersarchive.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderWindowRuntimeTest {
    @Test fun twoPreviewPagesAreReadyWithoutAdvancingProgressOrAddingForegroundRequests() = runBlocking {
        FixtureServer().use { fixture ->
            val repo = AndroidRepository(ApplicationProvider.getApplicationContext())
            val old = repo.prefetchEnabled
            val comic = fixture.comic()
            var session: ReaderSession? = null
            val pages = mutableListOf<ReaderPage>()
            try {
                repo.prefetchEnabled = true
                session = repo.open(comic)
                pages += session.loadPage(0)
                val initial = repo.cacheSnapshot().committedBytes
                session.prefetchPage(1)
                withTimeout(5_000) { while (repo.cacheSnapshot().committedBytes < initial + 8_192) delay(10) }
                assertEquals(0, repo.lastPage(comic.id))
                val requests = fixture.requests.get()
                val second = session.loadPage(1).also { pages += it }
                val third = session.loadPage(2).also { pages += it }
                assertTrue(second.fromCache && third.fromCache)
                assertEquals(requests, fixture.requests.get())
                assertEquals(2, repo.lastPage(comic.id))
                pages.forEach { it.release() }; session.close()
                assertEquals(0, repo.cacheSnapshot().pinnedEntries)
            } finally {
                pages.forEach { it.release() }; session?.close(); repo.prefetchEnabled = old
            }
        }
    }

    @Test fun cachedPreviousPageDoesNotWaitForOrCancelTheBlockedSecondPreview() = runBlocking {
        FixtureServer(blockThirdHeader = true).use { fixture ->
            val repo = AndroidRepository(ApplicationProvider.getApplicationContext())
            val old = repo.prefetchEnabled
            val comic = fixture.comic()
            var session: ReaderSession? = null
            val pages = mutableListOf<ReaderPage>()
            try {
                repo.prefetchEnabled = true
                session = repo.open(comic)
                pages += session.loadPage(0)
                session.prefetchPage(1)
                withTimeout(5_000) { fixture.thirdHeaderStarted.await() }
                val beforeThirdPage = repo.cacheSnapshot().committedBytes
                val back = withTimeout(2_000) { session.loadPage(0) }.also { pages += it }
                assertTrue(back.fromCache)
                assertEquals("A cached page is available even though the archive read mutex is occupied", 1L, fixture.releaseThirdHeader.count)
                fixture.releaseThirdHeader.countDown()
                // A cache hit must leave the useful preview alive instead of cancelling it.
                withTimeout(5_000) { while (repo.cacheSnapshot().committedBytes <= beforeThirdPage) delay(10) }
                assertEquals(0, repo.lastPage(comic.id))
            } finally {
                fixture.releaseThirdHeader.countDown()
                pages.forEach { it.release() }; session?.close(); repo.prefetchEnabled = old
            }
        }
    }

    private class FixtureServer(private val blockThirdHeader: Boolean = false) : Closeable {
        private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
        private val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            repeat(3) { zip.putNextEntry(ZipEntry("page-$it.png")); zip.write(png); zip.closeEntry() }
        } }.toByteArray()
        private val thirdOffset = (0..bytes.size - 4).filter { at -> bytes[at] == 0x50.toByte() && bytes[at + 1] == 0x4b.toByte() && bytes[at + 2] == 3.toByte() && bytes[at + 3] == 4.toByte() }[2]
        private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        val thirdHeaderStarted = CompletableDeferred<Unit>()
        val releaseThirdHeader = CountDownLatch(1)
        private val accept = Thread {
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
                    val start = values[0].toInt(); val end = values[1].toInt()
                    requests.incrementAndGet()
                    if (blockThirdHeader && start == thirdOffset) {
                        thirdHeaderStarted.complete(Unit)
                        releaseThirdHeader.await(8, TimeUnit.SECONDS)
                    }
                    val output = socket.getOutputStream()
                    runCatching {
                        output.write(("HTTP/1.1 206 Partial Content\r\nETag: \"window-fixture-v1\"\r\nContent-Range: bytes $start-$end/${bytes.size}\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n").toByteArray())
                        output.write(bytes, start, end - start + 1); output.flush()
                    }
                } }
            }
        }.apply { isDaemon = true; start() }

        fun comic() = DemoComic("window-${UUID.randomUUID()}", "Generated window test", "", "fixture.cbz", sourceUrl = "http://127.0.0.1:${server.localPort}/fixture.cbz")
        override fun close() { releaseThirdHeader.countDown(); server.close(); accept.join(1000); workers.shutdownNow() }
    }
}
