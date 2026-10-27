package org.watchersarchive.app

import android.graphics.Bitmap

/** The UI only displays data returned by the real reader and cache implementation. */
data class DemoComic(
    val id: String,
    val title: String,
    val description: String,
    val path: String,
    /** Display metadata for the catalogue shell. These are test/local values until real metadata is connected. */
    val series: String = "本地测试系列",
    val year: String = "测试内容",
    val pageCountHint: Int? = null,
    val isTestContent: Boolean = true,
    val sourceUrl: String? = null,
    val sourceAssetStatus: SourceAssetStatus? = null,
    val sourceRecordId: String? = null,
    val verifiedETag: String? = null,
    val verifiedLength: Long? = null,
)

data class ReaderPage(
    val index: Int,
    val total: Int,
    val bitmap: Bitmap,
    val fromCache: Boolean,
    val transferredBytes: Long,
    val stablePageId: String? = null,
    /** Releases the cache lease for this displayed page; idempotent in the repository. */
    val release: () -> Unit = {},
)

data class CacheSnapshot(
    val committedBytes: Long,
    val reservedBytes: Long,
    val limitBytes: Long,
    val pinnedEntries: Int,
)

interface ReaderSession {
    val totalPages: Int
    suspend fun loadPage(index: Int): ReaderPage
    suspend fun close()
    /** Optional bounded read-ahead. It never marks a page as read. */
    fun prefetchPage(index: Int) { }
    fun cancelPrefetch() { }
}

interface ReaderRepository {
    val comics: List<DemoComic>
    var baseUrl: String
    var prefetchEnabled: Boolean
    suspend fun open(comic: DemoComic): ReaderSession
    suspend fun lastPage(comicId: String): Int
    suspend fun bookmarkPages(comicId: String): Set<Int>
    suspend fun setBookmark(comicId: String, index: Int, enabled: Boolean)
    suspend fun cacheSnapshot(): CacheSnapshot
    /** Returns a result explaining retained entries if they are currently in use. */
    suspend fun clearCache(): String
    suspend fun refreshGetComics(): List<CatalogSourceRecord>
    suspend fun getComicsRecords(): List<CatalogSourceRecord>
    suspend fun refreshOfficialGuide(): OfficialGuide
    suspend fun refreshOfficialCharacter(): OfficialCharacterProfile
    suspend fun loadCover(url: String): Bitmap?
    fun peekCover(url: String): Bitmap? = null
    suspend fun eventCover(collection: RecommendationCollection): String? = collection.coverUrl
    suspend fun inspectGetComics(record: CatalogSourceRecord): SourceAssetInspection
    suspend fun validateSourceAsset(asset: SourceAsset): SourceAsset
    suspend fun shelfNames(): List<String>
    suspend fun createShelf(name: String)
    suspend fun deleteShelf(name: String)
    suspend fun addToShelf(shelf: String, comicId: String)
    suspend fun removeFromShelf(shelf: String, comicId: String)
    suspend fun moveToShelf(from: String?, to: String, comicId: String)
    suspend fun shelfItems(shelf: String): Set<String>
    suspend fun searchGetComics(query: String, page: Int = 1): List<CatalogSourceRecord>
    suspend fun savedOfficialGuides(): List<OfficialGuide>
    suspend fun savedOfficialCharacters(): List<OfficialCharacterProfile>
    suspend fun savedSourceInspection(record: CatalogSourceRecord): SourceAssetInspection?
    suspend fun prepareSourceReading(record: CatalogSourceRecord): DemoComic
    suspend fun importLocalComic(uri: String): CatalogSourceRecord
    fun traceLines(): List<String>
}
