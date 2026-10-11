package com.denebapps.patrimonio.domain.model

/**
 * What the preview of an [InstrumentCandidate] shows, downloaded for the preview only (never stored): the daily
 * [history] in the product's [currency], the same series converted to EUR ([eurHistory], days without an exchange
 * rate dropped) and the [fundamentals] the search already carried (`null` for providers that have none).
 */
data class InstrumentPreviewData(
    val candidate: InstrumentCandidate,
    val currency: Currency,
    val history: List<PricePoint>,
    val eurHistory: List<PricePoint>,
    val fundamentals: InstrumentFundamentals?,
)
