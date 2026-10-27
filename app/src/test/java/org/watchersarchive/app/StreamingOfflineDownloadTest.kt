package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.watchersarchive.core.*
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.Base64
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Virtual byte-pattern HTTP objects exercise streaming without a large fixture array. */
class StreamingOfflineDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = OfflineDownloadStore(temporary.newFolder())
    private fun spec(server: PatternServer, id: String = "fixture") =
        OfflineDownloadSpec(id, "Synthetic streaming fixture", server.url, "\"stream-v1\"", server.logicalLength)
    private fun spec(server: CapServer, id: String = "fixture") =
        OfflineDownloadSpec(id, "Synthetic streaming fixture", server.url, "\"stream-v1\"", server.logicalLength)
    private fun engine(store: OfflineDownloadStore, factory: (OfflineDownloadSpec) -> ArchiveByteSource) =
        OfflineDownloadEngine(store, sourceFactory = factory, freeSpace = { Long.MAX_VALUE }, reserveBytes = 32)

    @Test fun defaultStreamingDownloadRemainsACompleteCbzAfterTheServerStops() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
        val archive = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            repeat(3) { zip.putNextEntry(ZipEntry("test-$it.png")); zip.write(png); zip.closeEntry() }
        } }.toByteArray()
        val store = store()
        val completed = PatternServer(archive.size.toLong(), payload = archive).use { server ->
            val result = OfflineDownloadEngine(store, freeSpace = { Long.MAX_VALUE }, reserveBytes = 32).download(spec(server))
            assertEquals(2, server.requests.size)
            assertArrayEquals(archive, result.file.readBytes())
            result
        }
        val restarted = OfflineDownloadStore(store.directory)
        OfflineArchiveSource(checkNotNull(restarted.completed(completed.spec.comicId))).use { source ->
            val reader = ZipArchiveReader(source)
            val index = reader.open()
            assertEquals(3, index.pages.size)
            repeat(3) { assertArrayEquals(png, reader.readPage(index, it)) }
        }
    }

    @Test fun sixteenMiBStreamingReducesRequestsWithoutIncreasingBodyBufferOrPayload() {
        val length = 32L * 1024 * 1024
        val counts = mutableListOf<Int>()
        val totals = mutableListOf<Long>()
        var maximumBuffer = 0
        for (streaming in listOf(false, true)) PatternServer(length).use { server ->
            val traces = mutableListOf<RangeTrace>()
            val source: ArchiveByteSource = if (!streaming) HttpRangeSource(server.url, ReaderLimits()) { traces += it }
            else {
                val delegate = StreamingHttpRangeSource(server.url, observer = { traces += it })
                object : ArchiveByteSource by delegate, OfflineRangeTransfer {
                    override val maxTransferBytes get() = delegate.maxTransferBytes
                    override fun transferRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken,
                        sink: (ByteArray, Int, Int) -> Unit) = delegate.transferRange(identity, start, length, token) { bytes, offset, count ->
                        maximumBuffer = maxOf(maximumBuffer, bytes.size)
                        sink(bytes, offset, count)
                    }
                }
            }
            val completed = engine(store()) { source }.download(spec(server))
            assertPattern(completed.file, length)
            counts += server.requests.size
            totals += traces.sumOf { it.receivedBytes }
        }
        assertEquals(listOf(9, 3), counts)
        assertEquals(listOf(length + 1, length + 1), totals)
        assertTrue(maximumBuffer in 1..65_536)
        println("STREAMING_FIXTURE logicalBytes=$length oldRequests=${counts[0]} newRequests=${counts[1]} clientBytes=${totals[1]} maxBodyBuffer=$maximumBuffer")
    }

    @Test fun aSourceThatRejectsLargeRangesFallsBackOnceToTheReaderWindow() {
        CapServer(8L * 1024 * 1024).use { server ->
            val store = store(); val completed = engine(store) {
                object : StreamingHttpRangeSource(server.url, maxTransferBytes = 16 * 1024 * 1024) {
                    override fun transferRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken,
                        sink: (ByteArray, Int, Int) -> Unit) {
                        if (length > 4 * 1024 * 1024) throw ArchiveException(ArchiveError.INVALID_LENGTH, "fixture cap")
                        super.transferRange(identity, start, length, token, sink)
                    }
                }
            }.download(spec(server))
            assertEquals(3, server.requests.size)
            assertEquals(8L * 1024 * 1024, completed.file.length())
            assertTrue(server.requests.all { it.length <= 4 * 1024 * 1024 || it.length == 1 })
        }
    }

    @Test fun invalidResponseHeadersNeverReachTheOfflineFileSink() {
        for (mode in listOf("ignored", "bad-range", "bad-length", "weak", "encoded")) PatternServer(256 * 1024L, mode).use { server ->
            val traces = mutableListOf<RangeTrace>()
            val store = store(); val spec = spec(server)
            val source = StreamingHttpRangeSource(server.url, observer = { traces += it })
            val failure = runCatching { engine(store) { source }.download(spec) }.exceptionOrNull()
            assertTrue("$mode must reject the malformed response", failure is ArchiveException)
            assertEquals("Only the one-byte identity probe was consumed: $mode", 1L, traces.sumOf { it.receivedBytes })
            assertEquals(0L, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(0L, store.partial(spec).length())
            assertNull(store.completed(spec.comicId))
        }
    }

    @Test fun truncatedWindowRollsBackAndResumeStartsAtThePreviousDurableBoundary() {
        PatternServer(256 * 1024L, "truncated-second").use { server ->
            val store = store(); val spec = spec(server)
            val failure = runCatching { engine(store) { StreamingHttpRangeSource(it.url, maxTransferBytes = 65_536) }.download(spec) }.exceptionOrNull()
            assertEquals(ArchiveError.INVALID_LENGTH, (failure as ArchiveException).code)
            assertEquals(65_536L, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(65_536L, store.partial(spec).length())
            assertNull(store.completed(spec.comicId))
            val requestsBefore = server.requests.size
            server.mode = "normal"
            val restarted = OfflineDownloadStore(store.directory)
            val completed = engine(restarted) { StreamingHttpRangeSource(it.url, maxTransferBytes = 65_536) }.download(spec)
            assertEquals(65_536L, server.requests.drop(requestsBefore).first { it.length > 1 }.start)
            assertPattern(completed.file, spec.length)
        }
    }

    @Test fun activeCancellationRollsBackProvisionalBytesAndCanResumeSafely() {
        PatternServer(3L * 1024 * 1024, "slow").use { server ->
            val store = store(); val spec = spec(server)
            val token = CancellationToken()
            var provisional = 0L
            val failure = runCatching {
                engine(store) { StreamingHttpRangeSource(it.url, maxTransferBytes = 2 * 1024 * 1024) }.download(spec, token) {
                    if (it.downloadedBytes > 0) { provisional = it.downloadedBytes; token.cancel() }
                }
            }.exceptionOrNull()
            assertEquals(ArchiveError.CANCELLED, (failure as ArchiveException).code)
            assertTrue(provisional > 0)
            assertEquals(0L, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(OfflineDownloadState.PAUSED, store.status(spec.comicId)?.state)
            assertEquals(0L, store.partial(spec).length())
            server.mode = "normal"
            val completed = engine(OfflineDownloadStore(store.directory)) {
                StreamingHttpRangeSource(it.url, maxTransferBytes = 2 * 1024 * 1024)
            }.download(spec)
            assertPattern(completed.file, spec.length)
        }
    }

    @Test fun objectChangesNeverCommitTheNewVersionWindow() {
        PatternServer(256 * 1024L, "changed-second").use { server ->
            val store = store(); val spec = spec(server)
            val failure = runCatching { engine(store) { StreamingHttpRangeSource(it.url, maxTransferBytes = 65_536) }.download(spec) }.exceptionOrNull()
            assertEquals(ArchiveError.OBJECT_CHANGED, (failure as ArchiveException).code)
            assertEquals(65_536L, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(65_536L, store.partial(spec).length())
            assertNull(store.completed(spec.comicId))
        }
    }

    @Test fun chunkedOverrunCannotCheckpointEvenAnApparentlyCompleteFinalWindow() {
        PatternServer(128 * 1024L, "overrun").use { server ->
            val store = store(); val spec = spec(server)
            val failure = runCatching { engine(store) { StreamingHttpRangeSource(it.url) }.download(spec) }.exceptionOrNull()
            assertEquals(ArchiveError.INVALID_LENGTH, (failure as ArchiveException).code)
            assertEquals(0L, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(0L, store.partial(spec).length())
            assertNull(store.completed(spec.comicId))
            val recovered = OfflineDownloadStore(store.directory)
            assertNull(recovered.completed(spec.comicId))
        }
    }

    @Test fun ordinaryReaderLimitsStayIndependentOfTheExplicitStreamingWindow() {
        PatternServer(4096).use { server ->
            val source = StreamingHttpRangeSource(server.url, ReaderLimits(maxRangeBytes = 64), maxTransferBytes = 1024, bufferBytes = 32)
            val identity = source.discover(CancellationToken())
            val requests = server.requests.size
            val failure = runCatching { source.readRange(identity, 0, 65, CancellationToken()) }.exceptionOrNull()
            assertEquals(ArchiveError.LIMIT_EXCEEDED, (failure as ArchiveException).code)
            assertEquals(requests, server.requests.size)
            var received = 0
            source.transferRange(identity, 0, 1024, CancellationToken()) { bytes, offset, count ->
                assertTrue(bytes.size <= 32)
                repeat(count) { assertEquals(pattern((received + it).toLong()), bytes[offset + it]) }
                received += count
            }
            assertEquals(1024, received)
        }
    }

    @Test fun localSinkFailureIsNotMisclassifiedAsARetryableNetworkFailure() {
        PatternServer(4096).use { server ->
            val source = StreamingHttpRangeSource(server.url)
            val failure = IOException("synthetic disk write failure")
            val actual = runCatching {
                source.transferRange(source.discover(CancellationToken()), 0, 1024, CancellationToken()) { _, _, _ -> throw failure }
            }.exceptionOrNull()
            assertSame(failure, actual)
        }
    }

    @Test fun normalRedirectsPreserveRangeAndStrongVersionChecks() {
        PatternServer(4096).use { server ->
            val traces = mutableListOf<RangeTrace>()
            val source = StreamingHttpRangeSource(server.url, observer = { traces += it })
            val identity = source.discover(CancellationToken()).copy(url = server.url.replace("fixture", "redirect"))
            var received = 0
            source.transferRange(identity, 0, 1024, CancellationToken()) { _, _, count -> received += count }
            assertEquals(1024, received)
            assertTrue(traces.any { it.statusCode == 302 && it.receivedBytes == 0L })
            assertTrue(server.requests.filter { it.length > 1 }.all { it.ifMatch == identity.eTag })
        }
    }

    private fun assertPattern(file: File, expected: Long) {
        assertEquals(expected, file.length())
        file.inputStream().use { input ->
            val bytes = ByteArray(32 * 1024)
            var position = 0L
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                repeat(count) { index -> if (bytes[index] != pattern(position + index)) fail("Pattern mismatch at ${position + index}") }
                position += count
            }
            assertEquals(expected, position)
        }
    }

    private data class Request(val start: Long, val length: Int, val ifMatch: String?)

    private class PatternServer(val logicalLength: Long, @Volatile var mode: String = "normal", private val payload: ByteArray? = null) : Closeable {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val url = "http://127.0.0.1:${socket.localPort}/fixture"
        private val accept = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                workers.submit { client.use { connection -> runCatching {
                    connection.soTimeout = 5000
                    val input = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val requestLine = input.readLine() ?: return@runCatching
                    var range = ""; var ifMatch: String? = null
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                        if (line.startsWith("If-Match:", true)) ifMatch = line.substringAfter(':').trim()
                    }
                    val fields = range.removePrefix("bytes=").split('-')
                    val start = fields[0].toLong(); val end = fields[1].toLong(); val length = (end - start + 1).toInt()
                    requests += Request(start, length, ifMatch)
                    val output = connection.getOutputStream()
                    if (requestLine.contains(" /redirect ")) {
                        output.write("HTTP/1.1 302 Found\r\nLocation: /fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush(); return@runCatching
                    }
                    val selectedMode = mode
                    val transfer = length > 1
                    val changed = transfer && selectedMode == "changed-second" && start >= 65_536
                    val status = if (transfer && selectedMode == "ignored") 200 else if (changed) 412 else 206
                    val chunked = transfer && selectedMode == "overrun"
                    val sentStart = if (transfer && selectedMode == "bad-range") start + 1 else start
                    val tag = if (changed) "\"stream-v2\"" else if (transfer && selectedMode == "weak") "W/\"stream-v1\"" else "\"stream-v1\""
                    val headers = buildString {
                        append("HTTP/1.1 $status Test\r\nETag: $tag\r\nContent-Range: bytes $sentStart-$end/$logicalLength\r\n")
                        if (transfer && selectedMode == "encoded") append("Content-Encoding: gzip\r\n")
                        if (chunked) append("Transfer-Encoding: chunked\r\n")
                        else append("Content-Length: ${if (transfer && selectedMode == "bad-length") length + 1 else if (status == 412) 0 else length}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    output.write(headers.toByteArray(Charsets.US_ASCII)); output.flush()
                    if (status != 206 || (transfer && selectedMode in setOf("bad-range", "bad-length", "weak", "encoded"))) return@runCatching
                    val bodyBytes = if (selectedMode == "truncated-second" && transfer && start >= 65_536) length / 2 else length + if (chunked) 1 else 0
                    var sent = 0
                    val block = ByteArray(8192)
                    while (sent < bodyBytes) {
                        val count = minOf(block.size, bodyBytes - sent)
                        repeat(count) { block[it] = payload?.get((start + sent + it).toInt()) ?: pattern(start + sent + it) }
                        if (chunked) output.write("${count.toString(16)}\r\n".toByteArray())
                        output.write(block, 0, count)
                        if (chunked) output.write("\r\n".toByteArray())
                        output.flush(); sent += count
                        if (transfer && selectedMode == "slow") Thread.sleep(2)
                    }
                    if (chunked) { output.write("0\r\n\r\n".toByteArray()); output.flush() }
                } } }
            }
        }.apply { isDaemon = true; start() }

        override fun close() { socket.close(); accept.join(1000); workers.shutdownNow() }
    }

    private class CapServer(val logicalLength: Long) : Closeable {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val url = "http://127.0.0.1:${socket.localPort}/cap"
        private val accept = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                workers.submit { client.use { connection -> runCatching {
                    val input = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                    input.readLine() ?: return@runCatching
                    var range = ""
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim() }
                    val fields = range.removePrefix("bytes=").split('-'); val start = fields[0].toLong(); val end = fields[1].toLong(); val length = (end - start + 1).toInt()
                    requests += Request(start, length, null)
                    val output = connection.getOutputStream()
                    output.write("HTTP/1.1 206 Partial Content\r\nETag: \"stream-v1\"\r\nContent-Range: bytes $start-$end/$logicalLength\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                    var sent = 0
                    val block = ByteArray(8192)
                    while (sent < length) { val count = minOf(block.size, length - sent); repeat(count) { block[it] = pattern(start + sent + it) }; output.write(block, 0, count); sent += count }
                    output.flush()
                } } }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); accept.join(1000); workers.shutdownNow() }
    }

    companion object {
        private fun pattern(position: Long) = ((position * 31 + 17) and 255).toByte()
    }
}
