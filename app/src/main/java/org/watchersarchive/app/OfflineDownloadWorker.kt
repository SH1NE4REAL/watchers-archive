package org.watchersarchive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Durable scheduling for a user-requested full download; not the online page cache. */
internal object OfflineDownloads {
    const val COMIC_ID = "comicId"
    const val WORK_TAG = "watchers-offline-download"
    internal val lifecycle = OfflineWorkerLifecycle()
    private val operations = ConcurrentHashMap<String, Mutex>()

    fun uniqueName(comicId: String): String = "offline-" + MessageDigest.getInstance("SHA-256")
        .digest(comicId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun schedule(context: Context, comicId: String, wifiOnly: Boolean): UUID =
        operations.computeIfAbsent(comicId) { Mutex() }.withLock {
        require(comicId.isNotBlank())
        val manager = WorkManager.getInstance(context.applicationContext)
        val previous = manager.getWorkInfosForUniqueWork(uniqueName(comicId)).awaitOfflineOperation()
        // KEEP means a repeated tap must not invalidate the work already running.
        previous.firstOrNull { !it.state.isFinished }?.let { return@withLock it.id }
        // Terminal WorkInfo precedes CoroutineWorker's final cleanup. Fence old
        // IDs and await every registered finally before accepting a replacement.
        lifecycle.pause(comicId, previous.map { it.id }) { }
        lifecycle.allowNewWork(comicId)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val work = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf(COMIC_ID to comicId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        manager
            .enqueueUniqueWork(uniqueName(comicId), ExistingWorkPolicy.KEEP, work)
            .result.awaitOfflineOperation()
        work.id
    }

    suspend fun pause(context: Context, comicId: String) {
        operations.computeIfAbsent(comicId) { Mutex() }.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                val manager = WorkManager.getInstance(context.applicationContext)
                val previous = manager.getWorkInfosForUniqueWork(uniqueName(comicId)).awaitOfflineOperation()
                lifecycle.pause(comicId, previous.map { it.id }) {
                    manager.cancelUniqueWork(uniqueName(comicId)).result.awaitOfflineOperation()
                }
            }
        }
    }
}

/** Process-local lifetime fence; cancelled WorkSpec IDs cannot start late after resume. */
internal class OfflineWorkerLifecycle {
    internal class Ticket(val comicId: String, val workId: UUID) {
        val finished = CompletableDeferred<Unit>()
    }
    private class Slot {
        var paused = false
        val revoked = mutableSetOf<UUID>()
        val active = mutableMapOf<UUID, Ticket>()
    }
    private val lock = Any()
    private val slots = mutableMapOf<String, Slot>()

    fun enter(comicId: String, workId: UUID): Ticket? = synchronized(lock) {
        val slot = slots.getOrPut(comicId) { Slot() }
        if (slot.paused || workId in slot.revoked || workId in slot.active) null
        else Ticket(comicId, workId).also { slot.active[workId] = it }
    }

    fun leave(ticket: Ticket) {
        synchronized(lock) { slots[ticket.comicId]?.active?.remove(ticket.workId, ticket) }
        ticket.finished.complete(Unit)
    }

    suspend fun pause(comicId: String, knownIds: Collection<UUID>, cancelWork: suspend () -> Unit) {
        val draining = synchronized(lock) {
            val slot = slots.getOrPut(comicId) { Slot() }
            slot.paused = true
            slot.revoked.addAll(knownIds)
            slot.revoked.addAll(slot.active.keys)
            slot.active.values.toList()
        }
        cancelWork()
        draining.forEach { it.finished.await() }
    }

    fun allowNewWork(comicId: String) = synchronized(lock) {
        val slot = slots.getOrPut(comicId) { Slot() }
        check(slot.active.isEmpty()) { "上次下载任务尚未结束" }
        slot.paused = false
    }
}

internal interface OfflineDownloadTask {
    suspend fun status(comicId: String): OfflineDownloadStatus?
    suspend fun run(comicId: String, progress: (OfflineDownloadStatus) -> Unit)
    suspend fun failed(comicId: String, message: String)
    suspend fun retrying(comicId: String)
    suspend fun paused(comicId: String)
}

private class RepositoryDownloadTask(context: Context) : OfflineDownloadTask {
    private val repository = AndroidRepository(context)
    override suspend fun status(comicId: String) = repository.offlineStatus(comicId)
    override suspend fun run(comicId: String, progress: (OfflineDownloadStatus) -> Unit) =
        repository.runOfflineDownload(comicId, progress)
    override suspend fun failed(comicId: String, message: String) =
        repository.markOfflineDownloadFailure(comicId, message)
    override suspend fun retrying(comicId: String) = repository.markOfflineDownloadRetry(comicId)
    override suspend fun paused(comicId: String) = repository.markOfflineDownloadPaused(comicId)
}

/** Long-running work uses Android's foreground notification and dataSync service type. */
class OfflineDownloadWorker private constructor(
    context: Context,
    parameters: WorkerParameters,
    private val task: OfflineDownloadTask,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) :
        this(context, parameters, RepositoryDownloadTask(context.applicationContext))

    internal constructor(context: Context, parameters: WorkerParameters, task: OfflineDownloadTask, @Suppress("UNUSED_PARAMETER") testing: Unit) :
        this(context, parameters, task)

    companion object {
        private const val CHANNEL = "offline-comics"
        private val serialDownload = Mutex()
        internal fun retryable(error: Throwable, attempt: Int): Boolean =
            attempt < 2 && error is ArchiveException && error.code == ArchiveError.NETWORK
    }

    override suspend fun doWork(): Result {
        val comicId = inputData.getString(OfflineDownloads.COMIC_ID)
            ?.takeIf { it.isNotBlank() } ?: return Result.failure()
        // Register synchronously before dispatch or the global queue, so pause
        // also owns workers which have not entered the archive engine yet.
        val lifetime = OfflineDownloads.lifecycle.enter(comicId, id) ?: return Result.failure()
        try {
            return withContext(Dispatchers.IO) {
        try {
            // Waiting work is cancellable and never acquires an archive or file
            // before the previous download's cleanup has completed.
            serialDownload.withLock {
                currentCoroutineContext().ensureActive()
                val initial = task.status(comicId) ?: return@withLock Result.failure()
                if (initial.state == OfflineDownloadState.COMPLETED) return@withLock Result.success()
                setForeground(foreground(initial))
                coroutineScope {
                    val updates = Channel<OfflineDownloadStatus>(Channel.CONFLATED)
                    val reporter = launch {
                        for (status in updates) {
                            setForeground(foreground(status))
                            setProgress(workDataOf("downloaded" to status.downloadedBytes, "total" to status.totalBytes))
                            delay(500)
                        }
                    }
                    try {
                        task.run(comicId) { updates.trySend(it) }
                    } finally {
                        updates.close()
                        reporter.cancelAndJoin()
                    }
                }
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            // The engine/repository records PAUSED and preserves only durable
            // checkpoints. Cancellation must never be rewritten as FAILED.
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is ArchiveException && error.code == ArchiveError.CANCELLED) {
                throw CancellationException("离线下载已暂停").also { it.initCause(error) }
            }
            if (retryable(error, runAttemptCount)) {
                task.retrying(comicId)
                Result.retry()
            }
            else {
                val message = when {
                    error is ArchiveException && error.code == ArchiveError.OBJECT_CHANGED -> "来源文件已变化，请重新下载。"
                    error is ArchiveException && error.code == ArchiveError.NETWORK -> "网络连接中断，请稍后继续下载。"
                    error is ArchiveException -> "这个来源暂时无法完整下载，请稍后重试。"
                    else -> "下载未完成，请检查手机存储或稍后重试。"
                }
                task.failed(comicId, message)
                Result.failure(workDataOf("message" to message))
            }
        }
            }
        } catch (cancelled: CancellationException) {
            recordPaused(comicId)
            throw cancelled
        } finally {
            // PAUSED / retry / failure persistence is part of the lifetime. A
            // resumed worker cannot be admitted before all old writes finish.
            OfflineDownloads.lifecycle.leave(lifetime)
        }
    }

    private suspend fun recordPaused(comicId: String) = withContext(NonCancellable + Dispatchers.IO) {
        // A notification can cancel work while it is still waiting on the serial
        // gate, before the engine has a chance to persist a PAUSED checkpoint.
        try { task.paused(comicId) }
        catch (_: Exception) { /* Do not mask cancellation when storage itself fails. */ }
    }

    private fun foreground(status: OfflineDownloadStatus): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "漫画离线下载", NotificationManager.IMPORTANCE_LOW))
        val percentage = if (status.totalBytes > 0) ((status.downloadedBytes.coerceAtLeast(0) * 100) / status.totalBytes).coerceIn(0, 100).toInt() else 0
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载漫画")
            .setContentText("${status.title.take(80)} · $percentage%")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percentage, false)
            .addAction(android.R.drawable.ic_media_pause, "暂停", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        val notificationId = 0x40000000 or (id.hashCode() and 0x3fffffff)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }
}

private suspend fun <T> ListenableFuture<T>.awaitOfflineOperation(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { if (continuation.isActive) continuation.resume(get()) }
        catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error.cause ?: error) }
    }, Executor { it.run() })
    continuation.invokeOnCancellation { cancel(false) }
}
