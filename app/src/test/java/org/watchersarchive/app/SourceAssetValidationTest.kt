package org.watchersarchive.app

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SourceAssetValidationTest {
    private lateinit var server: ServerSocket
    private val workers = Executors.newCachedThreadPool()
    private lateinit var acceptThread: Thread
    private val requests = CopyOnWriteArrayList<String>()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg==")
    private lateinit var archive: ByteArray
    private val base get() = "http://127.0.0.1:${server.localPort}"
    // Plain JVM tests inject a decoder contract. Android BitmapFactory itself is
    // exercised by ReaderDeviceTest and the separate Android runtime suite.
    private val adapter = GetComicsCatalogService(decodeImage = { it.contentEquals(png) })

    @Before fun setUp() {
        archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                repeat(4) { page -> zip.putNextEntry(ZipEntry("page-${page + 1}.png")); zip.write(png); zip.closeEntry() }
            }
        }.toByteArray()
        server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        acceptThread = Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: java.io.IOException) { break }
                workers.submit { serve(client) }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun serve(client: Socket) {
        client.use {
            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = reader.readLine() ?: return
            val path = request.split(' ')[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            requests += "$path:${headers["range"]}"
            val payload = when (path) {
                "/html.cbz" -> "<html>Download gate, not a comic</html>".toByteArray()
                "/rar" -> "Rar!\u001a\u0007\u0000some archive data".toByteArray()
                "/corrupt.cbz" -> archive.copyOf().apply { this[size - 22] = 0 }
                else -> archive
            }
            val range = headers["range"]?.removePrefix("bytes=")?.split('-')
            val output = client.getOutputStream()
            if (path == "/dls/redirect") {
                output.write("HTTP/1.1 302 Found\r\nLocation: /valid.cbz\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                return
            }
            if (path.startsWith("/dls/") || path == "/ignored.cbz" || range == null) {
                output.write("HTTP/1.1 200 OK\r\nContent-Type: ${if (path.startsWith("/dls/")) "text/html" else "application/octet-stream"}\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                runCatching { output.write(payload) }
            } else {
                val start = range[0].toInt(); val end = range[1].toInt()
                val count = end - start + 1
                val tag = if (path == "/weak.cbz") "W/\"v1\"" else "\"v1\""
                output.write("HTTP/1.1 206 Partial Content\r\nETag: $tag\r\nContent-Range: bytes $start-$end/${payload.size}\r\nContent-Length: $count\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                runCatching { output.write(payload, start, count) }
            }
            output.flush()
        }
    }

    @After fun tearDown() { server.close(); acceptThread.join(1000); workers.shutdownNow() }

    private fun asset(path: String) = SourceAsset("asset", "post:1", "127.0.0.1", "$base$path", path.substringAfterLast('/'), null, null, SourceAssetStatus.METADATA_ONLY, "", "")

    @Test fun validCbzNeedsDirectoryPageCrcAndImageDecodeBeforeReady() = runBlocking {
        val checked = adapter.validateAsset(asset("/valid.cbz"))
        assertEquals(SourceAssetStatus.READY, checked.status)
        assertEquals("\"v1\"", checked.verifiedETag)
        assertEquals(archive.size.toLong(), checked.verifiedLength)
        assertEquals(4, checked.verifiedPageCount)
        assertEquals("$base/valid.cbz", checked.verifiedUrl)
        assertTrue("Validation must go beyond one-byte HTTP capability", requests.size >= 6)
    }

    @Test fun onlyValidatedFirstPagesArePassedToTheSharedReaderCache() = runBlocking {
        var commits = 0
        val cachingAdapter = GetComicsCatalogService(decodeImage = { it.contentEquals(png) }, onValidatedPage = { index, bytes ->
            assertEquals(4, index.pages.size)
            assertTrue(bytes.contentEquals(png))
            commits++
        })
        assertEquals(SourceAssetStatus.READY, cachingAdapter.validateAsset(asset("/valid.cbz")).status)
        assertEquals(SourceAssetStatus.UNSUPPORTED_FORMAT, cachingAdapter.validateAsset(asset("/html.cbz")).status)
        assertEquals(1, commits)
    }

    @Test fun htmlWithValid206AndStrongEtagNeverBecomesReady() = runBlocking {
        val checked = adapter.validateAsset(asset("/html.cbz"))
        assertEquals(SourceAssetStatus.UNSUPPORTED_FORMAT, checked.status)
        assertNull(checked.verifiedETag)
        assertTrue(requests.size >= 2)
    }

    @Test fun malformedRar4CannotBeMarkedAsReady() = runBlocking {
        assertEquals(SourceAssetStatus.UNSUPPORTED_FORMAT, adapter.validateAsset(asset("/rar")).status)
        assertTrue(requests.size >= 2)
    }

    @Test fun ignoredRangeAndWeakEtagCannotBecomeReady() = runBlocking {
        assertEquals(SourceAssetStatus.RANGE_UNSUPPORTED, adapter.validateAsset(asset("/ignored.cbz")).status)
        assertEquals(SourceAssetStatus.METADATA_ONLY, adapter.validateAsset(asset("/weak.cbz")).status)
    }

    @Test fun corruptDirectoryAndUndecodableImageCannotBecomeReady() = runBlocking {
        assertEquals(SourceAssetStatus.UNSUPPORTED_FORMAT, adapter.validateAsset(asset("/corrupt.cbz")).status)
        val rejectingDecoder = GetComicsCatalogService(decodeImage = { false })
        assertEquals(SourceAssetStatus.UNSUPPORTED_FORMAT, rejectingDecoder.validateAsset(asset("/valid.cbz")).status)
    }

    @Test fun gatedHtmlResponseNeverBecomesAReadableAsset() = runBlocking {
        val checked = adapter.validateAsset(asset("/dls/opaque"))
        assertEquals(SourceAssetStatus.NEEDS_USER_ACTION, checked.status)
        assertEquals(1, requests.size) // Header-only normal request; HTML cannot become a file.
    }

    @Test fun normalDls302IsResolvedThenTheActualCbzIsValidated() = runBlocking {
        val checked = adapter.validateAsset(asset("/dls/redirect"))
        assertEquals(SourceAssetStatus.READY, checked.status)
        assertEquals("$base/valid.cbz", checked.verifiedUrl)
        assertTrue(requests.first().startsWith("/dls/redirect:"))
        assertTrue(requests.any { it.startsWith("/valid.cbz:") })
    }

    @Test fun zipMagicWinsOverAMisleadingCbrExtension() = runBlocking {
        val checked = adapter.validateAsset(asset("/actually-zip.cbr").copy(declaredFormat = "CBR"))
        assertEquals(SourceAssetStatus.READY, checked.status)
        assertEquals("CBZ", checked.declaredFormat)
    }
}
