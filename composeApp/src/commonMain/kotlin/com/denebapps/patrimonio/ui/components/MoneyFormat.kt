package com.denebapps.patrimonio.ui.components

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import kotlinx.datetime.LocalDate

// es-ES display helpers. Older screens still carry private per-file copies of these; new code uses
// these shared ones.

fun currencySymbol(currency: Currency): String = when (currency) {
    Currency.USD -> "$"
    Currency.JPY -> "¥"
    Currency.GBP -> "£"
    Currency.EUR -> "€"
}

/** "1.234,56 €" in [currency]'s own decimals (JPY has none), `.` thousands separator. */
fun formatMoney(money: Money, currency: Currency): String {
    val sign = if (money.minorUnits < 0) "-" else ""
    val abs = kotlin.math.abs(money.minorUnits)
    val factor = pow10(currency.decimals)
    val grouped = (abs / factor).toString().reversed().chunked(3).joinToString(".").reversed()
    val fraction = if (currency.decimals == 0) "" else "," + (abs % factor).toString().padStart(currency.decimals, '0')
    return "$sign$grouped$fraction ${currencySymbol(currency)}"
}

/** Aggregated [Money] values are EUR by convention. */
fun formatEur(money: Money): String = formatMoney(money, Currency.EUR)

/** Editable text for an amount field ("1234,5" style input → "1234,50"), the inverse of `parseAmountToMinor`. */
fun amountInputText(money: Money, currency: Currency): String {
    val factor = pow10(currency.decimals)
    val whole = money.minorUnits / factor
    return if (currency.decimals == 0) {
        whole.toString()
    } else {
        "$whole," + (money.minorUnits % factor).toString().padStart(currency.decimals, '0')
    }
}

/** Keeps digits and at most one decimal comma (a period is coerced to a comma). */
fun filterAmountInput(raw: String): String {
    val allowed = raw.filter { it.isDigit() || it == ',' || it == '.' }.replace('.', ',')
    val firstComma = allowed.indexOf(',')
    return if (firstComma == -1) {
        allowed
    } else {
        allowed.substring(0, firstComma + 1) + allowed.substring(firstComma + 1).filter { it.isDigit() }
    }
}

/** "14 oct". */
fun formatDayMonth(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS_ES_SHORT[date.monthNumber - 1]}"

/** "14 de octubre de 2026". */
fun formatDateLong(date: LocalDate): String =
    "${date.dayOfMonth} de ${MONTHS_ES_LONG[date.monthNumber - 1]} de ${date.year}"

private val MONTHS_ES_SHORT = listOf(
    "ene", "feb", "mar", "abr", "may", "jun",
    "jul", "ago", "sept", "oct", "nov", "dic",
)

private val MONTHS_ES_LONG = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)

private fun pow10(exponent: Int): Long {
    var result = 1L
    repeat(exponent) { result *= 10 }
    return result
}
