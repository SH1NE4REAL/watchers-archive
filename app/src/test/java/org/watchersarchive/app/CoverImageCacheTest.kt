package org.watchersarchive.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.watchersarchive.core.CacheManager
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverImageCacheTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")

    @Test fun repeatedCoverDisplaysImmediatelyFromMemoryWithoutAnotherRequest() = runBlocking {
        val root = Files.createTempDirectory("watchers-covers-").toFile()
        try { CacheManager(root).use { disk ->
            val requests = AtomicInteger()
            val loader = CoverImageCache { requests.incrementAndGet(); png }
            val first = loader.load("https://example.org/cover.png", disk)
            assertNotNull(first)
            assertSame(first, loader.peek("https://example.org/cover.png"))
            assertSame(first, loader.load("https://example.org/cover.png", disk))
            assertEquals(1, requests.get())
            assertTrue(disk.snapshot().accountedBytes <= CacheManager.HARD_LIMIT)
        } } finally { root.deleteRecursively() }
    }

    @Test fun concurrentViewsShareOneDownloadAndOneBitmap() = runBlocking {
        val root = Files.createTempDirectory("watchers-covers-").toFile()
        try { CacheManager(root).use { disk ->
            val requests = AtomicInteger()
            val loader = CoverImageCache { requests.incrementAndGet(); delay(100); png }
            val results = (0..5).map { async { loader.load("https://example.org/shared.png", disk) } }.awaitAll()
            assertNotNull(results.first())
            results.forEach { assertSame(results.first(), it) }
            assertEquals(1, requests.get())
        } } finally { root.deleteRecursively() }
    }

    @Test fun restartedMemoryCacheCanReadTheSameCoverWithNetworkOffline() = runBlocking {
        val root = Files.createTempDirectory("watchers-covers-").toFile()
        try {
            CacheManager(root).use { disk -> assertNotNull(CoverImageCache { png }.load("https://example.org/offline.png", disk)) }
            CacheManager(root).use { disk ->
                val requests = AtomicInteger()
                val loader = CoverImageCache { requests.incrementAndGet(); error("network must not be used") }
                val image = loader.load("https://example.org/offline.png", disk)
                assertNotNull(image)
                assertEquals(1, image!!.width)
                assertEquals(0, requests.get())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun leavingThenReturningDoesNotReuseACancelledDownload() = runBlocking {
        val root = Files.createTempDirectory("watchers-covers-").toFile()
        try { CacheManager(root).use { disk ->
            val started = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val loader = CoverImageCache {
                if (requests.incrementAndGet() == 1) { started.complete(Unit); awaitCancellation() }
                png
            }
            val firstView = launch { loader.load("https://example.org/return.png", disk) }
            started.await()
            firstView.cancelAndJoin()
            assertNotNull(loader.load("https://example.org/return.png", disk))
            assertEquals(2, requests.get())
        } } finally { root.deleteRecursively() }
    }
}
