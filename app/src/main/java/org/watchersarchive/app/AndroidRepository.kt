package org.watchersarchive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.watchersarchive.core.ArchiveException
import org.watchersarchive.core.CacheManager
import org.watchersarchive.core.CancellationToken
import org.watchersarchive.core.HttpRangeSource
import org.watchersarchive.core.PersistentLibrary
import org.watchersarchive.core.ReaderLimits
import org.watchersarchive.core.ArchiveByteSource
import org.watchersarchive.core.ComicArchiveReader
import org.watchersarchive.core.ComicArchiveReaders
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

    private class State(val context: Context) {
        private val root = File(context.filesDir, "watchers-archive-phase1")
        val traces = CopyOnWriteArrayList<String>()
        val preferences = context.getSharedPreferences("phase1-settings", Context.MODE_PRIVATE)
        val limits = ReaderLimits()
        val covers = CoverImageCache()
        val eventCovers = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val initializationLock = Any()
        @Volatile private var initialized = false
        private lateinit var cacheManager: CacheManager
        private lateinit var persistentLibrary: PersistentLibrary
        private lateinit var catalogMetadata: CatalogMetadataStore
        private lateinit var shelfStore: ShelfStore
        private lateinit var catalogSnapshots: CatalogSnapshots

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
                try {
                    val createdCatalog = CatalogMetadataStore(File(root, "catalog-metadata.properties"))
                    val createdShelves = ShelfStore(File(root, "shelves.properties"))
                    val createdSnapshots = CatalogSnapshots(File(root, "catalog-snapshots.json"))
                    val createdLibrary = PersistentLibrary(File(root, "persistent-library"))
                    try {
                        demoComics.forEach { comic ->
                            createdLibrary.registerComic(comic.id, comic.title, comic.path, true)
                        }
                        cacheManager = createdCache
                        persistentLibrary = createdLibrary
                        catalogMetadata = createdCatalog
                        shelfStore = createdShelves
                        catalogSnapshots = createdSnapshots
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
        val snapshots: CatalogSnapshots get() = check(initialized) { "Repository state is not initialized" }.let { catalogSnapshots }
    }

    private val base: String
        get() = state.preferences.getString("baseUrl", "http://10.0.2.2:8765")!!

    override val comics: List<DemoComic> = demoComics

    override var baseUrl: String
        get() = base
        set(value) { state.preferences.edit().putString("baseUrl", value.trim().trimEnd('/')).apply() }

    override var prefetchEnabled: Boolean
        get() = state.preferences.getBoolean("prefetchNextPage", true)
        set(value) { state.preferences.edit().putBoolean("prefetchNextPage", value).apply() }

    override suspend fun open(comic: DemoComic): ReaderSession = withReaderHandoff<Session>(release = { it.closeNow() }) {
        state.ensureInitialized()
        val token = CancellationToken()
        val cancellation = currentCoroutineContext()[Job]?.onCancellation { token.cancel() }
        var source: ArchiveByteSource? = null
        var reader: ComicArchiveReader? = null
        try {
            if (!comic.isTestContent) {
                require(comic.sourceAssetStatus == SourceAssetStatus.READY && comic.verifiedETag != null && comic.verifiedLength != null) {
                    "该来源尚无已验证的漫画文件，请在详情页重试。"
                }
            }
            source = archiveSource(comic.sourceUrl ?: "${baseUrl.trimEnd('/')}/${comic.path}")
            reader = ComicArchiveReaders.create(source, state.limits, token)
            val index = reader.open(token)
            if (!comic.isTestContent && (index.identity.eTag != comic.verifiedETag || index.identity.length != comic.verifiedLength)) {
                throw ArchiveException(org.watchersarchive.core.ArchiveError.OBJECT_CHANGED, "来源文件已改变，请返回详情页重新打开。")
            }
            Session(comic, source, reader, index, token)
        } catch (failure: Throwable) {
            runCatching { reader?.close() }
            runCatching { (source as? java.io.Closeable)?.close() }
            throw failure
        } finally { cancellation?.dispose() }
    }

    private fun archiveSource(address: String): ArchiveByteSource {
        if (address.startsWith("content://")) return DocumentArchiveSource(state.context.contentResolver, android.net.Uri.parse(address), state.limits)
        return HttpRangeSource(address, state.limits) { trace ->
            state.traces.add("${trace.outcome} · ${trace.statusCode ?: "—"} · ${trace.start}-${trace.endInclusive} · ${trace.receivedBytes} B · ${trace.contentRange ?: "无 Content-Range"}")
            while (state.traces.size > 80) state.traces.removeAt(0)
        }
    }

    private inner class Session(
        private val comic: DemoComic,
        private val source: ArchiveByteSource,
        private val reader: ComicArchiveReader,
        private val index: org.watchersarchive.core.ArchiveIndex,
        private val token: CancellationToken,
    ) : ReaderSession {
        private val closed = AtomicBoolean(false)
        private val activeLeases = CopyOnWriteArrayList<CacheManager.CacheLease>()
        override val totalPages: Int get() = index.pages.size
        private val pageAccess = Mutex()
        private val prefetcher = ReaderPrefetcher { target -> prefetchRawPage(target) }

        override fun prefetchPage(index: Int) {
            if (closed.get() || !prefetchEnabled || index !in 0 until totalPages) return
            val entry = this.index.pages[index]
            if (entry.compressedSize > 8 * 1024 * 1024 || entry.uncompressedSize > 16 * 1024 * 1024) return
            prefetcher.schedule(index)
        }
        override fun cancelPrefetch() = prefetcher.cancel()

        private suspend fun prefetchRawPage(target: Int) {
            val pageToken = CancellationToken()
            val sessionCancellation = token.onCancel { pageToken.cancel() }
            val cancellation = currentCoroutineContext()[Job]?.onCancellation { pageToken.cancel() }
            try {
                pageAccess.withLock {
                    pageToken.throwIfCancelled()
                    if (closed.get()) return@withLock
                    val entry = index.pages[target]
                    val key = "${index.identity.url}|${index.identity.eTag}|${entry.stableId}"
                    val cached = state.cache.acquire(key, comic.id, markRead = false)
                    if (cached != null) { cached.close(); return@withLock }
                    val raw = reader.readPage(index, target, pageToken)
                    pageToken.throwIfCancelled()
                    state.cache.putPrefetched(key, comic.id, raw) { pageToken.throwIfCancelled() }.close()
                    // Do not decode a second Bitmap or write reading progress for a preview.
                }
            } finally { cancellation?.dispose(); sessionCancellation.close() }
        }

        override suspend fun loadPage(index: Int): ReaderPage = withReaderHandoff(release = { it.release() }) {
            prefetcher.beforeForeground(index)
            pageAccess.withLock {
                val pageToken = CancellationToken()
                val sessionCancellation = token.onCancel { pageToken.cancel() }
                val cancellation = currentCoroutineContext()[Job]?.onCancellation { pageToken.cancel() }
                try {
                    check(!closed.get()) { "阅读会话已关闭" }
                    pageToken.throwIfCancelled()
                    require(index in 0 until totalPages)
                    val page = this@Session.index.pages[index]
                    val key = "${this@Session.index.identity.url}|${this@Session.index.identity.eTag}|${page.stableId}"
                    var lease = state.cache.acquire(key, comic.id)
                    val fromCache = lease != null
                    var transferred = 0L
                    try {
                        if (lease != null) activeLeases += lease!!
                        val bytes = if (lease != null) lease!!.readBytes(state.limits.maxPageBytes) else {
                            val raw = reader.readPage(this@Session.index, index, pageToken)
                            transferred = raw.size.toLong()
                            lease = state.cache.put(key, comic.id, raw) { pageToken.throwIfCancelled() }
                            activeLeases += lease!!
                            raw
                        }
                        val bitmap = decodeBounded(bytes)
                        pageToken.throwIfCancelled()
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
                    sessionCancellation.close()
                }
            }
        }

        override suspend fun close() = withContext(Dispatchers.IO) { closeNow() }
        fun closeNow() {
            prefetcher.close()
            if (closed.compareAndSet(false, true)) token.cancel()
            activeLeases.toList().forEach { lease -> lease.close(); activeLeases.remove(lease) }
            reader.close()
            (source as? java.io.Closeable)?.close()
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
        state.covers.clearMemory()
        ComicArchiveReaders.clearMemoryCache()
        val reclaimed = state.cache.clearUnpinned()
        "已清理 ${formatBytes(reclaimed)}；正在阅读的页面仍受租约保护。"
    }
    override suspend fun refreshGetComics(): List<CatalogSourceRecord> = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        val service = GetComicsCatalogService()
        // WordPress already includes content/OG metadata. Avoid twenty serial
        // article requests just to render a catalogue page.
        val records = try { service.fetchWordPressPosts() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { service.fetchRss() }
        state.metadata.upsertAll(records)
        state.metadata.records()
    }
    override suspend fun getComicsRecords(): List<CatalogSourceRecord> = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        state.metadata.records()
    }
    override suspend fun refreshOfficialGuide(): OfficialGuide = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        OfficialMarvelRecommendationService().fetchGuide(
            "https://www.marvel.com/comics/guides/2531/the_road_to_avengers_armageddon",
        ).also { state.snapshots.saveGuide(it) }
    }
    override suspend fun refreshOfficialCharacter(): OfficialCharacterProfile = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        OfficialMarvelRecommendationService().fetchCharacter(
            "https://www.marvel.com/characters/spider-man-peter-parker",
        ).also { state.snapshots.saveCharacter(it) }
    }
    override suspend fun savedOfficialGuides(): List<OfficialGuide> = withContext(Dispatchers.IO) { state.ensureInitialized(); state.snapshots.guides() }
    override suspend fun savedOfficialCharacters(): List<OfficialCharacterProfile> = withContext(Dispatchers.IO) { state.ensureInitialized(); state.snapshots.characters() }
    override suspend fun searchGetComics(query: String, page: Int): List<CatalogSourceRecord> = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        require(query.trim().length in 2..120) { "请输入至少两个字符。" }
        GetComicsCatalogService().fetchWordPressPosts(search = query.trim(), page = page, perPage = 30).also { state.metadata.upsertAll(it) }
    }
    override suspend fun savedSourceInspection(record: CatalogSourceRecord): SourceAssetInspection? = withContext(Dispatchers.IO) {
        state.ensureInitialized(); state.snapshots.inspection(record.externalId)
    }
    override suspend fun prepareSourceReading(record: CatalogSourceRecord): DemoComic = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        if (record.source == "local") return@withContext prepareLocalReading(record)
        val raw = inspectGetComics(record)
        val checked = mutableListOf<SourceAsset>()
        var ready: SourceAsset? = null
        // Sequential, bounded probes. Landing pages and gated redirects are never bypassed.
        for (asset in raw.assets.take(6)) {
            val result = GetComicsCatalogService(onValidatedPage = { index, bytes ->
                // Reuse the already verified first page when entering the reader.
                // This obeys the same disk budget and avoids downloading it twice.
                val key = "${index.identity.url}|${index.identity.eTag}|${index.pages.first().stableId}"
                try {
                    val cached = state.cache.acquire(key, record.externalId)
                    if (cached != null) cached.close() else state.cache.put(key, record.externalId, bytes).close()
                } catch (_: java.io.IOException) { /* Full cache can safely read on demand instead. */ }
            }).validateAsset(asset)
            checked += result
            if (isVerifiedReady(result)) { ready = result; break }
        }
        state.snapshots.saveInspection(record.externalId, raw.copy(assets = checked, canStartRemoteReader = ready != null))
        val asset = ready ?: throw java.io.IOException(
            checked.firstOrNull { it.status == SourceAssetStatus.UNSUPPORTED_FORMAT }?.reason
                ?: checked.firstOrNull { it.status == SourceAssetStatus.NEEDS_USER_ACTION }?.reason
                ?: checked.firstOrNull()?.reason
                ?: "该来源暂未提供可直接阅读的文件，可先加入书架或导入本地漫画。"
        )
        DemoComic(id = record.externalId, title = record.title, description = record.synopsis.orEmpty(),
            path = asset.fileName.orEmpty(), series = "GetComics", year = record.yearLabel.orEmpty(),
            pageCountHint = asset.verifiedPageCount, isTestContent = false, sourceUrl = asset.verifiedUrl,
            sourceAssetStatus = SourceAssetStatus.READY, sourceRecordId = record.externalId,
            verifiedETag = asset.verifiedETag, verifiedLength = asset.verifiedLength)
    }
    override suspend fun importLocalComic(uri: String): CatalogSourceRecord = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        val selected = android.net.Uri.parse(uri)
        require(selected.scheme == "content") { "请通过系统文件选择器选择漫画文件。" }
        try { state.context.contentResolver.takePersistableUriPermission(selected, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { throw java.io.IOException("此文件不能保留读取权限，请从手机本地存储中选择。") }
        val name = state.context.contentResolver.query(selected, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "本地漫画"
        val id = "local:" + java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val record = CatalogSourceRecord("local", id, uri, name.substringBeforeLast('.'), "从手机选择的漫画文件，正文保留在原位置。", uri,
            null, null, java.time.Instant.now().toString(), emptyList(), null, null, null, SourceConfidence.VERIFIED)
        val ready = prepareLocalReading(record)
        val length = checkNotNull(ready.verifiedLength)
        val enriched = record.copy(sizeLabel = String.format(java.util.Locale.ROOT, "%.1f MB", length / 1_000_000.0))
        state.metadata.upsertAll(listOf(enriched))
        state.library.registerComic(id, record.title, uri, false)
        state.shelves.add("默认书架", id)
        enriched
    }

    private suspend fun prepareLocalReading(record: CatalogSourceRecord): DemoComic {
        val token = CancellationToken()
        val cancellation = currentCoroutineContext()[Job]?.onCancellation { token.cancel() }
        try {
            DocumentArchiveSource(state.context.contentResolver, android.net.Uri.parse(record.canonicalUrl), state.limits).use { source ->
                ComicArchiveReaders.create(source, state.limits, token).use { reader ->
                    val index = reader.open(token)
                    val bytes = reader.readPage(index, 0, token)
                    decodeBounded(bytes).recycle()
                    state.covers.load(record.canonicalUrl, state.cache) { bytes }
                    return DemoComic(record.externalId, record.title, record.synopsis.orEmpty(), record.title,
                        series = "本地漫画", year = record.yearLabel.orEmpty(), pageCountHint = index.pages.size,
                        isTestContent = false, sourceUrl = record.canonicalUrl, sourceAssetStatus = SourceAssetStatus.READY,
                        sourceRecordId = record.externalId, verifiedETag = index.identity.eTag, verifiedLength = index.identity.length)
                }
            }
        } finally { cancellation?.dispose() }
    }

    override fun peekCover(url: String): Bitmap? = state.covers.peek(url)
    override suspend fun loadCover(url: String): Bitmap? = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        if (url.startsWith("content://")) {
            state.covers.load(url, state.cache) {
                DocumentArchiveSource(state.context.contentResolver, android.net.Uri.parse(url), state.limits).use { source ->
                    ComicArchiveReaders.create(source, state.limits).use { reader ->
                        val index = reader.open()
                        reader.readPage(index, 0)
                    }
                }
            }
        } else state.covers.load(url, state.cache)
    }
    override suspend fun eventCover(collection: RecommendationCollection): String? = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        collection.coverUrl?.let { return@withContext it }
        state.eventCovers[collection.id]?.let { return@withContext it }
        val cached = state.metadata.records().firstOrNull { record ->
            record.title.contains(collection.searchTerm, true) && !record.coverUrl.isNullOrBlank()
        }?.coverUrl
        if (cached != null) {
            state.eventCovers[collection.id] = cached
            return@withContext cached
        }
        try {
            val found = GetComicsCatalogService().fetchWordPressPosts(search = collection.searchTerm, perPage = 5)
            state.metadata.upsertAll(found)
            found.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl?.also { state.eventCovers[collection.id] = it }
        } catch (c: CancellationException) { throw c }
        catch (_: Exception) { null }
    }
    override suspend fun inspectGetComics(record: CatalogSourceRecord): SourceAssetInspection =
        GetComicsCatalogService().inspectArticle(record.canonicalUrl)
    override suspend fun validateSourceAsset(asset: SourceAsset): SourceAsset =
        GetComicsCatalogService().validateAsset(asset)
    override suspend fun shelfNames(): List<String> = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.shelves() }
    override suspend fun createShelf(name: String) = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.createShelf(name) }
    override suspend fun deleteShelf(name: String) = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.deleteShelf(name) }
    override suspend fun addToShelf(shelf: String, comicId: String) = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.add(shelf, comicId) }
    override suspend fun removeFromShelf(shelf: String, comicId: String) = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.remove(shelf, comicId) }
    override suspend fun moveToShelf(from: String?, to: String, comicId: String) = withContext(Dispatchers.IO) { state.ensureInitialized(); state.shelves.move(from, to, comicId) }
    override suspend fun shelfItems(shelf: String): Set<String> = withContext(Dispatchers.IO) {
        state.ensureInitialized()
        if (shelf == "全部") state.shelves.allItems() else state.shelves.items(shelf)
    }
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
