package org.watchersarchive.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogSearchServiceTest {
    private data class Call(val query: String, val page: Int, val perPage: Int)
    private fun book(id: String, title: String, source: String = "getcomics") = CatalogSourceRecord(
        source, id, "https://example.test/$source/$id", title, null, null,
        "2026-10-27T00:00:00Z", null, "2026-10-27T00:00:00Z", emptyList(), null, null, "CBZ", SourceConfidence.MEDIUM,
    )

    @Test fun normalizesCasePunctuationAndSendsTheRealPageToTheSource() = runBlocking {
        val calls = mutableListOf<Call>()
        val service = CatalogSearchService({ emptyList() }, { query, page, count ->
            calls += Call(query, page, count)
            listOf(book("one", "Spider-Man #1"))
        })
        val result = service.search("SPIDER—MAN", 3)
        assertEquals(listOf(Call("spider man", 3, 30)), calls)
        assertEquals("one", result.records.single().externalId)
    }

    @Test fun supportedChineseAliasSearchesEnglishWithoutDiscardingTheIssueNumber() = runBlocking {
        val calls = mutableListOf<Call>()
        val service = CatalogSearchService({ emptyList() }, { query, page, count ->
            calls += Call(query, page, count)
            listOf(book("one", "Civil War #1 (2006)"), book("ten", "Civil War #10 (2006)"))
        }, expandQuery = { listOf("Civil War") })
        val result = service.search("内战 #1")
        assertEquals(listOf(Call("civil war 1", 1, 30)), calls)
        assertEquals(listOf("one"), result.records.map { it.externalId })
        assertEquals(listOf("Civil War"), result.expandedTerms)
    }

    @Test fun unknownChineseIsSentUntranslatedAndDoesNotTriggerAnInventedEnglishGuess() = runBlocking {
        val calls = mutableListOf<String>()
        val service = CatalogSearchService({ emptyList() }, { query, _, _ -> calls += query; emptyList() })
        val result = service.search("未知中文漫画")
        assertEquals(listOf("未知中文漫画"), calls)
        assertTrue(result.records.isEmpty())
        assertTrue(result.expandedTerms.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test fun emptyEnglishSearchTriesOneBoundedPrefixThenReusesThatPlanOnTheNextPage() = runBlocking {
        val calls = mutableListOf<Call>()
        val service = CatalogSearchService({ emptyList() }, { query, page, count ->
            calls += Call(query, page, count)
            if (query == "spidrman") emptyList()
            else (1..30).map { book("$page-$it", "Spider-Man #$it") }
        })
        val first = service.search("spidrman")
        assertEquals(listOf("spidrman", "spid"), calls.map { it.query })
        assertTrue(first.hasMore)
        assertFalse(first.records.isEmpty())
        calls.clear()
        service.search("spidrman", 2)
        assertEquals(listOf(Call("spid", 2, 30)), calls)
    }

    @Test fun localMatchesDoNotInventRemoteContinuationAndLaterPagesDoNotRepeatThem() = runBlocking {
        val local = (1..40).map { book("cached-$it", "Civil War collection $it") }
        val service = CatalogSearchService({ local }, { _, _, _ -> emptyList() })
        val first = service.search("Civil War")
        assertEquals(40, first.records.size)
        assertFalse("Continuation uses raw remote page length rather than merged local size", first.hasMore)
        val next = service.search("Civil War", 2)
        assertTrue(next.records.isEmpty())
        assertFalse(next.hasMore)
    }

    @Test fun sourceIdentityDeduplicationKeepsSeparateVersionsAndStoresBoundedRemoteCandidates() = runBlocking {
        val one = book("post:1", "Secret Wars (1984)")
        val two = book("post:2", "Secret Wars (2015)")
        val local = book("post:1", "Secret Wars (2015)", source = "local")
        var persisted = emptyList<CatalogSourceRecord>()
        val service = CatalogSearchService({ listOf(one, local) }, { _, _, _ -> listOf(one, one, two) }, { persisted = it })
        val result = service.search("secret wars")
        assertEquals(3, result.records.size)
        assertEquals(2, persisted.size)
        assertEquals(setOf("getcomics" to "post:1", "getcomics" to "post:2", "local" to "post:1"),
            result.records.map { it.source to it.externalId }.toSet())
    }

    @Test fun sourceFailurePreservesRankedLocalResultsWithoutRetryingEveryAlias() = runBlocking {
        var calls = 0
        val service = CatalogSearchService({ listOf(book("cached", "Civil War")) }, { _, _, _ -> calls++; throw IOException("fixture unavailable") },
            expandQuery = { listOf("Civil War", "Civil Wars", "Marvel Civil War") })
        val result = service.search("内战")
        assertEquals(1, calls)
        assertEquals("cached", result.records.single().externalId)
        assertTrue(result.offlineFallback)
        assertFalse(result.hasMore)
    }

    @Test fun unavailableSourceWithoutAnyLocalMatchFailsInsteadOfPretendingThereAreNoBooks() = runBlocking {
        val service = CatalogSearchService({ listOf(book("other", "Thor")) }, { _, _, _ -> throw IOException("fixture unavailable") })
        val failure = runCatching { service.search("Civil War") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("在线搜索暂时不可用"))
    }

    @Test fun cancellationIsNeverSwallowedOrPersistedAsAnEmptySearch() = runBlocking {
        var persisted = false
        val service = CatalogSearchService({ listOf(book("cached", "Civil War")) }, { _, _, _ -> throw CancellationException("fixture cancelled") },
            persist = { persisted = true })
        assertTrue(runCatching { service.search("Civil War") }.exceptionOrNull() is CancellationException)
        assertFalse(persisted)
    }

    @Test fun aliasesAndMisbehavingSourceCannotExceedThreeRequestsOrThirtyRecordsPerRequest() = runBlocking {
        val calls = mutableListOf<Call>()
        var persistedCount = 0
        val service = CatalogSearchService({ emptyList() }, { query, page, count ->
            calls += Call(query, page, count)
            (1..80).map { book("$query-$it", "Civil War collection") }
        }, persist = { persistedCount = it.size }, expandQuery = { listOf("Civil War", "Civil Wars", "Marvel Civil War", "Civil War Event", "CIVIL WAR") })
        val result = service.search("内战")
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.perPage == 30 })
        assertEquals(90, persistedCount)
        assertTrue(result.hasMore)
    }

    @Test fun partialSourceFailureRetainsSuccessfulCandidatesAndReportsTheIncompleteOnlineResult() = runBlocking {
        val service = CatalogSearchService({ emptyList() }, { query, _, _ ->
            if (query == "civil war") listOf(book("one", "Civil War")) else throw IOException("fixture partial failure")
        }, expandQuery = { listOf("Civil War", "Marvel Civil War") })
        val result = service.search("内战")
        assertEquals("one", result.records.single().externalId)
        assertTrue(result.offlineFallback)
    }

    @Test fun fullUnrelatedLocalSnapshotCannotHideAFreshRemoteMatch() = runBlocking {
        val cached = (1..5_000).map { book("cached-$it", "Thor collection $it") }
        val service = CatalogSearchService({ cached }, { _, _, _ -> listOf(book("fresh", "Spider-Man")) })
        assertEquals(listOf("fresh"), service.search("Spider-Man").records.map { it.externalId })
    }

    @Test fun freshArticleMetadataWinsOverTheSameCachedSourceIdentity() = runBlocking {
        val service = CatalogSearchService({ listOf(book("same", "Spider-Man old article title")) }, { _, _, _ ->
            listOf(book("same", "Spider-Man updated article title"))
        })
        assertEquals("Spider-Man updated article title", service.search("Spider-Man").records.single().title)
    }
}
