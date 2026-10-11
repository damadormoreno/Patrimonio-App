package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.PricePoint
import kotlinx.datetime.LocalDate

/**
 * Port to the daily price history a public market-data source keeps. Separate from [QuoteSource] because only the
 * history cache needs it, while the portfolio and the add form only need the latest price. Like [QuoteSource],
 * implementations MUST surface every failure as [QuoteUnavailableException].
 */
interface PriceHistorySource {
    /**
     * One price per calendar day of [ref], ascending by date, from [from] (inclusive) to today; everything the provider
     * has when [from] is `null`. An empty list means "the provider has nothing in that window".
     */
    suspend fun priceHistory(ref: InstrumentRef, from: LocalDate?): List<PricePoint>
}
