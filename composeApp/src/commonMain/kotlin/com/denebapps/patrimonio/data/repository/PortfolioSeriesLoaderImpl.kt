package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.domain.calc.eurSeriesById
import com.denebapps.patrimonio.domain.calc.quotedRefs
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.InstrumentHistoryRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.PortfolioScopeSeries
import com.denebapps.patrimonio.domain.repository.PortfolioSeriesLoader
import kotlinx.coroutines.flow.first

/**
 * [PortfolioSeriesLoader] over the repositories the portfolio detail screen observes: the stored history of every
 * quoted holding of the scope, converted to EUR with the stored exchange-rate history (the same conversion the detail
 * screen applies, see `eurSeriesById`).
 */
class PortfolioSeriesLoaderImpl(
    private val portfolios: PortfolioRepository,
    private val history: InstrumentHistoryRepository,
    private val fxHistory: FxHistoryRepository,
    private val fx: FxRepository,
) : PortfolioSeriesLoader {
    override suspend fun load(portfolioId: String?): PortfolioScopeSeries {
        val holdings = portfolios.observeAll().first().filter { portfolioId == null || it.portfolioId == portfolioId }
        val histories = holdings.quotedRefs().mapValues { (_, ref) -> history.observe(ref).first() }
        return PortfolioScopeSeries(
            holdings = holdings,
            eurSeries = eurSeriesById(histories, fxHistory.observe().first()),
            rates = fx.observeRates().first(),
        )
    }
}
