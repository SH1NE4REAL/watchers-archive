package org.watchersarchive.app

import org.junit.Assert.*
import org.junit.Test
import org.watchersarchive.core.CacheManager
import org.watchersarchive.core.CacheFullException
import java.nio.file.Files

class PrefetchCacheTest {
    @Test fun readAheadDoesNotPromoteTheLastReadTimestamp() {
        val root = Files.createTempDirectory("watchers-prefetch-cache-").toFile()
        try { CacheManager(root).use { cache ->
            cache.put("page-0", "book", ByteArray(200)).use { assertTrue(it.file.setLastModified(12_000)) }
            cache.putPrefetched("page-1", "book", ByteArray(200)).use { assertEquals(12_000L, it.file.lastModified()) }
            cache.acquire("page-1", "book", markRead = false)!!.use { assertEquals(12_000L, it.file.lastModified()) }
            cache.acquire("page-1", "book")!!.use { assertTrue(it.file.lastModified() > 12_000L) }
        } } finally { root.deleteRecursively() }
    }

    @Test fun previewCannotOverrunTheBudgetOrDeleteThePinnedCurrentPage() {
        val root = Files.createTempDirectory("watchers-prefetch-cache-").toFile()
        try { CacheManager(root, 16_384).use { cache ->
            cache.put("current", "book", ByteArray(8_192)).use { current ->
                try { cache.putPrefetched("oversized", "book", ByteArray(32_768)); fail("Read-ahead must obey the budget") }
                catch (_: CacheFullException) { }
                assertTrue(current.file.isFile)
                assertTrue(cache.snapshot().accountedBytes <= cache.budgetBytes)
                assertEquals(0L, cache.snapshot().temporaryBytes)
                assertEquals(0L, cache.snapshot().reservedBytes)
            }
        } } finally { root.deleteRecursively() }
    }

    @Test fun longOpenComicCanEvictOldUnpinnedPagesWithoutDeletingTheDisplayedPage() {
        val root = Files.createTempDirectory("watchers-prefetch-cache-").toFile()
        try { CacheManager(root, 20_480).use { cache ->
            cache.put("old", "book", ByteArray(8_192)).use { assertTrue(it.file.setLastModified(1_000)) }
            cache.put("current", "book", ByteArray(8_192)).use { current ->
                cache.putPrefetched("next", "book", ByteArray(8_192)).close()
                assertNull(cache.acquire("old", "book", markRead = false))
                assertTrue(current.file.isFile)
                assertNotNull(cache.acquire("next", "book", markRead = false)?.also { it.close() })
                assertTrue(cache.snapshot().accountedBytes <= cache.budgetBytes)
            }
        } } finally { root.deleteRecursively() }
    }
}
