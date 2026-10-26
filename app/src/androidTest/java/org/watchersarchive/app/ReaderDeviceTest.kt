package org.watchersarchive.app

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CacheFullException
import org.watchersarchive.core.CacheManager
import org.watchersarchive.core.HttpRangeSource
import org.watchersarchive.core.PersistentLibrary
import org.watchersarchive.core.RangeTrace
import org.watchersarchive.core.ZipArchiveReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Real Android tests: HTTP and ZIP run on the instrumentation worker thread and
 * images are decoded by Android BitmapFactory, never by host JVM image stubs.
 * Start tools/range_server.py, adb reverse tcp:8765 tcp:8765, then run connectedAndroidTest.
 * Override the instrumentation argument testServer when the host is elsewhere.
 */
@RunWith(AndroidJUnit4::class)
class ReaderDeviceTest {
    private lateinit var temporaryRoot: File
    private lateinit var persistentRoot: File
    private lateinit var cache: CacheManager
    private lateinit var library: PersistentLibrary
    private var cacheClosed = false
    private var libraryClosed = false
    private val traces = mutableListOf<RangeTrace>()
    private val baseUrl: String get() =
        (InstrumentationRegistry.getArguments().getString("testServer") ?: "http://127.0.0.1:8765").trimEnd('/')

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = "reader-device-${UUID.randomUUID()}"
        temporaryRoot = File(context.cacheDir, unique)
        // Outside the disposable directory, just like production user records.
        persistentRoot = File(context.filesDir, unique)
        cache = CacheManager(temporaryRoot)
        library = PersistentLibrary(persistentRoot)
        resetServer()
    }

    @After
    fun tearDown() {
        if (!cacheClosed) cache.close()
        if (!libraryClosed) library.close()
        check(temporaryRoot.deleteRecursively()) { "Could not delete isolated test cache" }
        check(persistentRoot.deleteRecursively()) { "Could not delete isolated test library" }
    }

    @Test
    fun standardCbzDownloadsOnlyRangesAndAndroidDecodesAllFourPages() {
        val reader = reader("standard.cbz")
        val index = reader.open()
        assertFalse(index.isZip64)
        assertEquals(4, index.pages.size)
        assertEquals(setOf(0, 8), index.pages.map { it.method }.toSet())
        index.pages.indices.forEach { page ->
            val bytes = reader.readPage(index, page)
            assertAndroidImage(bytes, page)
            cache.put("standard-page-$page", "standard", bytes).use { lease ->
                assertTrue(lease.readBytes().contentEquals(bytes))
                assertWithinBudget()
            }
        }
        assertTrue(traces.isNotEmpty())
        traces.forEach {
            assertEquals(206, it.statusCode)
            assertEquals(it.endInclusive - it.start + 1, it.receivedBytes)
            assertTrue(it.contentRange?.startsWith("bytes ${it.start}-${it.endInclusive}/") == true)
        }
    }

    @Test
    fun zip64UsesOffsetsAboveFourGiBAndAndroidDecodesBothPages() {
        val reader = reader("zip64.cbz")
        val index = reader.open()
        assertTrue(index.isZip64)
        assertTrue(index.identity.length > 0x1_0000_0000L)
        assertTrue(index.centralDirectoryOffset > 0xffff_ffffL)
        assertEquals(2, index.pages.size)
        index.pages.indices.forEach { page ->
            assertTrue(index.pages[page].localHeaderOffset > 0xffff_ffffL)
            assertAndroidImage(reader.readPage(index, page), page)
        }
        val transfer = traces.sumOf { it.receivedBytes }
        assertTrue("The logical multi-GB archive must not be transferred", transfer < 1_000_000)
        assertEquals(0L, cache.snapshot().committedBytes)
        Log.i(TAG, "ZIP64 logicalBytes=${index.identity.length} clientConsumedBytes=$transfer")
    }

    @Test
    fun ignoredRangeResponseIsRejectedBeforeConsumingAnyBody() {
        expectArchiveError(ArchiveError.RANGE_NOT_SUPPORTED) { reader("ignore.cbz").open() }
        assertEquals(1, traces.size)
        assertEquals(200, traces.single().statusCode)
        assertEquals(0L, traces.single().receivedBytes)
        assertNoCommittedOrTemporaryPages()
    }

    @Test
    fun malformedContentRangeIsRejectedBeforeConsumingAnyBody() {
        expectArchiveError(ArchiveError.INVALID_CONTENT_RANGE) { reader("bad-range.cbz").open() }
        assertEquals(1, traces.size)
        assertEquals(206, traces.single().statusCode)
        assertEquals(0L, traces.single().receivedBytes)
        assertNoCommittedOrTemporaryPages()
    }

    @Test
    fun interruptedResponseNeverCommitsPartialPageOrLeavesReservation() {
        // Mirrors the write transaction boundary: reserve first, commit only after a valid page.
        cache.beginWrite("interrupted-page", "truncated", 1024).use {
            val error = expectArchiveError { reader("truncated.cbz").open() }
            assertTrue(error.code in setOf(ArchiveError.NETWORK, ArchiveError.INVALID_LENGTH))
        }
        assertTrue(traces.any { it.receivedBytes > 0 && it.receivedBytes < it.endInclusive - it.start + 1 })
        assertNoCommittedOrTemporaryPages()
    }

    @Test
    fun objectVersionChangeRejectsMixedArchiveBytes() {
        expectArchiveError(ArchiveError.OBJECT_CHANGED) { reader("changing.cbz").open() }
        assertTrue(traces.any { it.statusCode == 412 })
        assertEquals(0L, traces.last().receivedBytes)
        assertNoCommittedOrTemporaryPages()
    }

    @Test
    fun clearingCacheProtectsLivePageAndReopenedUserRecordsSurvive() {
        val reader = reader("standard.cbz")
        val index = reader.open()
        val bytes = reader.readPage(index, 1)
        assertAndroidImage(bytes, 1)
        library.registerComic("standard", "自建测试漫画", "$baseUrl/standard.cbz")
        library.setProgress("standard", 1)
        library.addBookmark("standard", 1)
        library.addBookmark("standard", 2)
        library.removeBookmark("standard", 2)
        library.setNote("standard", 1, "测试笔记，缓存淘汰不得删除。")

        cache.put("page-1", "standard", bytes).use { livePage ->
            assertEquals(0L, cache.clearUnpinned())
            assertTrue(livePage.file.isFile)
            assertTrue(livePage.readBytes().contentEquals(bytes))
            assertEquals(1, cache.snapshot().pinnedFiles)
            assertWithinBudget()
        }
        assertTrue(cache.clearUnpinned() > 0)
        assertNoCommittedOrTemporaryPages()
        cache.close(); cacheClosed = true
        library.close(); libraryClosed = true

        // Reconstructs managers from disk; this is a persistence reopen test,
        // not a claim that a whole Android process was killed and relaunched.
        CacheManager(temporaryRoot).use { reopenedCache ->
            assertEquals(0L, reopenedCache.snapshot().committedBytes)
        }
        PersistentLibrary(persistentRoot).use { reopened ->
            assertEquals(1, reopened.progress("standard"))
            assertEquals(setOf(1), reopened.bookmarks("standard"))
            assertEquals("测试笔记，缓存淘汰不得删除。", reopened.note("standard", 1))
            assertEquals("自建测试漫画", reopened.comics().single().title)
            assertTrue(reopened.comics().single().testContent)
        }
    }

    @Test
    fun cacheReservationsRejectOversubscriptionAndAbortCleanly() {
        assertEquals(500_000_000L, CacheManager.HARD_LIMIT)
        val testDirectory = File(temporaryRoot, "isolated-boundary")
        CacheManager(testDirectory, 16_384).use { small ->
            // 4 KiB overhead + 12 KiB reservation fills the configured budget.
            small.beginWrite("one", "comic", 12_288).use { write ->
                assertEquals(16_384L, small.snapshot().accountedBytes)
                try {
                    small.beginWrite("two", "other", 1).close()
                    fail("A second reservation must be refused")
                } catch (_: CacheFullException) { /* Required outcome. */ }
                write.write(ByteArray(4096))
                assertEquals(16_384L, small.snapshot().accountedBytes)
                assertTrue(small.snapshot().accountedBytes <= small.snapshot().limitBytes)
            }
            assertEquals(0L, small.snapshot().temporaryBytes)
            assertEquals(0L, small.snapshot().reservedBytes)
        }
        check(testDirectory.deleteRecursively())
        assertWithinBudget()
    }

    private fun reader(path: String): ZipArchiveReader = ZipArchiveReader(HttpRangeSource("$baseUrl/$path", observer = {
        traces += it
        Log.i(TAG, JSONObject().apply {
            put("range", "bytes=${it.start}-${it.endInclusive}")
            put("status", it.statusCode)
            put("contentRange", it.contentRange)
            put("clientConsumedBytes", it.receivedBytes)
            put("outcome", it.outcome)
        }.toString())
    }))

    private fun assertAndroidImage(bytes: ByteArray, zeroBasedPage: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(480, bounds.outWidth)
        assertEquals(720, bounds.outHeight)
        assertTrue(bounds.outWidth.toLong() * bounds.outHeight < 20_000_000)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Android must decode a real image", bitmap)
        try {
            assertEquals(480, bitmap.width)
            assertEquals(720, bitmap.height)
            Log.i(TAG, "ANDROID_DECODE page=${zeroBasedPage + 1} imageBytes=${bytes.size} pixels=${bitmap.width}x${bitmap.height}")
        } finally { bitmap.recycle() }
    }

    private fun assertNoCommittedOrTemporaryPages() {
        val snapshot = cache.snapshot()
        assertEquals(0L, snapshot.committedBytes)
        assertEquals(0L, snapshot.temporaryBytes)
        assertEquals(0L, snapshot.reservedBytes)
        assertEquals(0, snapshot.pinnedFiles)
        assertWithinBudget()
    }

    private fun assertWithinBudget() {
        val snapshot = cache.snapshot()
        assertTrue(snapshot.accountedBytes <= CacheManager.HARD_LIMIT)
        Log.i(TAG, "CACHE committed=${snapshot.committedBytes} temporary=${snapshot.temporaryBytes} reserved=${snapshot.reservedBytes} overhead=${snapshot.overheadBytes} accounted=${snapshot.accountedBytes} limit=${snapshot.limitBytes}")
    }

    private fun expectArchiveError(expected: ArchiveError? = null, action: () -> Unit): ArchiveException {
        try { action() } catch (error: ArchiveException) {
            if (expected != null) assertEquals(expected, error.code)
            return error
        }
        throw AssertionError("Expected ArchiveException${expected?.let { ": $it" } ?: ""}")
    }

    private fun resetServer() {
        val connection = URL("$baseUrl/reset").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(0)
        try {
            connection.outputStream.close()
            assertEquals("Start tools/range_server.py and configure adb reverse before device tests", 200, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private companion object { const val TAG = "ReaderDeviceTest" }
}
