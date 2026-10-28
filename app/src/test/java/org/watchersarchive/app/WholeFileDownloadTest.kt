package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.watchersarchive.core.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WholeFileDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = OfflineDownloadStore(temporary.newFolder())
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
    private fun cbz(): ByteArray = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        repeat(3) { zip.putNextEntry(ZipEntry("test-$it.png")); zip.write(png); zip.closeEntry() }
    } }.toByteArray()
    private fun spec(server: FullServer, verified: Boolean = true, id: String = "full-test") = OfflineDownloadSpec(id,
        "Synthetic complete download", server.url, if (verified) "\"full-v1\"" else "\"local-attempt-v1\"",
        server.body.size.toLong(), "test.cbz", OfflineTransferMode.FULL, verified)
    private fun validateZip(spec: OfflineDownloadSpec, file: File, token: CancellationToken) {
        assertEquals(spec.length, file.length())
        PrivateArchiveFileSource(file).use { source ->
            ZipArchiveReader(source).use { reader ->
                val index = reader.open(token)
                assertEquals(3, index.pages.size)
                repeat(3) { assertArrayEquals(png, reader.readPage(index, it, token)) }
            }
        }
    }

    @Test fun normalHttp200SourceDownloadsWithoutRangeThenReadsAfterServerStops() {
        val store = store()
        val completed = FullServer(cbz()).use { server ->
            val spec = spec(server)
            val result = OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }, validateComplete = ::validateZip).download(spec)
            assertArrayEquals(server.body, result.file.readBytes())
            assertEquals(1, server.requests.size)
            assertNull(server.requests.single()["range"])
            assertEquals(spec.eTag, server.requests.single()["if-match"])
            result
        }
        val restarted = OfflineDownloadStore(store.directory)
        OfflineArchiveSource(checkNotNull(restarted.completed(completed.spec.comicId))).use { source ->
            ZipArchiveReader(source).use { reader ->
                val index = reader.open()
                assertEquals(3, index.pages.size)
                assertArrayEquals(png, reader.readPage(index, 2))
            }
        }
    }

    @Test fun sourceWithoutEtagUsesNoIfMatchAndInterruptedFullDownloadRestartsFromZero() {
        val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        FullServer(body, tag = null).use { server ->
            val store = store(); val spec = spec(server, verified = false)
            val token = CancellationToken()
            val failure = runCatching { OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }).download(spec, token) {
                if (it.downloadedBytes >= 1024 * 1024) token.cancel()
            } }.exceptionOrNull()
            assertEquals(ArchiveError.CANCELLED, (failure as ArchiveException).code)
            assertEquals(0L, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(0L, store.partial(spec).length())
            assertNull(store.completed(spec.comicId))
            val result = OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }).download(spec)
            assertArrayEquals(body, result.file.readBytes())
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.all { it["range"] == null && it["if-match"] == null })
        }
    }

    @Test fun prefixAndMimeHtmlNeverBecomeCompletedComics() {
        for ((type, bytes) in listOf("text/html" to "<html>verification page</html>".toByteArray(),
            "application/octet-stream" to "<!doctype html><html>quota exceeded</html>".toByteArray())) {
            FullServer(bytes, contentType = type).use { server ->
                val store = store(); val spec = spec(server)
                val failure = runCatching { OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }).download(spec) }.exceptionOrNull()
                assertEquals(ArchiveError.UNSUPPORTED_ARCHIVE, (failure as ArchiveException).code)
                assertNull(store.completed(spec.comicId)); assertEquals(0L, store.status(spec.comicId)?.downloadedBytes)
                assertEquals(0L, store.partial(spec).length())
            }
        }
    }

    @Test fun localArchiveValidationRejectsCorruptCompleteBodyBeforeCommit() {
        FullServer(ByteArray(4096) { 7 }).use { server ->
            val store = store(); val spec = spec(server)
            val failure = runCatching { OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }, validateComplete = ::validateZip).download(spec) }.exceptionOrNull()
            assertTrue(failure is ArchiveException)
            assertNull(store.completed(spec.comicId))
            assertEquals(0L, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(0L, store.partial(spec).length())
        }
    }

    @Test fun truncatedBodyRollsBackEntireFullFile() {
        FullServer(ByteArray(2048) { 3 }, truncate = true).use { server ->
            val store = store(); val spec = spec(server)
            val failure = runCatching { OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }).download(spec) }.exceptionOrNull()
            assertEquals(ArchiveError.INVALID_LENGTH, (failure as ArchiveException).code)
            assertNull(store.completed(spec.comicId)); assertEquals(0L, store.partial(spec).length())
        }
    }

    @Test fun changedOrMissingVerifiedVersionRejectsBeforeFileSink() {
        for (tag in listOf("\"full-v2\"", null, "W/\"full-v1\"")) FullServer(cbz(), tag = tag).use { server ->
            val store = store(); val spec = spec(server)
            val failure = runCatching { OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }).download(spec) }.exceptionOrNull()
            assertTrue(failure is ArchiveException)
            assertTrue((failure as ArchiveException).code in setOf(ArchiveError.IDENTITY_REQUIRED, ArchiveError.OBJECT_CHANGED))
            assertNull(store.completed(spec.comicId)); assertEquals(0L, store.partial(spec).length())
        }
    }

    @Test fun sourceKeepsItsBodyBufferAtMost64KiBEvenForLargerPayload() {
        FullServer(ByteArray(200_000) { (it % 251).toByte() }, tag = null).use { server ->
            val spec = spec(server, verified = false)
            var maximumBuffer = 0; var bytes = 0L
            WholeFileHttpSource(spec).transferWhole(CancellationToken()) { buffer, _, count ->
                maximumBuffer = maxOf(maximumBuffer, buffer.size); bytes += count
            }
            assertEquals(spec.length, bytes)
            assertTrue(maximumBuffer in 1..65536)
        }
    }

    @Test fun virtualLargeSizeWithLowSpaceNeverConnectsOrExpandsAFile() {
        val store = store()
        val spec = OfflineDownloadSpec("large", "Virtual 3GiB", "http://127.0.0.1:1/not-connected", "\"local-large\"",
            3L * 1024 * 1024 * 1024, mode = OfflineTransferMode.FULL, verifyRemoteETag = false)
        val failure = runCatching { OfflineDownloadEngine(store, freeSpace = { 1L shl 30 }).download(spec) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("空间不足") == true)
        assertFalse(store.partial(spec).exists())
    }

    @Test fun discoverRequestCanNeverEnterTheFileEngine() {
        val store = store()
        val pending = OfflineDownloadSpec("pending", "Source selection", "https://example.test/article", "", 0,
            mode = OfflineTransferMode.DISCOVER, verifyRemoteETag = false)
        assertTrue(runCatching { OfflineDownloadEngine(store).download(pending) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(store.status(pending.comicId))
    }

    @Test fun privateValidationSourceRejectsMutationAndOversizedRange() {
        val file = temporary.newFile().apply { writeBytes(cbz()) }
        PrivateArchiveFileSource(file, ReaderLimits(maxRangeBytes = 64)).use { source ->
            val identity = source.discover()
            assertEquals(ArchiveError.INVALID_LENGTH, (runCatching { source.readRange(identity, 0, 65) }.exceptionOrNull() as ArchiveException).code)
            file.appendBytes(byteArrayOf(1))
            assertEquals(ArchiveError.OBJECT_CHANGED, (runCatching { source.readRange(identity, 0, 1) }.exceptionOrNull() as ArchiveException).code)
        }
    }

    @Test fun observerFailureAfterCompletionCannotDowngradeTheStoredBook() {
        FullServer(cbz()).use { server ->
            val store = store(); val spec = spec(server)
            val completed = OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }, validateComplete = ::validateZip).download(spec) {
                if (it.state == OfflineDownloadState.COMPLETED) error("observer is gone")
            }
            assertTrue(completed.file.exists())
            assertEquals(OfflineDownloadState.COMPLETED, store.status(spec.comicId)?.state)
        }
    }

    @Test fun verifiedFullFileKeepsItsCheckpointWhenFinalRenameFailsAndCanRecoverWithoutNetwork() {
        val store = store()
        val archive = cbz()
        lateinit var downloadedSpec: OfflineDownloadSpec
        FullServer(archive).use { server ->
            val spec = spec(server).also { downloadedSpec = it }
            val target = store.archive(spec)
            val failure = runCatching {
                OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }, validateComplete = { checked, file, token ->
                    validateZip(checked, file, token)
                    // A non-empty directory makes final rename fail reliably on
                    // Windows and Unix, after the validated length checkpoint.
                    check(target.mkdir())
                    File(target, "rename-blocker").writeText("owned synthetic fixture")
                }).download(spec)
            }.exceptionOrNull()
            assertNotNull(failure)
            assertNull(store.completed(spec.comicId))
            assertEquals(OfflineDownloadState.FAILED, store.status(spec.comicId)?.state)
            assertEquals("A post-validation commit failure retains the durable length", spec.length, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(spec.length, store.partial(spec).length())
            assertEquals(1, server.requests.size)

            // Move the already validated bytes after removing our own obstacle.
            // This reproduces the durable state of rename succeeding immediately
            // before the final metadata write fails, without a timing race.
            check(File(target, "rename-blocker").delete())
            check(target.delete())
            java.nio.file.Files.move(store.partial(spec).toPath(), target.toPath())
        }
        val restarted = OfflineDownloadStore(store.directory)
        assertEquals(OfflineDownloadState.COMPLETED, restarted.status(downloadedSpec.comicId)?.state)
        val recovered = OfflineDownloadEngine(restarted, sourceFactory = { error("Recovered data must not contact a source") })
            .download(downloadedSpec)
        assertArrayEquals(archive, recovered.file.readBytes())
    }

    private class FullServer(
        val body: ByteArray,
        private val tag: String? = "\"full-v1\"",
        private val contentType: String = "application/octet-stream",
        private val truncate: Boolean = false,
    ) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val requests: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
        val url get() = "http://127.0.0.1:${socket.localPort}/test.cbz"
        private val accepting = Thread {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: Exception) { break }
                workers.submit { connection.use { client ->
                    val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    requests += headers
                    runCatching {
                        val output = client.getOutputStream()
                        output.write(buildString {
                            append("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
                            if (tag != null) append("ETag: $tag\r\n")
                            append("\r\n")
                        }.toByteArray())
                        val count = if (truncate) body.size / 2 else body.size
                        var offset = 0
                        while (offset < count) {
                            val length = minOf(4096, count - offset)
                            output.write(body, offset, length); offset += length
                        }
                        output.flush()
                    }
                } }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); accepting.join(1000); workers.shutdownNow() }
    }
}
