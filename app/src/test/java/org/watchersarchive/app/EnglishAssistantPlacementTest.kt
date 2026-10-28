package org.watchersarchive.app

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EnglishAssistantPlacementTest {
    @Test fun lowerPageWordLeavesTheSummaryAboveIt() {
        val result = placeEnglishAssistantCard(720f, 160f, RectF(40f, 600f, 130f, 626f),
            safeTop = 32f, safeBottom = 24f, gap = 12f, minimumUsableHeight = 96f)
        assertEquals(EnglishCardEdge.TOP, result.edge)
        assertEquals(32f, result.top, .01f)
        assertEquals(160f, result.height, .01f)
        assertFalse(result.overlapsSelection)
    }

    @Test fun upperPageWordLeavesTheSummaryBelowIt() {
        val result = placeEnglishAssistantCard(720f, 160f, RectF(40f, 90f, 130f, 116f),
            safeTop = 32f, safeBottom = 24f, gap = 12f, minimumUsableHeight = 96f)
        assertEquals(EnglishCardEdge.BOTTOM, result.edge)
        assertEquals(536f, result.top, .01f)
        assertFalse(result.overlapsSelection)
    }

    @Test fun expansionShrinksToAvailableSideBeforeCoveringTheSelectedText() {
        val result = placeEnglishAssistantCard(640f, 300f, RectF(20f, 270f, 250f, 330f),
            safeTop = 24f, safeBottom = 24f, gap = 12f, minimumUsableHeight = 96f)
        assertEquals(EnglishCardEdge.BOTTOM, result.edge)
        assertEquals(274f, result.height, .01f)
        assertEquals(342f, result.top, .01f)
        assertFalse(result.overlapsSelection)
    }

    @Test fun explicitManualPositionWinsUntilTheUserReturnsToAutomaticPlacement() {
        val selected = RectF(10f, 540f, 200f, 580f)
        val manual = placeEnglishAssistantCard(640f, 160f, selected, gap = 12f, manualEdge = EnglishCardEdge.BOTTOM)
        assertEquals(EnglishCardEdge.BOTTOM, manual.edge)
        assertTrue(manual.overlapsSelection)
        val automatic = placeEnglishAssistantCard(640f, 160f, selected, gap = 12f)
        assertEquals(EnglishCardEdge.TOP, automatic.edge)
        assertFalse(automatic.overlapsSelection)
    }

    @Test fun offscreenAndMissingSelectionUseAStableDefault() {
        val missing = placeEnglishAssistantCard(640f, 160f, null, safeBottom = 30f)
        val offscreen = placeEnglishAssistantCard(640f, 160f, RectF(5f, -90f, 90f, -20f), safeBottom = 30f)
        assertEquals(EnglishCardEdge.BOTTOM, missing.edge)
        assertEquals(missing, offscreen)
        assertEquals(450f, missing.top, .01f)
    }

    @Test fun extremeSelectionReportsWhenItCannotBeAvoidedRatherThanHidingTheCard() {
        val result = placeEnglishAssistantCard(360f, 90f, RectF(5f, 5f, 300f, 355f),
            gap = 12f, minimumUsableHeight = 80f)
        assertEquals(90f, result.height, .01f)
        assertTrue(result.overlapsSelection)
    }

    @Test fun malformedDimensionsNeverProduceANegativeOrOutOfBoundsCard() {
        val empty = placeEnglishAssistantCard(Float.NaN, 160f, null)
        assertEquals(0f, empty.height, .01f)
        val clipped = placeEnglishAssistantCard(100f, 200f, null, safeTop = 60f, safeBottom = 60f)
        assertEquals(60f, clipped.top, .01f)
        assertEquals(0f, clipped.height, .01f)
    }
}
