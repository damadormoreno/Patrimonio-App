package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private val SPARK_HEIGHT = 64.dp
private val SPARK_PADDING = 8.dp
private val SPARK_LINE_WIDTH = 2.dp
private val SPARK_DOT_RADIUS = 3.5.dp

/**
 * Net-worth trend sparkline (design.md: `Canvas` W340×H64, P8 — draws pre-computed geometry only,
 * design.md Decision 4). Mirrors `design-reference/patrimonio.jsx`'s `NetWorthSpark`: a gradient
 * area (income tone, 0.22 -> 0 alpha), a line, and an end dot. Renders nothing when [values] has
 * fewer than two points — spec: Sparkline Reflects Snapshot History.
 */
@Composable
fun NetWorthSparkline(values: List<Long>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val colors = LocalAppColors.current

    Canvas(modifier = modifier.fillMaxWidth().height(SPARK_HEIGHT)) {
        val padding = SPARK_PADDING.toPx()
        val points = netWorthSparkPoints(values, size.width, size.height, padding)
        val baseline = size.height - padding

        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, baseline)
            lineTo(points.first().x, baseline)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(colors.income.copy(alpha = .22f), colors.income.copy(alpha = 0f)),
                startY = padding,
                endY = baseline,
            ),
        )
        drawPath(
            path = linePath,
            color = colors.income,
            style = Stroke(width = SPARK_LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val end = points.last()
        val dotRadius = SPARK_DOT_RADIUS.toPx()
        drawCircle(color = colors.surface2, radius = dotRadius, center = Offset(end.x, end.y))
        drawCircle(
            color = colors.income,
            radius = dotRadius,
            center = Offset(end.x, end.y),
            style = Stroke(width = SPARK_LINE_WIDTH.toPx()),
        )
    }
}
