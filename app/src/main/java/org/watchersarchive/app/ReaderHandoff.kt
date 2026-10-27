package org.watchersarchive.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Releases a candidate whose IO result is discarded by prompt coroutine cancellation. */
internal suspend fun <T : Any> withReaderHandoff(
    release: (T) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    load: suspend () -> T,
): T {
    val candidate = AtomicReference<T?>(null)
    return try {
        withContext(dispatcher) { load().also { candidate.set(it) } }
    } catch (failure: Throwable) {
        candidate.getAndSet(null)?.let(release)
        throw failure
    }
}
