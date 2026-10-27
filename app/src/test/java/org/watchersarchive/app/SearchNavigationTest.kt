package org.watchersarchive.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val searches = AtomicInteger()
    private val records get() = (1..20).map { i ->
        CatalogSourceRecord("getcomics", "post:$i", "https://getcomics.org/marvel/civil-$i/", "Civil War #$i", "Synopsis",
            null, "2026-10-27T00:00:00Z", null, "2026-10-27T00:00:00Z", emptyList(), "2026", "20 MB", "CBZ", SourceConfidence.MEDIUM)
    }

    private fun repository(): ReaderRepository {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        return object : ReaderRepository by actual {
            override suspend fun getComicsRecords() = records
            override suspend fun savedOfficialGuides() = emptyList<OfficialGuide>()
            override suspend fun savedOfficialCharacters() = emptyList<OfficialCharacterProfile>()
            override suspend fun searchGetComics(query: String, page: Int): List<CatalogSourceRecord> { searches.incrementAndGet(); return records }
            override suspend fun eventCover(collection: RecommendationCollection): String? = null
            override suspend fun loadCover(url: String) = null
            override fun peekCover(url: String) = null
        }
    }

    @Test fun systemBackClearsSearchInsteadOfLeavingTheRecommendationScreen() {
        compose.setContent { MaterialTheme { DiscoveryScreen(repository()) {} } }
        compose.onNodeWithTag("catalogue-search").performTextInput("Civil")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("", compose.onNodeWithTag("catalogue-search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("catalogue-search").assertIsNotFocused()
        compose.onNodeWithTag("library-tab:recent").assertIsSelected()
        compose.onNodeWithTag("event-card:civil-war").assertDoesNotExist()
        assertTrue(!compose.activity.isFinishing)
    }

    @Test fun visibleSearchBackClearsFocusAndReturnsToCollections() {
        compose.setContent { MaterialTheme { DiscoveryScreen(repository()) {} } }
        compose.onNodeWithTag("library-tab:events").performClick()
        compose.onNodeWithTag("catalogue-search").performTextInput("Civil")
        compose.onNodeWithTag("search-back").performClick()
        compose.waitForIdle()
        assertEquals("", compose.onNodeWithTag("catalogue-search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("catalogue-search").assertIsNotFocused()
        compose.onNodeWithTag("search-clear").assertDoesNotExist()
        compose.onNodeWithTag("library-tab:events").assertIsSelected()
        compose.onNodeWithTag("event-card:civil-war").assertIsDisplayed()
    }

    @Test fun returningFromDetailRetainsTheQueryResultsAndScrollWithoutAnotherSearch() {
        val repo = repository()
        lateinit var searchState: DiscoverySearchState
        compose.setContent {
            MaterialTheme {
                val state = rememberSaveable(saver = DiscoverySearchState.Saver) { DiscoverySearchState() }
                searchState = state
                var detail by remember { mutableStateOf(false) }
                if (detail) Button(onClick = { detail = false }) { Text("返回搜索结果") }
                else DiscoveryScreen(repo, state) { detail = true }
            }
        }
        compose.onNodeWithTag("library-tab:official").performClick()
        compose.onNodeWithTag("catalogue-search").performTextInput("Civil")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { searches.get() == 1 && searchState.completedQuery == "Civil" }
        compose.onNodeWithTag("discovery-list").performScrollToNode(hasText("Civil War #18"))
        var before = 0
        compose.runOnIdle { before = searchState.listState.firstVisibleItemIndex }
        assertTrue(before > 0)
        compose.onNodeWithText("Civil War #18").performClick()
        compose.onNodeWithText("返回搜索结果").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Civil", searchState.query)
            assertEquals(before, searchState.listState.firstVisibleItemIndex)
            assertEquals(20, searchState.results.size)
            assertEquals(1, searches.get())
            assertEquals(LibrarySection.Official, searchState.section)
        }
    }
}
