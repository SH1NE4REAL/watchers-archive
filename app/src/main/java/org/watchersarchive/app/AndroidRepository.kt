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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        val traces = CopyOnWriteArrayList<String>()
        val preferences = context.getSharedPreferences("phase1-settings", Context.MODE_PRIVATE)
        val limits = ReaderLimits()
        private val initializationLock = Any()
        @Volatile private var initialized = false
        private lateinit var cacheManager: CacheManager
        private lateinit var persistentLibrary: PersistentLibrary
        private lateinit var catalogMetadata: CatalogMetadataStore
        private lateinit var shelfStore: ShelfStore

        /**
         * Cache and persistent records are opened lazily from a suspend API. The
         * previous eager construction ran directory scans, file locking and seven
         * synchronous fsyncs from Activity.onCreate's main thread.
         */
        fun ensureInitialized() {
            if (initialized) return
            synchronized(initializationLock) {
                if (initialized) return
                root.mkdirs()
                val createdCache = CacheManager(File(root, "temporary-cache"))
                val createdCatalog = CatalogMetadataStore(File(root, "catalog-metadata.properties"))
                try {
                    val createdLibrary = PersistentLibrary(File(root, "persistent-library"))
                    try {
                        demoComics.forEach { comic ->
                            createdLibrary.registerComic(comic.id, comic.title, comic.path, true)
                        }
                        cacheManager = createdCache
                        persistentLibrary = createdLibrary
                        catalogMetadata = createdCatalog
                        shelfStore = ShelfStore(File(root, "shelves.properties"))
                        initialized = true
                    } catch (error: Throwable) {
                        createdLibrary.close()
                        throw error
                    }
                } catch (error: Throwable) {
                    createdCache.close()
                    throw error
                }
            }
        }

        val cache: CacheManager get() = check(initialized) { "Repository state is not initialized" }.let { cacheManager }
        val library: PersistentLibrary get() = check(initialized) { "Repository state is not initialized" }.let { persistentLibrary }
        val metadata: CatalogMetadataStore get() = check(initialized) { "Repository state is not initialized" }.let { catalogMetadata }
        val shelves: ShelfStore get() = check(initialized) { "Repository state is not initialized" }.let { shelfStore }
    }

    private val base: String
        get() = state.preferences.getString("baseUrl", "http://10.0.2.2:8765")!!

    override val comics: List<DemoComic> = demoComics

    override var baseUrl: String
        get() = base
        set(value) { state.preferences.edit().putString("baseUrl", value.trim().trimEnd('/')).apply() }

    override suspend fun open(comic: DemoComic): ReaderSession = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        val token = CancellationToken()
        val cancellation = currentCoroutineContext()[Job]?.onCancellation { token.cancel() }
        try {
            val sourceUrl = comic.sourceUrl ?: "${baseUrl.trimEnd('/')}/${comic.path}"
            val source = HttpRangeSource(sourceUrl, state.limits) { trace ->
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

    override suspend fun lastPage(comicId: String): Int = withContext(Dispatchers.IO) {
        state.ensureInitialized(); state.library.progress(comicId)
    }
    override suspend fun bookmarkPages(comicId: String): Set<Int> = withContext(Dispatchers.IO) {
        state.ensureInitialized(); state.library.bookmarks(comicId)
    }
    override suspend fun setBookmark(comicId: String, index: Int, enabled: Boolean) = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        if (enabled) state.library.addBookmark(comicId, index) else state.library.removeBookmark(comicId, index)
    }
    override suspend fun cacheSnapshot(): CacheSnapshot = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        val snapshot = state.cache.snapshot()
        CacheSnapshot(snapshot.committedBytes, snapshot.reservedBytes + snapshot.temporaryBytes, snapshot.limitBytes, snapshot.pinnedFiles)
    }
    override suspend fun clearCache(): String = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        val reclaimed = state.cache.clearUnpinned()
        "已清理 ${formatBytes(reclaimed)}；正在阅读的页面仍受租约保护。"
    }
    override suspend fun refreshGetComics(): List<CatalogSourceRecord> = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        val service = GetComicsCatalogService()
        val records = service.fetchWordPressPosts().map { service.enrichCover(it) }
        state.metadata.upsertAll(records)
        state.metadata.records()
    }
    override suspend fun getComicsRecords(): List<CatalogSourceRecord> = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        state.metadata.records()
    }
    override suspend fun refreshOfficialGuide(): OfficialGuide =
        OfficialMarvelRecommendationService().fetchGuide(
            "https://www.marvel.com/comics/guides/2531/the_road_to_avengers_armageddon",
        )
    override suspend fun refreshOfficialCharacter(): OfficialCharacterProfile =
        OfficialMarvelRecommendationService().fetchCharacter(
            "https://www.marvel.com/characters/spider-man-peter-parker",
        )
    override suspend fun loadCover(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "WatchersArchive-Cover/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 2 * 1024 * 1024) return@withContext null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while ((bounds.outWidth / sample) * (bounds.outHeight / sample) > 2_000_000) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        } finally {
            connection.disconnect()
        }
    }
    override suspend fun inspectGetComics(record: CatalogSourceRecord): SourceAssetInspection =
        GetComicsCatalogService().inspectArticle(record.canonicalUrl)
    override suspend fun validateSourceAsset(asset: SourceAsset): SourceAsset =
        GetComicsCatalogService().validateAsset(asset)
    override suspend fun shelfNames(): List<String> = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.shelves() }
    override suspend fun createShelf(name: String) = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.createShelf(name) }
    override suspend fun addToShelf(shelf: String, comicId: String) = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.add(shelf, comicId) }
    override suspend fun shelfItems(shelf: String): Set<String> = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.items(shelf) }
    override fun traceLines(): List<String> = state.traces.toList()

    private fun decodeBounded(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ArchiveException(org.watchersarchive.core.ArchiveError.UNSUPPORTED_IMAGE, "Android 无法识别测试页面")
        val width = bounds.outWidth.toLong()
        val height = bounds.outHeight.toLong()
        val pixels = width * height
        if (width > state.limits.maxImageDimension || height > state.limits.maxImageDimension ||
            pixels > state.limits.maxImagePixels) {
            throw ArchiveException(org.watchersarchive.core.ArchiveError.LIMIT_EXCEEDED, "图片尺寸或像素数超出 Android 解码限制")
        }
        var sample = 1
        while ((ceilDiv(width, sample.toLong()) * ceilDiv(height, sample.toLong())) > 4_000_000L) {
            if (sample > Int.MAX_VALUE / 2) throw ArchiveException(org.watchersarchive.core.ArchiveError.LIMIT_EXCEEDED, "图片采样因子超出限制")
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw ArchiveException(org.watchersarchive.core.ArchiveError.UNSUPPORTED_IMAGE, "页面解码失败")
    }

    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1L) / divisor

    private fun formatBytes(bytes: Long) = String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)

    /** Registers before-cancellation cleanup so blocking HTTP reads are disconnected promptly. */
    @OptIn(InternalCoroutinesApi::class)
    private fun Job.onCancellation(action: () -> Unit): DisposableHandle =
        invokeOnCompletion(onCancelling = true) { cause ->
            // Completion handlers also run after a successful page read. Only a
            // non-null cause means this Job is actually being cancelled or failed.
            if (cause != null) action()
        }
}

private val demoComics: List<DemoComic> = listOf(
    DemoComic("standard", "标准 CBZ · 多页 PNG", "自建测试内容：标准 ZIP，含 STORE 与 DEFLATE 页面。", "standard.cbz"),
    DemoComic("zip64", "ZIP64 · 大偏移归档", "自建测试内容：逻辑偏移超过 4 GiB，不占用相同大小磁盘。", "zip64.cbz"),
    DemoComic("ignore", "Range 被忽略", "自建错误端点：服务器返回 200，客户端必须立即拒绝。", "ignore.cbz"),
    DemoComic("bad-range", "错误 Content-Range", "自建错误端点：范围起点不匹配。", "bad-range.cbz"),
    DemoComic("truncated", "网络中断", "自建错误端点：响应长度不足，不提交页面。", "truncated.cbz"),
    DemoComic("changing", "对象版本变化", "自建错误端点：ETag 变化后拒绝混合读取。", "changing.cbz"),
    DemoComic("slow", "可取消读取", "自建测试端点：低速响应，用于取消测试。", "slow.cbz"),
)
