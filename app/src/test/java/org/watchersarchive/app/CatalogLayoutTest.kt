package org.watchersarchive.app

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual Compose layout/navigation tests for event banners and shelf cover folders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val bitmap by lazy { Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888) }
    private fun record(id: String, title: String) = CatalogSourceRecord("getcomics", id,
        "https://getcomics.org/marvel/$id/", title, "Synopsis", "https://example.org/$id.jpg",
        "2026-10-27T00:00:00Z", null, "2026-10-27T00:00:00Z", listOf("3"), "2026", "77 MB", "CBZ", SourceConfidence.MEDIUM)
    private val books get() = listOf(
        record("civil", "Civil War collection"), record("secret", "Secret Wars collection"),
        record("gauntlet", "Infinity Gauntlet collection"), record("plain", "Another comic"))

    private fun repository(): ReaderRepository {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        val memberships = mutableMapOf(
            "大事件" to mutableSetOf("civil", "secret"),
            "默认书架" to mutableSetOf("gauntlet", "plain"),
        )
        return object : ReaderRepository by actual {
            override suspend fun getComicsRecords() = books
            override suspend fun savedOfficialGuides() = emptyList<OfficialGuide>()
            override suspend fun savedOfficialCharacters() = emptyList<OfficialCharacterProfile>()
            override suspend fun shelfNames() = listOf("默认书架", "大事件")
            override suspend fun shelfItems(shelf: String): Set<String> = when (shelf) {
                "全部" -> memberships.values.flatten().toSet()
                else -> memberships[shelf].orEmpty().toSet()
            }
            override suspend fun removeFromShelf(shelf: String, comicId: String) {
                if (shelf == "全部") memberships.values.forEach { it.remove(comicId) }
                else memberships[shelf]?.remove(comicId)
            }
            override suspend fun moveToShelf(from: String?, to: String, comicId: String) {
                if (from == null) memberships.values.forEach { it.remove(comicId) }
                else memberships[from]?.remove(comicId)
                memberships.getOrPut(to) { mutableSetOf() }.add(comicId)
            }
            override fun peekCover(url: String) = bitmap
            override suspend fun loadCover(url: String) = bitmap
            override suspend fun eventCover(collection: RecommendationCollection) = books.first().coverUrl
        }
    }

    @Test fun eventCardsSpanTheRowAndAreStackedVertically() {
        compose.setContent { MaterialTheme { DiscoveryScreen(repository()) {} } }
        compose.waitForIdle()
        val first = compose.onNodeWithTag("event-card:civil-war").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("event-card:secret-wars").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, second.left, 0.1f)
        assertEquals(first.right, second.right, 0.1f)
        assertTrue("Each event must be a row below the previous event", second.top >= first.bottom)
        compose.onNodeWithTag("discovery-list").performScrollToNode(hasTestTag("event-card:infinity-gauntlet"))
        compose.onNodeWithTag("event-card:infinity-gauntlet").assertIsDisplayed()
    }

    @Test fun shelfPlacesFolderAndBooksInOneGridRowThenOpensTheFolder() {
        compose.setContent { MaterialTheme { ShelfScreen(repository()) {} } }
        compose.waitForIdle()
        val folder = compose.onNodeWithTag("shelf-folder:大事件").fetchSemanticsNode().boundsInRoot
        val firstBook = compose.onNodeWithTag("shelf-book:gauntlet").fetchSemanticsNode().boundsInRoot
        val secondBook = compose.onNodeWithTag("shelf-book:plain").fetchSemanticsNode().boundsInRoot
        assertEquals(folder.top, firstBook.top, 0.1f)
        assertEquals(firstBook.top, secondBook.top, 0.1f)
        assertTrue(folder.right < firstBook.left && firstBook.right < secondBook.left)
        compose.onNodeWithTag("shelf-book:civil").assertDoesNotExist()
        compose.onNodeWithTag("shelf-folder:大事件").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shelf-book:civil").assertIsDisplayed()
        compose.onNodeWithTag("shelf-book:secret").assertIsDisplayed()
        compose.onNodeWithTag("shelf-book:plain").assertDoesNotExist()
        compose.onNodeWithText("‹ 书架").performClick()
        compose.onNodeWithTag("shelf-folder:大事件").assertIsDisplayed()
    }

    @Test fun longPressRemoveHidesTheBookWithoutDeletingItsSourceRecord() {
        val repository = repository()
        compose.setContent { MaterialTheme { ShelfScreen(repository) {} } }
        compose.waitForIdle()
        compose.onNodeWithTag("shelf-book:plain").performTouchInput { longClick() }
        compose.onNodeWithText("从书架移除").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shelf-book:plain").assertDoesNotExist()
        assertTrue(runBlocking { repository.getComicsRecords() }.any { it.externalId == "plain" })
        assertTrue(runBlocking { repository.shelfItems("全部") }.contains("gauntlet"))
    }

    @Test fun longPressMoveOutOfCategoryReturnsTheBookToTheRootShelf() {
        val repository = repository()
        compose.setContent { MaterialTheme { ShelfScreen(repository) {} } }
        compose.waitForIdle()
        compose.onNodeWithTag("shelf-folder:大事件").performClick()
        compose.onNodeWithTag("shelf-book:civil").performTouchInput { longClick() }
        compose.onNodeWithText("移出当前分类").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shelf-book:civil").assertDoesNotExist()
        compose.onNodeWithText("‹ 书架").performClick()
        compose.onNodeWithTag("shelf-book:civil").assertIsDisplayed()
        assertTrue(runBlocking { repository.getComicsRecords() }.any { it.externalId == "civil" })
    }

    @Test fun longPressMoveToCategoryRemovesTheRootTileAndAddsTheFolderMember() {
        val repository = repository()
        compose.setContent { MaterialTheme { ShelfScreen(repository) {} } }
        compose.waitForIdle()
        compose.onNodeWithTag("shelf-book:plain").performTouchInput { longClick() }
        compose.onNodeWithText("移动到其他分类").performClick()
        compose.onNodeWithTag("shelf-move-target:大事件").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shelf-book:plain").assertDoesNotExist()
        compose.onNodeWithTag("shelf-folder:大事件").performClick()
        compose.onNodeWithTag("shelf-book:plain").assertIsDisplayed()
    }
}
