package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/**
 * One position of the investment portfolio. The identity shared by every position lives here; how it is valued is
 * the [valuation]: priced by a quote source ([HoldingValuation.Quoted]) or entered by hand ([HoldingValuation.Manual]).
 * Ids are random UUID strings, like the rest of the app. [broker] is the optional bank or broker that manages the
 * position (free text, kept as typed); it is a property of the holding, whichever way it is valued. So are the
 * product's [fundamentals] (cost, risk, category, manager), which a data provider supplies and which stay `null` when
 * it knows nothing about the product.
 */
data class PortfolioHolding(
    val id: String,
    val name: String,
    val type: InstrumentType,
    val isin: String?,
    val ticker: String?,
    val valuation: HoldingValuation,
    val broker: String? = null,
    val fundamentals: InstrumentFundamentals? = null,
) {
    init {
        require(id.isNotBlank()) { "holding id must not be blank" }
        require(name.isNotBlank()) { "holding name must not be blank" }
        require(broker == null || normalizeBroker(broker) == broker) {
            "holding broker must be trimmed, not blank and at most $MAX_BROKER_LENGTH characters"
        }
    }
}

/** The longest bank or broker name a holding accepts, after trimming. */
const val MAX_BROKER_LENGTH = 60

/**
 * The bank or broker as it is stored: trimmed, `null` when nothing is left, casing untouched.
 *
 * @throws IllegalArgumentException when the trimmed text is longer than [MAX_BROKER_LENGTH].
 */
fun normalizeBroker(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    require(trimmed.length <= MAX_BROKER_LENGTH) { "broker must have at most $MAX_BROKER_LENGTH characters" }
    return trimmed.ifEmpty { null }
}

/**
 * How a holding is valued. A sealed hierarchy instead of nullable columns on the holding: a quoted position can never
 * carry a manual value and the other way round, and `when` stays exhaustive for every caller.
 */
sealed interface HoldingValuation {
    /**
     * Valued as [units] x the latest price of [ref]. [currency] is the currency the provider quotes it in; it is fixed
     * when the holding is added and a refresh never changes it. [lastQuote] is `null` until the first successful
     * fetch, and keeps the last known price when a later fetch fails.
     */
    data class Quoted(
        val ref: InstrumentRef,
        val currency: Currency,
        val units: Units,
        val lastQuote: LastQuote? = null,
    ) : HoldingValuation {
        init {
            require(lastQuote == null || lastQuote.price.currency == currency) {
                "last price is in ${lastQuote?.price?.currency}, the holding is in $currency"
            }
        }
    }

    /** A value the user typed in ([value], non-negative) at [valuedAtEpochMs], for instruments no source quotes. */
    data class Manual(val value: CurrencyAmount, val valuedAtEpochMs: Long) : HoldingValuation {
        init {
            require(value.amount.minorUnits >= 0) { "manual value must not be negative, was ${value.amount}" }
        }
    }
}

/**
 * The last price fetched for a quoted holding: [price] as of the provider's day [date], retrieved at
 * [fetchedAtEpochMs] (which drives the staleness check, unlike [date], which can lag behind on weekends).
 */
data class LastQuote(val price: Price, val date: LocalDate, val fetchedAtEpochMs: Long)
