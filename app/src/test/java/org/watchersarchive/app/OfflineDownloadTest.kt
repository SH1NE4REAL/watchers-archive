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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflineDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val limits = ReaderLimits(maxRangeBytes = 64)
    private fun store() = OfflineDownloadStore(temporary.newFolder())
    private fun spec(url: String = "https://example.test/book.cbz", length: Long = 256L, id: String = "comic") =
        OfflineDownloadSpec(id, "Synthetic offline test", url, "\"fixture-v1\"", length, "../../outside.cbz")
    private fun engine(store: OfflineDownloadStore, source: ArchiveByteSource, free: Long = Long.MAX_VALUE) =
        OfflineDownloadEngine(store, limits, { source }, { free }, reserveBytes = 32, chunkBytes = 64)

    @Test fun fullHttpDownloadIsVersionCheckedAndReadableWithoutAnyServer() {
        val bytes = comicBytes()
        val store = store()
        val traces = mutableListOf<RangeTrace>()
        val download = FixtureServer(bytes).use { fixture ->
            val spec = spec(fixture.url, bytes.size.toLong())
            val result = OfflineDownloadEngine(store, limits, { HttpRangeSource(it.url, limits) { trace -> traces += trace } },
                { Long.MAX_VALUE }, 32, 64).download(spec)
            assertArrayEquals(bytes, result.file.readBytes())
            assertTrue(traces.all { it.statusCode == 206 && it.receivedBytes in 1..64 })
            assertTrue(fixture.ifMatchHeaders.drop(1).all { it == spec.eTag })
            assertEquals(bytes.size + 1L, traces.sumOf { it.receivedBytes })
            result
        }
        val restarted = OfflineDownloadStore(store.directory)
        val completed = checkNotNull(restarted.completed(download.spec.comicId))
        OfflineArchiveSource(completed, ReaderLimits()).use { source ->
            val reader = ZipArchiveReader(source)
            val index = reader.open()
            assertEquals(3, index.pages.size)
            assertEquals(download.spec.eTag, index.identity.eTag)
            repeat(3) { assertArrayEquals(png, reader.readPage(index, it)) }
        }
        assertEquals(OfflineDownloadState.COMPLETED, restarted.status(download.spec.comicId)?.state)
    }

    @Test fun cancellationKeepsOnlyDurableCheckpointAndResumeSkipsCommittedBytes() {
        val bytes = ByteArray(256) { it.toByte() }
        val store = store(); val spec = spec()
        val token = CancellationToken(); val first = MemorySource(bytes)
        val failure = runCatching { engine(store, first).download(spec, token) { if (it.downloadedBytes >= 64) token.cancel() } }.exceptionOrNull()
        assertEquals(ArchiveError.CANCELLED, (failure as ArchiveException).code)
        assertNull(store.completed(spec.comicId))
        assertEquals(64L, store.status(spec.comicId)?.downloadedBytes)
        assertEquals(OfflineDownloadState.PAUSED, store.status(spec.comicId)?.state)
        // An uncommitted tail from a crash must be discarded before resuming.
        store.partial(spec).appendBytes(ByteArray(17) { 99 })
        val restarted = OfflineDownloadStore(store.directory)
        assertEquals(64L, restarted.partial(spec).length())
        val resumed = MemorySource(bytes)
        val completed = engine(restarted, resumed).download(spec)
        assertEquals(listOf(64L, 128L, 192L), resumed.starts)
        assertArrayEquals(bytes, completed.file.readBytes())
        assertEquals(OfflineDownloadState.COMPLETED, restarted.status(spec.comicId)?.state)
    }

    @Test fun truncatedChunkIsNotWrittenOrMarkedComplete() {
        val bytes = ByteArray(256)
        FixtureServer(bytes, mode = "truncated").use { fixture ->
            val store = store(); val spec = spec(fixture.url)
            val failure = runCatching { OfflineDownloadEngine(store, limits, { HttpRangeSource(it.url, limits) }, { Long.MAX_VALUE }, 32, 64).download(spec) }.exceptionOrNull()
            assertEquals(ArchiveError.INVALID_LENGTH, (failure as ArchiveException).code)
            assertNull(store.completed(spec.comicId))
            assertEquals(0L, store.status(spec.comicId)?.downloadedBytes)
            assertEquals(0L, store.partial(spec).length())
        }
    }

    @Test fun ignoredRangeAndIncorrectContentRangeConsumeNoResponseBody() {
        for (mode in listOf("ignored", "bad-range")) {
            FixtureServer(ByteArray(256), mode).use { fixture ->
                val store = store(); val spec = spec(fixture.url)
                val traces = mutableListOf<RangeTrace>()
                val failure = runCatching { OfflineDownloadEngine(store, limits,
                    { HttpRangeSource(it.url, limits) { trace -> traces += trace } }, { Long.MAX_VALUE }, 32, 64).download(spec) }.exceptionOrNull()
                assertTrue(failure is ArchiveException)
                assertEquals(0L, traces.sumOf { it.receivedBytes })
                assertNull(store.completed(spec.comicId))
                assertFalse(store.partial(spec).exists())
            }
        }
    }

    @Test fun changedObjectNeverCombinesDifferentVersions() {
        FixtureServer(ByteArray(256), "changed").use { fixture ->
            val store = store(); val spec = spec(fixture.url)
            val failure = runCatching { OfflineDownloadEngine(store, limits, { HttpRangeSource(it.url, limits) }, { Long.MAX_VALUE }, 32, 64).download(spec) }.exceptionOrNull()
            assertEquals(ArchiveError.OBJECT_CHANGED, (failure as ArchiveException).code)
            assertEquals(0L, store.status(spec.comicId)?.downloadedBytes)
            assertNull(store.completed(spec.comicId))
        }
    }

    @Test fun lowStorageRejectsMultiGigabyteDownloadBeforeFileExpansionOrPageReads() {
        val total = 3L * 1024 * 1024 * 1024
        val source = MemorySource(ByteArray(0), total)
        val store = store(); val spec = spec(length = total)
        val failure = runCatching { engine(store, source, free = 1L shl 30).download(spec) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("空间不足"))
        assertEquals(0, source.starts.size)
        assertEquals(total, store.status(spec.comicId)?.totalBytes)
        assertFalse(store.partial(spec).exists())
        assertNull(store.completed(spec.comicId))
    }

    @Test fun concurrentStoresCannotDownloadOrRemoveTheSameActiveAsset() {
        val store = store(); val spec = spec()
        val started = CountDownLatch(1); val proceed = CountDownLatch(1)
        val source = object : MemorySource(ByteArray(256)) {
            override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
                if (start == 0L) { started.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS)) }
                return super.readRange(identity, start, length, token)
            }
        }
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { engine(store, source).download(spec) } catch (error: Throwable) { failure.set(error) } }.apply { start() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = OfflineDownloadStore(store.directory)
            assertTrue(runCatching { engine(second, MemorySource(ByteArray(256))).download(spec) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { second.remove(spec.comicId) }.exceptionOrNull() is IllegalStateException)
            assertEquals(OfflineDownloadState.DOWNLOADING, second.status(spec.comicId)?.state)
        } finally { proceed.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive); assertNull(failure.get())
        assertNotNull(store.completed(spec.comicId))
    }

    @Test fun multipleReaderLeasesProtectOfflineFileUntilAllReadersClose() {
        val store = store(); val spec = spec()
        val completed = engine(store, MemorySource(ByteArray(256))).download(spec)
        val first = OfflineArchiveSource(completed); val second = OfflineArchiveSource(completed)
        first.discover(); second.discover()
        assertTrue(runCatching { store.remove(spec.comicId) }.exceptionOrNull() is IllegalStateException)
        first.close(); first.close()
        assertTrue(runCatching { store.remove(spec.comicId) }.exceptionOrNull() is IllegalStateException)
        second.close(); store.remove(spec.comicId)
        assertFalse(completed.file.exists()); assertNull(store.status(spec.comicId))
    }

    @Test fun offlineRemovalDoesNotTouchPersistentLibraryOrTemporaryCache() {
        val root = temporary.newFolder()
        val cache = File(root, "temporary-cache").apply { mkdirs() }
        val sentinel = File(cache, "page").apply { writeText("cache") }
        PersistentLibrary(File(root, "persistent-library")).use { library ->
            library.registerComic("comic", "Test", "source", false)
            library.setProgress("comic", 7); library.addBookmark("comic", 3)
            val store = OfflineDownloadStore(File(root, "offline"))
            engine(store, MemorySource(ByteArray(256))).download(spec())
            store.remove("comic")
            assertEquals(7, library.progress("comic")); assertEquals(setOf(3), library.bookmarks("comic"))
            assertEquals("cache", sentinel.readText())
        }
    }

    @Test fun completedDownloadCannotBeDowngradedByLatePauseOrFailure() {
        val store = store(); val spec = spec()
        engine(store, MemorySource(ByteArray(256))).download(spec)
        store.markPaused(spec.comicId); store.markFailed(spec.comicId, "late worker callback")
        store.enqueue(spec)
        assertEquals(OfflineDownloadState.COMPLETED, store.status(spec.comicId)?.state)
        assertNotNull(store.completed(spec.comicId))
        val different = spec.copy(eTag = "\"fixture-v2\"")
        assertTrue(runCatching { store.enqueue(different) }.exceptionOrNull() is IllegalStateException)
        assertEquals(spec.eTag, store.completed(spec.comicId)?.spec?.eTag)
    }

    @Test fun privateArchiveNamesNeverUseTitlesOrSourceFilePaths() {
        val store = store(); val spec = spec(id = "../../outside")
        val completed = engine(store, MemorySource(ByteArray(256))).download(spec)
        assertEquals(store.directory.canonicalFile, completed.file.canonicalFile.parentFile)
        assertTrue(completed.file.name.matches(Regex("[0-9a-f]{64}\\.archive")))
        assertFalse(File(store.directory.parentFile, "outside.cbz").exists())
    }

    @Test fun precancelledDownloadMakesNoNetworkRequestsOrRecords() {
        val store = store(); val source = MemorySource(ByteArray(256)); val token = CancellationToken().apply { cancel() }
        val failure = runCatching { engine(store, source).download(spec(), token) }.exceptionOrNull()
        assertEquals(ArchiveError.CANCELLED, (failure as ArchiveException).code)
        assertEquals(0, source.discoveries); assertTrue(store.statuses().isEmpty())
    }

    @Test fun changedLocalOfflineFileIsRejectedAndItsLeaseCanStillBeReleased() {
        val store = store(); val completed = engine(store, MemorySource(ByteArray(256))).download(spec())
        OfflineArchiveSource(completed).use { source ->
            val identity = source.discover()
            completed.file.appendBytes(byteArrayOf(1))
            val failure = runCatching { source.readRange(identity, 0, 1) }.exceptionOrNull()
            assertEquals(ArchiveError.OBJECT_CHANGED, (failure as ArchiveException).code)
        }
        assertNull(store.completed("comic"))
        assertEquals(OfflineDownloadState.FAILED, store.status("comic")?.state)
        store.remove("comic")
    }

    @Test fun restartAfterFinalRenameRecoversCompletionWithoutAnotherNetworkDownload() {
        val store = store(); val spec = spec()
        store.enqueue(spec)
        store.update(spec, OfflineDownloadState.DOWNLOADING, spec.length)
        store.archive(spec).writeBytes(ByteArray(spec.length.toInt()) { it.toByte() })
        val restarted = OfflineDownloadStore(store.directory)
        assertNotNull(restarted.completed(spec.comicId))
        assertEquals(OfflineDownloadState.COMPLETED, restarted.status(spec.comicId)?.state)
        val source = MemorySource(ByteArray(256))
        engine(restarted, source).download(spec)
        assertEquals(0, source.discoveries)
    }

    private open class MemorySource(private val bytes: ByteArray, private val total: Long = bytes.size.toLong()) : ArchiveByteSource {
        val starts = mutableListOf<Long>(); var discoveries = 0
        override fun discover(token: CancellationToken): RemoteIdentity {
            token.throwIfCancelled(); discoveries++
            return RemoteIdentity("https://example.test/book.cbz", total, "\"fixture-v1\"", null)
        }
        override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
            token.throwIfCancelled(); starts += start
            return bytes.copyOfRange(start.toInt(), start.toInt() + length)
        }
    }

    private class FixtureServer(private val bytes: ByteArray, private val mode: String = "normal") : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val requests = AtomicInteger()
        val ifMatchHeaders = Collections.synchronizedList(mutableListOf<String?>())
        val url = "http://127.0.0.1:${server.localPort}/fixture.cbz"
        private val accept = Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                workers.submit { client.use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    input.readLine() ?: return@use
                    var range = ""; var ifMatch: String? = null
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                        if (line.startsWith("If-Match:", true)) ifMatch = line.substringAfter(':').trim()
                    }
                    ifMatchHeaders += ifMatch
                    val request = requests.incrementAndGet()
                    val values = range.removePrefix("bytes=").split('-')
                    val start = values[0].toInt(); val end = values[1].toInt(); val length = end - start + 1
                    val changed = mode == "changed" && request > 1
                    val status = when { mode == "ignored" -> 200; changed -> 412; else -> 206 }
                    val etag = if (changed) "\"fixture-v2\"" else "\"fixture-v1\""
                    val sentStart = if (mode == "bad-range") start + 1 else start
                    val header = "HTTP/1.1 $status Test\r\nETag: $etag\r\nContent-Range: bytes $sentStart-$end/${bytes.size}\r\nContent-Length: ${if (mode == "ignored") bytes.size else if (changed) 0 else length}\r\nConnection: close\r\n\r\n"
                    runCatching {
                        val output = socket.getOutputStream(); output.write(header.toByteArray(Charsets.US_ASCII))
                        when {
                            changed -> { }
                            mode == "ignored" -> output.write(bytes)
                            mode == "truncated" && request > 1 -> output.write(bytes, start, length / 2)
                            else -> output.write(bytes, start, length)
                        }
                        output.flush()
                    }
                } }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); accept.join(1000); workers.shutdownNow() }
    }

    companion object {
        private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
        private fun comicBytes() = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            repeat(3) { zip.putNextEntry(ZipEntry("page-$it.png")); zip.write(png); zip.closeEntry() }
        } }.toByteArray()
    }
}
