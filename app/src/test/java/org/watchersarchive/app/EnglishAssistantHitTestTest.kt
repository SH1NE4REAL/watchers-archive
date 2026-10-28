package org.watchersarchive.app

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EnglishAssistantHitTestTest {
    @Test fun fitLetterboxTapIsNotAnImageWordAndImageCenterMapsExactly() {
        val bounds = ReaderImageBounds(320f, 640f, 320f, 320f)
        assertNull(bounds.imagePointAt(ReaderImageTransform(), 160f, 80f))
        assertEquals(Offset(160f, 160f), bounds.imagePointAt(ReaderImageTransform(), 160f, 320f))
        assertEquals(Offset(0f, 0f), bounds.imagePointAt(ReaderImageTransform(), 0f, 160f))
        assertNull(bounds.imagePointAt(ReaderImageTransform(), Float.NaN, 320f))
    }

    @Test fun renderedBoxesAndInverseTapsShareCenterZoomPanAndFitCoordinates() {
        for (viewport in listOf(32f, 320f, 1080f)) {
            val bounds = ReaderImageBounds(viewport, viewport * 2f, 600f, 900f)
            val transform = ReaderImageTransform(2.5f, viewport * .1f, -viewport * .15f)
            val box = RectF(250f, 400f, 350f, 500f)
            val rendered = checkNotNull(bounds.imageRectOnScreen(transform, box))
            val inverse = checkNotNull(bounds.imagePointAt(transform, rendered.centerX(), rendered.centerY()))
            assertEquals(300f, inverse.x, .001f)
            assertEquals(450f, inverse.y, .001f)
            assertEquals(6f, bounds.imageUnitsForScreenPixels(transform, 6f) * (viewport / 600f) * transform.scale, .001f)
        }
    }

    @Test fun closestToleranceDoesNotExpandEveryBoxOverNeighboringWords() {
        val left = EnglishToken("left", "hero", RectF(10f, 10f, 45f, 30f), "hero wins")
        val right = EnglishToken("right", "wins", RectF(50f, 10f, 85f, 30f), "hero wins")
        val page = EnglishPageTokens("p", 100, 100, listOf(left, right), false)
        assertEquals(right, englishTokenAt(page, 51f, 20f, 6f))
        assertEquals(right, englishTokenAt(page, 49f, 20f, 6f))
        assertEquals(left, englishTokenAt(page, 46f, 20f, 6f))
        assertNull(englishTokenAt(page, 98f, 20f, 6f))
        assertNull(englishTokenAt(page, -1f, 20f, 6f))
        assertNull(englishTokenAt(page, 49f, 20f))
    }

    @Test fun invalidGeometryIsIgnoredWithoutAllocationOrOutOfBoundsSelection() {
        val invalid = EnglishToken("bad", "bad", RectF(Float.NaN, 0f, 30f, 30f), "bad")
        val outside = EnglishToken("outside", "outside", RectF(-20f, 0f, 30f, 30f), "outside")
        val page = EnglishPageTokens("p", 100, 100, listOf(invalid, outside), false)
        assertNull(englishTokenAt(page, 10f, 10f, 100f))
        assertNull(ReaderImageBounds(0f, 320f, 100f, 100f).imagePointAt(ReaderImageTransform(), 0f, 0f))
        assertNull(ReaderImageBounds(320f, 640f, 100f, 100f).imagePointAt(ReaderImageTransform(0f), 10f, 10f))
    }
}
