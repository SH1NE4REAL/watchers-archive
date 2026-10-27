package org.watchersarchive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGestureTest {
    @Test fun consecutiveForwardAndBackwardSwipesDoNotShareState() {
        assertEquals(ReaderSwipe.NEXT, ReaderTouchSequence(1f).apply { move(-120f, 2f, false) }.finish(1f, 56f))
        assertEquals(ReaderSwipe.PREVIOUS, ReaderTouchSequence(1f).apply { move(120f, 2f, false) }.finish(1f, 56f))
        assertEquals(ReaderSwipe.NEXT, ReaderTouchSequence(1f).apply { move(-120f, 2f, false) }.finish(1f, 56f))
    }

    @Test fun pinchNeverTurnsPageEvenAfterReturningToOneTimes() {
        val sequence = ReaderTouchSequence(1f)
        sequence.move(-240f, 0f, true)
        sequence.move(-240f, 0f, false)
        assertEquals(ReaderSwipe.NONE, sequence.finish(1f, 56f))
    }

    @Test fun panWhileZoomedDoesNotTurnPage() {
        val sequence = ReaderTouchSequence(2f).apply { move(300f, 0f, false) }
        assertEquals(ReaderSwipe.NONE, sequence.finish(1f, 56f))
    }

    @Test fun verticalAndSmallDragsDoNotTurnPage() {
        assertEquals(ReaderSwipe.NONE, ReaderTouchSequence(1f).apply { move(30f, 0f, false) }.finish(1f, 56f))
        assertEquals(ReaderSwipe.NONE, ReaderTouchSequence(1f).apply { move(120f, 150f, false) }.finish(1f, 56f))
    }

    @Test fun thresholdDoesNotChangePageUntilReleased() {
        val sequence = ReaderTouchSequence(1f)
        sequence.move(-35f, 0f, false)
        sequence.move(-35f, 0f, false)
        assertEquals(-70f, sequence.horizontalDistance, 0f)
        assertEquals(ReaderSwipe.NEXT, sequence.finish(1f, 56f))
    }

    @Test fun fittedPortraitPageClampsToRealImageBounds() {
        // 480x720 fits to 600x900 in a 600x1000 viewport; at 2x the extra
        // image dimensions allow at most +/-300 horizontal and +/-400 vertical.
        val bounds = ReaderImageBounds(600f, 1000f, 480f, 720f)
        val clamped = bounds.clamp(ReaderImageTransform(2f, 900f, -900f))
        assertEquals(2f, clamped.scale, 0f)
        assertEquals(300f, clamped.translationX, 0f)
        assertEquals(-400f, clamped.translationY, 0f)
    }

    @Test fun shortLandscapeImageCannotBePannedVerticallyIntoEmptySpace() {
        val bounds = ReaderImageBounds(600f, 1000f, 1200f, 600f)
        assertEquals(0f, bounds.clamp(ReaderImageTransform(2f, 20f, 100f)).translationY, 0f)
    }

    @Test fun zoomResetClearsOffsets() {
        val bounds = ReaderImageBounds(600f, 1000f, 480f, 720f)
        assertEquals(ReaderImageTransform(), bounds.clamp(ReaderImageTransform(1f, 100f, -200f)))
    }

    @Test fun zoomLimitsAndInvalidInputAreBounded() {
        val bounds = ReaderImageBounds(600f, 1000f, 480f, 720f)
        val previous = ReaderImageTransform()
        assertEquals(4f, bounds.transform(previous, 300f, 500f, 100f, 0f, 0f).scale, 0f)
        assertEquals(previous, bounds.transform(previous, 300f, 500f, Float.NaN, 0f, 0f))
    }

    @Test fun zoomAroundCenterKeepsCenterStationary() {
        val bounds = ReaderImageBounds(600f, 1000f, 480f, 720f)
        val result = bounds.transform(ReaderImageTransform(), 300f, 500f, 2f, 0f, 0f)
        assertEquals(0f, result.translationX, 0f)
        assertEquals(0f, result.translationY, 0f)
        assertTrue(result.scale <= 4f)
    }
}
