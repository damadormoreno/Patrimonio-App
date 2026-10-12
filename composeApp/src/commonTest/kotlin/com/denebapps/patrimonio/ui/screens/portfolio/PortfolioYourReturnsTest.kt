package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FUND_A
import com.denebapps.patrimonio.domain.model.FUND_B
import com.denebapps.patrimonio.domain.model.FUND_C
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.opening
import com.denebapps.patrimonio.domain.model.transferIn
import com.denebapps.patrimonio.domain.model.transferOut
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
import kotlinx.datetime.TimeZone
import kotlin.math.pow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** "Tu rentabilidad" in the portfolio detail: one portfolio or all of them, transfers, coverage and visibility. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioYourReturnsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = fixedClock("2026-01-01T10:00:00Z")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("pf-hijos", "Hijos", 1))

    private fun fund(id: String, portfolio: String, unitsHeld: Long, lastEuros: Long) = PortfolioHolding(
        id = id,
        portfolioId = portfolio,
        name = "Fondo $id",
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = Currency.EUR,
            units = UnitsBalance(unitsHeld * UNITS_SCALE),
            lastQuote = LastQuote(Price(lastEuros * 1_000_000, Currency.EUR), date(2026, 1, 1), 1L),
        ),
    )

    private val manual = PortfolioHolding(
        id = "art",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Cuadro",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(250_000), Currency.EUR), 1L),
    )

    // A (Mi cartera) bought 10 units for 1000.00 on 2025-01-01 and moved them all to B (Hijos) on 2025-07-01.
    private val transfer = listOf(
        buy("b", date(2025, 1, 1), units(10), 100_000),
        transferOut("o", date(2025, 7, 1), units(10), "t"),
        transferIn("i", date(2025, 7, 1), units(10), "t"),
    )
    private val closedA = fund(FUND_A, TEST_PORTFOLIO_ID, 0, 120)
    private val heldB = fund(FUND_B, "pf-hijos", 10, 130)

    private fun viewModel(
        holdings: List<PortfolioHolding>,
        movements: List<Movement>,
        portfolioId: String? = null,
        histories: Map<String, List<PricePoint>> = emptyMap(),
    ): PortfolioDetailViewModel {
        val repository = FakePortfolioRepository(holdings)
        val history = FakeInstrumentHistoryRepository().apply {
            histories.forEach { (id, points) -> set(InstrumentRef(QuoteProvider.MORNINGSTAR, id), points) }
        }
        return PortfolioDetailViewModel(
            portfolioId = portfolioId,
            portfolios = FakePortfolioCollectionRepository(portfolios, repository),
            portfolioRepository = repository,
            movementRepository = FakeMovementRepository(repository, movements),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            fxHistoryRepository = FakeFxHistoryRepository(FxHistory(emptyMap())),
            historyRepository = history,
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
    }

    private fun TestScope.open(vm: PortfolioDetailViewModel): Job {
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    private fun YourReturnsUi.value(label: String) = figures.first { it.label == label }.valueText

    @Test
    fun `all the portfolios together cancel the transfer between them`() = runTest(dispatcher) {
        val vm = viewModel(listOf(closedA, heldB), transfer)
        val job = open(vm)

        val block = assertNotNull(vm.state.value.yourReturns)
        assertEquals("Calculado con los 2 productos.", block.coverageText)
        assertEquals("1.000,00 €", block.value("Aportado"))
        assertEquals("1.300,00 €", block.value("Valor actual"))
        assertEquals("+300,00 € (+30,0 %)", block.value("Plusvalía latente"))
        assertEquals("0,00 €", block.value("Plusvalía realizada"))
        // The 1000,00 EUR of 2025-01-01 became 1300,00 EUR exactly a year later: the transfer is no flow.
        assertEquals("+30,00 %", block.value("Rentabilidad anual (XIRR)"))
        job.cancel()
    }

    @Test
    fun `one portfolio keeps the transfer out as money taken out at market value`() = runTest(dispatcher) {
        val history = listOf(PricePoint(date(2025, 7, 1), Price(120_000_000, Currency.EUR)))
        val vm = viewModel(listOf(closedA, heldB), transfer, TEST_PORTFOLIO_ID, mapOf(FUND_A to history))
        val job = open(vm)

        val block = assertNotNull(vm.state.value.yourReturns)
        assertEquals("Calculado con 1 producto.", block.coverageText)
        assertEquals("0,00 €", block.value("Valor actual"))
        // 1000,00 EUR out on 2025-01-01, 1200,00 EUR back (the units at market) on 2025-07-01: 181 days.
        assertEquals(
            formatPercent(1.2.pow(365.0 / 181.0) - 1, signed = true),
            block.value("Rentabilidad anual (XIRR)"),
        )
        job.cancel()
    }

    @Test
    fun `a product with an unknown cost, a manual one and a closed one are told apart in the coverage`() =
        runTest(dispatcher) {
            val held = fund(FUND_A, TEST_PORTFOLIO_ID, 10, 120)
            val unknown = fund(FUND_C, TEST_PORTFOLIO_ID, 5, 100)
            val movements = listOf(
                buy("b", date(2025, 1, 1), units(10), 100_000),
                opening("o", date(2025, 1, 1), units(5), costCents = null, holding = FUND_C),
            )
            val vm = viewModel(listOf(held, unknown, manual), movements, TEST_PORTFOLIO_ID)
            val job = open(vm)

            val block = assertNotNull(vm.state.value.yourReturns)
            assertEquals(
                "Calculado con 1 de 3 productos (2 sin movimientos o con coste desconocido).",
                block.coverageText,
            )
            assertEquals("1.000,00 €", block.value("Aportado"))
            assertEquals("1.200,00 €", block.value("Valor actual"))
            assertEquals("+200,00 € (+20,0 %)", block.value("Plusvalía latente"))
            job.cancel()
        }

    @Test
    fun `the block is hidden when no product of the scope has movements`() = runTest(dispatcher) {
        // The movements belong to A, in another portfolio: Hijos only holds a painting.
        val painting = manual.copy(id = "art2", portfolioId = "pf-hijos")
        val vm = viewModel(listOf(closedA, painting), transfer.take(1), portfolioId = "pf-hijos")
        val job = open(vm)

        assertNull(vm.state.value.yourReturns)
        job.cancel()
    }

    @Test
    fun `the block is hidden without any movement`() = runTest(dispatcher) {
        val vm = viewModel(listOf(heldB, manual), emptyList())
        val job = open(vm)

        assertNull(vm.state.value.yourReturns)
        job.cancel()
    }
}
