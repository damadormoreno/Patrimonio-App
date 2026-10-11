package com.denebapps.patrimonio.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.first

private val CHART_HEIGHT = 210.dp
private val AXIS_HEIGHT = 26.dp
private val AXIS_TEXT_GAP = 8.dp
private val LABEL_GAP = 3.dp
private val MIN_BAR_HEIGHT = 2.dp
private val GRID_WIDTH = 1.dp
private val BAR_CORNER = 3.dp
private const val FADED_ALPHA = 0.45f
private const val LABEL_SIZE = 11

/**
 * One bar of a [BarChart]: its [label] under the axis, its [value] (a gain rises above the baseline, a loss hangs
 * below it, zero is a thin mark) and the [valueText] printed next to it. A [faded] bar is drawn lighter, for a figure
 * that is not final.
 */
data class BarChartBar(val label: String, val value: Double, val valueText: String, val faded: Boolean = false)

/**
 * A vertical bar chart of [bars], left to right in the given order, on a zero baseline with light gridlines. Gains are
 * drawn in the income color and losses in the expense one; the value text goes above a gain and below a loss and each
 * bar's label under the chart. The bars spread over the width, up to a maximum bar width; when they do not fit at a
 * comfortable width the chart scrolls sideways, starting at the end. [contentDescription] is what a screen reader
 * says about the whole chart. The geometry (see [barScale], [barSlots], [barLayout]) is pure and tested without a
 * screen; the colors come from the theme.
 */
@Composable
fun BarChart(
    bars: List<BarChartBar>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = CHART_HEIGHT,
) {
    if (bars.isEmpty()) return
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val valueStyle = TextStyle(color = colors.ink, fontSize = LABEL_SIZE.sp, fontWeight = FontWeight.SemiBold)
    val axisStyle = TextStyle(color = colors.muted, fontSize = LABEL_SIZE.sp, fontWeight = FontWeight.Medium)
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription
        },
    ) {
        val slots = remember(bars.size, maxWidth) { barSlots(bars.size, maxWidth.value) }
        LaunchedEffect(slots.scrolls, bars.size) {
            if (slots.scrolls) {
                // The latest years are the ones that matter: wait for the layout to know the end, then go there.
                scrollState.scrollTo(snapshotFlow { scrollState.maxValue }.first { it in 1 until Int.MAX_VALUE })
            }
        }
        val values = remember(bars) { bars.map { it.value } }
        val layout = remember(bars, slots, height, density) {
            with(density) {
                val labelHeight = textMeasurer.measure("0", valueStyle).size.height
                barLayout(
                    values = values,
                    scale = barScale(values),
                    slots = BarSlots(
                        slotWidth = slots.slotWidth.dp.toPx(),
                        barWidth = slots.barWidth.dp.toPx(),
                        contentWidth = slots.contentWidth.dp.toPx(),
                        scrolls = slots.scrolls,
                    ),
                    plotHeight = (height - AXIS_HEIGHT).toPx(),
                    labelSpace = labelHeight + LABEL_GAP.toPx(),
                    labelGap = LABEL_GAP.toPx(),
                    minBarHeight = MIN_BAR_HEIGHT.toPx(),
                )
            }
        }

        Canvas(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .width(slots.contentWidth.dp)
                .height(height),
        ) {
            layout.gridY.forEach {
                drawLine(colors.line, Offset(0f, it), Offset(size.width, it), GRID_WIDTH.toPx())
            }
            drawLine(colors.muted2, Offset(0f, layout.zeroY), Offset(size.width, layout.zeroY), GRID_WIDTH.toPx())

            bars.forEachIndexed { index, bar ->
                val shape = layout.bars[index]
                val color = when {
                    bar.value > 0.0 -> colors.income
                    bar.value < 0.0 -> colors.expense
                    else -> colors.muted2
                }
                drawRoundRect(
                    color = if (bar.faded) color.copy(alpha = FADED_ALPHA) else color,
                    topLeft = Offset(shape.left, shape.top),
                    size = Size(shape.right - shape.left, shape.bottom - shape.top),
                    cornerRadius = CornerRadius(BAR_CORNER.toPx()),
                )
                val value = textMeasurer.measure(bar.valueText, valueStyle, softWrap = false, maxLines = 1)
                val valueTop = when (shape.labelSide) {
                    LabelSide.ABOVE -> shape.labelEdgeY - value.size.height
                    LabelSide.BELOW -> shape.labelEdgeY
                }
                drawText(value, topLeft = Offset(shape.centerX - value.size.width / 2f, valueTop))

                val year = textMeasurer.measure(bar.label, axisStyle, softWrap = false, maxLines = 1)
                val yearTop = size.height - AXIS_HEIGHT.toPx() + AXIS_TEXT_GAP.toPx()
                drawText(year, topLeft = Offset(shape.centerX - year.size.width / 2f, yearTop))
            }
        }
    }
}
