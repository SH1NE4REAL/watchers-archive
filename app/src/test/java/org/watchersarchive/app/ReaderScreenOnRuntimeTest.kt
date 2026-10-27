package org.watchersarchive.app

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class ReaderScreenOnRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun stayAwakePreferenceOnlyAppliesWhileTheReaderOwnsTheScreen() {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        val record = CatalogSourceRecord("getcomics", "screen-on-test", "https://getcomics.org/marvel/screen-on-test/",
            "屏幕常亮测试漫画", null, null, null, null, "2026-10-27T00:00:00Z", emptyList(), null, null, "CBZ", SourceConfidence.MEDIUM)
        val repository = object : ReaderRepository by actual {
            override var keepScreenOn = true
            override suspend fun getComicsRecords() = listOf(record)
            override suspend fun savedOfficialGuides() = emptyList<OfficialGuide>()
            override suspend fun savedOfficialCharacters() = emptyList<OfficialCharacterProfile>()
            override suspend fun offlineStatus(comicId: String): OfflineDownloadStatus? = null
            override suspend fun prepareSourceReading(record: CatalogSourceRecord) = DemoComic(record.externalId, record.title, "", "fixture.cbz")
            override suspend fun lastPage(comicId: String) = 0
            override suspend fun bookmarkPages(comicId: String) = emptySet<Int>()
            override suspend fun open(comic: DemoComic) = object : ReaderSession {
                override val totalPages = 1
                override suspend fun loadPage(index: Int) = ReaderPage(0, 1, Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888), true, 0)
                override suspend fun close() { }
            }
        }
        lateinit var root: View
        compose.setContent { root = LocalView.current; MaterialTheme { ArchiveApp(repository) } }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(root.keepScreenOn) }
        compose.onNodeWithText(record.title).performClick()
        compose.onNodeWithText("开始阅读").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(root.keepScreenOn); compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(root.keepScreenOn) }
    }
}
