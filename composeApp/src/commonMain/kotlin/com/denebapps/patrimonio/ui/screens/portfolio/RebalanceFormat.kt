package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.ui.components.filterAmountInput
import kotlin.math.abs

// Shares in basis points (hundredths of a percentage point) as the rebalancing screens read and show them, es-ES: a
// decimal comma, and decimals only when there are some.

private const val MINUS = "−"
private const val BP_PER_POINT = 100
private const val POINT_DECIMALS = 2
private const val MAX_WHOLE_DIGITS = 3
private const val PERCENT = 100L

/** "72 %", "12,5 %", "72,33 %": a share in basis points, with the decimals it has. */
internal fun formatBp(bp: Int): String = "${pointsText(bp)} %"

/**
 * A distance in percentage points: "−8 pts", "+7,5 pts", "0 pts". With [signed] false the sign is left out (a band, not
 * a drift). One point is "1 pt".
 */
internal fun formatPoints(bp: Int, signed: Boolean = true): String {
    val sign = when {
        !signed || bp == 0 -> ""
        bp < 0 -> MINUS
        else -> "+"
    }
    val unit = if (abs(bp) == BP_PER_POINT) "pt" else "pts"
    return "$sign${pointsText(bp)} $unit"
}

/** The text a field holds for [bp]: "80", "12,5", "0". */
internal fun bpInputText(bp: Int): String = pointsText(bp)

/** [bp] as points with a decimal comma and without trailing zeros (absolute value). */
private fun pointsText(bp: Int): String {
    val abs = abs(bp)
    val whole = abs / BP_PER_POINT
    val fraction = (abs % BP_PER_POINT).toString().padStart(POINT_DECIMALS, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$whole" else "$whole,$fraction"
}

/** What a share field accepts: digits and one decimal comma (a period becomes one), up to 3 whole digits and 2 decimals. */
internal fun filterPercentInput(raw: String): String {
    val filtered = filterAmountInput(raw)
    val comma = filtered.indexOf(',')
    return if (comma == -1) {
        filtered.take(MAX_WHOLE_DIGITS)
    } else {
        filtered.substring(0, comma).take(MAX_WHOLE_DIGITS) + "," + filtered.substring(comma + 1).take(POINT_DECIMALS)
    }
}

/**
 * The basis points a typed share stands for: "12,5" is 1 250. A blank field is 0; `null` for text that is not a share
 * (a lone comma, more than two decimals, anything but digits and one comma). Exact: no floating point.
 */
internal fun parsePercentToBp(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    val parts = trimmed.split(",")
    if (parts.size > 2) return null
    val whole = parts[0]
    val fraction = parts.getOrElse(1) { "" }
    if (whole.isEmpty() && fraction.isEmpty()) return null
    if (whole.any { !it.isDigit() } || fraction.any { !it.isDigit() } || fraction.length > POINT_DECIMALS) return null
    val wholeValue = if (whole.isEmpty()) 0 else whole.toIntOrNull() ?: return null
    val fractionValue = fraction.padEnd(POINT_DECIMALS, '0').toInt()
    return wholeValue * BP_PER_POINT + fractionValue
}

/**
 * The split of [values] in whole percents that add up to 100 (largest remainder; equal fractions go to the class that
 * comes first). Only classes that get at least 1 % are in the result; empty for no value at all.
 */
internal fun roundedPercentSplit(values: Map<AssetClass, Money>): Map<AssetClass, Int> {
    val total = values.values.sumOf { it.minorUnits }
    if (total <= 0) return emptyMap()
    class Share(val assetClass: AssetClass, val floor: Long, val remainder: Long)

    val shares = AssetClass.entries.mapNotNull { assetClass ->
        val value = values[assetClass]?.minorUnits?.takeIf { it > 0 } ?: return@mapNotNull null
        Share(assetClass, value * PERCENT / total, value * PERCENT % total)
    }
    val missing = (PERCENT - shares.sumOf { it.floor }).toInt()
    val boosted = shares.sortedWith(compareByDescending<Share> { it.remainder }.thenBy { it.assetClass })
        .take(missing)
        .map { it.assetClass }
        .toSet()
    return shares
        .associate { it.assetClass to (it.floor + if (it.assetClass in boosted) 1 else 0).toInt() }
        .filterValues { it > 0 }
}
