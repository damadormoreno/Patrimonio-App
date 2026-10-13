package com.denebapps.patrimonio.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.icons.AppIcons

private const val VIEWPORT = 24f

/**
 * Clockwise arrow in a circle, "Actualizar". Drawn like the icons of [AppIcons] (24x24 viewport, round-capped strokes at
 * the shared width, tinted at draw time) because the set has no refresh glyph.
 */
val RefreshIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = VIEWPORT.dp,
        defaultHeight = VIEWPORT.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply {
        listOf("M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5").forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = AppIcons.DEFAULT_STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}
