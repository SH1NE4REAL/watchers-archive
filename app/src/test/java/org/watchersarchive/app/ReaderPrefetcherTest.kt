package org.watchersarchive.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ReaderPrefetcherTest {
    @Test fun samePageIsOnlyFetchedOnceAndForegroundWaitsForIt() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val prefetcher = ReaderPrefetcher { reads.incrementAndGet(); started.complete(Unit); finish.await() }
        try { withTimeout(5_000) {
            prefetcher.schedule(1); started.await(); prefetcher.schedule(1)
            val foreground = async { prefetcher.beforeForeground(1) }
            delay(20); assertFalse(foreground.isCompleted)
            finish.complete(Unit); foreground.await()
            assertEquals(1, reads.get())
        } } finally { prefetcher.close() }
    }

    @Test fun reverseFlipCancelsUnrelatedReadAheadBeforeForegroundContinues() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val prefetcher = ReaderPrefetcher { try { started.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) } }
        try { withTimeout(5_000) {
            prefetcher.schedule(2); started.await(); prefetcher.beforeForeground(0)
            assertTrue(cancelled.isCompleted)
        } } finally { prefetcher.close() }
    }

    @Test fun backgroundFailureDoesNotFailTheCurrentReadingSession() = runBlocking {
        val prefetcher = ReaderPrefetcher { throw IOException("test network interruption") }
        try { withTimeout(5_000) { prefetcher.schedule(1); prefetcher.beforeForeground(1) } }
        finally { prefetcher.close() }
    }

    @Test fun closeStopsThePendingRequestAndRejectsFutureSchedules() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val reads = AtomicInteger()
        val prefetcher = ReaderPrefetcher { try { reads.incrementAndGet(); started.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) } }
        withTimeout(5_000) {
            prefetcher.schedule(1); started.await(); prefetcher.close(); cancelled.await(); prefetcher.schedule(2)
            delay(30); assertEquals(1, reads.get())
        }
    }
}
