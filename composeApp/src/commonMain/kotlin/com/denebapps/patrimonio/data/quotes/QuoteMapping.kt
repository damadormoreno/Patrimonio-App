package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.quotes.dto.MorningstarScreenerRow
import com.denebapps.patrimonio.domain.calc.roundHalfEven
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.MAX_RISK_LEVEL
import com.denebapps.patrimonio.domain.model.MIN_RISK_LEVEL
import com.denebapps.patrimonio.domain.model.PERCENT_SCALE
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.normalizeFundamentalsText
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.minus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.round

private val ISIN_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{9}\\d$")

/** Yahoo quotes London shares in pence under these currency codes. */
internal val PENCE_CODES = setOf("GBp", "GBX")
internal const val PENCE_PER_POUND = 100L

// Yahoo serves closes as single-precision floats (131.67 arrives as 131.669998): keep four decimals of the
// quoted unit, which is what exchanges publish, and drop the rest as noise. In `Price` scale that is a step of 100.
private const val YAHOO_PRICE_STEP = 100L

/** Lenient parser shared by the adapters: the unofficial payloads carry many fields the app ignores. */
internal val QuoteJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** `true` when [query] (trimmed, any case) has the shape of an ISIN, e.g. `IE00B03HCZ61`. */
fun looksLikeIsin(query: String): Boolean = ISIN_REGEX.matches(query.trim().uppercase())

/** Maps a Yahoo `quoteType`; crypto, futures, indices and the rest are out of scope and yield `null`. */
fun yahooInstrumentType(quoteType: String?): InstrumentType? = when (quoteType) {
    "EQUITY" -> InstrumentType.STOCK
    "ETF" -> InstrumentType.ETF
    "MUTUALFUND" -> InstrumentType.FUND
    else -> null
}

/**
 * Converts a Yahoo quote [value] in [currencyCode] to a [Price], or `null` when the currency is not one of the
 * app's [Currency] values or the number is unusable. Keeps four decimals of the quoted unit; pence (`GBp`/`GBX`)
 * become pounds. This and [Price.fromDouble] are the only places a JSON `Double` becomes money.
 */
fun yahooPrice(value: Double, currencyCode: String?): Price? {
    val (currency, divisor) = when {
        currencyCode == null -> return null
        currencyCode in PENCE_CODES -> Currency.GBP to PENCE_PER_POUND
        else -> (Currency.entries.firstOrNull { it.code == currencyCode.uppercase() } ?: return null) to 1L
    }
    val quoted = try {
        Price.fromDouble(value, currency)
    } catch (_: IllegalArgumentException) {
        return null
    }
    // A multiple of YAHOO_PRICE_STEP (= PENCE_PER_POUND), so dividing pence into pounds stays exact.
    val denoised = roundHalfEven(quoted.scaled, YAHOO_PRICE_STEP) * YAHOO_PRICE_STEP
    return Price(denoised / divisor, currency)
}

/** Runs [block] (parsing/mapping a provider payload) and turns any malformed-data failure into the domain error. */
internal inline fun <T> parsingQuotes(source: String, block: () -> T): T = try {
    block()
} catch (failure: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException; so are the JsonElement accessors.
    throw QuoteUnavailableException("Unexpected $source response", failure)
}

/** A yearly ongoing charge above this percentage is a data error, not a fund. */
private const val MAX_ONGOING_CHARGE_PERCENT = 100.0

/**
 * Converts a Morningstar ongoing charge ([percent], e.g. `0.18` for 0.18 %) to its fixed-point form (`1_800`), or
 * `null` when it is not a finite percentage between 0 and 100. The only `Double` site of the fundamentals.
 */
fun ongoingChargePercentScaled(percent: Double): Long? {
    if (!percent.isFinite() || percent !in 0.0..MAX_ONGOING_CHARGE_PERCENT) return null
    return round(percent * PERCENT_SCALE).toLong()
}

/** The SRRI class for a Morningstar value (`6`, `6.0`), or `null` when it is not a whole number from 1 to 7. */
fun riskLevelOf(value: Double): Int? {
    val level = value.toInt()
    return level.takeIf { value == level.toDouble() && it in MIN_RISK_LEVEL..MAX_RISK_LEVEL }
}

private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** What the screener row says about the product, or `null` when none of its four datapoints is usable. */
internal fun MorningstarScreenerRow.toFundamentals(): InstrumentFundamentals? = InstrumentFundamentals(
    ongoingChargePercentScaled = ongoingCharge.number()?.let(::ongoingChargePercentScaled),
    riskLevel = collectedSrri.number()?.let(::riskLevelOf),
    category = normalizeFundamentalsText(categoryName.text()),
    managementCompany = normalizeFundamentalsText(brandingCompanyName.text()),
).takeUnless { it.isEmpty }

/**
 * Daily NAVs the way rumbo keeps them (`mover_fin_de_semana`): a point dated on a Saturday or a Sunday belongs to the
 * previous Friday (money-market funds publish Friday's NAV dated on the weekend), but only when that Friday has no
 * point of its own; among several weekend points for the same Friday the earliest wins. The result is ascending by
 * date with one point per day.
 */
internal fun List<PricePoint>.weekendsMovedToFriday(): List<PricePoint> {
    val byDay = filterNot { it.date.dayOfWeek.isWeekend() }.associateByTo(mutableMapOf()) { it.date }
    sortedBy { it.date }
        .filter { it.date.dayOfWeek.isWeekend() }
        .forEach { point ->
            val friday = point.date.minus(if (point.date.dayOfWeek == DayOfWeek.SATURDAY) 1 else 2, DateTimeUnit.DAY)
            byDay.getOrPut(friday) { point.copy(date = friday) }
        }
    return byDay.values.sortedBy { it.date }
}

private fun DayOfWeek.isWeekend(): Boolean = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY
