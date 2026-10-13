package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.formatScaled6
import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.calc.roundHalfEven
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.calc.value
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.ui.components.currencySymbol
import com.denebapps.patrimonio.ui.components.filterAmountInput
import com.denebapps.patrimonio.ui.components.formatEur
import com.denebapps.patrimonio.ui.components.formatMoney
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Decimal places a units field accepts, like `UNITS_SCALE`. */
private const val UNITS_INPUT_DECIMALS = 6

private const val TWO_DIGITS = 2

/** Spanish name of a kind of product, as shown on its pill. */
fun instrumentTypeLabel(type: InstrumentType): String = when (type) {
    InstrumentType.FUND -> "Fondo"
    InstrumentType.ETF -> "ETF"
    InstrumentType.STOCK -> "Acción"
    InstrumentType.CRYPTO -> "Cripto"
    InstrumentType.OTHER -> "Otro"
}

/** Name of the public data source that quotes an instrument. */
fun providerLabel(provider: QuoteProvider): String = when (provider) {
    QuoteProvider.MORNINGSTAR -> "Morningstar"
    QuoteProvider.YAHOO -> "Yahoo Finance"
    QuoteProvider.COINGECKO -> "CoinGecko"
}

/** The credit a provider asks for wherever its prices are shown; `null` when it asks for none. */
fun priceAttribution(provider: QuoteProvider): String? = when (provider) {
    QuoteProvider.COINGECKO -> "Powered by CoinGecko"
    QuoteProvider.MORNINGSTAR, QuoteProvider.YAHOO -> null
}

/** "10/10/2026". */
fun formatShortDate(date: LocalDate): String {
    val day = date.dayOfMonth.toString().padStart(TWO_DIGITS, '0')
    val month = date.monthNumber.toString().padStart(TWO_DIGITS, '0')
    return "$day/$month/${date.year}"
}

/**
 * "245,12 €": a unit price keeps the currency's own decimals at least and every significant one up to six, so a
 * fund's 12,345678 NAV is not cut.
 */
fun formatPrice(price: Price): String =
    "${formatScaled6(roundPriceForDisplay(price.scaled), price.currency.decimals, grouped = true)} " +
        currencySymbol(price.currency)

// Display precision by magnitude, in PRICE_SCALE (1e6) units: two decimals from 1.000 (74.781,76 €), up to four
// from 1 (12,3457 €) and up to six below 1 (0,123456 €). Fund NAVs (three decimals) read unchanged.
private const val THOUSAND_SCALED = 1_000_000_000L
private const val ONE_SCALED = 1_000_000L
private const val TWO_DECIMALS_STEP = 10_000L
private const val FOUR_DECIMALS_STEP = 100L

/** A non-negative scaled price rounded half-even to the decimals worth showing for its magnitude. */
internal fun roundPriceForDisplay(scaled: Long): Long {
    val step = when {
        scaled >= THOUSAND_SCALED -> TWO_DECIMALS_STEP
        scaled >= ONE_SCALED -> FOUR_DECIMALS_STEP
        else -> return scaled
    }
    return roundHalfEven(scaled, step) * step
}

/** Keeps digits and one decimal comma (a period becomes a comma), with at most six decimals. */
fun filterUnitsInput(raw: String): String {
    val filtered = filterAmountInput(raw)
    val comma = filtered.indexOf(',')
    return if (comma == -1) filtered else filtered.take(comma + 1 + UNITS_INPUT_DECIMALS)
}

/** "IE00B4L5Y983 · IWDA": whatever identifies a product besides its name, empty for a hand-made one. */
internal fun identifierText(isin: String?, ticker: String?): String = listOfNotNull(isin, ticker).joinToString(" · ")

/** The calendar day of [epochMs] in [zone]. */
internal fun dayOf(epochMs: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

/** Shown instead of a value while a quoted holding has not been priced yet. */
internal const val NO_PRICE_TEXT = "Sin precio"

/**
 * The holding's value in its own currency ("1.234,57 €"), and below it, when that is not euros, what it comes to in
 * euros ("≈ 1.840,00 €"). A quoted holding with no price yet reads [NO_PRICE_TEXT] and has no euro line.
 */
internal fun holdingValueTexts(holding: PortfolioHolding, rates: FxRates): Pair<String, String?> {
    val value = holding.value() ?: return NO_PRICE_TEXT to null
    val eurText = value.takeIf { it.currency != Currency.EUR }?.let { "≈ ${formatEur(it.toEur(rates))}" }
    return formatMoney(value.amount, value.currency) to eurText
}

/** "12,345678 part. · 245,12 € · 10/10/2026", or "Valor manual · 10/10/2026". */
internal fun holdingDetailLine(holding: PortfolioHolding, zone: TimeZone): String =
    when (val valuation = holding.valuation) {
        is HoldingValuation.Quoted -> listOfNotNull(
            "${formatUnits(valuation.units)} part.",
            valuation.lastQuote?.let { formatPrice(it.price) },
            valuation.lastQuote?.let { formatShortDate(it.date) },
        ).joinToString(" · ")
        is HoldingValuation.Manual -> "Valor manual · ${formatShortDate(dayOf(valuation.valuedAtEpochMs, zone))}"
    }
