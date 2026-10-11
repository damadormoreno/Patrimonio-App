package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.calc.roundHalfEven
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.serialization.json.Json

private val ISIN_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{9}\\d$")

/** Yahoo quotes London shares in pence under these currency codes. */
private val PENCE_CODES = setOf("GBp", "GBX")
private const val PENCE_PER_POUND = 100L

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
