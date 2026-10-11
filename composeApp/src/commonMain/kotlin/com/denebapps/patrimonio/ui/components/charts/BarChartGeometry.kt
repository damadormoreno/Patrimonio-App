package com.denebapps.patrimonio.ui.components.charts

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

// Pure geometry of a vertical bar chart: no Compose types, so it is unit-tested directly. Values grow upwards from a
// zero baseline (losses hang below it); a bar is as wide as its slot allows and its label sits next to its far end.

/** The roughly how many steps the value range is divided into by the gridlines. */
private const val TARGET_STEPS = 4

/** The "nice" multiples a step is rounded up to, within a power of ten: 1, 2, 5 and 10. */
private val NICE_STEP_FACTORS = doubleArrayOf(1.0, 2.0, 5.0, 10.0)

/** Rounding noise tolerated before a value is considered to be beyond a step. */
private const val STEP_EPSILON = 1e-9

/** The height of the scale of a chart without bars or with only zeros (any positive number would do). */
private const val EMPTY_EXTENT = 1.0

private const val HALF = 2f

/** The smallest width of a bar slot, in dp, below which the bars scroll instead of getting narrower. */
const val BAR_MIN_SLOT_WIDTH = 48f

/** The share of a slot a bar takes, the rest is the gap to its neighbors. */
const val BAR_WIDTH_RATIO = 0.7f

/** The widest a bar gets, in dp, however few bars there are. */
const val BAR_MAX_WIDTH = 56f

/**
 * The vertical range of a bar chart: [min] (at most zero) to [max] (at least zero), both multiples of [step]. A scale
 * of only gains starts at zero and one of only losses ends at it.
 */
data class BarScale(val min: Double, val max: Double, val step: Double) {
    /** The values of the gridlines, from [min] to [max]. */
    val ticks: List<Double>
        get() {
            val first = (min / step).roundToInt()
            val last = (max / step).roundToInt()
            return (first..last).map { it * step }
        }
}

/**
 * The scale that fits every one of [values] and zero, with a step of 1, 2 or 5 times a power of ten that divides the
 * whole range into about four. Without values, or with only zeros, a positive range is still returned.
 */
fun barScale(values: List<Double>): BarScale {
    val highest = max(values.maxOrNull() ?: 0.0, 0.0)
    val lowest = minOf(values.minOrNull() ?: 0.0, 0.0)
    val extent = if (highest - lowest > 0.0) highest - lowest else EMPTY_EXTENT
    val step = niceStep(extent / TARGET_STEPS)
    val top = if (highest > 0.0) ceil(highest / step - STEP_EPSILON) * step else 0.0
    val bottom = if (lowest < 0.0) floor(lowest / step + STEP_EPSILON) * step else 0.0
    return if (top > bottom) BarScale(bottom, top, step) else BarScale(0.0, step, step)
}

private fun niceStep(raw: Double): Double {
    val exponent = floor(log10(raw))
    val magnitude = 10.0.pow(exponent)
    val mantissa = raw / magnitude
    val factor = NICE_STEP_FACTORS.firstOrNull { mantissa <= it + STEP_EPSILON } ?: NICE_STEP_FACTORS.last()
    return factor * magnitude
}

/**
 * How the width is shared among the bars: each gets a slot of [slotWidth] and draws [barWidth] of it, centered. The
 * chart is [contentWidth] wide, wider than the room when it [scrolls].
 */
data class BarSlots(val slotWidth: Float, val barWidth: Float, val contentWidth: Float, val scrolls: Boolean)

/**
 * Slots for [count] bars in [availableWidth]. The bars spread over the whole width, in slots at least [minSlotWidth]
 * wide; when they would not fit like that the slots stay at [minSlotWidth] and the chart is wider than the room, so
 * it scrolls. A bar is [barWidthRatio] of its slot, never wider than [maxBarWidth]. Any unit works if all are alike.
 */
fun barSlots(
    count: Int,
    availableWidth: Float,
    minSlotWidth: Float = BAR_MIN_SLOT_WIDTH,
    barWidthRatio: Float = BAR_WIDTH_RATIO,
    maxBarWidth: Float = BAR_MAX_WIDTH,
): BarSlots {
    if (count <= 0) return BarSlots(availableWidth, 0f, availableWidth, scrolls = false)
    val scrolls = count * minSlotWidth > availableWidth
    val slotWidth = if (scrolls) minSlotWidth else availableWidth / count
    val barWidth = minOf(slotWidth * barWidthRatio, maxBarWidth)
    return BarSlots(slotWidth, barWidth, if (scrolls) count * slotWidth else availableWidth, scrolls)
}

/** Which side of its bar a label goes: above a gain (or a zero), below a loss. */
enum class LabelSide { ABOVE, BELOW }

/**
 * A bar on the canvas, in pixels from its top-left corner. Its label is centered on [centerX] and touches
 * [labelEdgeY] with the edge nearest the bar: its bottom edge for [LabelSide.ABOVE], its top edge for
 * [LabelSide.BELOW].
 */
data class BarShape(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val labelSide: LabelSide,
    val labelEdgeY: Float,
) {
    val centerX: Float get() = (left + right) / HALF
}

/** Where everything goes: the baseline at [zeroY], a gridline at each of [gridY] and the [bars] in order. */
data class BarLayout(val zeroY: Float, val gridY: List<Float>, val bars: List<BarShape>)

/**
 * The layout of [values] (in the order they are drawn, left to right) on a plot [plotHeight] pixels tall, within
 * [scale] and in [slots]. [labelSpace] pixels are kept free above the highest bar when there is a gain or a zero and
 * below the lowest when there is a loss, so the label of the extreme bars is inside the plot; labels are [labelGap]
 * pixels from their bar. A bar is at least [minBarHeight] tall, so a tiny value or a zero is still a visible mark
 * centered on the baseline.
 */
fun barLayout(
    values: List<Double>,
    scale: BarScale,
    slots: BarSlots,
    plotHeight: Float,
    labelSpace: Float,
    labelGap: Float,
    minBarHeight: Float,
): BarLayout {
    val top = if (values.any { it >= 0.0 }) labelSpace else 0f
    val bottom = plotHeight - if (values.any { it < 0.0 }) labelSpace else 0f
    val span = scale.max - scale.min

    fun yOf(value: Double): Float = top + ((scale.max - value) / span).toFloat() * (bottom - top)

    val zeroY = yOf(0.0)
    val inset = (slots.slotWidth - slots.barWidth) / HALF
    val bars = values.mapIndexed { index, value ->
        val left = index * slots.slotWidth + inset
        val right = left + slots.barWidth
        val rawEnd = yOf(value)
        when {
            value > 0.0 -> {
                val barTop = minOf(rawEnd, zeroY - minBarHeight)
                BarShape(left, barTop, right, zeroY, LabelSide.ABOVE, barTop - labelGap)
            }
            value < 0.0 -> {
                val barBottom = max(rawEnd, zeroY + minBarHeight)
                BarShape(left, zeroY, right, barBottom, LabelSide.BELOW, barBottom + labelGap)
            }
            else -> {
                val barTop = zeroY - minBarHeight / HALF
                BarShape(left, barTop, right, zeroY + minBarHeight / HALF, LabelSide.ABOVE, barTop - labelGap)
            }
        }
    }
    return BarLayout(zeroY, scale.ticks.map(::yOf), bars)
}
