package com.denebapps.patrimonio.ui.screens.patrimonio

/** A single sparkline point in local `Canvas` coordinates. */
data class SparkPoint(val x: Float, val y: Float)

/**
 * Pure geometry for the net-worth sparkline (design.md Decision 4): min/max-normalizes [values]
 * into [SparkPoint]s inside a [width]x[height] canvas with [padding] on every edge, oldest-first.
 * Mirrors `design-reference/patrimonio.jsx`'s `NetWorthSpark`. Returns an empty list when fewer than two [values] are
 * given — a single point (or none) is not a trend (spec: Sparkline Reflects Snapshot History).
 */
fun netWorthSparkPoints(values: List<Long>, width: Float, height: Float, padding: Float): List<SparkPoint> {
    if (values.size < 2) return emptyList()
    val min = values.min().toFloat()
    val max = values.max().toFloat()
    val range = (max - min).takeIf { it != 0f } ?: 1f
    val step = (width - 2f * padding) / (values.size - 1)
    val baseline = height - padding
    val chartHeight = height - 2f * padding
    return values.mapIndexed { index, value ->
        val ratio = (value.toFloat() - min) / range
        SparkPoint(x = padding + index * step, y = baseline - ratio * chartHeight)
    }
}
