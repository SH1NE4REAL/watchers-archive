package org.watchersarchive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class OfflineDownloadUiTest {
    @get:Rule val compose = createComposeRule()
    private val record = CatalogSourceRecord("getcomics", "download-ui", "https://getcomics.org/marvel/download-ui/",
        "测试漫画", null, null, null, null, "2026-10-27T00:00:00Z", emptyList(), null, "77 MB", "CBZ", SourceConfidence.MEDIUM)

    @Test fun fullBookDownloadRequiresExplicitConfirmationAndDefaultsToWifi() {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        var calls = 0
        var wifi = false
        val repository = object : ReaderRepository by actual {
            override suspend fun offlineDownloads() = emptyList<OfflineDownloadStatus>()
            override suspend fun offlineStatus(comicId: String): OfflineDownloadStatus? = null
            override suspend fun downloadComic(record: CatalogSourceRecord, wifiOnly: Boolean) { calls++; wifi = wifiOnly }
        }
        compose.setContent { MaterialTheme { DownloadComicAction(record, repository) } }
        compose.onNodeWithTag("download-comic").performClick()
        compose.onNodeWithText("下载整本漫画").assertIsDisplayed()
        compose.onNodeWithTag("allow-mobile-data").assertIsOff()
        assertEquals(0, calls)
        compose.onNodeWithTag("confirm-download").performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        assertTrue(wifi)
    }

    @Test fun startingDownloadUsesMobileDataOnlyAfterExplicitChoice() {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        var calls = 0
        var wifi = true
        val repository = object : ReaderRepository by actual {
            override suspend fun offlineStatus(comicId: String): OfflineDownloadStatus? = null
            override suspend fun downloadComic(record: CatalogSourceRecord, wifiOnly: Boolean) { calls++; wifi = wifiOnly }
        }
        compose.setContent { MaterialTheme { DownloadComicAction(record, repository) } }
        compose.onNodeWithTag("download-comic").performClick()
        compose.onNodeWithTag("allow-mobile-data").performClick()
        assertEquals(0, calls)
        compose.onNodeWithTag("confirm-download").performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        assertFalse(wifi)
    }

    @Test fun managerResumeDefaultsToWifiWithoutImmediatelyStarting() = verifyManagerResume(allowMobileData = false)

    @Test fun managerResumeCanExplicitlyUseMobileData() = verifyManagerResume(allowMobileData = true)

    private fun verifyManagerResume(allowMobileData: Boolean) {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        var calls = 0
        var wifi: Boolean? = null
        val repository = object : ReaderRepository by actual {
            override suspend fun offlineDownloads() = listOf(OfflineDownloadStatus(record.externalId, record.title,
                OfflineDownloadState.PAUSED, 512, 1024))
            override suspend fun resumeDownload(comicId: String, wifiOnly: Boolean) { calls++; wifi = wifiOnly }
        }
        compose.setContent { MaterialTheme { OfflineDownloadsCard(repository) } }
        compose.waitForIdle()
        compose.onNodeWithText("管理下载").performClick()
        compose.onNodeWithText("继续").performClick()
        compose.onNodeWithText("继续下载漫画").assertIsDisplayed()
        compose.onNodeWithTag("allow-mobile-data").assertIsOff()
        assertEquals(0, calls)
        if (allowMobileData) compose.onNodeWithTag("allow-mobile-data").performClick()
        compose.onNodeWithTag("confirm-resume-download").performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        assertEquals(!allowMobileData, wifi)
    }

    @Test fun completedDownloadIsPresentedAsLocalReadingWithoutAnotherDownloadAction() {
        val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        val repository = object : ReaderRepository by actual {
            override suspend fun offlineStatus(comicId: String) = OfflineDownloadStatus(record.externalId, record.title,
                OfflineDownloadState.COMPLETED, 1024, 1024)
        }
        compose.setContent { MaterialTheme { DownloadComicAction(record, repository) } }
        compose.waitForIdle()
        compose.onNodeWithText("已下载，可离线阅读").assertIsDisplayed()
        compose.onNodeWithText("下载整本").assertDoesNotExist()
    }
}
