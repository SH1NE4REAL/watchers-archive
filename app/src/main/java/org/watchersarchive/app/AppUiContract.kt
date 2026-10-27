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
}

interface ReaderRepository {
    val comics: List<DemoComic>
    var baseUrl: String
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
    suspend fun inspectGetComics(record: CatalogSourceRecord): SourceAssetInspection
    suspend fun validateSourceAsset(asset: SourceAsset): SourceAsset
    suspend fun shelfNames(): List<String>
    suspend fun createShelf(name: String)
    suspend fun addToShelf(shelf: String, comicId: String)
    suspend fun shelfItems(shelf: String): Set<String>
    suspend fun searchGetComics(query: String, page: Int = 1): List<CatalogSourceRecord>
    suspend fun savedOfficialGuides(): List<OfficialGuide>
    suspend fun savedOfficialCharacters(): List<OfficialCharacterProfile>
    suspend fun savedSourceInspection(record: CatalogSourceRecord): SourceAssetInspection?
    suspend fun prepareSourceReading(record: CatalogSourceRecord): DemoComic
    fun traceLines(): List<String>
}
