package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.units
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Mi posición" in the position detail: when it shows, its figures, and the chart of the value of my units. */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionDetailOwnReturnsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = fixedClock("2026-10-11T10:00:00Z")
    private val ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "own")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // 10 units, worth 130 EUR each at the last quote.
    private val fund = PortfolioHolding(
        id = "own",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo propio",
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = ref,
            currency = Currency.EUR,
            units = UnitsBalance(units(10).scaled),
            lastQuote = LastQuote(Price(130_000_000, Currency.EUR), LocalDate(2026, 9, 28), 1L),
        ),
    )

    // A price every Monday from 2026-03-02 (100 EUR) to 2026-09-28 (130 EUR).
    private val history = (0..30).map { week ->
        PricePoint(
            LocalDate(2026, 3, 2).plus(week * 7, DateTimeUnit.DAY),
            Price((100L + week) * 1_000_000, Currency.EUR),
        )
    }

    private fun viewModel(movements: List<Movement>): PositionDetailViewModel {
        val repository = FakePortfolioRepository(listOf(fund))
        return PositionDetailViewModel(
            holdingId = "own",
            portfolioRepository = repository,
            portfolios = FakePortfolioCollectionRepository(holdings = repository),
            movementRepository = FakeMovementRepository(repository, movements),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            fxHistoryRepository = FakeFxHistoryRepository(),
            historyRepository = FakeInstrumentHistoryRepository().apply { set(ref, history) },
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
    }

    private fun TestScope.open(vm: PositionDetailViewModel): Job {
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    private val purchase = buy("b", date(2026, 3, 2), units(10), 100_000, holding = "own")

    @Test
    fun `a position with movements shows its card, lots and the chart of my units`() = runTest(dispatcher) {
        val vm = viewModel(listOf(purchase))
        val job = open(vm)

        val mine = assertNotNull(vm.state.value.myPosition)
        assertEquals("1.000,00 €", mine.figures.first { it.label == "Aportado" }.valueText)
        assertEquals("1.300,00 €", mine.figures.first { it.label == "Valor actual" }.valueText)
        assertEquals("+300,00 € (+30,0 %)", mine.figures.first { it.label == "Plusvalía latente" }.valueText)
        assertEquals(listOf(LotUi("02/03/2026", "10,00 part.", "1.000,00 €")), mine.lots)
        val chart = assertNotNull(mine.chart)
        assertEquals(31, chart.points.size)
        assertEquals(1_000.0, chart.points.first().value)
        assertEquals(1_300.0, chart.points.last().value)
        assertEquals(HistoryRange.entries.size, mine.ranges.size)
        job.cancel()
    }

    @Test
    fun `a position without movements has no card`() = runTest(dispatcher) {
        val vm = viewModel(emptyList())
        val job = open(vm)

        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.myPosition)
        job.cancel()
    }

    @Test
    fun `the range of the chart of my units is chosen apart from the product chart`() = runTest(dispatcher) {
        val vm = viewModel(listOf(purchase))
        val job = open(vm)

        vm.onMyPositionRangeSelect(HistoryRange.ONE_MONTH)
        advanceUntilIdle()

        val state = vm.state.value
        val mine = assertNotNull(state.myPosition)
        assertEquals(HistoryRange.ONE_MONTH, mine.ranges.single { it.selected }.range)
        assertEquals(3, assertNotNull(mine.chart).points.size)
        assertEquals(HistoryRange.ONE_YEAR, state.ranges.single { it.selected }.range)
        job.cancel()
    }

    @Test
    fun `a chart needs two prices since the first movement`() = runTest(dispatcher) {
        val vm = viewModel(listOf(buy("late", date(2026, 9, 28), units(10), 100_000, holding = "own")))
        val job = open(vm)

        val mine = assertNotNull(vm.state.value.myPosition)
        assertNull(mine.chart)
        assertTrue(mine.ranges.isEmpty())
        job.cancel()
    }
}
