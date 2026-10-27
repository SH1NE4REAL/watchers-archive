package org.watchersarchive.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Original comic-panel decoration; no Marvel logo or official-product mark. */
@Composable
internal fun ComicHeaderDecoration(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(24.dp).testTag("comic-header-decoration")) {
        Canvas(Modifier.matchParentSize()) {
            val red = Color(0xFFC42430)
            val unit = size.height
            val emblemWidth = unit * 1.9f
            val panel = Path().apply {
                moveTo(unit * .18f, unit * .10f)
                lineTo(emblemWidth, unit * .10f)
                lineTo(emblemWidth - unit * .30f, unit * .87f)
                lineTo(0f, unit * .87f)
                close()
            }
            drawPath(panel, red)
            val flash = Path().apply {
                moveTo(unit * 1.05f, unit * .17f)
                lineTo(unit * .54f, unit * .56f)
                lineTo(unit * .90f, unit * .52f)
                lineTo(unit * .73f, unit * .83f)
                lineTo(unit * 1.31f, unit * .41f)
                lineTo(unit * .96f, unit * .45f)
                close()
            }
            drawPath(flash, Color.White)
            val start = emblemWidth + unit * .32f
            drawLine(red, androidx.compose.ui.geometry.Offset(start, unit * .72f),
                androidx.compose.ui.geometry.Offset(size.width, unit * .72f), 2.dp.toPx())
            repeat(3) { index ->
                val x = start + index * unit * .32f
                drawLine(red.copy(alpha = .45f), androidx.compose.ui.geometry.Offset(x, unit * .22f),
                    androidx.compose.ui.geometry.Offset(x + unit * .16f, unit * .53f), 1.5.dp.toPx())
            }
        }
    }
}
