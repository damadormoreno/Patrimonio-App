package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.repository.FundamentalsSyncFailure
import com.denebapps.patrimonio.domain.repository.FundamentalsSyncResult
import com.denebapps.patrimonio.domain.repository.HistorySyncFailure
import com.denebapps.patrimonio.domain.repository.HistorySyncResult
import com.denebapps.patrimonio.domain.repository.HistoryUnavailableException
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FxSyncCall
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.math.roundToLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val rates = FxRates(mapOf(Currency.USD to 920_000L))
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-11T12:00:00Z")
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000FUND")
    private val etfRef = InstrumentRef(QuoteProvider.YAHOO, "ETFM")
    private val stockRef = InstrumentRef(QuoteProvider.YAHOO, "ACME")
    private val bondRef = InstrumentRef(QuoteProvider.YAHOO, "BOND")

    private fun quoted(
        id: String,
        name: String,
        ref: InstrumentRef,
        type: InstrumentType,
        currency: Currency,
        lastPriceScaled: Long,
        units: Units = Units(10_000_000),
        broker: String? = null,
        fundamentals: InstrumentFundamentals? = null,
    ) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = name,
        type = type,
        isin = null,
        ticker = id.uppercase(),
        valuation = HoldingValuation.Quoted(
            ref = ref,
            currency = currency,
            units = units,
            lastQuote = LastQuote(Price(lastPriceScaled, currency), LocalDate(2026, 10, 9), fetchedAtEpochMs = 1L),
        ),
        broker = broker,
        fundamentals = fundamentals,
    )

    // 10 units at 245,12 € = 2.451,20 €; TER 0,18 %, risk 5.
    private val fund = quoted(
        "fund",
        "Fondo Global",
        fundRef,
        InstrumentType.FUND,
        Currency.EUR,
        245_120_000,
        broker = "MyInvestor",
        fundamentals = InstrumentFundamentals(ongoingChargePercentScaled = 1_800, riskLevel = 5),
    )

    // 20 units at 50,00 € = 1.000,00 €; no fundamentals.
    private val etf = quoted(
        "etf",
        "ETF Mundial",
        etfRef,
        InstrumentType.ETF,
        Currency.EUR,
        50_000_000,
        units = Units(20_000_000),
        broker = "MyInvestor",
    )

    // 10 shares at 200,00 $ = 2.000,00 $ = 1.840,00 €; TER 0,5 %, risk 6.
    private val stock = quoted(
        "stock",
        "Acme Corp",
        stockRef,
        InstrumentType.STOCK,
        Currency.USD,
        200_000_000,
        fundamentals = InstrumentFundamentals(ongoingChargePercentScaled = 5_000, riskLevel = 6),
    )

    // Quoted, but never given a history.
    private val bond = quoted("bond", "Bono sin histórico", bondRef, InstrumentType.OTHER, Currency.EUR, 100_000_000)

    private fun manual(euros: Long) = PortfolioHolding(
        id = "art",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Cuadro",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(euros * 100), Currency.EUR), 1_791_633_600_000L),
    )

    private val art = manual(2_500)

    // ---- histories: weekly Fridays, built so the correlations are known exactly --------------------------------

    private val lastFriday = LocalDate(2026, 10, 9)

    private fun week(date: LocalDate) = date.toEpochDays() / 7

    /** +1, -1, +1, -1 ... by week. */
    private fun s2(w: Int) = if (w % 2 == 0) 1.0 else -1.0

    /** +1, +1, -1, -1 ... by week: orthogonal to [s2] over any four consecutive weeks. */
    private fun s4(w: Int) = if ((w / 2) % 2 == 0) 1.0 else -1.0

    /** Weekly returns of the fund: +-2 %. */
    private fun returnsA(w: Int) = 0.02 * s2(w)

    /** Weekly returns of the ETF: 0,894 correlated with the fund and 0,447 with the stock. */
    private fun returnsB(w: Int) = 0.02 * (2 * s2(w) + s4(w))

    /** Weekly returns of the stock: uncorrelated with the fund. */
    private fun returnsC(w: Int) = 0.02 * s4(w)

    /** [count] Friday closes ending [endWeeksBack] weeks before the last Friday, compounding [returnOf] per week. */
    private fun series(
        count: Int,
        currency: Currency,
        endWeeksBack: Int = 0,
        returnOf: (Int) -> Double,
    ): List<PricePoint> {
        var price = 100.0
        return (count - 1 downTo 0).mapIndexed { index, offset ->
            val date = lastFriday.minus((endWeeksBack + offset) * 7, DateTimeUnit.DAY)
            if (index > 0) price *= 1 + returnOf(week(date))
            PricePoint(date, Price((price * 1_000_000).roundToLong(), currency))
        }
    }

    private val fundSeries = series(81, Currency.EUR, returnOf = ::returnsA)
    private val etfSeries = series(81, Currency.EUR, returnOf = ::returnsB)
    private val stockSeries = series(81, Currency.USD, returnOf = ::returnsC)

    private val usdHistory = FxHistory(
        mapOf(
            Currency.USD to (80 downTo 0).map { DatedFxRate(lastFriday.minus(it * 7, DateTimeUnit.DAY), 920_000L) },
        ),
    )

    private class Setup(
        val portfolio: FakePortfolioRepository,
        val portfolios: FakePortfolioCollectionRepository,
        val history: FakeInstrumentHistoryRepository,
        val fx: FakeFxHistoryRepository,
        val vm: PortfolioDetailViewModel,
    )

    private fun setup(
        holdings: List<PortfolioHolding> = listOf(fund, etf, stock, art),
        portfolioId: String? = null,
        portfolios: List<Portfolio>? = null,
        histories: Map<InstrumentRef, List<PricePoint>> = mapOf(
            fundRef to fundSeries,
            etfRef to etfSeries,
            stockRef to stockSeries,
        ),
        fxHistory: FxHistory = usdHistory,
    ): Setup {
        val portfolio = FakePortfolioRepository(holdings)
        val history = FakeInstrumentHistoryRepository().apply {
            histories.forEach { (ref, points) ->
                set(
                    ref,
                    points,
                )
            }
        }
        val fx = FakeFxHistoryRepository(fxHistory)
        val collection = portfolios?.let { FakePortfolioCollectionRepository(it, portfolio) }
            ?: FakePortfolioCollectionRepository(holdings = portfolio)
        val vm = PortfolioDetailViewModel(
            portfolioId = portfolioId,
            portfolios = collection,
            portfolioRepository = portfolio,
            movementRepository = FakeMovementRepository(portfolio),
            fxRepository = FakeFxRepository(rates),
            fxHistoryRepository = fx,
            historyRepository = history,
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
        return Setup(portfolio, collection, history, fx, vm)
    }

    private fun TestScope.collect(vm: PortfolioDetailViewModel): Job = launch { vm.state.collect {} }

    private fun TestScope.open(s: Setup): Job {
        val job = collect(s.vm)
        advanceUntilIdle()
        return job
    }

    // ---- one portfolio or all of them ----------------------------------------------------------------------------

    private val twoPortfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("pf-hijos", "Hijos", 1))

    // Mi cartera: the fund and the ETF (3.451,20 €); Hijos: the stock and the painting (4.340,00 €).
    private val split = listOf(fund, etf, stock.copy(portfolioId = "pf-hijos"), art.copy(portfolioId = "pf-hijos"))

    @Test
    fun `all the portfolios are titled Tu cartera and add up everything`() = runTest(dispatcher) {
        val s = setup(holdings = split, portfolios = twoPortfolios)
        val job = open(s)

        val header = s.vm.state.value.header
        assertEquals("Tu cartera", header.title)
        assertEquals("7.791,20 €", header.totalText)
        assertEquals("4 productos", header.productsText)
        job.cancel()
    }

    @Test
    fun `a portfolio is titled with its name and shows only its holdings`() = runTest(dispatcher) {
        val s = setup(holdings = split, portfolios = twoPortfolios, portfolioId = "pf-hijos")
        val job = open(s)

        val state = s.vm.state.value
        assertEquals("Hijos", state.header.title)
        assertEquals("4.340,00 €", state.header.totalText)
        assertEquals("2 productos", state.header.productsText)
        assertEquals("1 con cotización · 1 manual", state.header.mixText)
        job.cancel()
    }

    @Test
    fun `the analysis of a portfolio only counts its own holdings`() = runTest(dispatcher) {
        val s = setup(holdings = split, portfolios = twoPortfolios, portfolioId = TEST_PORTFOLIO_ID)
        val job = open(s)

        val state = s.vm.state.value
        assertEquals("3.451,20 €", state.header.totalText)
        // The largest holding of Mi cartera is the fund, not the (larger) painting of the other portfolio.
        assertEquals("Fondo Global", state.concentration?.name)
        assertEquals(listOf("Fondo", "ETF"), state.allocations.first().rows.map { it.label })
        assertEquals(HistoryStatus.AVAILABLE, state.historyStatus)
        job.cancel()
    }

    @Test
    fun `a portfolio of manual holdings has no history to draw`() = runTest(dispatcher) {
        val s = setup(
            holdings = listOf(art.copy(portfolioId = "pf-hijos"), fund),
            portfolios = twoPortfolios,
            portfolioId = "pf-hijos",
        )
        val job = open(s)

        assertEquals(HistoryStatus.NOT_APPLICABLE, s.vm.state.value.historyStatus)
        assertEquals("1 producto", s.vm.state.value.header.productsText)
        job.cancel()
    }

    @Test
    fun `an empty portfolio is loaded with a zero total`() = runTest(dispatcher) {
        val s = setup(
            holdings = listOf(fund),
            portfolios = twoPortfolios + Portfolio("pf-vacia", "Vacía", 2),
            portfolioId = "pf-vacia",
        )
        val job = open(s)

        val state = s.vm.state.value
        assertTrue(state.loaded)
        assertEquals("Vacía", state.header.title)
        assertEquals("0,00 €", state.header.totalText)
        assertEquals("Sin productos", state.header.productsText)
        job.cancel()
    }

    @Test
    fun `the title follows a rename`() = runTest(dispatcher) {
        val s = setup(holdings = split, portfolios = twoPortfolios, portfolioId = "pf-hijos")
        val job = open(s)

        s.portfolios.renamePortfolio("pf-hijos", "Los peques")
        advanceUntilIdle()

        assertEquals("Los peques", s.vm.state.value.header.title)
        job.cancel()
    }

    // ---- header ----------------------------------------------------------------------------------------------

    @Test
    fun `the header has the total, the number of products and how many are quoted or manual`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val state = s.vm.state.value
        assertTrue(state.loaded)
        assertEquals("7.791,20 €", state.header.totalText)
        assertEquals("4 productos", state.header.productsText)
        assertEquals("3 con cotización · 1 manual", state.header.mixText)
        job.cancel()
    }

    @Test
    fun `before anything is loaded the state is empty`() = runTest(dispatcher) {
        val s = setup()

        assertFalse(s.vm.state.value.loaded)
    }

    // ---- evolution with the current composition ---------------------------------------------------------------

    @Test
    fun `the evolution chart is the value of today's units at past prices`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val state = s.vm.state.value
        assertEquals(HistoryStatus.AVAILABLE, state.historyStatus)
        val evolution = assertNotNull(state.evolution)
        assertEquals("Valor de tus participaciones actuales a precios pasados", evolution.subtitle)
        // 10 fund units + 20 ETF units + 10 shares at 0,92 EUR/USD, on the last Friday.
        val expected = (
            10.0 * fundSeries.last().price.scaled + 20.0 * etfSeries.last().price.scaled +
                10.0 * stockSeries.last().price.scaled * 0.92
            ) / 1_000_000
        assertEquals(expected, evolution.chart.points.last().value, 0.02)
        assertEquals(HistoryRange.ONE_YEAR, evolution.ranges.single { it.selected }.range)
        assertEquals(52, evolution.chart.prices.size)
        assertEquals("17/10/2025", evolution.chart.firstDateText)
        assertEquals("09/10/2026", evolution.chart.lastDateText)
        assertTrue(evolution.chart.prices.all { it.price.currency == Currency.EUR })
        assertNotNull(evolution.chart.changeText)
        job.cancel()
    }

    @Test
    fun `the holdings left out of the evolution are named`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        assertEquals("Excluye: Cuadro", s.vm.state.value.evolution?.excludedText)
        job.cancel()
    }

    @Test
    fun `a quoted holding without history is left out too`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund, etf, bond, stock, art))
        val job = open(s)

        assertEquals("Excluye: Bono sin histórico, Cuadro", s.vm.state.value.evolution?.excludedText)
        job.cancel()
    }

    @Test
    fun `nothing is left out when every holding has a history`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund, etf, stock))
        val job = open(s)

        assertNull(s.vm.state.value.evolution?.excludedText)
        job.cancel()
    }

    @Test
    fun `a holding in another currency is left out of the evolution until its exchange rates are there`() =
        runTest(dispatcher) {
            val s = setup(fxHistory = FxHistory(emptyMap()))
            val job = open(s)

            assertEquals("Excluye: Acme Corp, Cuadro", s.vm.state.value.evolution?.excludedText)
            job.cancel()
        }

    @Test
    fun `picking another range redraws the chart`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onRangeSelect(HistoryRange.SIX_MONTHS)
        advanceUntilIdle()
        assertEquals(HistoryRange.SIX_MONTHS, s.vm.state.value.evolution?.ranges?.single { it.selected }?.range)
        assertEquals(26, s.vm.state.value.evolution?.chart?.prices?.size)

        s.vm.onRangeSelect(HistoryRange.MAX)
        advanceUntilIdle()
        assertEquals(81, s.vm.state.value.evolution?.chart?.prices?.size)
        assertEquals("28/03/2025", s.vm.state.value.evolution?.chart?.firstDateText)
        job.cancel()
    }

    @Test
    fun `the returns per year and the risk are those of the composition series`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val evolution = assertNotNull(s.vm.state.value.evolution)
        assertEquals(listOf(2025, 2026), evolution.yearReturns.map { it.year })
        assertEquals(listOf("28/03", null), evolution.yearReturns.map { it.partialSince })
        assertEquals(listOf(false, true), evolution.yearReturns.map { it.yearToDate })
        assertTrue(evolution.risk.volatilityLabel.startsWith("Volatilidad ("))
        assertTrue(evolution.risk.volatilityText.endsWith(" %"))
        assertTrue(evolution.risk.drawdownText.startsWith("−"))
        assertNotNull(evolution.risk.drawdownDates)
        job.cancel()
    }

    @Test
    fun `with only manual holdings there is no chart and nothing to download`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(art), histories = emptyMap())
        val job = open(s)

        val state = s.vm.state.value
        assertTrue(state.loaded)
        assertEquals(HistoryStatus.NOT_APPLICABLE, state.historyStatus)
        assertNull(state.evolution)
        assertNull(state.correlation)
        assertNull(state.note)
        assertEquals("2.500,00 €", state.header.totalText)
        assertEquals("Manual", state.header.mixText)
        assertTrue(s.history.syncAllCalls.isEmpty())
        assertTrue(s.fx.syncCalls.isEmpty())
        assertTrue(s.portfolio.fundamentalsCalls.isEmpty())
        assertFalse(state.isSyncing)
        job.cancel()
    }

    @Test
    fun `while the first download runs the chart area is loading, and when it ends without data unavailable`() =
        runTest(dispatcher) {
            val s = setup(holdings = listOf(fund, etf), histories = emptyMap())
            val gate = CompletableDeferred<Unit>()
            s.history.syncGate = gate
            val job = open(s)

            assertEquals(HistoryStatus.LOADING, s.vm.state.value.historyStatus)
            assertTrue(s.vm.state.value.isSyncing)
            assertNull(s.vm.state.value.evolution)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(HistoryStatus.UNAVAILABLE, s.vm.state.value.historyStatus)
            assertFalse(s.vm.state.value.isSyncing)
            assertNull(s.vm.state.value.correlation)
            job.cancel()
        }

    @Test
    fun `a single point of history cannot be drawn`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund), histories = mapOf(fundRef to fundSeries.takeLast(1)))
        val job = open(s)

        assertEquals(HistoryStatus.UNAVAILABLE, s.vm.state.value.historyStatus)
        assertNull(s.vm.state.value.evolution)
        job.cancel()
    }

    // ---- correlation ------------------------------------------------------------------------------------------

    @Test
    fun `the correlation matrix has a row and a column per quoted holding with history`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val matrix = assertNotNull(s.vm.state.value.correlation?.matrix)
        assertEquals(listOf("Fondo Global", "ETF Mundial", "Acme Corp"), matrix.names)
        assertEquals(listOf("FG", "EM", "AC"), matrix.labels)
        assertEquals(
            listOf(
                listOf("1,00", "0,89", "0,00"),
                listOf("0,89", "1,00", "0,45"),
                listOf("0,00", "0,45", "1,00"),
            ),
            matrix.cells.map { row -> row.map { it.valueText } },
        )
        assertEquals(1.0, matrix.cells[1][1].value)
        assertEquals(0.8944, assertNotNull(matrix.cells[0][1].value), 0.001)
        assertEquals(matrix.cells[0][1].value, matrix.cells[1][0].value)
        job.cancel()
    }

    @Test
    fun `a cell says which pair it is and over how many weeks`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val matrix = assertNotNull(s.vm.state.value.correlation?.matrix)
        assertEquals("Fondo Global y ETF Mundial · 80 semanas", matrix.cells[0][1].detailText)
        assertEquals("ETF Mundial y Acme Corp · 80 semanas", matrix.cells[1][2].detailText)
        assertNull(matrix.cells[0][0].detailText)
        job.cancel()
    }

    @Test
    fun `the explanation tells how to read the matrix`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        assertEquals(
            "Correlación de las rentabilidades semanales en euros (últimos 3 años). " +
                "Cerca de 1: se mueven igual; cerca de 0 o negativa: diversifican.",
            s.vm.state.value.correlation?.matrix?.explanation,
        )
        job.cancel()
    }

    @Test
    fun `the most correlated pair is highlighted`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        assertEquals(
            "Más parecidos: Fondo Global y ETF Mundial (0,89)",
            s.vm.state.value.correlation?.matrix?.mostSimilarText,
        )
        job.cancel()
    }

    @Test
    fun `a negative correlation is shown with its sign and is not the most similar pair`() = runTest(dispatcher) {
        val opposite = series(81, Currency.EUR) { -returnsA(it) }
        val s = setup(
            holdings = listOf(fund, etf),
            histories = mapOf(fundRef to fundSeries, etfRef to opposite),
        )
        val job = open(s)

        val matrix = assertNotNull(s.vm.state.value.correlation?.matrix)
        assertEquals("−1,00", matrix.cells[0][1].valueText)
        assertEquals("Más parecidos: Fondo Global y ETF Mundial (−1,00)", matrix.mostSimilarText)
        job.cancel()
    }

    @Test
    fun `a pair without enough common weeks has a dash and says so`() = runTest(dispatcher) {
        // The ETF has the last 50 weeks, the stock the first 50: they share 18 weekly returns, the fund overlaps both.
        val recent = series(50, Currency.EUR, returnOf = ::returnsB)
        val old = series(50, Currency.EUR, endWeeksBack = 31, returnOf = ::returnsC)
        val oldRef = InstrumentRef(QuoteProvider.YAHOO, "OLD")
        val older = quoted("old", "Bono Corto", oldRef, InstrumentType.OTHER, Currency.EUR, 100_000_000)
        val s = setup(
            holdings = listOf(fund, etf, older),
            histories = mapOf(fundRef to fundSeries, etfRef to recent, oldRef to old),
        )
        val job = open(s)

        val matrix = assertNotNull(s.vm.state.value.correlation?.matrix)
        assertEquals(3, matrix.cells.size)
        assertEquals("—", matrix.cells[1][2].valueText)
        assertNull(matrix.cells[1][2].value)
        assertEquals("ETF Mundial y Bono Corto · sin datos suficientes · 18 semanas", matrix.cells[1][2].detailText)
        assertEquals("—", matrix.cells[2][1].valueText)
        assertTrue(matrix.cells[0][1].valueText != "—")
        job.cancel()
    }

    @Test
    fun `a holding with too little history is left out of the matrix and named`() = runTest(dispatcher) {
        val short = series(10, Currency.USD, returnOf = ::returnsC)
        val s = setup(
            holdings = listOf(fund, etf, stock),
            histories = mapOf(fundRef to fundSeries, etfRef to etfSeries, stockRef to short),
        )
        val job = open(s)

        val correlation = assertNotNull(s.vm.state.value.correlation)
        assertEquals(listOf("Fondo Global", "ETF Mundial"), correlation.matrix?.names)
        assertEquals("Sin histórico suficiente: Acme Corp", correlation.omittedText)
        job.cancel()
    }

    @Test
    fun `a holding in another currency without exchange rates is not in the matrix`() = runTest(dispatcher) {
        val s = setup(fxHistory = FxHistory(emptyMap()))
        val job = open(s)

        val correlation = assertNotNull(s.vm.state.value.correlation)
        assertEquals(listOf("Fondo Global", "ETF Mundial"), correlation.matrix?.names)
        assertEquals("Sin histórico suficiente: Acme Corp", correlation.omittedText)
        job.cancel()
    }

    @Test
    fun `every holding is in the matrix when all qualify`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        assertNull(s.vm.state.value.correlation?.omittedText)
        assertNull(s.vm.state.value.correlation?.message)
        job.cancel()
    }

    @Test
    fun `with a single quoted holding there is no matrix, only the reason`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund, art), histories = mapOf(fundRef to fundSeries))
        val job = open(s)

        val correlation = assertNotNull(s.vm.state.value.correlation)
        assertNull(correlation.matrix)
        assertEquals("Necesitas al menos dos productos con histórico para ver la correlación.", correlation.message)
        job.cancel()
    }

    @Test
    fun `when fewer than two holdings share enough history there is no matrix`() = runTest(dispatcher) {
        val short = series(10, Currency.EUR, returnOf = ::returnsC)
        val s = setup(
            holdings = listOf(fund, etf),
            histories = mapOf(fundRef to fundSeries, etfRef to short),
        )
        val job = open(s)

        val correlation = assertNotNull(s.vm.state.value.correlation)
        assertNull(correlation.matrix)
        assertEquals("Necesitas al menos dos productos con histórico para ver la correlación.", correlation.message)
        assertNull(correlation.omittedText)
        job.cancel()
    }

    // ---- allocation -------------------------------------------------------------------------------------------

    private fun AllocationRowUi.summary() = Triple(label, amountText, shareText)

    @Test
    fun `the portfolio is split by type, by bank or broker and by currency`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val groups = s.vm.state.value.allocations
        assertEquals(listOf("Por tipo", "Por banco o bróker", "Por divisa"), groups.map { it.title })
        assertEquals(
            listOf(
                Triple("Otro", "2.500,00 €", "32,1 %"),
                Triple("Fondo", "2.451,20 €", "31,5 %"),
                Triple("Acción", "1.840,00 €", "23,6 %"),
                Triple("ETF", "1.000,00 €", "12,8 %"),
            ),
            groups[0].rows.map { it.summary() },
        )
        assertEquals(
            listOf(
                Triple("Sin banco", "4.340,00 €", "55,7 %"),
                Triple("MyInvestor", "3.451,20 €", "44,3 %"),
            ),
            groups[1].rows.map { it.summary() },
        )
        assertEquals(
            listOf(Triple("EUR", "5.951,20 €", "76,4 %"), Triple("USD", "1.840,00 €", "23,6 %")),
            groups[2].rows.map { it.summary() },
        )
        job.cancel()
    }

    @Test
    fun `the bars are proportional to the share`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val shares = s.vm.state.value.allocations[0].rows.map { it.share }
        assertEquals(0.3209f, shares[0], 0.001f)
        assertEquals(1.0f, s.vm.state.value.allocations[0].rows.sumOf { it.share.toDouble() }.toFloat(), 0.001f)
        job.cancel()
    }

    @Test
    fun `the bank or broker split is left out when no holding has one`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(stock, art))
        val job = open(s)

        assertEquals(listOf("Por tipo", "Por divisa"), s.vm.state.value.allocations.map { it.title })
        job.cancel()
    }

    @Test
    fun `a holding without a price is not in the split`() = runTest(dispatcher) {
        val unpriced = fund.copy(valuation = (fund.valuation as HoldingValuation.Quoted).copy(lastQuote = null))
        val s = setup(holdings = listOf(unpriced, art))
        val job = open(s)

        assertEquals(listOf("Otro"), s.vm.state.value.allocations[0].rows.map { it.label })
        job.cancel()
    }

    // ---- costs and risk ---------------------------------------------------------------------------------------

    @Test
    fun `the average cost and risk say how many products they are based on`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val costs = assertNotNull(s.vm.state.value.costsRisk)
        assertEquals("0,32 %", costs.terText)
        assertEquals("≈ 13,61 €/año", costs.yearlyCostText)
        assertEquals("Con datos para 2 de 4 productos", costs.terCoverage)
        assertEquals("5,4 de 7", costs.riskText)
        assertEquals("Con datos para 2 de 4 productos", costs.riskCoverage)
        job.cancel()
    }

    @Test
    fun `without a coverage gap there is no note`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund, stock))
        val job = open(s)

        val costs = assertNotNull(s.vm.state.value.costsRisk)
        assertNotNull(costs.terText)
        assertNull(costs.terCoverage)
        assertNull(costs.riskCoverage)
        job.cancel()
    }

    @Test
    fun `without any fundamentals the costs section is left out`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(etf, art))
        val job = open(s)

        assertNull(s.vm.state.value.costsRisk)
        job.cancel()
    }

    @Test
    fun `a cost without a risk class only shows the cost`() = runTest(dispatcher) {
        val onlyCost = fund.copy(fundamentals = InstrumentFundamentals(ongoingChargePercentScaled = 1_800))
        val s = setup(holdings = listOf(onlyCost, art))
        val job = open(s)

        val costs = assertNotNull(s.vm.state.value.costsRisk)
        assertEquals("0,18 %", costs.terText)
        assertNull(costs.riskText)
        assertNull(costs.riskCoverage)
        job.cancel()
    }

    // ---- concentration ----------------------------------------------------------------------------------------

    @Test
    fun `the largest holding is shown without a warning below forty percent`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val concentration = assertNotNull(s.vm.state.value.concentration)
        assertEquals("Cuadro", concentration.name)
        assertEquals("32,1 %", concentration.weightText)
        assertNull(concentration.warning)
        job.cancel()
    }

    @Test
    fun `above forty percent a warning names the holding`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund, etf, stock, manual(20_000)))
        val job = open(s)

        val concentration = assertNotNull(s.vm.state.value.concentration)
        assertEquals("79,1 %", concentration.weightText)
        assertEquals("«Cuadro» supone el 79,1 % de tu cartera.", concentration.warning)
        job.cancel()
    }

    @Test
    fun `a long name is shortened in the warning`() = runTest(dispatcher) {
        val long = manual(20_000).copy(name = "Colección de arte contemporáneo de la familia")
        val s = setup(holdings = listOf(fund, long))
        val job = open(s)

        assertEquals(
            "«Colección de arte contempor…» supone el 89,1 % de tu cartera.",
            s.vm.state.value.concentration?.warning,
        )
        job.cancel()
    }

    @Test
    fun `a portfolio of one product is not warned about`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund))
        val job = open(s)

        val concentration = assertNotNull(s.vm.state.value.concentration)
        assertEquals("100,0 %", concentration.weightText)
        assertNull(concentration.warning)
        job.cancel()
    }

    @Test
    fun `an empty portfolio has a header and nothing else`() = runTest(dispatcher) {
        val s = setup(holdings = emptyList(), histories = emptyMap())
        val job = open(s)

        val state = s.vm.state.value
        assertTrue(state.loaded)
        assertEquals("0,00 €", state.header.totalText)
        assertEquals("Sin productos", state.header.productsText)
        assertNull(state.header.mixText)
        assertNull(state.evolution)
        assertNull(state.correlation)
        assertTrue(state.allocations.isEmpty())
        assertNull(state.costsRisk)
        assertNull(state.concentration)
        assertNull(state.note)
        job.cancel()
    }

    @Test
    fun `the footer explains that the returns are not those of the purchases`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        assertEquals(
            "Rentabilidades de los productos con tu composición actual, no de tus compras.",
            s.vm.state.value.note,
        )
        job.cancel()
    }

    // ---- recomputing ------------------------------------------------------------------------------------------

    @Test
    fun `the figures follow a change in the holdings`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund, etf, stock, art))
        val job = open(s)

        val doubled =
            fund.copy(valuation = (fund.valuation as HoldingValuation.Quoted).copy(units = UnitsBalance(20_000_000)))
        s.portfolio.emit(listOf(doubled, etf, stock, art))
        advanceUntilIdle()

        assertEquals("10.242,40 €", s.vm.state.value.header.totalText)
        job.cancel()
    }

    @Test
    fun `a new history re-draws the chart and the matrix`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund, etf), histories = mapOf(fundRef to fundSeries))
        val job = open(s)
        assertNull(s.vm.state.value.correlation?.matrix)

        s.history.set(etfRef, etfSeries)
        advanceUntilIdle()

        assertEquals(listOf("Fondo Global", "ETF Mundial"), s.vm.state.value.correlation?.matrix?.names)
        job.cancel()
    }

    @Test
    fun `a new exchange rate history brings the holding back`() = runTest(dispatcher) {
        val s = setup(fxHistory = FxHistory(emptyMap()))
        val job = open(s)
        assertEquals(2, s.vm.state.value.correlation?.matrix?.names?.size)

        s.fx.set(usdHistory)
        advanceUntilIdle()

        assertEquals(3, s.vm.state.value.correlation?.matrix?.names?.size)
        assertEquals("Excluye: Cuadro", s.vm.state.value.evolution?.excludedText)
        job.cancel()
    }

    @Test
    fun `new fundamentals update the costs`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(etf, art))
        val job = open(s)
        assertNull(s.vm.state.value.costsRisk)

        val withCost = etf.copy(fundamentals = InstrumentFundamentals(ongoingChargePercentScaled = 2_000))
        s.portfolio.emit(listOf(withCost, art))
        advanceUntilIdle()

        assertEquals("0,20 %", s.vm.state.value.costsRisk?.terText)
        job.cancel()
    }

    // ---- sync -------------------------------------------------------------------------------------------------

    @Test
    fun `opening the screen syncs the histories, the fundamentals and the exchange rates without forcing`() =
        runTest(dispatcher) {
            val s = setup()
            val job = open(s)

            assertEquals(listOf(setOf(fundRef, etfRef, stockRef) to false), s.history.syncAllCalls)
            assertEquals(listOf<Pair<String?, Boolean>>(null to false), s.portfolio.fundamentalsCalls)
            assertEquals(
                listOf(FxSyncCall(setOf(Currency.USD), stockSeries.first().date, force = false)),
                s.fx.syncCalls,
            )
            assertFalse(s.vm.state.value.isSyncing)
            assertFalse(s.vm.state.value.syncError)
            job.cancel()
        }

    @Test
    fun `the exchange rates are only downloaded for the currencies of quoted holdings`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(fund, etf, art), histories = mapOf(fundRef to fundSeries, etfRef to etfSeries))
        val job = open(s)

        assertTrue(s.fx.syncCalls.isEmpty())
        assertEquals(1, s.history.syncAllCalls.size)
        job.cancel()
    }

    @Test
    fun `refreshing forces the three downloads`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onRefresh()
        advanceUntilIdle()

        assertEquals(listOf(false, true), s.history.syncAllCalls.map { it.second })
        assertEquals(listOf<Pair<String?, Boolean>>(null to false, null to true), s.portfolio.fundamentalsCalls)
        assertEquals(listOf(false, true), s.fx.syncCalls.map { it.force })
        job.cancel()
    }

    @Test
    fun `the screen says it is updating while the download runs and ignores a second refresh`() = runTest(dispatcher) {
        val s = setup()
        val gate = CompletableDeferred<Unit>()
        s.history.syncGate = gate
        val job = open(s)

        assertTrue(s.vm.state.value.isSyncing)
        assertTrue(s.vm.state.value.evolution != null)
        s.vm.onRefresh()
        advanceUntilIdle()
        assertEquals(1, s.history.syncAllCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(s.vm.state.value.isSyncing)
        job.cancel()
    }

    @Test
    fun `a failed history download keeps the cached data and shows a warning`() = runTest(dispatcher) {
        val s = setup()
        s.history.syncAllResult = HistorySyncResult(
            synced = 2,
            upToDate = 0,
            failures = listOf(HistorySyncFailure(fundRef, "boom")),
        )
        val job = open(s)

        val state = s.vm.state.value
        assertTrue(state.syncError)
        assertFalse(state.isSyncing)
        assertNotNull(state.evolution)
        assertNotNull(state.correlation?.matrix)
        job.cancel()
    }

    @Test
    fun `a history download that throws is a warning too`() = runTest(dispatcher) {
        val s = setup()
        s.history.syncFailure = HistoryUnavailableException("offline")
        val job = open(s)

        assertTrue(s.vm.state.value.syncError)
        assertNotNull(s.vm.state.value.evolution)
        // The rates are still tried: one failing download does not stop the others.
        assertEquals(1, s.fx.syncCalls.size)
        job.cancel()
    }

    @Test
    fun `a failed exchange rate download is a warning`() = runTest(dispatcher) {
        val s = setup()
        s.fx.syncFailure = HistoryUnavailableException("offline")
        val job = open(s)

        assertTrue(s.vm.state.value.syncError)
        assertNotNull(s.vm.state.value.evolution)
        job.cancel()
    }

    @Test
    fun `a failing fundamentals sync does not raise the warning`() = runTest(dispatcher) {
        val s = setup()
        s.portfolio.fundamentalsResult = FundamentalsSyncResult(
            updated = 0,
            upToDate = 0,
            failures = listOf(FundamentalsSyncFailure("fund", "Fondo Global", "boom")),
        )
        val job = open(s)

        assertFalse(s.vm.state.value.syncError)
        job.cancel()
    }

    @Test
    fun `the warning can be dismissed and a successful refresh clears it`() = runTest(dispatcher) {
        val s = setup()
        s.history.syncFailure = HistoryUnavailableException("offline")
        val job = open(s)
        assertTrue(s.vm.state.value.syncError)

        s.vm.onSyncErrorDismiss()
        advanceUntilIdle()
        assertFalse(s.vm.state.value.syncError)

        s.history.syncFailure = null
        s.vm.onRefresh()
        advanceUntilIdle()
        assertFalse(s.vm.state.value.syncError)
        assertEquals(listOf(false, true), s.history.syncAllCalls.map { it.second })
        job.cancel()
    }

    @Test
    fun `a refresh after a failure that fails again warns again`() = runTest(dispatcher) {
        val s = setup()
        s.fx.syncFailure = HistoryUnavailableException("offline")
        val job = open(s)
        s.vm.onSyncErrorDismiss()
        advanceUntilIdle()
        assertFalse(s.vm.state.value.syncError)

        s.vm.onRefresh()
        advanceUntilIdle()

        assertTrue(s.vm.state.value.syncError)
        job.cancel()
    }
}
