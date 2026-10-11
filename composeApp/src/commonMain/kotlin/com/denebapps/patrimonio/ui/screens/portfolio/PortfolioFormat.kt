package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.MAX_RISK_LEVEL
import kotlin.math.abs
import kotlin.math.roundToLong

// Pure text formatting of the portfolio detail (es-ES, neutral Spanish).

private const val MINUS = "−"
private const val HUNDRED = 100L
private const val TEN = 10L
private const val CORRELATION_DECIMALS = 2
private const val LISTED_NAMES = 3

/** The longest holding name shown whole in a sentence or a list; longer ones are cut with an ellipsis. */
internal const val MAX_NAME_LENGTH = 28

/** A correlation (-1 to 1) with two decimals and a decimal comma: "0,85", "−0,12"; "—" when there is none. */
internal fun formatCorrelation(value: Double?): String {
    if (value == null || !value.isFinite()) return NO_FIGURE_TEXT
    val scaled = (abs(value) * HUNDRED).roundToLong()
    val sign = if (scaled != 0L && value < 0) MINUS else ""
    val fraction = (scaled % HUNDRED).toString().padStart(CORRELATION_DECIMALS, '0')
    return "$sign${scaled / HUNDRED},$fraction"
}

/** "1 semana", "52 semanas". */
internal fun weeksText(weeks: Int): String = if (weeks == 1) "1 semana" else "$weeks semanas"

/**
 * How many of the products are priced by a source and how many were typed in: "3 con cotización · 2 manuales",
 * "Todos manuales". `null` without products.
 */
internal fun holdingsMixText(quoted: Int, manual: Int): String? = when {
    quoted == 0 && manual == 0 -> null
    manual == 0 -> if (quoted == 1) "Con cotización" else "Todos con cotización"
    quoted == 0 -> if (manual == 1) "Manual" else "Todos manuales"
    else -> "$quoted con cotización · $manual ${if (manual == 1) "manual" else "manuales"}"
}

/** "A, B, C y 2 más": a few [names], shortened, and how many more there are. */
internal fun namesList(names: List<String>): String {
    val listed = names.take(LISTED_NAMES).joinToString(", ") { shortName(it) }
    val more = names.size - LISTED_NAMES
    return listed + if (more > 0) " y $more más" else ""
}

/** "Excluye: A, B, C y 2 más": the holdings left out of the evolution, a few by name. `null` when none is. */
internal fun excludedText(names: List<String>): String? = if (names.isEmpty()) null else "Excluye: ${namesList(names)}"

/** [name] as it is, or cut to [max] characters ending in an ellipsis. */
internal fun shortName(name: String, max: Int = MAX_NAME_LENGTH): String =
    if (name.length <= max) name else name.take(max - 1).trimEnd() + "…"

/** "5,4 de 7": the average SRRI class with one decimal. */
internal fun averageRiskText(level: Double): String {
    val tenths = (level * TEN).roundToLong()
    return "${tenths / TEN},${tenths % TEN} de $MAX_RISK_LEVEL"
}

/** "Con datos para 3 de 4 productos" when [covered] is less than [total]; `null` when every product has data. */
internal fun coverageNote(covered: Int, total: Int): String? =
    if (total == 0 || covered >= total) null else "Con datos para $covered de $total productos"
