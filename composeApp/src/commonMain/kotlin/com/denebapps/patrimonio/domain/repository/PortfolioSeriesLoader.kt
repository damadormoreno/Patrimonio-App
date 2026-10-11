package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PricePoint

/**
 * The holdings of a portfolio scope with what is needed to compare a product with them: [eurSeries] holds, per quoted
 * holding id, its cached price history converted to EUR (empty while it was never downloaded; manual holdings have no
 * entry), and [rates] are today's exchange rates.
 */
data class PortfolioScopeSeries(
    val holdings: List<PortfolioHolding>,
    val eurSeries: Map<String, List<PricePoint>>,
    val rates: FxRates,
)

/** Reads, once, the cached data of a portfolio scope the way the portfolio detail screen reads it. */
interface PortfolioSeriesLoader {
    /** The data of portfolio [portfolioId], or of every portfolio when it is `null`. Downloads nothing. */
    suspend fun load(portfolioId: String?): PortfolioScopeSeries
}
