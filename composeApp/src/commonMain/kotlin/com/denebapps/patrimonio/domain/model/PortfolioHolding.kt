package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/**
 * One position of the investment portfolio. The identity shared by every position lives here; how it is valued is
 * the [valuation]: priced by a quote source ([HoldingValuation.Quoted]) or entered by hand ([HoldingValuation.Manual]).
 * Ids are random UUID strings, like the rest of the app.
 */
data class PortfolioHolding(
    val id: String,
    val name: String,
    val type: InstrumentType,
    val isin: String?,
    val ticker: String?,
    val valuation: HoldingValuation,
) {
    init {
        require(id.isNotBlank()) { "holding id must not be blank" }
        require(name.isNotBlank()) { "holding name must not be blank" }
    }
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
