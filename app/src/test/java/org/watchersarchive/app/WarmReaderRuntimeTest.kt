package org.watchersarchive.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WarmReaderRuntimeTest {
    private fun snapshots(repository: AndroidRepository): CatalogSnapshots {
        val state = ReflectionHelpers.getField<Any>(repository, "state")
        return ReflectionHelpers.getField(state, "catalogSnapshots")
    }
    private fun record(fixture: PreparationRangeFixture, id: String) = CatalogSourceRecord("getcomics", id, fixture.articleUrl,
        "Synthetic warm-open fixture", null, null, null, null, Instant.now().toString(), emptyList(), null, null, "CBZ", SourceConfidence.MEDIUM)
    private fun inspection(record: CatalogSourceRecord, fixture: PreparationRangeFixture): SourceAssetInspection = SourceAssetInspection(record.canonicalUrl,
        listOf(SourceAsset("fixture-asset", record.externalId, "127.0.0.1", fixture.fileUrl, "book.cbz", "CBZ", null,
            SourceAssetStatus.READY, "Synthetic validation metadata", Instant.now().toString(), fixture.fileUrl,
            "\"v1\"", fixture.archive.size.toLong(), 3)), true)

    @Test fun warmPrepareSkipsArticleButActualReaderRejectsAChangedObjectAndInvalidatesTheSnapshot() = runBlocking {
        PreparationRangeFixture().use { fixture ->
            val repository = AndroidRepository(ApplicationProvider.getApplicationContext<Context>())
            repository.cacheSnapshot()
            val record = record(fixture, "warm-${UUID.randomUUID()}")
            val snapshots = snapshots(repository)
            snapshots.saveInspection(record.externalId, inspection(record, fixture))
            val prepared = repository.prepareSourceReading(record)
            assertEquals(0, fixture.requests.get())
            fixture.eTag = "\"v2\""
            val failure = runCatching { repository.open(prepared) }.exceptionOrNull()
            assertEquals(ArchiveError.OBJECT_CHANGED, (failure as ArchiveException).code)
            assertTrue(fixture.requests.get() > 0)
            assertEquals(0, fixture.articleRequests.get())
            val saved = checkNotNull(snapshots.inspection(record.externalId))
            assertFalse(saved.canStartRemoteReader)
            assertEquals(SourceAssetStatus.EXPIRED, saved.assets.single().status)
            assertNull(saved.assets.single().verifiedETag)
        }
    }

    @Test fun cancellingActualOpenDoesNotInvalidateRecentVerifiedMetadata() = runBlocking {
        PreparationRangeFixture(blockFirstBody = true).use { fixture ->
            val repository = AndroidRepository(ApplicationProvider.getApplicationContext<Context>())
            repository.cacheSnapshot()
            val record = record(fixture, "warm-cancel-${UUID.randomUUID()}")
            val snapshots = snapshots(repository)
            snapshots.saveInspection(record.externalId, inspection(record, fixture))
            val prepared = repository.prepareSourceReading(record)
            // Capture the typed ArchiveException(CANCELLED) produced by the core
            // reader so it cannot escape the async test scope as an uncaught child
            // failure while we verify snapshot preservation.
            val opening = launch { runCatching { repository.open(prepared) } }
            try {
                withTimeout(5_000) { fixture.firstBodyStarted.await() }
                // The core HTTP primitive reports active cancellation as its
                // typed ArchiveException rather than kotlinx CancellationException.
                // The repository must still leave the recent verification snapshot intact.
                opening.cancel()
                withTimeout(5_000) { opening.join() }
                assertTrue(opening.isCompleted)
                assertTrue(checkNotNull(snapshots.inspection(record.externalId)).canStartRemoteReader)
            } finally {
                fixture.releaseBody.countDown()
                opening.cancel()
                runCatching { opening.join() }
            }
        }
    }
}
