package com.denebapps.patrimonio.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlin.math.abs

private val CHART_HEIGHT = 190.dp
private val CHART_INSET = 8.dp
private val LINE_WIDTH = 2.dp
private val DOT_RADIUS = 4.dp
private val MARKER_WIDTH = 1.dp
private const val AREA_ALPHA = 0.22f

/**
 * A line chart of [points] (ascending by date) with a soft area under the line, drawn in [lineColor]. Pressing it and
 * dragging sideways scrubs: a vertical marker with a dot follows the finger on the nearest point and [onScrub] is
 * told its index in [points], then `null` when the finger lifts. A mostly vertical gesture is left to the screen's
 * scroll. Nothing is drawn without points; a single point is just a dot. The geometry (see [chartBounds],
 * [projectPoints], [nearestPointIndex]) is pure and tested without a screen; the other colors come from the theme.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = CHART_HEIGHT,
    showArea: Boolean = true,
    onScrub: (Int?) -> Unit = {},
) {
    val colors = LocalAppColors.current
    val bounds = remember(points) { chartBounds(points) }
    var scrubbed by remember(points) { mutableStateOf<Int?>(null) }
    val currentOnScrub by rememberUpdatedState(onScrub)

    fun scrubTo(index: Int?) {
        if (index == scrubbed) return
        scrubbed = index
        currentOnScrub(index)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(points, bounds) {
                if (bounds == null) return@pointerInput
                val inset = CHART_INSET.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scrubTo(nearestPointIndex(points, bounds, size.width.toFloat(), down.position.x, inset))
                    var travelled = Offset.Zero
                    var horizontal = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        travelled += change.position - change.previousPosition
                        if (!horizontal) {
                            horizontal = abs(travelled.x) > slop && abs(travelled.x) > abs(travelled.y)
                            // A vertical gesture is the screen scrolling: step aside.
                            if (!horizontal && abs(travelled.y) > slop) break
                        }
                        if (horizontal) change.consume()
                        scrubTo(nearestPointIndex(points, bounds, size.width.toFloat(), change.position.x, inset))
                    }
                    scrubTo(null)
                }
            },
    ) {
        if (bounds == null) return@Canvas
        val inset = CHART_INSET.toPx()
        val plot = projectPoints(points, bounds, size.width, size.height, inset)
        val baseline = size.height - inset

        if (plot.size > 1) {
            val linePath = Path().apply {
                moveTo(plot.first().x, plot.first().y)
                for (i in 1 until plot.size) lineTo(plot[i].x, plot[i].y)
            }
            if (showArea) {
                val areaPath = Path().apply {
                    addPath(linePath)
                    lineTo(plot.last().x, baseline)
                    lineTo(plot.first().x, baseline)
                    close()
                }
                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = AREA_ALPHA), lineColor.copy(alpha = 0f)),
                        startY = inset,
                        endY = baseline,
                    ),
                )
            }
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        val marked = scrubbed?.let { plot.getOrNull(it) } ?: plot.last()
        if (scrubbed != null) {
            drawLine(
                color = colors.line,
                start = Offset(marked.x, inset),
                end = Offset(marked.x, baseline),
                strokeWidth = MARKER_WIDTH.toPx(),
            )
        }
        val center = Offset(marked.x, marked.y)
        drawCircle(color = colors.surface2, radius = DOT_RADIUS.toPx(), center = center)
        drawCircle(
            color = lineColor,
            radius = DOT_RADIUS.toPx(),
            center = center,
            style = Stroke(width = LINE_WIDTH.toPx()),
        )
    }
}
