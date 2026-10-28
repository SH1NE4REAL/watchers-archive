package org.watchersarchive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Personal labels are separate from source identity and local document locations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ShelfRenameRuntimeTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture {
        val original = CatalogSourceRecord("local", "local:rename-fixture", "content://fixture/documents/original.cbz",
            "Original X-Men title", "Synthetic local comic", null, null, null, "2026-10-28T00:00:00Z", emptyList(),
            "2022", "20 MB", "CBZ", SourceConfidence.VERIFIED)
        var current = original
        val changes = mutableListOf<Pair<String, String?>>()
        var removeCalls = 0
        private val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        val repository = object : ReaderRepository by actual {
            override suspend fun getComicsRecords() = listOf(current)
            override suspend fun shelfNames() = listOf("默认书架")
            override suspend fun shelfItems(shelf: String) = setOf(original.externalId)
            override suspend fun renameComic(comicId: String, title: String?) {
                changes += comicId to title
                require(comicId == original.externalId)
                current = current.copy(customTitle = title)
            }
            override suspend fun removeFromShelf(shelf: String, comicId: String) { removeCalls++ }
        }
    }

    private fun openRename() {
        compose.onNodeWithTag("shelf-book:local:rename-fixture").performTouchInput { longClick() }
        compose.onNodeWithText("修改书名").performClick()
    }

    @Test fun renameChangesCoverLabelAndReopensWithSavedPersonalNameWithoutChangingTheFile() {
        val fixture = Fixture()
        compose.setContent { MaterialTheme { ShelfScreen(fixture.repository) {} } }
        openRename()
        compose.onNodeWithTag("book-custom-title").performTextReplacement("我的 X 战警合集")
        compose.onNodeWithTag("confirm-book-rename").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("我的 X 战警合集").assertIsDisplayed()
        assertEquals(listOf(fixture.original.externalId to "我的 X 战警合集"), fixture.changes)
        assertEquals(fixture.original.title, fixture.current.title)
        assertEquals(fixture.original.canonicalUrl, fixture.current.canonicalUrl)
        assertEquals(fixture.original.externalId, fixture.current.externalId)
        assertEquals(0, fixture.removeCalls)
        openRename()
        assertEquals("我的 X 战警合集", compose.onNodeWithTag("book-custom-title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("reset-book-name").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(fixture.original.title).assertIsDisplayed()
        assertNull(fixture.changes.last().second)
        assertNull(fixture.current.customTitle)
    }

    @Test fun cancellingAndBlankInputDoNotChangeThePersonalName() {
        val fixture = Fixture()
        compose.setContent { MaterialTheme { ShelfScreen(fixture.repository) {} } }
        openRename()
        compose.onNodeWithTag("book-custom-title").performTextReplacement(" ")
        compose.onNodeWithTag("confirm-book-rename").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(fixture.original.title).assertIsDisplayed()
        assertTrue(fixture.changes.isEmpty())
        assertEquals(fixture.original, fixture.current)
    }

    @Test fun detailsShowPersonalNameAndRetainTheOriginalNameAsProvenance() {
        val fixture = Fixture().apply { current = original.copy(customTitle = "个人收藏：X 战警") }
        compose.setContent { MaterialTheme { ArticleDetailScreen(fixture.current, fixture.repository) {} } }
        compose.onNodeWithText("个人收藏：X 战警").assertIsDisplayed()
        compose.onNodeWithText("原名：${fixture.original.title}").assertIsDisplayed()
    }
}
