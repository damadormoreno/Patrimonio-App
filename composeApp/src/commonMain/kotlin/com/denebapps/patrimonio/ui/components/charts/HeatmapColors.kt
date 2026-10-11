package com.denebapps.patrimonio.ui.components.charts

import kotlin.math.pow
import kotlin.math.roundToInt

// Pure color math of a heatmap on packed ARGB integers (the layout of `Color.toArgb()`): no Compose types, so it is
// unit-tested directly. The screen converts its theme colors to ARGB, calls these, and converts the answer back.

private const val CHANNEL_MAX = 255
private const val BYTE_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

// WCAG 2.x relative luminance and contrast ratio.
private const val LUMINANCE_RED = 0.2126
private const val LUMINANCE_GREEN = 0.7152
private const val LUMINANCE_BLUE = 0.0722
private const val LINEAR_THRESHOLD = 0.03928
private const val LINEAR_SLOPE = 12.92
private const val GAMMA_OFFSET = 0.055
private const val GAMMA_DIVISOR = 1.055
private const val GAMMA = 2.4
private const val LUMINANCE_FLARE = 0.05

private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and BYTE_MASK

private fun mix(from: Int, to: Int, fraction: Float, shift: Int): Int =
    (channel(from, shift) + (channel(to, shift) - channel(from, shift)) * fraction).roundToInt()
        .coerceIn(0, CHANNEL_MAX)

/** The color [fraction] (0 to 1, clamped) of the way from [from] to [to], channel by channel, alpha included. */
fun blendArgb(from: Int, to: Int, fraction: Float): Int {
    val t = fraction.coerceIn(0f, 1f)
    return (mix(from, to, t, ALPHA_SHIFT) shl ALPHA_SHIFT) or
        (mix(from, to, t, RED_SHIFT) shl RED_SHIFT) or
        (mix(from, to, t, GREEN_SHIFT) shl GREEN_SHIFT) or
        mix(from, to, t, 0)
}

/**
 * The color of [value] on a scale from -1 to 1 that is [negative] at -1, [neutral] at 0 and [positive] at 1, blended
 * linearly in between; beyond the ends it saturates. A `null` or not finite value (no data) is [neutral].
 */
fun divergingArgb(value: Double?, negative: Int, neutral: Int, positive: Int): Int {
    if (value == null || !value.isFinite()) return neutral
    val clamped = value.coerceIn(-1.0, 1.0)
    return when {
        clamped < 0.0 -> blendArgb(neutral, negative, -clamped.toFloat())
        clamped > 0.0 -> blendArgb(neutral, positive, clamped.toFloat())
        else -> neutral
    }
}

private fun linear(component: Int): Double {
    val c = component / CHANNEL_MAX.toDouble()
    return if (c <= LINEAR_THRESHOLD) c / LINEAR_SLOPE else ((c + GAMMA_OFFSET) / GAMMA_DIVISOR).pow(GAMMA)
}

private fun luminance(argb: Int): Double = LUMINANCE_RED * linear(channel(argb, RED_SHIFT)) +
    LUMINANCE_GREEN * linear(channel(argb, GREEN_SHIFT)) +
    LUMINANCE_BLUE * linear(channel(argb, 0))

/** The WCAG contrast ratio between two colors, 1 (the same) to 21 (black on white); alpha is ignored. */
fun contrastRatio(first: Int, second: Int): Double {
    val a = luminance(first)
    val b = luminance(second)
    return (maxOf(a, b) + LUMINANCE_FLARE) / (minOf(a, b) + LUMINANCE_FLARE)
}

/** Whichever of [first] and [second] reads better on [background]; [first] when they are equally good. */
fun readableArgb(background: Int, first: Int, second: Int): Int =
    if (contrastRatio(background, first) >= contrastRatio(background, second)) first else second
