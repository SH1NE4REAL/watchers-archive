package org.watchersarchive.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver

internal enum class LibrarySection(val label: String, val key: String) {
    Recent("最近更新", "recent"),
    Events("大事件专题", "events"),
    Official("官方推荐", "official"),
}

/**
 * Owned by the catalogue destination's parent so opening a detail does not throw
 * away its search result or scroll position. Only small navigation state is saved
 * in the Android Bundle; article snapshots remain in the persistent catalogue.
 */
internal class DiscoverySearchState(
    query: String = "",
    firstVisibleIndex: Int = 0,
    firstVisibleOffset: Int = 0,
    section: LibrarySection = LibrarySection.Recent,
) {
    var section by mutableStateOf(section)
    var query by mutableStateOf(query)
    var results by mutableStateOf(emptyList<CatalogSourceRecord>())
    var completedQuery by mutableStateOf<String?>(null)
    var page by mutableIntStateOf(1)
    var moreResults by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    val listState = LazyListState(firstVisibleIndex, firstVisibleOffset)

    fun clear() {
        query = ""
        results = emptyList()
        completedQuery = null
        page = 1
        moreResults = false
        notice = null
    }

    companion object {
        val Saver = listSaver<DiscoverySearchState, Any>(
            save = { listOf(it.query, it.listState.firstVisibleItemIndex, it.listState.firstVisibleItemScrollOffset, it.section.name) },
            restore = { saved -> DiscoverySearchState(saved[0] as String, saved[1] as Int, saved[2] as Int,
                LibrarySection.entries.firstOrNull { it.name == saved.getOrNull(3) } ?: LibrarySection.Recent) },
        )
    }
}
