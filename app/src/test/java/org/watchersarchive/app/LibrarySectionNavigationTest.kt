package org.watchersarchive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
class LibrarySectionNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val books = (1..24).map { index -> CatalogSourceRecord("getcomics", "post:$index",
        "https://getcomics.org/marvel/fixture-$index/", "Latest #$index", "Synthetic test metadata", null,
        "2026-10-27T00:00:00Z", null, "2026-10-27T00:00:00Z", emptyList(), "2026", "20 MB", "CBZ", SourceConfidence.MEDIUM) }

    private fun repository(): ReaderRepository {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        return object : ReaderRepository by actual {
            override suspend fun getComicsRecords() = books
            override suspend fun savedOfficialGuides() = emptyList<OfficialGuide>()
            override suspend fun savedOfficialCharacters() = emptyList<OfficialCharacterProfile>()
            override suspend fun eventCover(collection: RecommendationCollection): String? = null
            override suspend fun loadCover(url: String) = null
            override fun peekCover(url: String) = null
        }
    }

    @Test fun startsWithRecentAndThreeFixedSectionButtons() {
        compose.setContent { MaterialTheme { DiscoveryScreen(repository()) {} } }
        compose.onNodeWithTag("catalogue-search").assertIsDisplayed()
        compose.onNodeWithTag("library-tab:recent").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("library-tab:events").assertIsDisplayed()
        compose.onNodeWithTag("library-tab:official").assertIsDisplayed()
        compose.onNodeWithText("Latest #1").assertIsDisplayed()
        compose.onNodeWithTag("event-card:civil-war").assertDoesNotExist()
        compose.onNodeWithTag("official-guide-entry").assertDoesNotExist()
    }

    @Test fun switchingSectionsSeparatesLatestEventsAndOfficialSources() {
        compose.setContent { MaterialTheme { DiscoveryScreen(repository()) {} } }
        compose.onNodeWithTag("library-tab:events").performClick()
        compose.onNodeWithTag("event-card:civil-war").assertIsDisplayed()
        compose.onNodeWithText("Latest #1").assertDoesNotExist()
        compose.onNodeWithTag("official-guide-entry").assertDoesNotExist()
        compose.onNodeWithTag("library-tab:official").performClick()
        compose.onNodeWithTag("official-guide-entry").assertIsDisplayed()
        compose.onNodeWithTag("event-card:civil-war").assertDoesNotExist()
        compose.onNodeWithText("Latest #1").assertDoesNotExist()
        compose.onNodeWithTag("library-tab:recent").performClick()
        compose.onNodeWithText("Latest #1").assertIsDisplayed()
        compose.onNodeWithTag("official-guide-entry").assertDoesNotExist()
    }

    @Test fun scrollingTheLatestListKeepsSearchAndSectionButtonsAtTheTop() {
        compose.setContent { MaterialTheme { DiscoveryScreen(repository()) {} } }
        val searchBefore = compose.onNodeWithTag("catalogue-search").fetchSemanticsNode().boundsInRoot
        val buttonsBefore = compose.onNodeWithTag("library-section-buttons").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("discovery-list").performScrollToNode(hasText("Latest #20"))
        compose.onNodeWithText("Latest #20").assertIsDisplayed()
        compose.onNodeWithTag("catalogue-search").assertIsDisplayed()
        compose.onNodeWithTag("library-tab:official").assertIsDisplayed()
        assertEquals(searchBefore, compose.onNodeWithTag("catalogue-search").fetchSemanticsNode().boundsInRoot)
        assertEquals(buttonsBefore, compose.onNodeWithTag("library-section-buttons").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun restoringLegacySavedSearchDefaultsToRecentWhileNewSavedStateKeepsSection() {
        val old = checkNotNull(DiscoverySearchState.Saver.restore(listOf("Civil", 3, 17)))
        assertEquals(LibrarySection.Recent, old.section)
        assertEquals("Civil", old.query)
        assertEquals(3, old.listState.firstVisibleItemIndex)
        val current = checkNotNull(DiscoverySearchState.Saver.restore(listOf("", 2, 9, "Events")))
        assertEquals(LibrarySection.Events, current.section)
        assertEquals(2, current.listState.firstVisibleItemIndex)
    }
}
