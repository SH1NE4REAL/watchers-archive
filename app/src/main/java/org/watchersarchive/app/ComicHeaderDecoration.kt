package org.watchersarchive.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A compact original comic border; decorative only and never intercepts input. */
@Composable
internal fun ComicHeaderDecoration(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(30.dp).testTag("comic-header-decoration")) {
        Canvas(Modifier.matchParentSize()) {
            val red = HeroRed
            val unit = size.height
            val emblemWidth = minOf(unit * 1.8f, size.width * .35f)
            drawRoundRect(red.copy(alpha = .075f),
                topLeft = Offset(0f, unit * .05f),
                size = Size(size.width, unit * .9f),
                cornerRadius = CornerRadius(unit * .18f))
            val panel = Path().apply {
                moveTo(unit * .15f, unit * .05f)
                lineTo(emblemWidth, unit * .05f)
                lineTo(emblemWidth - unit * .26f, unit * .95f)
                lineTo(0f, unit * .95f)
                close()
            }
            drawPath(panel, red)
            val flashExtent = minOf(unit * .85f, emblemWidth * .7f)
            paintHeroGlyph(HeroMotif.Lightning, Color.White,
                Offset((emblemWidth - flashExtent) / 2, (unit - flashExtent) / 2), flashExtent)

            val motifs = when {
                size.width > unit * 7f -> listOf(HeroMotif.Shield, HeroMotif.Star, HeroMotif.Web)
                size.width > unit * 5f -> listOf(HeroMotif.Shield, HeroMotif.Web)
                else -> listOf(HeroMotif.Shield)
            }
            val extent = unit * .72f
            val gap = unit * .36f
            val rightWidth = motifs.size * extent + (motifs.size - 1) * gap
            val rightStart = maxOf(emblemWidth + unit * .35f, size.width - rightWidth - unit * .25f)
            val lineStart = emblemWidth + unit * .25f
            if (rightStart > lineStart + unit * .5f) {
                drawLine(red.copy(alpha = .55f), Offset(lineStart, unit * .65f),
                    Offset(rightStart - unit * .25f, unit * .65f), 1.7.dp.toPx())
                repeat(3) { index ->
                    val x = lineStart + index * unit * .3f
                    if (x + unit * .12f < rightStart - unit * .3f) {
                        drawLine(red.copy(alpha = .45f), Offset(x, unit * .25f),
                            Offset(x + unit * .12f, unit * .48f), 1.35.dp.toPx())
                    }
                }
            }
            motifs.forEachIndexed { index, motif ->
                val x = rightStart + index * (extent + gap)
                if (x + extent <= size.width) paintHeroGlyph(motif, red.copy(alpha = .65f),
                    Offset(x, (unit - extent) / 2), extent)
            }
        }
    }
}
