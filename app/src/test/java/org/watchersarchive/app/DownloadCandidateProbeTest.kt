package org.watchersarchive.app

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.watchersarchive.core.ReaderLimits

class DownloadCandidateProbeTest {
    private val record = CatalogSourceRecord("getcomics", "probe", "https://getcomics.org/marvel/fixture/", "Generated",
        null, null, null, null, "2026-10-28T00:00:00Z", emptyList(), null, null, null, SourceConfidence.MEDIUM)
    private fun asset(url: String) = SourceAsset("asset", record.externalId, "127.0.0.1", url, null, null, null,
        SourceAssetStatus.METADATA_ONLY, "", "2026-10-28T00:00:00Z")
    private fun probe() = DownloadCandidateProbe(ReaderLimits(connectTimeoutMillis = 1_000, readTimeoutMillis = 1_000))

    @Test fun aStrongVersionedPartialResponseSelectsResumableRangeMode() = runBlocking {
        Fixture().use { server ->
            val spec = probe().probe(record, asset(server.url))
            assertEquals(OfflineTransferMode.RANGE, spec.mode)
            assertTrue(spec.verifyRemoteETag); assertEquals("\"v1\"", spec.eTag)
            assertEquals(256L, spec.length)
            assertEquals("Generated Book.cbz", spec.fileName)
            assertEquals("bytes=0-0", server.range)
        }
    }

    @Test fun aNormal200FileSelectsFullModeWithoutWaitingForOrReadingItsBody() = runBlocking {
        Fixture(status = 200, blockBody = true, tag = null).use { server ->
            val spec = probe().probe(record, asset(server.url))
            assertEquals(OfflineTransferMode.FULL, spec.mode)
            assertFalse(spec.verifyRemoteETag)
            assertTrue(spec.eTag.startsWith("\"download-"))
            assertEquals(256L, spec.length)
            assertEquals("The server has not released any response body", 1L, server.releaseBody.count)
        }
    }

    @Test fun aVersioned200FileStillUsesWholeFileModeAndRetainsItsRealTag() = runBlocking {
        Fixture(status = 200, blockBody = true).use { server ->
            val spec = probe().probe(record, asset(server.url))
            assertEquals(OfflineTransferMode.FULL, spec.mode); assertTrue(spec.verifyRemoteETag)
            assertEquals("\"v1\"", spec.eTag)
        }
    }

    @Test fun aPartialResponseWithoutStrongVersionUsesNonresumableFullMode() = runBlocking {
        for (tag in listOf(null, "W/\"v1\"")) Fixture(tag = tag).use { server ->
            val spec = probe().probe(record, asset(server.url))
            assertEquals(OfflineTransferMode.FULL, spec.mode)
            assertFalse(spec.verifyRemoteETag)
        }
    }

    @Test fun htmlAndUnknownFileLengthsAreRejectedWithoutConsumingTheBody() = runBlocking {
        for (fixture in listOf(Fixture(status = 200, contentType = "text/html", blockBody = true),
            Fixture(status = 200, declaredLength = false, blockBody = true))) fixture.use { server ->
            assertTrue(runCatching { probe().probe(record, asset(server.url)) }.exceptionOrNull() is ComicSourceException)
            assertEquals(1L, server.releaseBody.count)
        }
    }

    @Test fun incorrectContentRangesAndHostDenialsDoNotBecomeFiles() = runBlocking {
        for (fixture in listOf(Fixture(contentRange = "bytes 1-1/256", blockBody = true),
            Fixture(status = 403, blockBody = true), Fixture(status = 429, blockBody = true))) fixture.use { server ->
            val error = runCatching { probe().probe(record, asset(server.url)) }.exceptionOrNull()
            assertTrue(error is ComicSourceException)
            assertEquals(1L, server.releaseBody.count)
        }
    }

    @Test fun redirectLoopsHaveAFixedRequestBudget() = runBlocking {
        Fixture(status = 302, blockBody = true).use { server ->
            assertTrue(runCatching { probe().probe(record, asset(server.url)) }.exceptionOrNull() is ComicSourceException)
            assertEquals(6, server.requests.get())
        }
    }

    @Test fun unicodeDispositionNamesAreDecodedWithoutTreatingThemAsPaths() = runBlocking {
        Fixture(disposition = "attachment; filename*=UTF-8''..%2F..%2F%E8%9C%98%E8%9B%9B%E4%BE%A0%2B1.cbz").use { server ->
            assertEquals("蜘蛛侠+1.cbz", probe().probe(record, asset(server.url)).fileName)
        }
    }

    private class Fixture(
        private val status: Int = 206,
        private val tag: String? = "\"v1\"",
        private val contentType: String = "application/octet-stream",
        private val contentRange: String = "bytes 0-0/256",
        private val declaredLength: Boolean = true,
        private val blockBody: Boolean = false,
        private val disposition: String = "attachment; filename=\"Generated Book.cbz\"",
    ) : Closeable {
        private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val releaseBody = CountDownLatch(1)
        val requests = AtomicInteger()
        @Volatile var range: String? = null
        val url get() = "http://127.0.0.1:${server.localPort}/book.cbz"
        private val accept = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                workers.submit { socket.use { client ->
                    val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    input.readLine() ?: return@use
                    while (true) {
                        val header = input.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Range:", true)) range = header.substringAfter(':').trim()
                    }
                    requests.incrementAndGet()
                    val size = if (status == 206) 1 else 256
                    val headers = buildString {
                        append("HTTP/1.1 $status Fixture\r\nConnection: close\r\nContent-Type: $contentType\r\n")
                        if (status == 302) append("Location: /loop\r\n")
                        if (tag != null) append("ETag: $tag\r\n")
                        if (status == 206) append("Content-Range: $contentRange\r\n")
                        if (declaredLength) append("Content-Length: $size\r\n")
                        append("Content-Disposition: $disposition\r\n\r\n")
                    }
                    runCatching {
                        val out = client.getOutputStream()
                        out.write(headers.toByteArray(Charsets.US_ASCII)); out.flush()
                        if (blockBody) releaseBody.await(3, TimeUnit.SECONDS)
                        out.write(ByteArray(size) { 0x50 }); out.flush()
                    }
                } }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { releaseBody.countDown(); server.close(); accept.join(500); workers.shutdownNow() }
    }
}
