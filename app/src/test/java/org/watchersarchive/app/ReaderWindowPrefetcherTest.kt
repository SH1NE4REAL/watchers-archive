package org.watchersarchive.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ReaderWindowPrefetcherTest {
    @Test fun windowReadsAtMostTwoPagesInOrderAndSerially() = runBlocking {
        val reads = CopyOnWriteArrayList<Int>()
        val firstStarted = CompletableDeferred<Unit>(); val finishFirst = CompletableDeferred<Unit>(); val secondDone = CompletableDeferred<Unit>()
        val prefetcher = ReaderPrefetcher { index ->
            reads += index
            if (index == 1) { firstStarted.complete(Unit); finishFirst.await() }
            if (index == 2) secondDone.complete(Unit)
        }
        try { withTimeout(5_000) {
            prefetcher.scheduleWindow(listOf(1, 2, 3, 4)); firstStarted.await()
            assertEquals(listOf(1), reads.toList())
            finishFirst.complete(Unit); secondDone.await(); delay(20)
            assertEquals(listOf(1, 2), reads.toList())
        } } finally { prefetcher.close() }
    }

    @Test fun advancingTheWindowReusesTheAlreadyRunningSecondPage() = runBlocking {
        val reads = CopyOnWriteArrayList<Int>()
        val secondStarted = CompletableDeferred<Unit>(); val finishSecond = CompletableDeferred<Unit>(); val thirdDone = CompletableDeferred<Unit>()
        val prefetcher = ReaderPrefetcher { index ->
            reads += index
            if (index == 2) { secondStarted.complete(Unit); finishSecond.await() }
            if (index == 3) thirdDone.complete(Unit)
        }
        try { withTimeout(5_000) {
            prefetcher.scheduleWindow(listOf(1, 2)); secondStarted.await()
            prefetcher.scheduleWindow(listOf(2, 3)); finishSecond.complete(Unit); thirdDone.await()
            assertEquals(listOf(1, 2, 3), reads.toList())
        } } finally { prefetcher.close() }
    }

    @Test fun anUncachedForegroundReadCancelsBothUnrelatedPreviews() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val reads = CopyOnWriteArrayList<Int>()
        val prefetcher = ReaderPrefetcher { index ->
            reads += index
            if (index == 1) try { started.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        try { withTimeout(5_000) {
            prefetcher.scheduleWindow(listOf(1, 2)); started.await()
            prefetcher.withForeground(8) { assertTrue(cancelled.isCompleted) }
            assertEquals(listOf(1), reads.toList())
        } } finally { prefetcher.close() }
    }

    @Test fun backgroundWorkScheduledDuringForegroundWaitsUntilForegroundFinishes() = runBlocking {
        val reads = CopyOnWriteArrayList<Int>(); val completed = CompletableDeferred<Unit>()
        val prefetcher = ReaderPrefetcher { index -> reads += index; completed.complete(Unit) }
        try { withTimeout(5_000) {
            prefetcher.withForeground(0) {
                prefetcher.scheduleWindow(listOf(1, 2)); delay(30)
                assertTrue(reads.isEmpty())
            }
            completed.await()
            assertEquals(1, reads.first())
        } } finally { prefetcher.close() }
    }
}
