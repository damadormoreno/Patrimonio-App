package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.domain.calc.eurSeriesById
import com.denebapps.patrimonio.domain.calc.quotedRefs
import com.denebapps.patrimonio.domain.calc.toEurSeries
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortfolioSeriesLoaderTest {
    private val retirement = "retirement"
    private val kids = "kids"
    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000FUND")
    private val stockRef = InstrumentRef(QuoteProvider.YAHOO, "ACME")
    private val newRef = InstrumentRef(QuoteProvider.YAHOO, "NEW")

    private fun quoted(id: String, portfolioId: String, ref: InstrumentRef, currency: Currency) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = "Holding $id",
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(ref, currency, Units(1_000_000)),
    )

    private fun manual(id: String, portfolioId: String) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = "Holding $id",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(100_000), Currency.EUR), 1L),
    )

    private fun series(currency: Currency, vararg prices: Double) = prices.mapIndexed { index, price ->
        PricePoint(LocalDate(2026, 10, 5 + index), Price.fromDouble(price, currency))
    }

    private val fund = quoted("fund", retirement, fundRef, Currency.EUR)
    private val stock = quoted("stock", kids, stockRef, Currency.USD)
    private val fresh = quoted("fresh", retirement, newRef, Currency.EUR)
    private val art = manual("art", retirement)

    private val fundSeries = series(Currency.EUR, 100.0, 101.0, 102.0)
    private val stockSeries = series(Currency.USD, 50.0, 51.0, 52.0)
    private val usdRates = FxHistory(
        mapOf(Currency.USD to (5..9).map { DatedFxRate(LocalDate(2026, 10, it), 900_000L) }),
    )
    private val rates = FxRates(mapOf(Currency.USD to 920_000L))

    private val history = FakeInstrumentHistoryRepository().apply {
        set(fundRef, fundSeries)
        set(stockRef, stockSeries)
    }
    private val loader = PortfolioSeriesLoaderImpl(
        portfolios = FakePortfolioRepository(listOf(fund, stock, fresh, art)),
        history = history,
        fxHistory = FakeFxHistoryRepository(usdRates),
        fx = FakeFxRepository(rates),
    )

    @Test
    fun `a portfolio loads its own holdings with their histories converted to EUR`() = runTest {
        val data = loader.load(kids)

        assertEquals(listOf(stock), data.holdings)
        assertEquals(mapOf("stock" to toEurSeries(stockSeries, usdRates)), data.eurSeries)
        assertEquals(rates, data.rates)
    }

    @Test
    fun `all portfolios load every holding, quoted ones with an entry even when never downloaded`() = runTest {
        val data = loader.load(null)

        assertEquals(listOf(fund, stock, fresh, art), data.holdings)
        assertEquals(setOf("fund", "stock", "fresh"), data.eurSeries.keys)
        assertEquals(fundSeries, data.eurSeries.getValue("fund"))
        assertEquals(emptyList(), data.eurSeries.getValue("fresh"))
        assertNull(data.eurSeries["art"])
    }

    @Test
    fun `loading downloads nothing`() = runTest {
        loader.load(null)

        assertEquals(emptyList(), history.syncCalls)
        assertEquals(emptyList(), history.syncAllCalls)
    }

    @Test
    fun `the series match what the portfolio detail converts from the same histories`() = runTest {
        val data = loader.load(null)

        val histories = mapOf("fund" to fundSeries, "stock" to stockSeries, "fresh" to emptyList())
        assertEquals(eurSeriesById(histories, usdRates), data.eurSeries)
        assertEquals(histories.mapValues { (_, points) -> toEurSeries(points, usdRates) }, data.eurSeries)
    }

    @Test
    fun `quotedRefs lists the instrument of every quoted holding by id`() {
        assertEquals(
            mapOf("fund" to fundRef, "stock" to stockRef, "fresh" to newRef),
            listOf(fund, stock, fresh, art).quotedRefs(),
        )
    }
}
