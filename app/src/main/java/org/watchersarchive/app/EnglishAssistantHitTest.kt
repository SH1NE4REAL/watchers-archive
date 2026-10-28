package org.watchersarchive.app

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Image-space geometry shared by rendering, taps and host tests. No image allocation. */
internal fun ReaderImageBounds.imagePointAt(
    transform: ReaderImageTransform,
    screenX: Float,
    screenY: Float,
): Offset? {
    val fit = validFit(transform) ?: return null
    if (!screenX.isFinite() || !screenY.isFinite() || screenX !in 0f..viewportWidth || screenY !in 0f..viewportHeight) return null
    val fittedX = viewportWidth / 2f + (screenX - viewportWidth / 2f - transform.translationX) / transform.scale
    val fittedY = viewportHeight / 2f + (screenY - viewportHeight / 2f - transform.translationY) / transform.scale
    val imageX = (fittedX - (viewportWidth - imageWidth * fit) / 2f) / fit
    val imageY = (fittedY - (viewportHeight - imageHeight * fit) / 2f) / fit
    // A tap in ContentScale.Fit's black letterbox is a normal reader tap.
    if (imageX !in 0f..imageWidth || imageY !in 0f..imageHeight) return null
    return Offset(imageX, imageY)
}

internal fun ReaderImageBounds.imageRectOnScreen(
    transform: ReaderImageTransform,
    imageRect: RectF,
): RectF? {
    val fit = validFit(transform) ?: return null
    if (!imageRect.valid() || imageRect.width() <= 0f || imageRect.height() <= 0f) return null
    val left = (viewportWidth - imageWidth * fit) / 2f
    val top = (viewportHeight - imageHeight * fit) / 2f
    fun x(value: Float) = viewportWidth / 2f + (left + value * fit - viewportWidth / 2f) * transform.scale + transform.translationX
    fun y(value: Float) = viewportHeight / 2f + (top + value * fit - viewportHeight / 2f) * transform.scale + transform.translationY
    return RectF(x(imageRect.left), y(imageRect.top), x(imageRect.right), y(imageRect.bottom))
}

internal fun ReaderImageBounds.imageUnitsForScreenPixels(transform: ReaderImageTransform, pixels: Float): Float {
    val fit = validFit(transform) ?: return 0f
    return if (pixels.isFinite()) pixels.coerceAtLeast(0f) / (fit * transform.scale) else 0f
}

/** Exact small boxes take precedence; tolerance only chooses the nearest outside box. */
internal fun englishTokenAt(
    page: EnglishPageTokens,
    imageX: Float,
    imageY: Float,
    slopImagePixels: Float = 0f,
): EnglishToken? {
    if (!imageX.isFinite() || !imageY.isFinite() || page.imageWidth <= 0 || page.imageHeight <= 0 ||
        imageX !in 0f..page.imageWidth.toFloat() || imageY !in 0f..page.imageHeight.toFloat()) return null
    val tokens = page.tokens.asSequence().take(512).filter { token ->
        token.bounds.valid() && token.bounds.width() > 0f && token.bounds.height() > 0f &&
            token.bounds.left >= 0f && token.bounds.top >= 0f &&
            token.bounds.right <= page.imageWidth && token.bounds.bottom <= page.imageHeight
    }.toList()
    tokens.filter { imageX >= it.bounds.left && imageX <= it.bounds.right && imageY >= it.bounds.top && imageY <= it.bounds.bottom }
        .minByOrNull { it.bounds.width() * it.bounds.height() }?.let { return it }
    val tolerance = if (slopImagePixels.isFinite()) slopImagePixels.coerceIn(0f, max(page.imageWidth, page.imageHeight) * .05f) else 0f
    if (tolerance <= 0f) return null
    return tokens.asSequence().map { token ->
        val dx = max(max(token.bounds.left - imageX, imageX - token.bounds.right), 0f)
        val dy = max(max(token.bounds.top - imageY, imageY - token.bounds.bottom), 0f)
        token to hypot(dx, dy)
    }.filter { it.second <= tolerance }.minWithOrNull(compareBy<Pair<EnglishToken, Float>> { it.second }
        .thenBy { (token, _) -> hypot(token.bounds.centerX() - imageX, token.bounds.centerY() - imageY) })?.first
}

private fun ReaderImageBounds.validFit(transform: ReaderImageTransform): Float? {
    if (listOf(viewportWidth, viewportHeight, imageWidth, imageHeight, transform.scale).any { !it.isFinite() || it <= 0f } ||
        !transform.translationX.isFinite() || !transform.translationY.isFinite()) return null
    return min(viewportWidth / imageWidth, viewportHeight / imageHeight)
}

private fun RectF.valid() = left.isFinite() && right.isFinite() && top.isFinite() && bottom.isFinite()
