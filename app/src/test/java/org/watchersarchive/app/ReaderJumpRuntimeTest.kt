package org.watchersarchive.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException

/** UI routing tests use synthetic images and observable page leases, never HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h720dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderJumpRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private var readerView: View? = null

    private class Fixture(private val failFirstAt: Int? = null, private val holdAt: Int? = null) {
        val requested = CopyOnWriteArrayList<Int>()
        val events = CopyOnWriteArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        private val failed = AtomicBoolean(false)
        private val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        val comic = DemoComic("jump-fixture", "Jump fixture", "", "fixture.cbz")
        private val actual = AndroidRepository(ApplicationProvider.getApplicationContext())
        val repository = object : ReaderRepository by actual {
            override suspend fun lastPage(comicId: String) = 0
            override suspend fun bookmarkPages(comicId: String) = emptySet<Int>()
            override suspend fun open(comic: DemoComic) = object : ReaderSession {
                override val totalPages = 3
                override suspend fun loadPage(index: Int): ReaderPage {
                    require(index in 0 until totalPages)
                    requested += index
                    events += "start:$index"
                    try {
                        if (index == holdAt) gate.await()
                        if (index == failFirstAt && failed.compareAndSet(false, true)) {
                            events += "failed:$index"
                            throw ArchiveException(ArchiveError.NETWORK, "Synthetic interrupted page")
                        }
                        events += "return:$index"
                        val released = AtomicBoolean(false)
                        return ReaderPage(index, totalPages, bitmap, true, 0, stablePageId = "jump-$index") {
                            if (released.compareAndSet(false, true)) events += "release:$index"
                        }
                    } catch (cancelled: CancellationException) {
                        events += "cancelled:$index"
                        throw cancelled
                    }
                }
                override suspend fun close() { events += "close" }
            }
        }
    }

    private fun show(fixture: Fixture) {
        compose.setContent { readerView = LocalView.current; MaterialTheme { ReaderScreen(fixture.comic, fixture.repository) {} } }
        compose.waitForIdle()
        compose.onNodeWithTag("reader-gesture-surface").performTouchInput { click(Offset(2f, 2f)) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertPage(1)
    }

    /** Native rendering of generated fixtures only, not a physical-phone image. */
    private fun savePreview(name: String, dialog: Boolean = false) {
        compose.runOnIdle {
            runCatching {
                val view = if (dialog) ShadowDialog.getLatestDialog()?.window?.decorView else readerView
                check(view != null && view.width > 0 && view.height > 0) { "Preview view was not laid out" }
                val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                    .first { File(it, "settings.gradle.kts").isFile }
                val folder = root.resolve("artifacts/ui-v0.10.1").apply { mkdirs() }
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(image))
                    folder.resolve(name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            }.onFailure { System.err.println("Synthetic reader preview unavailable: ${it.message}") }
        }
    }

    private fun assertPage(number: Int) {
        compose.onNodeWithTag("reader-page-indicator").assertTextContains("第 $number / 3 页", substring = true)
    }

    private fun openJump() {
        compose.onNodeWithTag("reader-page-indicator").assertIsDisplayed().performClick()
        compose.onNodeWithText("跳转到页码").assertIsDisplayed()
    }

    private fun input(value: String) = compose.onNodeWithTag("reader-jump-page-input").performTextReplacement(value)

    @Test fun malformedOrOutOfRangeInputIsRejectedWithoutLoadingAnotherPage() {
        val fixture = Fixture()
        show(fixture)
        openJump()
        for (value in listOf("", "0", "-1", "1.5", "one", "2147483648", "4")) {
            input(value)
            compose.onNodeWithText("跳转").performClick()
            compose.onNodeWithText("页码无效，请输入 1 到 3 之间的数字。").assertIsDisplayed()
            compose.onNodeWithText("跳转到页码").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("Rejected input: $value", listOf(0), fixture.requested.toList())
                assertFalse(fixture.events.any { it.startsWith("release:") })
            }
        }
    }

    @Test fun firstAndLastPageJumpsSupportImeDoneAndThenPreviousAndNext() {
        val fixture = Fixture()
        show(fixture)
        savePreview("reader-controls.png")
        openJump()
        input("3")
        savePreview("jump-dialog.png", dialog = true)
        compose.onNodeWithTag("reader-jump-page-input").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("跳转到页码").assertDoesNotExist()
        assertPage(3)
        compose.onNodeWithText("下一页").assertIsNotEnabled()

        compose.onNodeWithText("上一页").performClick()
        compose.waitForIdle()
        assertPage(2)
        compose.onNodeWithText("下一页").performClick()
        compose.waitForIdle()
        assertPage(3)

        openJump()
        input("1")
        compose.onNodeWithText("跳转").performClick()
        compose.waitForIdle()
        assertPage(1)
        compose.onNodeWithText("上一页").assertIsNotEnabled()
        compose.onNodeWithText("下一页").performClick()
        compose.waitForIdle()
        assertPage(2)
        compose.runOnIdle { assertEquals(listOf(0, 2, 1, 2, 0, 1), fixture.requested.toList()) }
    }

    @Test fun cancellingTheDialogOrChoosingTheCurrentPageDoesNotReloadOrReleaseIt() {
        val fixture = Fixture()
        show(fixture)
        openJump()
        input("3")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("跳转到页码").assertDoesNotExist()
        assertPage(1)

        openJump()
        input("1")
        compose.onNodeWithText("跳转").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("跳转到页码").assertDoesNotExist()
        assertPage(1)
        compose.runOnIdle {
            assertEquals(listOf(0), fixture.requested.toList())
            assertFalse(fixture.events.any { it.startsWith("release:") })
        }
    }

    @Test fun failedJumpKeepsTheDisplayedPagePinnedAndRetryLoadsTheRequestedTarget() {
        val fixture = Fixture(failFirstAt = 2)
        show(fixture)
        openJump()
        input("3")
        compose.onNodeWithText("跳转").performClick()
        compose.waitForIdle()
        assertPage(1)
        compose.onNodeWithText("暂时无法加载，请检查网络后重试。").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(0, 2), fixture.requested.toList())
            assertFalse("Failed jump must keep old page leased", fixture.events.contains("release:0"))
        }

        compose.onNodeWithText("重试").performClick()
        compose.waitForIdle()
        assertPage(3)
        compose.runOnIdle {
            assertEquals(listOf(0, 2, 2), fixture.requested.toList())
            assertTrue(fixture.events.indexOf("return:2") < fixture.events.indexOf("release:0"))
            assertEquals(1, fixture.events.count { it == "release:0" })
            assertFalse("New displayed page remains leased", fixture.events.contains("release:2"))
        }
    }

    @Test fun busyJumpDisablesNavigationAndCancellationCannotPublishThePendingPage() {
        val fixture = Fixture(holdAt = 2)
        show(fixture)
        openJump()
        input("3")
        compose.onNodeWithText("跳转").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("reader-page-indicator").assertIsNotEnabled()
        compose.onNodeWithText("上一页").assertIsNotEnabled()
        compose.onNodeWithText("下一页").assertIsNotEnabled()
        compose.onNodeWithTag("reader-gesture-surface").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(listOf(0, 2), fixture.requested.toList()) }

        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertPage(1)
        compose.onNodeWithTag("reader-page-indicator").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(fixture.events.contains("cancelled:2"))
            assertFalse(fixture.events.contains("release:0"))
            fixture.gate.complete(Unit)
        }
        compose.waitForIdle()
        assertPage(1)
        compose.runOnIdle {
            assertEquals(listOf(0, 2), fixture.requested.toList())
            assertFalse(fixture.events.contains("return:2"))
            assertFalse(fixture.events.contains("release:0"))
        }
    }
}
