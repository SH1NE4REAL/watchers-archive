package org.watchersarchive.app

import kotlinx.coroutines.*

/** At most one page ahead. Foreground changes cancel unrelated work before reading. */
internal class ReaderPrefetcher(private val read: suspend (Int) -> Unit) {
    private data class Work(val index: Int, val job: Deferred<Unit>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var work: Work? = null
    private var closed = false

    fun schedule(index: Int) {
        val next = synchronized(lock) {
            if (closed || work?.let { it.index == index && !it.job.isCancelled } == true) return
            val previous = work
            previous?.job?.cancel()
            val job = scope.async(start = CoroutineStart.LAZY) {
                previous?.job?.join()
                currentCoroutineContext().ensureActive()
                read(index)
            }
            Work(index, job).also { work = it }
        }
        next.job.start()
    }

    suspend fun beforeForeground(index: Int) {
        val pending = synchronized(lock) {
            work?.also { if (it.index != index) it.job.cancel() }
        } ?: return
        if (pending.index == index) {
            try { pending.job.await() }
            catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) { pending.job.cancel(); throw cancelled }
            } catch (_: Exception) { /* The foreground read reports any actual error. */ }
        } else pending.job.join()
        synchronized(lock) { if (work === pending) work = null }
    }

    fun cancel() = synchronized(lock) { work?.job?.cancel(); work = null }
    fun close() = synchronized(lock) { closed = true; work?.job?.cancel(); work = null; scope.cancel() }
}
