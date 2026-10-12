package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import com.denebapps.patrimonio.testing.settle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The movements section of the position detail: the list, the empty states and the way into the forms. */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionDetailMovementsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = fixedClock("2026-10-11T10:00:00Z")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun quoted(
        id: String,
        type: InstrumentType = InstrumentType.FUND,
        units: UnitsBalance = UnitsBalance(10 * UNITS_SCALE),
    ) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo $id",
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = Currency.EUR,
            units = units,
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

    private fun units(whole: Long) = Units(whole * UNITS_SCALE)

    private fun buy(id: String, holding: String, day: Int, sequence: Long) = Movement(
        id = id,
        holdingId = holding,
        type = MovementType.BUY,
        date = LocalDate(2026, 3, day),
        units = units(10),
        amount = CurrencyAmount(Money(100_000), Currency.EUR),
        fee = Money(200),
        amountEur = Money(100_000),
        feeEur = Money(200),
        sequence = sequence,
    )

    private fun legs(): List<Movement> {
        val out = Movement(
            id = "out",
            holdingId = "a",
            type = MovementType.TRANSFER_OUT,
            date = LocalDate(2026, 4, 1),
            units = units(4),
            transferId = "t1",
            sequence = 2,
        )
        return listOf(out, out.copy(id = "in", holdingId = "b", type = MovementType.TRANSFER_IN, sequence = 3))
    }

    private class Setup(val vm: PositionDetailViewModel) {
        val state get() = vm.state.value
    }

    private fun setup(id: String, holdings: List<PortfolioHolding>, movements: List<Movement> = emptyList()): Setup {
        val portfolio = FakePortfolioRepository(holdings)
        return Setup(
            PositionDetailViewModel(
                holdingId = id,
                portfolioRepository = portfolio,
                portfolios = FakePortfolioCollectionRepository(holdings = portfolio),
                movementRepository = FakeMovementRepository(portfolio, movements),
                fxHistoryRepository = FakeFxHistoryRepository(),
                fxRepository = FakeFxRepository(FxRates(emptyMap())),
                historyRepository = FakeInstrumentHistoryRepository(),
                clock = clock,
                zoneProvider = { TimeZone.UTC },
                computationDispatcher = dispatcher,
            ),
        )
    }

    private fun TestScope.open(s: Setup): Job {
        val job = launch { s.vm.state.collect {} }
        settle()
        return job
    }

    private fun TestScope.events(s: Setup): MutableList<PositionNavigation> {
        val list = mutableListOf<PositionNavigation>()
        backgroundScope.launch { s.vm.navigate.collect { list += it } }
        return list
    }

    // ---- the list ------------------------------------------------------------------------------------------------

    @Test
    fun `the movements are listed newest first with the counterpart of a transfer`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a"), quoted("b")), listOf(buy("b1", "a", 5, 1)) + legs())
        val job = open(s)

        val section = s.state.movements
        assertTrue(section.visible)
        assertEquals(listOf("out", "b1"), section.rows.map { it.id })
        assertEquals("Traspaso a «Fondo b»", section.rows[0].title)
        assertEquals("−4,00 part.", section.rows[0].unitsText)
        assertEquals("Compra", section.rows[1].title)
        assertEquals("1.000,00 € · comisión 2,00 €", section.rows[1].amountText)
        assertNull(section.emptyText)
        job.cancel()
    }

    @Test
    fun `the receiving fund names the one the units came from`() = runTest(dispatcher) {
        val s = setup("b", listOf(quoted("a"), quoted("b")), listOf(buy("b1", "a", 5, 1)) + legs())
        val job = open(s)

        assertEquals(listOf("Traspaso desde «Fondo a»"), s.state.movements.rows.map { it.title })
        job.cancel()
    }

    @Test
    fun `a position without movements says its units are edited by hand`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a")))
        val job = open(s)

        assertTrue(s.state.movements.visible)
        assertTrue(s.state.movements.rows.isEmpty())
        assertEquals("Sin movimientos: las participaciones se editan a mano.", s.state.movements.emptyText)
        job.cancel()
    }

    @Test
    fun `a manual position has no movements section`() = runTest(dispatcher) {
        val s = setup("art", listOf(manual))
        val job = open(s)

        assertFalse(s.state.movements.visible)
        job.cancel()
    }

    @Test
    fun `only a fund with units and movements can be transferred`() = runTest(dispatcher) {
        val withMovements = setup("a", listOf(quoted("a")), listOf(buy("b1", "a", 5, 1)))
        val withoutMovements = setup("a", listOf(quoted("a")))
        val etf = setup("a", listOf(quoted("a", InstrumentType.ETF)), listOf(buy("b1", "a", 5, 1)))
        val closed = setup("a", listOf(quoted("a", units = UnitsBalance.ZERO)), listOf(buy("b1", "a", 5, 1)))
        val jobs = listOf(open(withMovements), open(withoutMovements), open(etf), open(closed))

        assertTrue(withMovements.state.movements.canTransfer)
        assertFalse(withoutMovements.state.movements.canTransfer)
        assertFalse(etf.state.movements.canTransfer)
        assertFalse(closed.state.movements.canTransfer)
        jobs.forEach { it.cancel() }
    }

    // ---- adding --------------------------------------------------------------------------------------------------

    @Test
    fun `adding to a position with units but no movements asks how to start`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a", units = UnitsBalance(200 * UNITS_SCALE))))
        val job = open(s)
        val nav = events(s)

        s.vm.onAddMovement()
        settle()

        val choice = s.state.choice
        assertEquals("Saldo inicial con tus 200,00 part.", choice?.openingLabel)
        assertEquals("Meter mis compras una a una", choice?.oneByOneLabel)
        assertNull(choice?.warning)
        assertTrue(nav.isEmpty())
        job.cancel()
    }

    @Test
    fun `choosing the opening balance opens that form`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a", units = UnitsBalance(200 * UNITS_SCALE))))
        val job = open(s)
        val nav = events(s)
        s.vm.onAddMovement()

        s.vm.onChooseOpeningBalance()
        settle()

        assertEquals(listOf<PositionNavigation>(PositionNavigation.MovementForm(null, openingBalance = true)), nav)
        assertNull(s.state.choice)
        job.cancel()
    }

    @Test
    fun `entering the purchases one by one warns that the units will come from them first`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a", units = UnitsBalance(200 * UNITS_SCALE))))
        val job = open(s)
        val nav = events(s)
        s.vm.onAddMovement()

        s.vm.onChooseOneByOne()
        settle()

        val warning = s.state.choice?.warning
        assertTrue(warning != null && "200,00 part." in warning, warning)
        assertTrue(nav.isEmpty())
        s.vm.onConfirmOneByOne()
        settle()

        assertEquals(listOf<PositionNavigation>(PositionNavigation.MovementForm(null, openingBalance = false)), nav)
        assertNull(s.state.choice)
        job.cancel()
    }

    @Test
    fun `the choice can be dismissed`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a", units = UnitsBalance(200 * UNITS_SCALE))))
        val job = open(s)
        val nav = events(s)
        s.vm.onAddMovement()

        s.vm.onDismissChoice()
        settle()

        assertNull(s.state.choice)
        assertTrue(nav.isEmpty())
        job.cancel()
    }

    @Test
    fun `a position without units goes straight to the movement form`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a", units = UnitsBalance.ZERO)))
        val job = open(s)
        val nav = events(s)

        s.vm.onAddMovement()
        settle()

        assertEquals(listOf<PositionNavigation>(PositionNavigation.MovementForm(null, openingBalance = false)), nav)
        assertNull(s.state.choice)
        job.cancel()
    }

    @Test
    fun `a position that already has movements goes straight to the movement form`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a")), listOf(buy("b1", "a", 5, 1)))
        val job = open(s)
        val nav = events(s)

        s.vm.onAddMovement()
        settle()

        assertEquals(listOf<PositionNavigation>(PositionNavigation.MovementForm(null, openingBalance = false)), nav)
        job.cancel()
    }

    // ---- opening one ---------------------------------------------------------------------------------------------

    @Test
    fun `tapping a movement opens the form that edits it`() = runTest(dispatcher) {
        val opening = Movement(
            id = "o1",
            holdingId = "a",
            type = MovementType.OPENING_BALANCE,
            date = LocalDate(2026, 1, 1),
            units = units(3),
            sequence = 1,
        )
        val s = setup(
            id = "a",
            holdings = listOf(quoted("a", units = UnitsBalance(13 * UNITS_SCALE))),
            movements = listOf(opening, buy("b1", "a", 5, 2)),
        )
        val job = open(s)
        val nav = events(s)

        s.vm.onOpenMovement("b1")
        s.vm.onOpenMovement("o1")
        s.vm.onOpenMovement("ghost")
        settle()

        assertEquals(
            listOf<PositionNavigation>(
                PositionNavigation.MovementForm("b1", openingBalance = false),
                PositionNavigation.MovementForm("o1", openingBalance = true),
            ),
            nav,
        )
        job.cancel()
    }

    @Test
    fun `tapping a transfer leg opens the transfer form`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a"), quoted("b")), listOf(buy("b1", "a", 5, 1)) + legs())
        val job = open(s)
        val nav = events(s)

        s.vm.onOpenMovement("out")
        settle()
        s.vm.onTransfer()
        settle()

        assertEquals(
            listOf<PositionNavigation>(PositionNavigation.Transfer("t1"), PositionNavigation.Transfer(null)),
            nav,
        )
        job.cancel()
    }

    @Test
    fun `a transfer cannot be started from a position that cannot make one`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a", InstrumentType.ETF)), listOf(buy("b1", "a", 5, 1)))
        val job = open(s)
        val nav = events(s)

        s.vm.onTransfer()
        settle()

        assertTrue(nav.isEmpty())
        job.cancel()
    }
}
