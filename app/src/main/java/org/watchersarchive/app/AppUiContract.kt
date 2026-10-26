package org.watchersarchive.app

import android.graphics.Bitmap

/** The UI only displays data returned by the real reader and cache implementation. */
data class DemoComic(
    val id: String,
    val title: String,
    val description: String,
    val path: String,
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
    fun traceLines(): List<String>
}
