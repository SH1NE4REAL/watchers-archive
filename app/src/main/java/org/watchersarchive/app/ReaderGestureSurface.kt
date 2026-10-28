package org.watchersarchive.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the image's touch sequence only. Place controls as siblings above this surface;
 * their buttons therefore never participate in page gestures. No page prefetch or I/O.
 */
@Composable
internal fun ReaderGestureSurface(
    bitmap: Bitmap,
    pageKey: Any,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleControls: () -> Unit,
    overlay: @Composable (ReaderImageTransform, ReaderImageBounds) -> Unit = { _, _ -> },
    onImageTap: ((Float, Float) -> Boolean)? = null,
    onImageLongPress: ((Float, Float) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember(pageKey) { mutableStateOf(ReaderImageTransform()) }
    val previousCallback by rememberUpdatedState(onPrevious)
    val nextCallback by rememberUpdatedState(onNext)
    val toggleCallback by rememberUpdatedState(onToggleControls)
    val imageTapCallback by rememberUpdatedState(onImageTap)
    val imageLongPressCallback by rememberUpdatedState(onImageLongPress)
    val currentlyEnabled by rememberUpdatedState(enabled)
    val gesturePermit = remember(pageKey, enabled) { AtomicBoolean(enabled) }
    DisposableEffect(gesturePermit) { onDispose { gesturePermit.set(false) } }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val doubleTapDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val currentBounds by rememberUpdatedState(ReaderImageBounds(
        viewport.width.toFloat(), viewport.height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat()))

    Box(modifier.fillMaxSize().background(Color.Black).clipToBounds()
        .onSizeChanged { viewport = it }
        .testTag("reader-gesture-surface")
        .pointerInput(pageKey, threshold, enabled) {
            coroutineScope {
                val gestureScope = this
                var pendingTap: Job? = null
                var lastTapTime = Long.MIN_VALUE
                var lastTapPosition = Offset.Unspecified
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val possibleSecondTap = lastTapTime != Long.MIN_VALUE &&
                        down.uptimeMillis - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis &&
                        lastTapPosition != Offset.Unspecified && (down.position - lastTapPosition).getDistance() <= doubleTapDistance
                    // Resolve the first tap as a double gesture as soon as the
                    // second finger-down arrives; its held-up duration must not
                    // fire the old delayed lookup while zoom is being decided.
                    if (possibleSecondTap) pendingTap?.cancel()
                    val sequence = ReaderTouchSequence(transform.scale)
                    var dragged = false
                    var lastTime = down.uptimeMillis
                    var lastPosition = down.position
                    var cancelled = false
                    do {
                        val event = awaitPointerEvent()
                        lastTime = event.changes.maxOf { it.uptimeMillis }
                        val multiple = event.changes.count { it.pressed || it.previousPressed } > 1
                        val primary = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        lastPosition = primary.position
                        if (event.changes.any { it.isConsumed }) {
                            cancelled = true
                            break
                        }
                        val pan = if (multiple) event.calculatePan() else primary.positionChange()
                        sequence.move(pan.x, pan.y, multiple)
                        val distance = hypot(sequence.horizontalDistance, sequence.verticalDistance)
                        if (multiple || distance > viewConfiguration.touchSlop) {
                            dragged = true
                            pendingTap?.cancel()
                            lastTapTime = Long.MIN_VALUE
                        }
                        if (currentlyEnabled && dragged) {
                            if (sequence.usedMultiplePointers || transform.scale > 1.01f) {
                                val centroid = event.calculateCentroid(useCurrent = false).let {
                                    if (it == Offset.Unspecified) primary.position else it
                                }
                                transform = currentBounds.transform(transform, centroid.x, centroid.y,
                                    if (multiple) event.calculateZoom() else 1f, pan.x, pan.y)
                            }
                            event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!cancelled && currentlyEnabled) {
                        when (sequence.finish(transform.scale, threshold)) {
                            ReaderSwipe.PREVIOUS -> previousCallback()
                            ReaderSwipe.NEXT -> nextCallback()
                            ReaderSwipe.NONE -> Unit
                        }
                    }
                    if (!cancelled && currentlyEnabled && !dragged && !sequence.usedMultiplePointers &&
                        lastTime - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                        pendingTap?.cancel()
                        lastTapTime = Long.MIN_VALUE
                        currentBounds.imagePointAt(transform, lastPosition.x, lastPosition.y)?.let { point ->
                            imageLongPressCallback?.invoke(point.x, point.y)
                        }
                    }
                    if (!cancelled && currentlyEnabled && !dragged && !sequence.usedMultiplePointers &&
                        lastTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        if (possibleSecondTap) {
                            pendingTap?.cancel()
                            lastTapTime = Long.MIN_VALUE
                            if (currentlyEnabled) {
                                transform = if (transform.scale > 1.01f) ReaderImageTransform() else
                                    currentBounds.transform(transform, lastPosition.x, lastPosition.y, 2f, 0f, 0f)
                            }
                        } else {
                            lastTapTime = lastTime
                            lastTapPosition = lastPosition
                            pendingTap = gestureScope.launch {
                                delay(viewConfiguration.doubleTapTimeoutMillis)
                                // A rendered replacement/disabled page revokes
                                // its predecessor immediately, independently of
                                // when pointerInput's child cancellation unwinds.
                                if (currentlyEnabled && gesturePermit.get()) {
                                    val point = currentBounds.imagePointAt(transform, lastPosition.x, lastPosition.y)
                                    val handled = point?.let { imageTapCallback?.invoke(it.x, it.y) } ?: false
                                    if (!handled) toggleCallback()
                                }
                            }
                        }
                    }
                }
            }
        }) {
        Image(bitmap.asImageBitmap(), "漫画页面", Modifier.fillMaxSize().graphicsLayer {
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.translationX
            translationY = transform.translationY
        }, contentScale = ContentScale.Fit)
        overlay(transform, currentBounds)
    }
}
