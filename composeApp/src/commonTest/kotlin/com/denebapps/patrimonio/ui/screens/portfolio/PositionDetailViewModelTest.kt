package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.annualizedReturn
import com.denebapps.patrimonio.domain.calc.volatility
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.FundamentalsSyncFailure
import com.denebapps.patrimonio.domain.repository.FundamentalsSyncResult
import com.denebapps.patrimonio.domain.repository.HistoryUnavailableException
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
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
import kotlinx.datetime.plus
import kotlin.math.round
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PositionDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val rates = FxRates(mapOf(Currency.USD to 920_000L))
    private val today = LocalDate(2026, 10, 11)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-11T12:00:00Z")
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000FUND")
    private val stockRef = InstrumentRef(QuoteProvider.YAHOO, "ACME")

    // 10 units at 245,12 € = 2.451,20 €; ongoing charge 0,18 %.
    private val fund = PortfolioHolding(
        id = "fund",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo Global",
        type = InstrumentType.FUND,
        isin = "IE00B4L5Y983",
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = fundRef,
            currency = Currency.EUR,
            units = Units(10_000_000),
            lastQuote = LastQuote(Price(245_120_000, Currency.EUR), LocalDate(2026, 10, 9), fetchedAtEpochMs = 1L),
        ),
        broker = "MyInvestor",
        fundamentals = InstrumentFundamentals(
            ongoingChargePercentScaled = 1_800,
            riskLevel = 5,
            category = "Renta variable global",
            managementCompany = "Vanguard",
        ),
    )

    // 10 shares at 200,00 $ = 2.000,00 $ = 1.840,00 €; ongoing charge 0,5 %.
    private val stock = PortfolioHolding(
        id = "stock",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Acme Corp",
        type = InstrumentType.STOCK,
        isin = null,
        ticker = "ACME",
        valuation = HoldingValuation.Quoted(
            ref = stockRef,
            currency = Currency.USD,
            units = Units(10_000_000),
            lastQuote = LastQuote(Price(200_000_000, Currency.USD), LocalDate(2026, 10, 9), fetchedAtEpochMs = 1L),
        ),
        fundamentals = InstrumentFundamentals(ongoingChargePercentScaled = 5_000),
    )
    private val manual = PortfolioHolding(
        id = "art",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Cuadro",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        // 2026-10-10T12:00:00Z
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(250_000), Currency.EUR), 1_791_633_600_000L),
    )

    /** The ratio is a `Double` and carries rounding noise (0.19999999999999996): compare it to six decimals. */
    private fun YearReturnUi.rounded() = copy(ratio = round(ratio * 1e6) / 1e6)

    private fun eur(date: LocalDate, whole: Long) = PricePoint(date, Price(whole * 1_000_000, Currency.EUR))

    /** 100 → 120 → 90 → 132, the cases below are worked out by hand from it. */
    private val shortHistory = listOf(
        eur(LocalDate(2025, 3, 14), 100),
        eur(LocalDate(2025, 12, 31), 120),
        eur(LocalDate(2026, 6, 30), 90),
        eur(LocalDate(2026, 10, 9), 132),
    )

    /** A Friday close every week from 2020-04-03 to 2026-10-09, gently rising with a zig-zag. */
    private val longHistory: List<PricePoint> = (0..339).map { week ->
        val price = (100.0 + week * 0.5 + (week % 2) * 2.0) * 1_000_000
        PricePoint(
            LocalDate(2020, 4, 3).plus(week * 7, DateTimeUnit.DAY),
            Price(price.toLong(), Currency.EUR),
        )
    }

    private class Setup(
        val portfolio: FakePortfolioRepository,
        val history: FakeInstrumentHistoryRepository,
        val vm: PositionDetailViewModel,
    )

    private fun setup(
        id: String,
        holdings: List<PortfolioHolding> = listOf(fund, stock),
        fundHistory: List<PricePoint> = shortHistory,
    ): Setup {
        val portfolio = FakePortfolioRepository(holdings)
        val history = FakeInstrumentHistoryRepository().apply { set(fundRef, fundHistory) }
        val vm = PositionDetailViewModel(
            holdingId = id,
            portfolioRepository = portfolio,
            fxRepository = FakeFxRepository(rates),
            historyRepository = history,
            clock = clock,
            zoneProvider = { TimeZone.UTC },
        )
        return Setup(portfolio, history, vm)
    }

    private fun TestScope.collect(vm: PositionDetailViewModel): Job = launch { vm.state.collect {} }

    private fun TestScope.collectNavigateBack(vm: PositionDetailViewModel): MutableList<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch { vm.navigateBack.collect { events += it } }
        return events
    }

    @Test
    fun `a quoted holding shows its identity, value, units and weight`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        val header = s.vm.state.value.header
        assertTrue(s.vm.state.value.loaded)
        assertEquals("Fondo Global", header.name)
        assertEquals("Fondo", header.typeLabel)
        assertEquals("MyInvestor", header.broker)
        assertEquals("IE00B4L5Y983 · Morningstar", header.identifier)
        assertEquals("2.451,20 €", header.valueText)
        assertNull(header.valueEurText)
        assertEquals("10,00 part. · 245,12 € · 09/10/2026", header.detailText)
        assertEquals("Peso en tu cartera: 57,1 %", header.weightText)
        job.cancel()
    }

    @Test
    fun `a holding in another currency also shows its value in euros`() = runTest(dispatcher) {
        val s = setup("stock")
        s.history.set(stockRef, shortHistory.map { PricePoint(it.date, Price(it.price.scaled, Currency.USD)) })
        val job = collect(s.vm)
        advanceUntilIdle()

        val header = s.vm.state.value.header
        assertEquals("ACME · Yahoo Finance", header.identifier)
        assertEquals("2.000,00 $", header.valueText)
        assertEquals("≈ 1.840,00 €", header.valueEurText)
        assertEquals("Peso en tu cartera: 42,9 %", header.weightText)
        assertNull(header.broker)
        job.cancel()
    }

    @Test
    fun `the chart starts on one year and shows how the product price changed`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        val state = s.vm.state.value
        assertEquals(HistoryStatus.AVAILABLE, state.historyStatus)
        assertEquals(
            listOf("1M" to false, "6M" to true, "1A" to true, "5A" to true, "Máx" to true),
            state.ranges.map { it.label to it.enabled },
        )
        assertEquals(HistoryRange.ONE_YEAR, state.ranges.single { it.selected }.range)
        val chart = assertNotNull(state.chart)
        assertEquals(listOf(shortHistory[1], shortHistory[2], shortHistory[3]), chart.prices)
        assertEquals(listOf(120.0, 90.0, 132.0), chart.points.map { it.value })
        assertEquals(shortHistory[1].date.toEpochDays().toLong(), chart.points.first().epochDay)
        assertEquals("+12,00 € · +10,00 %", chart.changeText)
        assertEquals(PerformanceTone.GAIN, chart.changeTone)
        assertEquals("Mín 90,00 €", chart.minText)
        assertEquals("Máx 132,00 €", chart.maxText)
        assertEquals("31/12/2025", chart.firstDateText)
        assertEquals("09/10/2026", chart.lastDateText)
        job.cancel()
    }

    @Test
    fun `picking another range redraws the chart and its change`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        s.vm.onRangeSelect(HistoryRange.SIX_MONTHS)
        advanceUntilIdle()
        assertEquals(HistoryRange.SIX_MONTHS, s.vm.state.value.ranges.single { it.selected }.range)
        assertEquals("+42,00 € · +46,67 %", s.vm.state.value.chart?.changeText)
        assertEquals(2, s.vm.state.value.chart?.points?.size)

        s.vm.onRangeSelect(HistoryRange.MAX)
        advanceUntilIdle()
        assertEquals("+32,00 € · +32,00 %", s.vm.state.value.chart?.changeText)
        assertEquals(4, s.vm.state.value.chart?.points?.size)
        assertEquals("14/03/2025", s.vm.state.value.chart?.firstDateText)
        job.cancel()
    }

    @Test
    fun `a range without data is disabled and cannot be picked`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        s.vm.onRangeSelect(HistoryRange.ONE_MONTH)
        advanceUntilIdle()

        assertEquals(HistoryRange.ONE_YEAR, s.vm.state.value.ranges.single { it.selected }.range)
        job.cancel()
    }

    @Test
    fun `when the chosen range has no data the chart shows all the history`() = runTest(dispatcher) {
        val old = listOf(eur(LocalDate(2024, 1, 5), 100), eur(LocalDate(2024, 6, 7), 110))
        val s = setup("fund", fundHistory = old)
        val job = collect(s.vm)
        advanceUntilIdle()

        val state = s.vm.state.value
        assertEquals(listOf(false, false, false, true, true), state.ranges.map { it.enabled })
        assertEquals(HistoryRange.MAX, state.ranges.single { it.selected }.range)
        assertEquals("+10,00 € · +10,00 %", state.chart?.changeText)
        job.cancel()
    }

    @Test
    fun `a falling range is marked as a loss`() = runTest(dispatcher) {
        val falling = listOf(eur(LocalDate(2026, 1, 2), 100), eur(LocalDate(2026, 10, 9), 90))
        val s = setup("fund", fundHistory = falling)
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals("−10,00 € · −10,00 %", s.vm.state.value.chart?.changeText)
        assertEquals(PerformanceTone.LOSS, s.vm.state.value.chart?.changeTone)
        job.cancel()
    }

    @Test
    fun `returns per calendar year list the partial first year first and the running year last`() =
        runTest(dispatcher) {
            val s = setup("fund")
            val job = collect(s.vm)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    YearReturnUi(
                        2025,
                        0.2,
                        "20,00 %",
                        PerformanceTone.GAIN,
                        partialSince = "14/03",
                        yearToDate = false,
                    ),
                    YearReturnUi(2026, 0.1, "10,00 %", PerformanceTone.GAIN, partialSince = null, yearToDate = true),
                ),
                s.vm.state.value.yearReturns.map { it.rounded() },
            )
            job.cancel()
        }

    @Test
    fun `a losing year is marked as a loss and a flat one as neutral`() = runTest(dispatcher) {
        val history = listOf(
            eur(LocalDate(2024, 12, 31), 100),
            eur(LocalDate(2025, 12, 31), 80),
            eur(LocalDate(2026, 10, 9), 80),
        )
        val s = setup("fund", fundHistory = history)
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals(
            listOf(
                YearReturnUi(2025, -0.2, "−20,00 %", PerformanceTone.LOSS, partialSince = null, yearToDate = false),
                YearReturnUi(2026, 0.0, "0,00 %", PerformanceTone.NEUTRAL, partialSince = null, yearToDate = true),
            ),
            s.vm.state.value.yearReturns.map { it.rounded() },
        )
        job.cancel()
    }

    @Test
    fun `annualized returns are dashes while the history is too short`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals(
            listOf("1 año" to "—", "3 años" to "—", "5 años" to "—"),
            s.vm.state.value.annualized.map { it.label to it.valueText },
        )
        job.cancel()
    }

    @Test
    fun `risk shows the deepest fall with its dates and no volatility on a short history`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        val risk = assertNotNull(s.vm.state.value.risk)
        assertEquals("Volatilidad (3 años)", risk.volatilityLabel)
        assertEquals("—", risk.volatilityText)
        assertEquals("−25,00 %", risk.drawdownText)
        assertEquals("31/12/2025 → 30/06/2026", risk.drawdownDates)
        job.cancel()
    }

    @Test
    fun `a long history gives annualized returns and a three year volatility`() = runTest(dispatcher) {
        val s = setup("fund", fundHistory = longHistory)
        val job = collect(s.vm)
        advanceUntilIdle()

        val state = s.vm.state.value
        val expectedAnnualized = listOf(1, 3, 5).map { formatPercent(assertNotNull(annualizedReturn(longHistory, it))) }
        assertEquals(expectedAnnualized, state.annualized.map { it.valueText })
        assertTrue(state.annualized.all { it.valueText != "—" })
        val risk = assertNotNull(state.risk)
        assertEquals("Volatilidad (3 años)", risk.volatilityLabel)
        assertEquals(
            formatPercent(assertNotNull(volatility(longHistory, today)).annualized, signed = false),
            risk.volatilityText,
        )
        job.cancel()
    }

    @Test
    fun `a history of a few months says the weeks the volatility looks at`() = runTest(dispatcher) {
        val s = setup("fund", fundHistory = longHistory.takeLast(41))
        val job = collect(s.vm)
        advanceUntilIdle()

        val weeks = assertNotNull(volatility(longHistory.takeLast(41), today)).weeks
        assertEquals("Volatilidad ($weeks semanas)", s.vm.state.value.risk?.volatilityLabel)
        assertTrue(s.vm.state.value.risk?.volatilityText != "—")
        job.cancel()
    }

    @Test
    fun `the product data shows the ongoing charge, what it costs a year, the risk and who manages it`() =
        runTest(dispatcher) {
            val s = setup("fund")
            val job = collect(s.vm)
            advanceUntilIdle()

            assertEquals(
                ProductDataUi(
                    terText = "0,18 %",
                    yearlyCostText = "≈ 4,41 €/año con tu posición",
                    riskLevel = 5,
                    category = "Renta variable global",
                    manager = "Vanguard",
                ),
                s.vm.state.value.product,
            )
            job.cancel()
        }

    @Test
    fun `the yearly cost of a holding in another currency is shown in euros`() = runTest(dispatcher) {
        val s = setup("stock")
        s.history.set(stockRef, shortHistory.map { PricePoint(it.date, Price(it.price.scaled, Currency.USD)) })
        val job = collect(s.vm)
        advanceUntilIdle()

        val product = assertNotNull(s.vm.state.value.product)
        assertEquals("0,50 %", product.terText)
        // 0,5 % of 2.000,00 $ is 10,00 $, i.e. 9,20 € at 0,92.
        assertEquals("≈ 9,20 €/año con tu posición", product.yearlyCostText)
        assertNull(product.riskLevel)
        assertNull(product.category)
        assertNull(product.manager)
        job.cancel()
    }

    @Test
    fun `product data rows the provider did not fill in are left out`() = runTest(dispatcher) {
        val onlyCategory = fund.copy(fundamentals = InstrumentFundamentals(category = "Bonos"))
        val s = setup("fund", holdings = listOf(onlyCategory))
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals(ProductDataUi(null, null, null, "Bonos", null), s.vm.state.value.product)
        job.cancel()
    }

    @Test
    fun `without fundamentals there is no product data section`() = runTest(dispatcher) {
        val s = setup("fund", holdings = listOf(fund.copy(fundamentals = null)))
        val job = collect(s.vm)
        advanceUntilIdle()

        assertNull(s.vm.state.value.product)
        job.cancel()
    }

    @Test
    fun `a quoted holding says the returns are the product's and not the user's`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals("Rentabilidades del producto, no de tus compras.", s.vm.state.value.note)
        job.cancel()
    }

    @Test
    fun `a manual holding has its value and date but no chart, returns or sync`() = runTest(dispatcher) {
        val s = setup("art", holdings = listOf(manual))
        val job = collect(s.vm)
        advanceUntilIdle()

        val state = s.vm.state.value
        assertEquals("Cuadro", state.header.name)
        assertEquals("Otro", state.header.typeLabel)
        assertEquals("", state.header.identifier)
        assertEquals("2.500,00 €", state.header.valueText)
        assertEquals("Valor manual · 10/10/2026", state.header.detailText)
        assertEquals("Peso en tu cartera: 100,0 %", state.header.weightText)
        assertEquals(HistoryStatus.NOT_APPLICABLE, state.historyStatus)
        assertNull(state.chart)
        assertTrue(state.ranges.isEmpty())
        assertTrue(state.yearReturns.isEmpty())
        assertTrue(state.annualized.isEmpty())
        assertNull(state.risk)
        assertNull(state.product)
        assertFalse(state.isSyncing)
        assertEquals("Sin histórico: es un valor manual.", state.note)
        assertTrue(s.history.syncCalls.isEmpty())
        assertTrue(s.portfolio.fundamentalsCalls.isEmpty())
        job.cancel()
    }

    @Test
    fun `opening the screen syncs the history and the fundamentals without forcing`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals(listOf(fundRef to false), s.history.syncCalls)
        assertEquals(listOf<Pair<String?, Boolean>>("fund" to false), s.portfolio.fundamentalsCalls)
        assertFalse(s.vm.state.value.isSyncing)
        assertFalse(s.vm.state.value.syncError)
        job.cancel()
    }

    @Test
    fun `refreshing forces both syncs`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        s.vm.onRefresh()
        advanceUntilIdle()

        assertEquals(listOf(fundRef to false, fundRef to true), s.history.syncCalls)
        assertEquals(
            listOf<Pair<String?, Boolean>>("fund" to false, "fund" to true),
            s.portfolio.fundamentalsCalls,
        )
        job.cancel()
    }

    @Test
    fun `a refresh while one is running is ignored and the screen says it is updating`() = runTest(dispatcher) {
        val s = setup("fund")
        val gate = CompletableDeferred<Unit>()
        s.history.syncGate = gate
        val job = collect(s.vm)
        advanceUntilIdle()

        assertTrue(s.vm.state.value.isSyncing)
        s.vm.onRefresh()
        advanceUntilIdle()
        assertEquals(1, s.history.syncCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(s.vm.state.value.isSyncing)
        job.cancel()
    }

    @Test
    fun `while the first download runs and nothing is cached the history is loading`() = runTest(dispatcher) {
        val s = setup("fund", fundHistory = emptyList())
        s.history.syncGate = CompletableDeferred()
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals(HistoryStatus.LOADING, s.vm.state.value.historyStatus)
        assertNull(s.vm.state.value.chart)
        job.cancel()
    }

    @Test
    fun `a failed update keeps the cached history and shows a dismissible warning`() = runTest(dispatcher) {
        val s = setup("fund")
        s.history.syncFailure = HistoryUnavailableException("offline")
        val job = collect(s.vm)
        advanceUntilIdle()

        val state = s.vm.state.value
        assertEquals(HistoryStatus.AVAILABLE, state.historyStatus)
        assertNotNull(state.chart)
        assertTrue(state.syncError)
        assertFalse(state.isSyncing)

        s.vm.onSyncErrorDismiss()
        advanceUntilIdle()
        assertFalse(s.vm.state.value.syncError)
        job.cancel()
    }

    @Test
    fun `a failed first download leaves no history and a way to retry`() = runTest(dispatcher) {
        val s = setup("fund", fundHistory = emptyList())
        s.history.syncFailure = HistoryUnavailableException("offline")
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals(HistoryStatus.UNAVAILABLE, s.vm.state.value.historyStatus)
        assertNull(s.vm.state.value.chart)
        assertTrue(s.vm.state.value.yearReturns.isEmpty())

        s.history.syncFailure = null
        s.history.set(fundRef, shortHistory)
        s.vm.onRefresh()
        advanceUntilIdle()

        assertEquals(listOf(fundRef to false, fundRef to true), s.history.syncCalls)
        assertEquals(HistoryStatus.AVAILABLE, s.vm.state.value.historyStatus)
        assertFalse(s.vm.state.value.syncError)
        job.cancel()
    }

    @Test
    fun `a single cached point is not a history`() = runTest(dispatcher) {
        val s = setup("fund", fundHistory = listOf(shortHistory.first()))
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals(HistoryStatus.UNAVAILABLE, s.vm.state.value.historyStatus)
        assertNull(s.vm.state.value.chart)
        job.cancel()
    }

    @Test
    fun `a failing fundamentals sync does not raise the history warning`() = runTest(dispatcher) {
        val s = setup("fund")
        s.portfolio.fundamentalsResult = FundamentalsSyncResult(
            updated = 0,
            upToDate = 0,
            failures = listOf(
                FundamentalsSyncFailure("fund", "Fondo Global", "boom"),
            ),
        )
        val job = collect(s.vm)
        advanceUntilIdle()

        assertFalse(s.vm.state.value.syncError)
        assertNotNull(s.vm.state.value.product)
        job.cancel()
    }

    @Test
    fun `the screen goes back when the holding disappears`() = runTest(dispatcher) {
        val s = setup("fund")
        val events = collectNavigateBack(s.vm)
        val job = collect(s.vm)
        advanceUntilIdle()
        assertTrue(events.isEmpty())

        s.portfolio.delete("fund")
        advanceUntilIdle()

        assertEquals(1, events.size)
        assertFalse(s.vm.state.value.loaded)
        job.cancel()
    }

    @Test
    fun `an unknown holding goes straight back`() = runTest(dispatcher) {
        val s = setup("missing")
        val events = collectNavigateBack(s.vm)
        val job = collect(s.vm)
        advanceUntilIdle()

        assertEquals(1, events.size)
        assertFalse(s.vm.state.value.loaded)
        assertTrue(s.history.syncCalls.isEmpty())
        job.cancel()
    }

    @Test
    fun `editing the units elsewhere updates the value and the weight`() = runTest(dispatcher) {
        val s = setup("fund")
        val job = collect(s.vm)
        advanceUntilIdle()

        s.portfolio.updateUnits("fund", Units(20_000_000))
        advanceUntilIdle()

        assertEquals("4.902,40 €", s.vm.state.value.header.valueText)
        assertEquals("Peso en tu cartera: 72,7 %", s.vm.state.value.header.weightText)
        job.cancel()
    }

    @Test
    fun `a holding with no price has no value, weight or yearly cost`() = runTest(dispatcher) {
        val unpriced = fund.copy(
            valuation = (fund.valuation as HoldingValuation.Quoted).copy(lastQuote = null),
        )
        val s = setup("fund", holdings = listOf(unpriced))
        val job = collect(s.vm)
        advanceUntilIdle()

        val state = s.vm.state.value
        assertEquals("Sin precio", state.header.valueText)
        assertNull(state.header.weightText)
        assertNull(state.product?.yearlyCostText)
        assertEquals("0,18 %", state.product?.terText)
        job.cancel()
    }
}
