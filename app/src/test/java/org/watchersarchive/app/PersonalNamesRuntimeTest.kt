package org.watchersarchive.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersonalNamesRuntimeTest {
    @Test fun threeImportedNamesSurviveCacheClearAndSourceRefreshWithoutChangingIdentityOrProgress() = runBlocking {
        val repository = AndroidRepository(ApplicationProvider.getApplicationContext<Context>())
        repository.cacheSnapshot()
        val state = ReflectionHelpers.getField<Any>(repository, "state")
        val metadata = ReflectionHelpers.getField<CatalogMetadataStore>(state, "catalogMetadata")
        val personal = ReflectionHelpers.getField<PersonalBookNames>(state, "personalBookNames")
        val prefix = "names-${UUID.randomUUID()}"
        val records = (1..3).map { index -> CatalogSourceRecord("local", "$prefix:$index", "content://test.documents/book-$index",
            "Original Book $index", null, null, null, null, "2026-10-28T00:00:00Z", emptyList(), null, null, "CBZ", SourceConfidence.VERIFIED) }
        metadata.upsertAll(records)
        for ((index, record) in records.withIndex()) repository.renameComic(record.externalId, "我的漫画${index + 1}")
        repository.setBookmark(records[0].externalId, 2, true)
        repository.clearCache()
        metadata.upsertAll(records)
        val results = repository.getComicsRecords().filter { it.externalId.startsWith(prefix) }.associateBy { it.externalId }
        val namesFile = ReflectionHelpers.getField<File>(personal, "file")
        val reloaded = PersonalBookNames(namesFile)
        for ((index, original) in records.withIndex()) {
            assertEquals("我的漫画${index + 1}", results[original.externalId]?.displayTitle)
            assertEquals(original.title, results[original.externalId]?.title)
            assertEquals(original.canonicalUrl, results[original.externalId]?.canonicalUrl)
            assertEquals("我的漫画${index + 1}", reloaded.title(original.externalId))
        }
        assertEquals(setOf(2), repository.bookmarkPages(records[0].externalId))
        repository.renameComic(records[0].externalId, null)
        assertEquals(records[0].title, repository.getComicsRecords().first { it.externalId == records[0].externalId }.displayTitle)
    }
}
