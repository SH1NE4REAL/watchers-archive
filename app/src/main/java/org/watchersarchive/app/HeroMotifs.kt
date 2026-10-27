package org.watchersarchive.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val HeroRed = Color(0xFFC42430)

/** Original, generic heroic motifs rather than a character or publisher's logo. */
internal enum class HeroMotif { Shield, Web, Lightning, Star }

@Composable
internal fun HeroGlyph(
    kind: HeroMotif,
    modifier: Modifier = Modifier,
    color: Color = HeroRed,
) {
    Canvas(modifier.size(24.dp)) {
        val extent = minOf(size.width, size.height)
        paintHeroGlyph(kind, color, Offset((size.width - extent) / 2, (size.height - extent) / 2), extent)
    }
}

/** Paint in a bounded 24-unit square, preserving proportions at any density. */
internal fun DrawScope.paintHeroGlyph(kind: HeroMotif, color: Color, origin: Offset, extent: Float) {
    if (extent <= 0f) return
    withTransform({
        translate(origin.x, origin.y)
        scale(extent / 24f, extent / 24f, Offset.Zero)
    }) {
        when (kind) {
            HeroMotif.Shield -> {
                val shield = Path().apply {
                    moveTo(12f, 2.5f)
                    lineTo(20f, 6f)
                    lineTo(19f, 13f)
                    quadraticTo(18f, 18f, 12f, 21.5f)
                    quadraticTo(6f, 18f, 5f, 13f)
                    lineTo(4f, 6f)
                    close()
                }
                drawPath(shield, color, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(Path().apply {
                    moveTo(12f, 7.2f); lineTo(14f, 11.2f); lineTo(12f, 15.5f)
                    lineTo(10f, 11.2f); close()
                }, color)
            }
            HeroMotif.Web -> {
                val center = Offset(12f, 12f)
                repeat(8) { spoke ->
                    val angle = spoke * PI / 4
                    drawLine(color, center, Offset(12f + 10f * cos(angle).toFloat(), 12f + 10f * sin(angle).toFloat()), 1.15f, StrokeCap.Round)
                }
                for (radius in listOf(3.5f, 6.8f, 10f)) {
                    val thread = Path()
                    repeat(8) { segment ->
                        val start = segment * PI / 4
                        val end = (segment + 1) * PI / 4
                        val middle = (start + end) / 2
                        if (segment == 0) thread.moveTo(12f + radius * cos(start).toFloat(), 12f + radius * sin(start).toFloat())
                        thread.quadraticTo(
                            12f + radius * .72f * cos(middle).toFloat(), 12f + radius * .72f * sin(middle).toFloat(),
                            12f + radius * cos(end).toFloat(), 12f + radius * sin(end).toFloat(),
                        )
                    }
                    thread.close()
                    drawPath(thread, color, style = Stroke(1.05f, cap = StrokeCap.Round))
                }
            }
            HeroMotif.Lightning -> drawPath(Path().apply {
                moveTo(14.5f, 2f); lineTo(5.5f, 13.5f); lineTo(11f, 12.2f)
                lineTo(9f, 22f); lineTo(19f, 9f); lineTo(13.4f, 10.4f); close()
            }, color)
            HeroMotif.Star -> {
                val star = Path()
                repeat(10) { point ->
                    val angle = -PI / 2 + point * PI / 5
                    val radius = if (point % 2 == 0) 9f else 4.3f
                    val x = 12f + radius * cos(angle).toFloat()
                    val y = 12f + radius * sin(angle).toFloat()
                    if (point == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                drawPath(star, color)
            }
        }
    }
}
