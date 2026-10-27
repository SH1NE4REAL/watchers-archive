package org.watchersarchive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogSearchUiRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private fun book(id: String, title: String) = CatalogSourceRecord("getcomics", id,
        "https://example.test/$id/", title, null, null, null, null, "2026-10-27T00:00:00Z",
        emptyList(), null, "20 MB", "CBZ", SourceConfidence.MEDIUM)

    private fun repository(records: List<CatalogSourceRecord>, search: suspend (String, Int) -> CatalogSearchPage): ReaderRepository {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        return object : ReaderRepository by actual {
            override suspend fun getComicsRecords() = records
            override suspend fun savedOfficialGuides() = emptyList<OfficialGuide>()
            override suspend fun savedOfficialCharacters() = emptyList<OfficialCharacterProfile>()
            override suspend fun searchCatalogue(query: String, page: Int) = search(query, page)
        }
    }

    @Test fun chineseCommonAliasFindsSavedEnglishTitleWhileOnlineSearchIsUnavailable() {
        val civil = book("civil", "Civil War #1 (2006)")
        val thor = book("thor", "Thor #1 (2020)")
        val repository = repository(listOf(civil, thor)) { _, _ -> throw java.io.IOException("fixture offline") }
        compose.setContent { MaterialTheme { DiscoveryScreen(repository) {} } }
        compose.onNodeWithTag("catalogue-search").performTextInput("内战")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("在线搜索暂时不可用，显示已保存的匹配条目。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(civil.title).assertIsDisplayed()
        compose.onNodeWithText(thor.title).assertDoesNotExist()
    }

    @Test fun paginationUsesRemoteContinuationInsteadOfTheRankedResultCount() {
        val first = book("thor1", "Thor #1 (2020)")
        val second = book("thor2", "Thor #2 (2020)")
        val pages = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val repository = repository(emptyList()) { _, page ->
            pages += page
            CatalogSearchPage(if (page == 1) listOf(first) else listOf(second), page == 1)
        }
        compose.setContent { MaterialTheme { DiscoveryScreen(repository) {} } }
        compose.onNodeWithTag("catalogue-search").performTextInput("tHoR")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { pages.size == 1 }
        compose.onNodeWithText("加载更多").performClick()
        compose.waitUntil(5_000) { pages.size == 2 }
        compose.onNodeWithTag("discovery-list").performScrollToNode(hasText(second.title))
        compose.onNodeWithText(second.title).assertIsDisplayed()
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(1, 2), pages) }
    }
}
