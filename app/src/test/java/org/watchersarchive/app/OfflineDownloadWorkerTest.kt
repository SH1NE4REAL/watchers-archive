package org.watchersarchive.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException

/** Executes CoroutineWorker decisions with a fake engine; no comic or remote connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OfflineDownloadWorkerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun initializeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(Executor { it.run() }).build())
    }

    private class Task : OfflineDownloadTask {
        val snapshot = OfflineDownloadStatus("fixture", "测试漫画", OfflineDownloadState.QUEUED, 0, 100)
        var runs = 0
        var failures = 0
        var retries = 0
        var pauses = 0
        var error: Exception? = null
        var started: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        override suspend fun status(comicId: String) = snapshot
        override suspend fun run(comicId: String, progress: (OfflineDownloadStatus) -> Unit) {
            runs++
            started?.complete(Unit)
            release?.await()
            error?.let { throw it }
            progress(snapshot.copy(state = OfflineDownloadState.DOWNLOADING, downloadedBytes = 100))
        }
        override suspend fun failed(comicId: String, message: String) { failures++ }
        override suspend fun retrying(comicId: String) { retries++ }
        override suspend fun paused(comicId: String) { pauses++ }
    }

    private fun worker(task: Task, attempt: Int = 0, data: Data = workDataOf(OfflineDownloads.COMIC_ID to "fixture")): OfflineDownloadWorker =
        TestListenableWorkerBuilder<OfflineDownloadWorker>(context)
            .setInputData(data)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    OfflineDownloadWorker(appContext, workerParameters, task, Unit)
            })
            .build()

    @Test fun successfulTaskRunsOnceWithoutRewritingFailureOrRetryState() = runBlocking {
        val task = Task()
        assertEquals(ListenableWorker.Result.success(), worker(task).doWork())
        assertEquals(1, task.runs)
        assertEquals(0, task.failures)
        assertEquals(0, task.retries)
        assertEquals(0, task.pauses)
    }

    @Test fun onlyNetworkErrorsRetryAndOnlyForThreeTotalAttempts() = runBlocking {
        val retry = Task().apply { error = ArchiveException(ArchiveError.NETWORK, "fixture network error") }
        assertEquals(ListenableWorker.Result.retry(), worker(retry, 0).doWork())
        assertEquals(1, retry.retries)
        assertEquals(0, retry.failures)
        val last = Task().apply { error = ArchiveException(ArchiveError.NETWORK, "fixture network error") }
        worker(last, 2).doWork().let { assertTrue(it is ListenableWorker.Result.Failure) }
        assertEquals(0, last.retries)
        assertEquals(1, last.failures)
        assertFalse(OfflineDownloadWorker.retryable(ArchiveException(ArchiveError.OBJECT_CHANGED, "changed"), 0))
        assertFalse(OfflineDownloadWorker.retryable(ArchiveException(ArchiveError.INVALID_CONTENT_RANGE, "range"), 0))
        assertFalse(OfflineDownloadWorker.retryable(java.io.IOException("disk"), 0))
    }

    @Test fun objectChangeFailsImmediatelyAndDoesNotRetryAnOldArchiveVersion() = runBlocking {
        val task = Task().apply { error = ArchiveException(ArchiveError.OBJECT_CHANGED, "changed") }
        assertTrue(worker(task).doWork() is ListenableWorker.Result.Failure)
        assertEquals(1, task.failures)
        assertEquals(0, task.retries)
    }

    @Test fun coroutineCancellationDoesNotRewritePausedDownloadAsFailed() = runBlocking {
        val task = Task().apply { started = CompletableDeferred(); release = CompletableDeferred() }
        val job = launch { worker(task).doWork() }
        task.started!!.await()
        job.cancelAndJoin()
        assertEquals(0, task.failures)
        assertEquals(0, task.retries)
        assertEquals(1, task.pauses)
    }

    @Test fun missingComicIdFailsBeforeAnyTaskOrForegroundDownload() = runBlocking {
        val task = Task()
        assertEquals(ListenableWorker.Result.failure(), worker(task, data = Data.EMPTY).doWork())
        assertEquals(0, task.runs)
    }

    @Test fun cancellationWhileWaitingForTheSerialGateStillPersistsPausedState() = runBlocking {
        val first = Task().apply { started = CompletableDeferred(); release = CompletableDeferred() }
        val queued = Task()
        val running = launch { worker(first, data = workDataOf(OfflineDownloads.COMIC_ID to "serial-first")).doWork() }
        try {
            first.started!!.await()
            // UNDISTPATCHED registers the lifetime before the IO dispatcher can
            // queue behind the first worker's global download mutex.
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                worker(queued, data = workDataOf(OfflineDownloads.COMIC_ID to "serial-queued")).doWork()
            }
            waiting.cancelAndJoin()
            assertEquals(0, queued.runs)
            assertEquals(1, queued.pauses)
            assertEquals(0, queued.failures)
        } finally { running.cancelAndJoin() }
    }

    @Test fun scheduledDownloadCanBeCancelledByItsStableComicIdentity() = runBlocking {
        val one = OfflineDownloads.uniqueName("fixture-a")
        val two = OfflineDownloads.uniqueName("fixture-b")
        assertEquals(one, OfflineDownloads.uniqueName("fixture-a"))
        assertFalse(one == two)
        OfflineDownloads.schedule(context, "fixture-a", wifiOnly = true)
        val workManager = WorkManager.getInstance(context)
        val before = workManager.getWorkInfosForUniqueWork(one).get()
        assertEquals(1, before.size)
        OfflineDownloads.pause(context, "fixture-a")
        assertTrue(workManager.getWorkInfosForUniqueWork(one).get().all { it.state.isFinished })
    }
}
