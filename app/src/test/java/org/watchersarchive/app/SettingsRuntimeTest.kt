package org.watchersarchive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class SettingsRuntimeTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture {
        var inspections = 0
        var cleanups = 0
        var removedOfflineFiles = 0
        private val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        val repository = object : ReaderRepository by actual {
            override var prefetchEnabled = true
            override var keepScreenOn = false
            override var allowMobileDownloads = false
            override suspend fun offlineDownloads() = listOf(OfflineDownloadStatus("downloaded", "已下载的测试漫画", OfflineDownloadState.COMPLETED, 77_000_000, 77_000_000))
            override suspend fun cacheSnapshot(): CacheSnapshot {
                inspections++
                return CacheSnapshot(if (cleanups == 0) 123_000_000 else 0, 0, 500_000_000, 0)
            }
            override suspend fun clearCache(): String { cleanups++; return "cleared" }
            override suspend fun removeOfflineComic(comicId: String) { removedOfflineFiles++ }
        }
    }

    @Test fun storageDetailsAreOpenedFromSettingsAndClearOnlyTemporaryContent() {
        val fixture = Fixture()
        compose.setContent { MaterialTheme { CacheScreen(fixture.repository) {} } }
        compose.waitForIdle()
        compose.onNodeWithText("临时阅读内容").assertDoesNotExist()
        compose.onNodeWithText("500 MB", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.inspections) }
        compose.onNodeWithTag("open-storage-management").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("123.00 MB / 500 MB").assertIsDisplayed()
        compose.onNodeWithText("空间满时自动清理", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("clear-temporary-content").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已清理可释放的临时内容。").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, fixture.cleanups)
            assertEquals(2, fixture.inspections)
            assertEquals(0, fixture.removedOfflineFiles)
        }
    }

    @Test fun actualReadingAndNetworkPreferencesAreChangedByTheirSwitches() {
        val fixture = Fixture()
        compose.setContent { MaterialTheme { CacheScreen(fixture.repository) {} } }
        compose.onNodeWithTag("setting-prefetch").assertIsOn().performClick()
        compose.onNodeWithTag("setting-keep-screen-on").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("setting-mobile-data").performScrollTo().assertIsOff().performClick()
        compose.runOnIdle {
            assertFalse(fixture.repository.prefetchEnabled)
            assertTrue(fixture.repository.keepScreenOn)
            assertTrue(fixture.repository.allowMobileDownloads)
        }
    }
}
