package org.watchersarchive.app

import kotlin.math.abs
import kotlin.math.min

/** Pixel-based math shared by the Compose surface and host-side regression tests. */
internal data class ReaderImageTransform(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
)

internal data class ReaderImageBounds(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val imageWidth: Float,
    val imageHeight: Float,
) {
    private val fit: Float
        get() = if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) 0f
        else min(viewportWidth / imageWidth, viewportHeight / imageHeight)

    fun clamp(transform: ReaderImageTransform): ReaderImageTransform {
        val scale = transform.scale.coerceIn(1f, 4f)
        if (scale <= 1.01f) return ReaderImageTransform()
        val limitX = ((imageWidth * fit * scale - viewportWidth) / 2f).coerceAtLeast(0f)
        val limitY = ((imageHeight * fit * scale - viewportHeight) / 2f).coerceAtLeast(0f)
        return ReaderImageTransform(scale, transform.translationX.coerceIn(-limitX, limitX), transform.translationY.coerceIn(-limitY, limitY))
    }

    /** Keep the point under the fingers stable while zooming around that point. */
    fun transform(
        previous: ReaderImageTransform,
        centroidX: Float,
        centroidY: Float,
        zoomFactor: Float,
        panX: Float,
        panY: Float,
    ): ReaderImageTransform {
        if (!zoomFactor.isFinite() || zoomFactor <= 0f || !panX.isFinite() || !panY.isFinite()) return previous
        val scale = (previous.scale * zoomFactor).coerceIn(1f, 4f)
        val ratio = scale / previous.scale
        val x = centroidX - viewportWidth / 2f
        val y = centroidY - viewportHeight / 2f
        return clamp(ReaderImageTransform(scale,
            x - ratio * (x - previous.translationX) + panX,
            y - ratio * (y - previous.translationY) + panY))
    }
}

internal enum class ReaderSwipe { NONE, PREVIOUS, NEXT }

/** A fresh instance is created for each down-to-up sequence, including after failed swipes. */
internal class ReaderTouchSequence(private val startedAtScale: Float) {
    var horizontalDistance = 0f
        private set
    var verticalDistance = 0f
        private set
    var usedMultiplePointers = false
        private set

    fun move(dx: Float, dy: Float, multiplePointers: Boolean) {
        if (multiplePointers) usedMultiplePointers = true
        horizontalDistance += dx
        verticalDistance += dy
    }

    fun finish(currentScale: Float, threshold: Float): ReaderSwipe = when {
        usedMultiplePointers || startedAtScale > 1.01f || currentScale > 1.01f -> ReaderSwipe.NONE
        abs(horizontalDistance) < threshold || abs(horizontalDistance) <= abs(verticalDistance) * 1.3f -> ReaderSwipe.NONE
        horizontalDistance < 0 -> ReaderSwipe.NEXT
        else -> ReaderSwipe.PREVIOUS
    }
}
