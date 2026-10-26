package org.watchersarchive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CacheManager
import org.watchersarchive.core.CancellationToken
import org.watchersarchive.core.HttpRangeSource
import org.watchersarchive.core.PersistentLibrary
import org.watchersarchive.core.ReaderLimits
import org.watchersarchive.core.ZipArchiveReader
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Android adapter for the pure JVM reader. No network or cache work is done on the UI thread. */
class AndroidRepository private constructor(private val state: State) : ReaderRepository {
    companion object {
        @Volatile private var processState: State? = null

        /** A process-wide owner prevents Activity recreation from opening the cache twice. */
        operator fun invoke(context: Context): AndroidRepository = synchronized(this) {
            val application = context.applicationContext
            val state = processState ?: State(application).also { processState = it }
            AndroidRepository(state)
        }
    }

    private class State(context: Context) {
        private val root = File(context.filesDir, "watchers-archive-phase1")
        val cache = CacheManager(File(root, "temporary-cache"))
        val library = PersistentLibrary(File(root, "persistent-library"))
        val traces = CopyOnWriteArrayList<String>()
        val preferences = context.getSharedPreferences("phase1-settings", Context.MODE_PRIVATE)
        val lock = Any()
        val limits = ReaderLimits()

        init {
            root.mkdirs()
            listOf(
                DemoComic("standard", "标准 CBZ · 多页 PNG", "自建测试内容：标准 ZIP，含 STORE 与 DEFLATE 页面。", "standard.cbz"),
                DemoComic("zip64", "ZIP64 · 大偏移归档", "自建测试内容：逻辑偏移超过 4 GiB，不占用相同大小磁盘。", "zip64.cbz"),
                DemoComic("ignore", "Range 被忽略", "自建错误端点：服务器返回 200，客户端必须立即拒绝。", "ignore.cbz"),
                DemoComic("bad-range", "错误 Content-Range", "自建错误端点：范围起点不匹配。", "bad-range.cbz"),
                DemoComic("truncated", "网络中断", "自建错误端点：响应长度不足，不提交页面。", "truncated.cbz"),
                DemoComic("changing", "对象版本变化", "自建错误端点：ETag 变化后拒绝混合读取。", "changing.cbz"),
                DemoComic("slow", "可取消读取", "自建测试端点：低速响应，用于取消测试。", "slow.cbz"),
            ).forEach { comic -> library.registerComic(comic.id, comic.title, comic.path, true) }
        }
    }

    private val base: String
        get() = state.preferences.getString("baseUrl", "http://10.0.2.2:8765")!!

    override val comics: List<DemoComic> = listOf(
        DemoComic("standard", "标准 CBZ · 多页 PNG", "自建测试内容：标准 ZIP，含 STORE 与 DEFLATE 页面。", "standard.cbz"),
        DemoComic("zip64", "ZIP64 · 大偏移归档", "自建测试内容：逻辑偏移超过 4 GiB，不占用相同大小磁盘。", "zip64.cbz"),
        DemoComic("ignore", "Range 被忽略", "自建错误端点：服务器返回 200，客户端必须立即拒绝。", "ignore.cbz"),
        DemoComic("bad-range", "错误 Content-Range", "自建错误端点：范围起点不匹配。", "bad-range.cbz"),
        DemoComic("truncated", "网络中断", "自建错误端点：响应长度不足，不提交页面。", "truncated.cbz"),
        DemoComic("changing", "对象版本变化", "自建错误端点：ETag 变化后拒绝混合读取。", "changing.cbz"),
        DemoComic("slow", "可取消读取", "自建测试端点：低速响应，用于取消测试。", "slow.cbz"),
    )

    override var baseUrl: String
        get() = base
        set(value) { state.preferences.edit().putString("baseUrl", value.trim().trimEnd('/')).apply() }

    override suspend fun open(comic: DemoComic): ReaderSession = withContext(Dispatchers.IO) {
        val token = CancellationToken()
        val cancellation = currentCoroutineContext()[Job]?.onCancellation { token.cancel() }
        try {
            val source = HttpRangeSource("${baseUrl.trimEnd('/')}/${comic.path}", state.limits) { trace ->
                state.traces.add("${trace.outcome} · ${trace.statusCode ?: "—"} · ${trace.start}-${trace.endInclusive} · ${trace.receivedBytes} B · ${trace.contentRange ?: "无 Content-Range"}")
                while (state.traces.size > 80) state.traces.removeAt(0)
            }
            val index = ZipArchiveReader(source, state.limits).open(token)
            Session(comic, source, index, token)
        } finally {
            cancellation?.dispose()
        }
    }

    private inner class Session(
        private val comic: DemoComic,
        private val source: HttpRangeSource,
        private val index: org.watchersarchive.core.ArchiveIndex,
        private val token: CancellationToken,
    ) : ReaderSession {
        private val closed = AtomicBoolean(false)
        private val activeLeases = CopyOnWriteArrayList<CacheManager.CacheLease>()
        override val totalPages: Int get() = index.pages.size

        override suspend fun loadPage(index: Int): ReaderPage = withContext(Dispatchers.IO) {
            val cancellation = currentCoroutineContext()[Job]?.onCancellation { token.cancel() }
            try {
                check(!closed.get()) { "阅读会话已关闭" }
                require(index in 0 until totalPages)
                val page = this@Session.index.pages[index]
                val key = "${this@Session.index.identity.url}|${this@Session.index.identity.eTag}|${page.stableId}"
                var lease = state.cache.acquire(key, comic.id)
                val fromCache = lease != null
                var transferred = 0L
                try {
                    if (lease != null) activeLeases += lease!!
                    val bytes = if (lease != null) lease!!.readBytes(state.limits.maxPageBytes) else {
                        val raw = ZipArchiveReader(source, state.limits).readPage(this@Session.index, index, token)
                        transferred = raw.size.toLong()
                        lease = state.cache.put(key, comic.id, raw) { token.throwIfCancelled() }
                        activeLeases += lease!!
                        raw
                    }
                    val bitmap = decodeBounded(bytes)
                    if (!fromCache && lease != null && !activeLeases.contains(lease)) activeLeases += lease!!
                    state.library.setProgress(comic.id, index)
                    val owned = lease
                    val released = AtomicBoolean(false)
                    ReaderPage(index, totalPages, bitmap, fromCache, transferred) {
                        if (released.compareAndSet(false, true)) {
                            owned?.close()
                            if (owned != null) activeLeases.remove(owned)
                        }
                    }
                } catch (e: Throwable) {
                    lease?.close()
                    if (lease != null) activeLeases.remove(lease)
                    throw e
                }
            } finally {
                cancellation?.dispose()
            }
        }

        override suspend fun close() = withContext(Dispatchers.IO) {
            if (closed.compareAndSet(false, true)) token.cancel()
            // Keep cleanup idempotent: a page load can race the first close after its
            // cache commit. Repeated close calls must still release that late lease.
            activeLeases.toList().forEach { it.close() }
            activeLeases.clear()
        }
    }

    override suspend fun lastPage(comicId: String): Int = withContext(Dispatchers.IO) { state.library.progress(comicId) }
    override suspend fun bookmarkPages(comicId: String): Set<Int> = withContext(Dispatchers.IO) { state.library.bookmarks(comicId) }
    override suspend fun setBookmark(comicId: String, index: Int, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (enabled) state.library.addBookmark(comicId, index) else state.library.removeBookmark(comicId, index)
    }
    override suspend fun cacheSnapshot(): CacheSnapshot = withContext(Dispatchers.IO) {
        val snapshot = state.cache.snapshot()
        CacheSnapshot(snapshot.committedBytes, snapshot.reservedBytes + snapshot.temporaryBytes, snapshot.limitBytes, snapshot.pinnedFiles)
    }
    override suspend fun clearCache(): String = withContext(Dispatchers.IO) {
        val reclaimed = state.cache.clearUnpinned()
        "已清理 ${formatBytes(reclaimed)}；正在阅读的页面仍受租约保护。"
    }
    override fun traceLines(): List<String> = state.traces.toList()

    private fun decodeBounded(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ArchiveException(org.watchersarchive.core.ArchiveError.UNSUPPORTED_IMAGE, "Android 无法识别测试页面")
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 4_000_000L) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw ArchiveException(org.watchersarchive.core.ArchiveError.UNSUPPORTED_IMAGE, "页面解码失败")
    }

    private fun formatBytes(bytes: Long) = String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)

    /** Registers before-cancellation cleanup so blocking HTTP reads are disconnected promptly. */
    @OptIn(InternalCoroutinesApi::class)
    private fun Job.onCancellation(action: () -> Unit): DisposableHandle =
        invokeOnCompletion(onCancelling = true) { action() }
}
