package com.denebapps.patrimonio.domain.importing

import kotlinx.datetime.LocalDate

private const val CENT_DIGITS = 2
private const val THOUSANDS_GROUP = 3
private const val UNICODE_MINUS = '−'

/** The cells of one CSV [line]; a cell may be quoted (`"1.234,56"`), with `""` for a quote inside it. */
internal fun splitCsvRow(line: String, separator: Char): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            quoted && char == '"' && line.getOrNull(index + 1) == '"' -> {
                cell.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == separator && !quoted -> {
                cells += cell.toString()
                cell.clear()
            }
            else -> cell.append(char)
        }
        index++
    }
    cells += cell.toString()
    return cells
}

/**
 * An amount in cents from Spanish (`1.234,56`, `299,89`, `-0,08`) or plain (`100.5`) notation, ignoring spaces and a
 * euro sign; blank text is zero. `null` when it is not an amount or has sub-cent digits. A comma is always the
 * decimal separator unless a dot comes after it; a lone dot is a thousands separator when groups of three digits
 * follow it (`1.234`, as in Spanish), and a decimal point otherwise (`100.5`).
 */
internal fun parseSignedCents(raw: String): Long? {
    val compact = raw.filterNot { it.isWhitespace() || it == '€' }
    val negative = compact.startsWith('-') || compact.startsWith(UNICODE_MINUS)
    val unsigned = if (negative || compact.startsWith('+')) compact.drop(1) else compact
    if (unsigned.isEmpty()) return if (compact.isEmpty()) 0L else null

    val decimalIndex = decimalSeparatorIndex(unsigned) ?: return null
    val whole = if (decimalIndex < 0) unsigned else unsigned.substring(0, decimalIndex)
    val fraction = if (decimalIndex < 0) "" else unsigned.substring(decimalIndex + 1)
    val digits = whole.filter { it != '.' && it != ',' }
    val cents = fraction.trimEnd('0').padEnd(CENT_DIGITS, '0')
    if (digits.any { it !in '0'..'9' } || fraction.any { it !in '0'..'9' } || cents.length > CENT_DIGITS) return null
    if (digits.isEmpty() && fraction.isEmpty()) return null
    val value = ((digits.ifEmpty { "0" }) + cents).toLongOrNull() ?: return null
    return if (negative) -value else value
}

/**
 * Where the decimal separator of [text] is: its index, `-1` when it has none (only whole digits, maybe with thousands
 * separators), or `null` when the separators make no sense.
 */
private fun decimalSeparatorIndex(text: String): Int? {
    val lastComma = text.lastIndexOf(',')
    val lastDot = text.lastIndexOf('.')
    return when {
        lastComma >= 0 && lastDot >= 0 -> maxOf(lastComma, lastDot)
        lastComma >= 0 -> lastComma.takeIf { text.count { it == ',' } == 1 }
        lastDot >= 0 -> if (isDotThousands(text)) -1 else lastDot.takeIf { text.count { it == '.' } == 1 }
        else -> -1
    }
}

/** Whether every dot of [text] separates a group of three digits (`1.234`, `12.345.678`). */
private fun isDotThousands(text: String): Boolean {
    val groups = text.split('.')
    return groups.first().length in 1..THOUSANDS_GROUP && groups.drop(1).all { it.length == THOUSANDS_GROUP }
}

/** A `dd/MM/yyyy` date; the day and the month may have one digit and the separator may be a dash. */
internal fun parseSpanishDate(raw: String): LocalDate? {
    val parts = raw.trim().split('/', '-')
    if (parts.size != 3) return null
    val (day, month, year) = parts
    val shape = day.length in 1..2 && month.length in 1..2 && year.length == 4
    if (!shape || parts.any { part -> part.any { it !in '0'..'9' } }) return null
    return try {
        LocalDate(year.toInt(), month.toInt(), day.toInt())
    } catch (_: IllegalArgumentException) {
        null
    }
}
