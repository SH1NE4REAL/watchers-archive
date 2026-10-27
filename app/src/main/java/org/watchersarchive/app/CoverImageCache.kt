package org.watchersarchive.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.watchersarchive.core.CacheManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Bounded memory thumbnails + the same disk budget used by reader pages. */
internal class CoverImageCache(
    private val fetch: suspend (String) -> ByteArray = ::fetchCoverBytes,
) {
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val downloads = Semaphore(3)
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
    private val readers = mutableMapOf<String, Int>()
    private val failedAt = mutableMapOf<String, Long>()

    private fun normalized(url: String) = if (url.startsWith("content://")) url else safeSourceUrl(url) ?: url
    fun peek(url: String): Bitmap? = memory.get(normalized(url))?.takeUnless { it.isRecycled }
    fun clearMemory() { memory.evictAll(); synchronized(lock) { failedAt.clear() } }

    suspend fun load(url: String, disk: CacheManager, localRead: (suspend () -> ByteArray)? = null): Bitmap? {
        val canonical = normalized(url)
        if (canonical != url) return load(canonical, disk, localRead)
        peek(url)?.let { return it }
        val task = synchronized(lock) {
            if (System.currentTimeMillis() - (failedAt[url] ?: 0L) < 15_000) return null
            readers[url] = (readers[url] ?: 0) + 1
            inFlight[url] ?: scope.async(start = CoroutineStart.LAZY) {
                downloads.withPermit {
                    peek(url)?.let { return@withPermit it }
                    val key = "cover:$url"
                    try {
                        disk.acquire(key, "covers")?.use { cached ->
                            decode(cached.readBytes(MAXIMUM_ENCODED))?.let { image ->
                                memory.put(url, image)
                                return@withPermit image
                            }
                        }
                        val bytes = localRead?.invoke() ?: fetch(url)
                        currentCoroutineContext().ensureActive()
                        val image = decode(bytes) ?: throw IOException("封面图片无法解码")
                        // Local first pages can be large; store only a bounded resized thumbnail.
                        val encoded = if (bytes.size <= MAXIMUM_ENCODED) bytes else ByteArrayOutputStream().also {
                            image.compress(Bitmap.CompressFormat.JPEG, 85, it)
                        }.toByteArray()
                        if (encoded.size <= MAXIMUM_ENCODED) {
                            try { disk.put(key, "covers", encoded).close() }
                            catch (_: IOException) { /* A full disk cache does not prevent a bounded in-memory cover. */ }
                        }
                        memory.put(url, image)
                        image
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) { failedAt[url] = System.currentTimeMillis() }
                        null
                    }
                }
            }.also { created ->
                inFlight[url] = created
                created.invokeOnCompletion { synchronized(lock) { if (inFlight[url] === created) inFlight.remove(url) } }
            }
        }
        task.start()
        return try { task.await() }
        finally {
            synchronized(lock) {
                val count = (readers[url] ?: 1) - 1
                if (count <= 0) {
                    readers.remove(url)
                    if (!task.isCompleted) {
                        if (inFlight[url] === task) inFlight.remove(url)
                        task.cancel()
                    }
                } else readers[url] = count
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.size > 32 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth.toLong(); val height = bounds.outHeight.toLong()
        if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384 || width * height > 20_000_000) return null
        var sample = 1
        while (((width + sample - 1) / sample) * ((height + sample - 1) / sample) > 400_000) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    companion object { private const val MAXIMUM_ENCODED = 2 * 1024 * 1024 }
}

private suspend fun fetchCoverBytes(url: String): ByteArray {
    var address = safeSourceUrl(url) ?: throw IOException("封面地址无效")
    repeat(6) { count ->
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; WatchersArchive/0.4)")
        val cancellation = currentCoroutineContext()[Job]?.onSourceCancellation { connection.disconnect() }
        try {
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                if (count == 5) throw IOException("封面重定向过多")
                address = safeSourceUrl(connection.getHeaderField("Location"), address) ?: throw IOException("封面重定向无效")
                return@repeat
            }
            if (status != 200) throw IOException("封面 HTTP $status")
            if ((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0) > 2 * 1024 * 1024) throw IOException("封面超出上限")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() > 2 * 1024 * 1024 - read) throw IOException("封面超出上限")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally { cancellation?.dispose(); connection.disconnect() }
    }
    throw IOException("封面重定向过多")
}
