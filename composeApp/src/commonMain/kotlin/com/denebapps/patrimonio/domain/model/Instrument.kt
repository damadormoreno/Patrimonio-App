package com.denebapps.patrimonio.domain.model

/** Kind of tradable product a holding can track. */
enum class InstrumentType { FUND, ETF, STOCK, OTHER }

/** Public data source that quotes an instrument. */
enum class QuoteProvider { MORNINGSTAR, YAHOO }

/**
 * Provider-scoped identifier of an instrument: a Morningstar SecId (e.g. `0P00012PP6`) when [provider] is
 * [QuoteProvider.MORNINGSTAR], or a Yahoo Finance symbol (e.g. `IWDA.AS`) when it is [QuoteProvider.YAHOO].
 */
data class InstrumentRef(val provider: QuoteProvider, val code: String)

/**
 * A search result the user can pick. [currency] is `null` when the provider's search does not expose it (Yahoo):
 * the real currency arrives with the first [Quote]. [fundamentals] is what the search already says about the product
 * (Morningstar's screener rows carry it); `null` for providers that do not.
 */
data class InstrumentCandidate(
    val ref: InstrumentRef,
    val name: String,
    val isin: String?,
    val ticker: String?,
    val type: InstrumentType,
    val currency: Currency?,
    val exchange: String?,
    val fundamentals: InstrumentFundamentals? = null,
)
