package org.watchersarchive.app

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderChromeRuntimeTest {
    @get:Rule val compose = createComposeRule()

    private val comic = DemoComic("clean-reader", "测试漫画", "", "fixture.cbz")

    private class ReaderFixture(failure: Exception? = null) {
        var cacheInspections = 0
        var tracesRead = 0
        private val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        val repository = object : ReaderRepository by actual {
            override suspend fun lastPage(comicId: String) = 0
            override suspend fun bookmarkPages(comicId: String) = emptySet<Int>()
            override suspend fun open(comic: DemoComic): ReaderSession {
                if (failure != null) throw failure
                return object : ReaderSession {
                    override val totalPages = 2
                    override suspend fun loadPage(index: Int) = ReaderPage(index, totalPages,
                        Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888), true, 123456)
                    override suspend fun close() { }
                }
            }
            override suspend fun cacheSnapshot(): CacheSnapshot {
                cacheInspections++
                return CacheSnapshot(123456, 4321, 500_000_000, 1)
            }
            override suspend fun clearCache(): String = error("The reader must not clear cache")
            override fun traceLines(): List<String> {
                tracesRead++
                return listOf("RANGE bytes=0-123456 CACHE HIT")
            }
        }
    }

    @Test fun readingControlsKeepPageActionsWithoutDiagnosticsOrStorageActions() {
        val fixture = ReaderFixture()
        compose.setContent { MaterialTheme { ReaderScreen(comic, fixture.repository) {} } }
        compose.waitForIdle()
        compose.onNodeWithTag("reader-gesture-surface").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        compose.onNodeWithText("下一页").assertIsDisplayed()
        compose.onNodeWithText("书签").assertIsDisplayed()
        compose.onNodeWithText("英文助手").assertIsDisplayed()
        compose.onNodeWithText("第 1 / 2 页", substring = true).assertIsDisplayed()
        listOf("缓存", "传输", "字节", "清理", "日志", "调试", "已解码").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle {
            assertEquals(0, fixture.cacheInspections)
            assertEquals(0, fixture.tracesRead)
        }
    }

    @Test fun settingsKeepTechnicalFixtureDetailsCollapsed() {
        val fixture = ReaderFixture()
        compose.setContent { MaterialTheme { CacheScreen(fixture.repository) {} } }
        compose.waitForIdle()
        compose.onNodeWithText("存储管理").assertExists()
        compose.onNodeWithText("临时阅读缓存").assertDoesNotExist()
        compose.onNodeWithText("500 MB", substring = true).assertDoesNotExist()
        compose.onNodeWithText("清理临时内容").assertDoesNotExist()
        compose.onNodeWithText("开发验证", substring = true).assertDoesNotExist()
        compose.onNodeWithText("服务器基础地址").assertDoesNotExist()
        compose.onNodeWithText("已提交", substring = true).assertDoesNotExist()
        compose.onNodeWithText("预留", substring = true).assertDoesNotExist()
        compose.onNodeWithText("10.0.2.2", substring = true).assertDoesNotExist()
        compose.onNodeWithText("标准 CBZ", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.cacheInspections) }
    }

    @Test fun sourceFailuresDisplayReadableErrorsWithoutProtocolDetails() {
        val fixture = ReaderFixture(ArchiveException(ArchiveError.RANGE_NOT_SUPPORTED,
            "RANGE_NOT_SUPPORTED: HTTP 200, Content-Range missing, 123456 bytes"))
        compose.setContent { MaterialTheme { ReaderScreen(comic, fixture.repository) {} } }
        compose.waitForIdle()
        compose.onNodeWithText("此漫画来源暂时无法稳定读取，请稍后重试。").assertIsDisplayed()
        compose.onNodeWithText("HTTP", substring = true).assertDoesNotExist()
        compose.onNodeWithText("RANGE_NOT_SUPPORTED", substring = true).assertDoesNotExist()
    }
}
