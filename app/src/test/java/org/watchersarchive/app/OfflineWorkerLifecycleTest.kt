package org.watchersarchive.app

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineWorkerLifecycleTest {
    @Test fun pauseWaitsForLatePausedWriteBeforeAReplacementCanDownload() = runBlocking {
        val lifecycle = OfflineWorkerLifecycle()
        val comicId = "fixture"
        val oldId = UUID.randomUUID()
        val oldLifetime = checkNotNull(lifecycle.enter(comicId, oldId))
        val writingPaused = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        var state = OfflineDownloadState.DOWNLOADING
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally {
                try {
                    withContext(NonCancellable) {
                        writingPaused.complete(Unit)
                        finishWrite.await()
                        state = OfflineDownloadState.PAUSED
                    }
                } finally { lifecycle.leave(oldLifetime) }
            }
        }
        val pausing = async(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.pause(comicId, listOf(oldId)) {
                old.cancel()
                writingPaused.await()
            }
        }
        try {
            writingPaused.await()
            assertFalse("Pause must include the old worker's state write", pausing.isCompleted)
            assertNull(lifecycle.enter(comicId, UUID.randomUUID()))
            assertTrue(runCatching { lifecycle.allowNewWork(comicId) }.isFailure)
            finishWrite.complete(Unit)
            pausing.await()
            old.join()
            assertEquals(OfflineDownloadState.PAUSED, state)
            lifecycle.allowNewWork(comicId)
            val replacement = checkNotNull(lifecycle.enter(comicId, UUID.randomUUID()))
            state = OfflineDownloadState.DOWNLOADING
            // A delayed invocation of the cancelled WorkSpec is permanently
            // excluded for this process, even after new work is admitted.
            assertNull(lifecycle.enter(comicId, oldId))
            assertEquals(OfflineDownloadState.DOWNLOADING, state)
            lifecycle.leave(replacement)
        } finally {
            finishWrite.complete(Unit)
            old.cancel()
            old.join()
            pausing.await()
        }
    }

    @Test fun revokedQueuedWorkCannotRegisterLateAfterResume() = runBlocking {
        val lifecycle = OfflineWorkerLifecycle()
        val neverStarted = UUID.randomUUID()
        lifecycle.pause("fixture", listOf(neverStarted)) { }
        lifecycle.allowNewWork("fixture")
        assertNull(lifecycle.enter("fixture", neverStarted))
        val current = lifecycle.enter("fixture", UUID.randomUUID())
        assertNotNull(current)
        lifecycle.leave(checkNotNull(current))
    }
}
