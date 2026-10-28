package org.watchersarchive.app

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Compose MotionEvents, with OCR words supplied as coordinates rather than child clickables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class EnglishAssistantGestureRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tapsSelectWordsButSwipesStartingOnTheWordStillTurnBothDirections() {
        var lookups = 0; var toggles = 0; var next = 0; var previous = 0
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        val token = EnglishToken("word", "hero", RectF(110f, 290f, 210f, 350f), "hero wins")
        val page = EnglishPageTokens("p", 320, 640, listOf(token), false)
        compose.setContent {
            ReaderGestureSurface(bitmap, "p", true, { previous++ }, { next++ }, { toggles++ },
                onImageTap = { x, y -> (englishTokenAt(page, x, y) != null).also { if (it) lookups++ } })
        }
        val surface = compose.onNodeWithTag("reader-gesture-surface")
        surface.performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, lookups); assertEquals(0, toggles) }
        surface.performTouchInput { swipeLeft() }
        surface.performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1, next); assertEquals(1, previous); assertEquals(1, lookups) }
    }

    @Test fun pinchAndDoubleTapNeverTriggerWordLookupAndZoomedWordTapUsesImageCoordinates() {
        var lookups = 0; var next = 0; var previous = 0
        var seenTransform = ReaderImageTransform()
        var seenBounds = ReaderImageBounds(0f, 0f, 0f, 0f)
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        val token = EnglishToken("word", "hero", RectF(150f, 310f, 170f, 330f), "hero wins")
        val page = EnglishPageTokens("p", 320, 640, listOf(token), false)
        compose.setContent {
            ReaderGestureSurface(bitmap, "p", true, { previous++ }, { next++ }, {},
                overlay = { transform, bounds -> seenTransform = transform; seenBounds = bounds },
                onImageTap = { x, y -> (englishTokenAt(page, x, y) != null).also { if (it) lookups++ } })
        }
        val surface = compose.onNodeWithTag("reader-gesture-surface")
        surface.performTouchInput {
            down(0, center + Offset(-20f, 0f)); down(1, center + Offset(20f, 0f))
            moveTo(0, center + Offset(-70f, 0f)); moveTo(1, center + Offset(70f, 0f))
            up(0); up(1)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(seenTransform.scale > 1f); assertEquals(0, next); assertEquals(0, previous); assertEquals(0, lookups) }
        // A double-tap resets the zoom without firing a lookup from its first tap.
        surface.performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        compose.runOnIdle { assertEquals(1f, seenTransform.scale, .01f); assertEquals(0, lookups) }
        surface.performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        val word = checkNotNull(seenBounds.imageRectOnScreen(seenTransform, token.bounds))
        surface.performTouchInput { click(Offset(word.centerX(), word.centerY())) }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, lookups); assertEquals(0, next); assertEquals(0, previous) }
    }

    @Test fun longPressOnWordCanSelectPhraseAndFitLetterboxStillTogglesControls() {
        var phrases = 0; var lookups = 0; var toggles = 0
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        compose.setContent {
            ReaderGestureSurface(bitmap, "square", true, {}, {}, { toggles++ },
                onImageTap = { _, _ -> lookups++; true },
                onImageLongPress = { _, _ -> phrases++; true })
        }
        val surface = compose.onNodeWithTag("reader-gesture-surface")
        surface.performTouchInput { longClick(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1, phrases); assertEquals(0, lookups); assertEquals(0, toggles) }
        surface.performTouchInput { click(Offset(center.x, 20f)) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1, toggles); assertEquals(0, lookups) }
    }

    @Test fun disablingOrReplacingThePageCancelsDelayedWordActions() {
        var pageKey by mutableStateOf("first")
        var enabled by mutableStateOf(true)
        var lookups = 0; var toggles = 0
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        compose.setContent {
            ReaderGestureSurface(bitmap, pageKey, enabled, {}, {}, { toggles++ },
                onImageTap = { _, _ -> lookups++; true })
        }
        val surface = compose.onNodeWithTag("reader-gesture-surface")
        compose.mainClock.autoAdvance = false
        surface.performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals("No word lookup before the double-tap delay", 0, lookups)
            pageKey = "replacement"
        }
        // With autoAdvance disabled, explicitly apply the new page's frame
        // before measuring whether its predecessor's delayed action survived.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(0, lookups); assertEquals(0, toggles) }
        surface.performTouchInput { click(center) }
        compose.runOnIdle { enabled = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(0, lookups); assertEquals(0, toggles) }
        surface.performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(0, lookups); assertEquals(0, toggles) }
    }
}
