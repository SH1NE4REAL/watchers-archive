package org.watchersarchive.app

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderTouchRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leftThenRightTouchSequencesFlipInBothDirections() {
        var previous = 0
        var next = 0
        val bitmap = Bitmap.createBitmap(480, 720, Bitmap.Config.ARGB_8888)
        compose.setContent {
            ReaderGestureSurface(bitmap, "same-page", true,
                onPrevious = { previous++ }, onNext = { next++ }, onToggleControls = {})
        }
        val image = compose.onNodeWithTag("reader-gesture-surface")
        image.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, next); assertEquals(0, previous) }
        image.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, next); assertEquals(1, previous) }
        image.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, next); assertEquals(1, previous) }
    }
}
