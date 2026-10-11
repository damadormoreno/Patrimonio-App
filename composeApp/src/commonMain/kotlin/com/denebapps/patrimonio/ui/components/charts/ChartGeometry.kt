package com.denebapps.patrimonio.ui.components.charts

import kotlin.math.abs
import kotlin.math.max

// Pure geometry of a line chart: no Compose types, so it is unit-tested directly. A chart has a data space (dates as
// epoch days, values as `Double`) and a canvas space (pixels, y growing downwards); these functions map one to the
// other and find the point under a finger.

/** The share of a flat series' value added above and below it, so a constant line draws in the middle. */
private const val FLAT_PADDING_RATIO = 0.05

/** The padding a flat series at zero gets, since a share of zero would be nothing. */
private const val FLAT_ZERO_PADDING = 1.0

/** The share of the value range added above and below the data by default, so the line never touches an edge. */
const val DEFAULT_CHART_PADDING = 0.06

private const val HALF = 2f

/** One sample of a chart: the [value] on the day [epochDay] (days since 1970-01-01). */
data class ChartPoint(val epochDay: Long, val value: Double)

/** The data rectangle a chart shows: dates from [minX] to [maxX] and values from [minY] to [maxY] (already padded). */
data class ChartBounds(val minX: Long, val maxX: Long, val minY: Double, val maxY: Double)

/** A [ChartPoint] on the canvas, in pixels from its top-left corner. */
data class ChartPlotPoint(val x: Float, val y: Float)

/**
 * The bounds of [points] (ascending by date): the first and last date, and the value range widened by [padRatio] of
 * its span on each side. A flat series gets a range around its value instead, and a single day sits alone on the date
 * axis. `null` without points.
 */
fun chartBounds(points: List<ChartPoint>, padRatio: Double = DEFAULT_CHART_PADDING): ChartBounds? {
    if (points.isEmpty()) return null
    val low = points.minOf { it.value }
    val high = points.maxOf { it.value }
    val padding = if (high > low) (high - low) * padRatio else max(abs(low) * FLAT_PADDING_RATIO, FLAT_ZERO_PADDING)
    return ChartBounds(points.first().epochDay, points.last().epochDay, low - padding, high + padding)
}

/** The pixel x of [epochDay], within [inset] pixels of both sides of a canvas [width] pixels wide. */
fun projectX(epochDay: Long, bounds: ChartBounds, width: Float, inset: Float = 0f): Float {
    val span = bounds.maxX - bounds.minX
    val usable = width - 2 * inset
    if (span <= 0L) return inset + usable / HALF
    return inset + usable * (epochDay - bounds.minX).toFloat() / span.toFloat()
}

/** The pixel y of [value] (the highest value is at the top), within [inset] pixels of both edges of [height]. */
fun projectY(value: Double, bounds: ChartBounds, height: Float, inset: Float = 0f): Float {
    val span = bounds.maxY - bounds.minY
    val usable = height - 2 * inset
    if (span <= 0.0) return inset + usable / HALF
    return inset + usable * ((bounds.maxY - value) / span).toFloat()
}

/** Every point of [points] on a canvas of [width] x [height] pixels, [inset] pixels away from each edge. */
fun projectPoints(
    points: List<ChartPoint>,
    bounds: ChartBounds,
    width: Float,
    height: Float,
    inset: Float = 0f,
): List<ChartPlotPoint> = points.map {
    ChartPlotPoint(projectX(it.epochDay, bounds, width, inset), projectY(it.value, bounds, height, inset))
}

/**
 * The index in [points] (ascending by date) of the one whose date is nearest to a finger at [touchX] pixels, on a
 * canvas [width] pixels wide with [inset] pixels on each side. A touch beyond either end picks the first or the last
 * point; `-1` when there are no points.
 */
fun nearestPointIndex(
    points: List<ChartPoint>,
    bounds: ChartBounds,
    width: Float,
    touchX: Float,
    inset: Float = 0f,
): Int {
    if (points.isEmpty()) return -1
    val span = bounds.maxX - bounds.minX
    val usable = width - 2 * inset
    if (span <= 0L || usable <= 0f) return 0
    val day = bounds.minX + (((touchX - inset) / usable).coerceIn(0f, 1f) * span.toFloat()).toDouble()
    return nearestByDay(points, day)
}

private fun nearestByDay(points: List<ChartPoint>, day: Double): Int {
    var low = 0
    var high = points.lastIndex
    while (low < high) {
        val mid = (low + high) / 2
        if (points[mid].epochDay < day) low = mid + 1 else high = mid
    }
    val after = low
    val before = (low - 1).coerceAtLeast(0)
    return if (abs(points[before].epochDay - day) <= abs(points[after].epochDay - day)) before else after
}
