package org.watchersarchive.app

import android.graphics.RectF

internal enum class EnglishCardEdge { TOP, BOTTOM }

internal data class EnglishCardPlacement(
    val edge: EnglishCardEdge,
    val top: Float,
    val height: Float,
    val overlapsSelection: Boolean,
)

/** All inputs are pixels in the unpadded reader viewport. */
internal fun placeEnglishAssistantCard(
    viewportHeight: Float,
    desiredHeight: Float,
    selectedScreenBounds: RectF?,
    safeTop: Float = 0f,
    safeBottom: Float = 0f,
    gap: Float = 0f,
    minimumUsableHeight: Float = 0f,
    manualEdge: EnglishCardEdge? = null,
): EnglishCardPlacement {
    if (!viewportHeight.isFinite() || viewportHeight <= 0f) return EnglishCardPlacement(EnglishCardEdge.BOTTOM, 0f, 0f, false)
    val top = safeTop.takeIf(Float::isFinite)?.coerceIn(0f, viewportHeight) ?: 0f
    val bottom = (viewportHeight - (safeBottom.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f)).coerceIn(top, viewportHeight)
    val available = bottom - top
    val wanted = desiredHeight.takeIf(Float::isFinite)?.coerceIn(0f, available) ?: 0f
    val margin = gap.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val minimum = minimumUsableHeight.takeIf(Float::isFinite)?.coerceIn(0f, wanted) ?: 0f
    val selected = selectedScreenBounds?.takeIf {
        it.top.isFinite() && it.bottom.isFinite() && it.bottom > it.top && it.bottom > top && it.top < bottom
    }
    val above = selected?.let { (it.top - margin - top).coerceIn(0f, available) } ?: available
    val below = selected?.let { (bottom - it.bottom - margin).coerceIn(0f, available) } ?: available
    val edge = manualEdge ?: when {
        selected == null -> EnglishCardEdge.BOTTOM
        above >= wanted && below >= wanted -> if ((selected.top + selected.bottom) / 2 < (top + bottom) / 2) EnglishCardEdge.BOTTOM else EnglishCardEdge.TOP
        above >= wanted -> EnglishCardEdge.TOP
        below >= wanted -> EnglishCardEdge.BOTTOM
        above >= below -> EnglishCardEdge.TOP
        else -> EnglishCardEdge.BOTTOM
    }
    val free = if (edge == EnglishCardEdge.TOP) above else below
    // If either side can hold a useful card, reduce its height before covering
    // the selected text. Explicit manual positioning remains the user's choice.
    val height = if (manualEdge == null && selected != null && free in minimum..wanted) free else wanted
    val y = if (edge == EnglishCardEdge.TOP) top else bottom - height
    val overlaps = selected != null && y < selected.bottom + margin && y + height > selected.top - margin
    return EnglishCardPlacement(edge, y, height, overlaps)
}
