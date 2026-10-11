package com.denebapps.patrimonio.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Test-only color science: how a color looks to someone with a color-vision deficiency, and how far apart two colors
 * look. Used to check the theme palettes with numbers instead of by eye.
 *
 * The deficiencies are simulated with the Machado, Oliveira and Fernandes (2009) matrices at full severity (dichromacy)
 * applied to linear sRGB; the distance is CIEDE2000 on CIELAB (D65).
 */
internal enum class Vision(val label: String, internal val matrix: List<Double>?) {
    NORMAL("normal vision", null),
    PROTANOPIA(
        "protanopia",
        listOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
    ),
    DEUTERANOPIA(
        "deuteranopia",
        listOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881),
    ),
}

/** A color in CIELAB. */
internal data class Lab(val l: Double, val a: Double, val b: Double)

private const val WHITE_X = 0.95047
private const val WHITE_Z = 1.08883

private fun toLinear(channel: Double): Double =
    if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

private fun labCurve(t: Double): Double = if (t > 216.0 / 24389.0) {
    t.pow(
        1.0 / 3.0,
    )
} else {
    (24389.0 / 27.0 * t + 16.0) / 116.0
}

/** [color] as [vision] sees it, in CIELAB. */
internal fun labAs(color: Color, vision: Vision): Lab {
    val linear = listOf(color.red, color.green, color.blue).map { toLinear(it.toDouble()) }
    val seen = vision.matrix?.let { m ->
        List(3) { row ->
            (m[row * 3] * linear[0] + m[row * 3 + 1] * linear[1] + m[row * 3 + 2] * linear[2]).coerceIn(0.0, 1.0)
        }
    } ?: linear
    val (r, g, b) = seen
    val x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
    val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    val z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
    val fx = labCurve(x / WHITE_X)
    val fy = labCurve(y)
    val fz = labCurve(z / WHITE_Z)
    return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
}

/** How far apart [first] and [second] look to someone with [vision]: CIEDE2000, where about 2 is just noticeable. */
internal fun distanceAs(first: Color, second: Color, vision: Vision): Double =
    deltaE2000(labAs(first, vision), labAs(second, vision))

private fun radians(degrees: Double) = degrees * PI / 180.0

private fun hueDegrees(b: Double, aPrime: Double): Double {
    if (b == 0.0 && aPrime == 0.0) return 0.0
    val degrees = atan2(b, aPrime) * 180.0 / PI
    return if (degrees < 0) degrees + 360.0 else degrees
}

/** CIEDE2000 (Sharma, Wu and Dalal 2005) between two CIELAB colors. */
@Suppress("LongMethod", "MagicNumber")
internal fun deltaE2000(first: Lab, second: Lab): Double {
    val meanChroma = (hypot(first.a, first.b) + hypot(second.a, second.b)) / 2.0
    val g = 0.5 * (1.0 - sqrt(meanChroma.pow(7) / (meanChroma.pow(7) + 25.0.pow(7))))
    val a1 = (1.0 + g) * first.a
    val a2 = (1.0 + g) * second.a
    val c1 = hypot(a1, first.b)
    val c2 = hypot(a2, second.b)
    val h1 = hueDegrees(first.b, a1)
    val h2 = hueDegrees(second.b, a2)

    val dL = second.l - first.l
    val dC = c2 - c1
    val noHue = c1 * c2 == 0.0
    var dh = 0.0
    if (!noHue) {
        dh = h2 - h1
        if (dh > 180.0) dh -= 360.0 else if (dh < -180.0) dh += 360.0
    }
    val dH = 2.0 * sqrt(c1 * c2) * sin(radians(dh / 2.0))

    val meanL = (first.l + second.l) / 2.0
    val meanC = (c1 + c2) / 2.0
    val meanH = when {
        noHue -> h1 + h2
        abs(h1 - h2) <= 180.0 -> (h1 + h2) / 2.0
        h1 + h2 < 360.0 -> (h1 + h2 + 360.0) / 2.0
        else -> (h1 + h2 - 360.0) / 2.0
    }
    val t = 1.0 - 0.17 * cos(radians(meanH - 30.0)) + 0.24 * cos(radians(2.0 * meanH)) +
        0.32 * cos(radians(3.0 * meanH + 6.0)) - 0.20 * cos(radians(4.0 * meanH - 63.0))
    val dTheta = 30.0 * exp(-(((meanH - 275.0) / 25.0).pow(2)))
    val rc = 2.0 * sqrt(meanC.pow(7) / (meanC.pow(7) + 25.0.pow(7)))
    val sl = 1.0 + 0.015 * (meanL - 50.0).pow(2) / sqrt(20.0 + (meanL - 50.0).pow(2))
    val sc = 1.0 + 0.045 * meanC
    val sh = 1.0 + 0.015 * meanC * t
    val rt = -sin(radians(2.0 * dTheta)) * rc
    return sqrt((dL / sl).pow(2) + (dC / sc).pow(2) + (dH / sh).pow(2) + rt * (dC / sc) * (dH / sh))
}
