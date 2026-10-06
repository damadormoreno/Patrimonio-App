package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency

/**
 * Parses a comma-decimal, period-thousands amount string (es-ES locale, e.g. `"1.234,56"`) into
 * minor units for [currency] — 2dp for EUR/USD/GBP, 0dp for JPY. Returns `null` for anything
 * invalid, negative, empty, or exactly zero (Save gating relies on a strictly-positive result).
 */
fun parseAmountToMinor(text: String, currency: Currency): Long? {
    if (text.isBlank()) return null

    val parts = text.split(",")
    if (parts.size > 2) return null

    val rawInt = parts[0].replace(".", "")
    val rawFrac = if (parts.size == 2) parts[1] else ""

    if (rawInt.isEmpty() || rawInt.any { !it.isDigit() }) return null
    if (rawFrac.any { !it.isDigit() }) return null

    val decimals = currency.decimals
    if (rawFrac.length > decimals) return null

    val fracDigits = rawFrac.padEnd(decimals, '0')
    val minorUnits = (rawInt + fracDigits).toLongOrNull() ?: return null
    return minorUnits.takeIf { it > 0 }
}
