package org.watchersarchive.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import org.watchersarchive.core.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real Android BitmapFactory + repository; the advertised remote URL is unavailable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OfflineReadingRuntimeTest {
    @Before fun initializeAndroidWorkDatabase() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(),
            Configuration.Builder().setExecutor { it.run() }.build())
    }
    @Test fun downloadedBookReadsLocallyAndKeepsProgressAndBookmarksAfterCacheAndDownloadRemoval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AndroidRepository(context)
        repository.cacheSnapshot() // Initialize persistent owners on IO before creating a store wrapper.
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            repeat(3) { zip.putNextEntry(ZipEntry("test-$it.png")); zip.write(png); zip.closeEntry() }
        } }.toByteArray()
        val id = "offline-runtime:${UUID.randomUUID()}"
        val spec = OfflineDownloadSpec(id, "离线测试漫画", "http://127.0.0.1:1/unavailable.cbz", "\"offline-runtime-v1\"", bytes.size.toLong())
        // Robolectric may rebuild Application while reusing a sandbox's project
        // statics. Install the fixture in the real repository-owned store rather
        // than assuming the current test Application owns that singleton.
        val owner = ReflectionHelpers.getField<Any>(repository, "state")
        val store = ReflectionHelpers.getField<OfflineDownloadStore>(owner, "offlineStore")
        val ownerContext = ReflectionHelpers.getField<Context>(owner, "context")
        println("OFFLINE_FIXTURE_OWNER_MATCH=${ownerContext.filesDir == context.filesDir}")
        val memory = object : ArchiveByteSource {
            override fun discover(token: CancellationToken) = RemoteIdentity(spec.url, spec.length, spec.eTag, null)
            override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
                token.throwIfCancelled()
                return bytes.copyOfRange(start.toInt(), start.toInt() + length)
            }
        }
        OfflineDownloadEngine(store, sourceFactory = { memory }).download(spec)
        // Fail locally if the fixture and the repository are not using the same
        // private storage owner; never contact a public site to diagnose a test.
        assertEquals("The completed fixture must be visible to the repository's storage owner", OfflineDownloadState.COMPLETED, repository.offlineStatus(id)?.state)
        val record = CatalogSourceRecord("getcomics", id, "http://127.0.0.1:1/unavailable-article", spec.title,
            "Synthetic test content", null, null, null, "2026-10-27T00:00:00Z", emptyList(), null, null, "CBZ", SourceConfidence.VERIFIED)
        val comic = repository.prepareSourceReading(record)
        assertTrue(comic.sourceUrl!!.startsWith("file:"))
        val session = repository.open(comic)
        val loaded = mutableListOf<ReaderPage>()
        try {
            assertEquals(3, session.totalPages)
            for (index in listOf(0, 2)) {
                val page = session.loadPage(index).also { loaded += it }
                assertEquals(1, page.bitmap.width)
                assertEquals(1, page.bitmap.height)
            }
            repository.setBookmark(id, 2, true)
            repository.clearCache()
            assertEquals(2, repository.lastPage(id))
            assertEquals(setOf(2), repository.bookmarkPages(id))
            assertEquals(OfflineDownloadState.COMPLETED, repository.offlineStatus(id)?.state)
            val cannotDelete = runCatching { repository.removeOfflineComic(id) }.exceptionOrNull()
            assertTrue("Displayed/offline source leases prevent deleting the downloaded archive", cannotDelete is IllegalStateException)
        } finally {
            loaded.forEach { it.release() }
            session.close()
        }
        repository.removeOfflineComic(id)
        assertNull(repository.offlineStatus(id))
        assertEquals(2, repository.lastPage(id))
        assertEquals(setOf(2), repository.bookmarkPages(id))
    }
}
