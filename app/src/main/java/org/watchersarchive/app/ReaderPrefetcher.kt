package org.watchersarchive.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A serial, rolling two-page window. Scheduling never disconnects HTTP on the UI thread. */
internal class ReaderPrefetcher(private val read: suspend (Int) -> Unit) {
    private data class Work(val index: Int, val job: Deferred<Unit>, var preceding: Work?)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val serialRead = Mutex()
    private val work = linkedMapOf<Int, Work>()
    private val jobs = mutableSetOf<Deferred<Unit>>()
    private var foreground = 0
    private var deferredWindow: List<Int>? = null
    private var closed = false

    fun schedule(index: Int) = scheduleWindow(listOf(index))

    fun scheduleWindow(indices: List<Int>) {
        val desired = indices.filter { it >= 0 }.distinct().take(2)
        val toStart = mutableListOf<Deferred<Unit>>()
        synchronized(lock) {
            if (closed) return
            if (foreground > 0) { deferredWindow = desired; return }
            val retained = work.filter { it.key in desired && !it.value.job.isCancelled }
            val protectedJobs = mutableSetOf<Deferred<Unit>>()
            retained.values.forEach { retainedWork ->
                var dependency: Work? = retainedWork
                while (dependency != null && protectedJobs.add(dependency.job)) {
                    // Do not retain a chain of every page previously previewed.
                    if (dependency.preceding?.job?.isCompleted == true) dependency.preceding = null
                    dependency = dependency.preceding
                }
            }
            val retired = jobs.filter { it !in protectedJobs }
            work.clear(); work.putAll(retained)
            val barrier = if (retired.isEmpty()) null else scope.async(start = CoroutineStart.LAZY) {
                retired.forEach { it.cancel() }
                retired.forEach { it.join() }
            }
            if (barrier != null) toStart += barrier
            var previous: Work? = null
            for (index in desired) {
                val existing = work[index]
                if (existing != null) { previous = existing; continue }
                val preceding = previous?.takeUnless { it.job.isCompleted }
                val job = scope.async(start = CoroutineStart.LAZY) {
                    barrier?.await()
                    preceding?.job?.await()
                    serialRead.withLock {
                        currentCoroutineContext().ensureActive()
                        read(index)
                    }
                }
                jobs += job
                job.invokeOnCompletion { synchronized(lock) { jobs.remove(job) } }
                val created = Work(index, job, preceding)
                work[index] = created
                previous = created
                toStart += job
            }
        }
        toStart.forEach { it.start() }
    }

    /** Wait only when the foreground page is not already in the validated page cache. */
    suspend fun beforeForeground(index: Int) {
        val (matching, others) = synchronized(lock) {
            foreground++
            val found = work[index]
            work.clear()
            found to jobs.filter { it !== found?.job }
        }
        try {
            others.forEach { it.cancel() }
            others.forEach { it.join() }
            matching?.let { pending ->
                try { pending.job.await() }
                catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) { pending.job.cancel(); throw cancelled }
                } catch (_: Exception) { /* Let the foreground retry and report a real source error. */ }
            }
        } catch (failure: Throwable) {
            finishForeground()
            throw failure
        }
    }

    fun finishForeground() {
        val next = synchronized(lock) {
            foreground = (foreground - 1).coerceAtLeast(0)
            if (foreground == 0) deferredWindow.also { deferredWindow = null } else null
        }
        next?.let(::scheduleWindow)
    }

    suspend fun <T> withForeground(index: Int, block: suspend () -> T): T {
        beforeForeground(index)
        return try { block() } finally { finishForeground() }
    }

    fun cancel() {
        val pending = synchronized(lock) { work.clear(); deferredWindow = null; jobs.toList() }
        // Job cancellation invokes HTTP cancellation handlers synchronously. Keep
        // those handlers away from a UI click/gesture that changes the window.
        scope.launch { pending.forEach { it.cancel() }; pending.forEach { it.join() } }
    }

    fun close() = synchronized(lock) { closed = true; deferredWindow = null; work.clear(); scope.cancel() }
}
