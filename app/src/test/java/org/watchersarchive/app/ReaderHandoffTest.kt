package org.watchersarchive.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class ReaderHandoffTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.put(block) }
        fun next(): Runnable = checkNotNull(queue.poll(5, TimeUnit.SECONDS)) { "Continuation did not arrive" }
    }

    @Test fun cancelledDispatchBackReleasesTheUndeliveredPage() {
        val dispatcher = QueueDispatcher()
        val parent = Job()
        val releases = AtomicInteger()
        var delivered = false
        val job = CoroutineScope(parent + dispatcher).launch {
            withReaderHandoff(release = { _: Any -> releases.incrementAndGet() }) { Any() }
            delivered = true
        }
        try {
            dispatcher.next().run() // Starts the request and dispatches work to IO.
            val returnToMain = dispatcher.next() // IO result exists but UI has not received it.
            job.cancel()
            returnToMain.run()
            assertTrue(job.isCompleted)
            assertFalse(delivered)
            assertEquals(1, releases.get())
        } finally { parent.cancel() }
    }

    @Test fun successfulPageHandoffDoesNotReleaseTheDisplayedPage() = runBlocking {
        val releases = AtomicInteger()
        val page = Any()
        assertSame(page, withReaderHandoff(release = { _: Any -> releases.incrementAndGet() }) { page })
        assertEquals(0, releases.get())
    }
}
