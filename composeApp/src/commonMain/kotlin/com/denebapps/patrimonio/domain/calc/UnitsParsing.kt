package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units

/** Decimal places a quantity of units keeps: the digits of `UNITS_SCALE`. */
private const val UNITS_DECIMALS = 6

/** Decimals a displayed quantity always shows, even when they are zeros ("12,50"). */
private const val MIN_DISPLAY_DECIMALS = 2

private const val THOUSANDS_GROUP = 3

/**
 * Parses a comma-decimal, period-thousands quantity (es-ES locale, e.g. `"1.234,567891"`) into [Units]. Up to six
 * decimals; a missing integer part (`",5"`) or fraction (`"12,"`) reads as zero. Returns `null` for anything
 * invalid, negative, empty, over six decimals, too large for the fixed-point range, or exactly zero (units are
 * always positive, and Save gating relies on that).
 */
fun parseUnitsEs(text: String): Units? {
    val parts = text.trim().split(",")
    if (parts.size > 2) return null

    val rawInt = parts[0].replace(".", "")
    val rawFrac = parts.getOrElse(1) { "" }
    if (rawInt.isEmpty() && rawFrac.isEmpty()) return null
    if (rawInt.any { !it.isDigit() } || rawFrac.any { !it.isDigit() }) return null
    if (rawFrac.length > UNITS_DECIMALS) return null

    val scaled = (rawInt + rawFrac.padEnd(UNITS_DECIMALS, '0')).toLongOrNull() ?: return null
    return scaled.takeIf { it > 0 }?.let(::Units)
}

/**
 * Display text of a quantity: `.` thousands separators, `,` decimal comma, trailing zeros dropped but never fewer
 * than two decimals (`"1.234,50"`, `"12,345678"`).
 */
fun formatUnits(units: Units): String = formatScaled6(units.scaled, MIN_DISPLAY_DECIMALS, grouped = true)

/**
 * Editable text of a quantity, the inverse of [parseUnitsEs]: no thousands separators and no forced decimals
 * (`"1234,5"`, `"12"`).
 */
fun unitsInputText(units: Units): String = formatScaled6(units.scaled, minDecimals = 0, grouped = false)

/**
 * Renders a non-negative [scaled] value carrying six decimals (units and prices share that scale) in es-ES style,
 * keeping at least [minDecimals] decimals and dropping the zeros beyond them.
 */
internal fun formatScaled6(scaled: Long, minDecimals: Int, grouped: Boolean): String {
    val whole = (scaled / UNITS_SCALE).toString()
    val fraction = (scaled % UNITS_SCALE).toString()
        .padStart(UNITS_DECIMALS, '0')
        .trimEnd('0')
        .padEnd(minDecimals, '0')
    val integer = if (grouped) whole.reversed().chunked(THOUSANDS_GROUP).joinToString(".").reversed() else whole
    return if (fraction.isEmpty()) integer else "$integer,$fraction"
}
