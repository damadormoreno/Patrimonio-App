package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
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
import com.denebapps.patrimonio.domain.repository.MovementDraft
import com.denebapps.patrimonio.testing.FakeMovementRepository
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The full list of the open lots of a position, oldest first, with the note about the lots of a transfer. */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionLotsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun quoted(id: String) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo $id",
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = Currency.EUR,
            units = UnitsBalance(10 * UNITS_SCALE),
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

    private fun buy(id: String, date: LocalDate, sequence: Long, holding: String = "a", cents: Long = 10_000) =
        Movement(
            id = id,
            holdingId = holding,
            type = MovementType.BUY,
            date = date,
            units = units(1),
            amount = CurrencyAmount(Money(cents), Currency.EUR),
            amountEur = Money(cents),
            feeEur = Money.ZERO,
            sequence = sequence,
        )

    private class Setup(
        val vm: PositionLotsViewModel,
        val portfolio: FakePortfolioRepository,
        val movements: FakeMovementRepository,
    ) {
        val state get() = vm.state.value
    }

    private fun setup(id: String, holdings: List<PortfolioHolding>, movements: List<Movement> = emptyList()): Setup {
        val portfolio = FakePortfolioRepository(holdings)
        val repository = FakeMovementRepository(portfolio, movements)
        return Setup(PositionLotsViewModel(id, portfolio, repository, dispatcher), portfolio, repository)
    }

    private fun TestScope.open(s: Setup): Job {
        val job = launch { s.vm.state.collect {} }
        settle()
        return job
    }

    @Test
    fun `the screen names the product and lists every open lot, oldest first`() = runTest(dispatcher) {
        val s = setup(
            "a",
            listOf(quoted("a")),
            (1..5).map { buy("b$it", LocalDate(2026, it, 10), it.toLong(), cents = 10_000L * it) },
        )
        val job = open(s)

        assertTrue(s.state.loaded)
        assertEquals("Fondo a", s.state.holdingName)
        assertEquals(
            listOf("10/01/2026", "10/02/2026", "10/03/2026", "10/04/2026", "10/05/2026"),
            s.state.lots.map { it.dateText },
        )
        assertEquals(LotUi("10/01/2026", "1,00 part.", "100,00 €"), s.state.lots.first())
        assertNull(s.state.note)
        assertNull(s.state.emptyText)
        job.cancel()
    }

    @Test
    fun `a lot of unknown cost is shown as unknown`() = runTest(dispatcher) {
        val opening = Movement(
            id = "open",
            holdingId = "a",
            type = MovementType.OPENING_BALANCE,
            date = LocalDate(2025, 12, 31),
            units = units(4),
            sequence = 1,
        )
        val s = setup("a", listOf(quoted("a")), listOf(opening, buy("b1", LocalDate(2026, 3, 1), 2)))
        val job = open(s)

        assertEquals(
            listOf(
                LotUi("31/12/2025", "4,00 part.", UNKNOWN_COST_TEXT),
                LotUi("01/03/2026", "1,00 part.", "100,00 €"),
            ),
            s.state.lots,
        )
        job.cancel()
    }

    @Test
    fun `a lot that arrived in a transfer keeps its date and the note explains it`() = runTest(dispatcher) {
        val out = Movement(
            id = "out",
            holdingId = "a",
            type = MovementType.TRANSFER_OUT,
            date = LocalDate(2026, 7, 1),
            units = units(1),
            transferId = "t1",
            sequence = 2,
        )
        val legs = listOf(out, out.copy(id = "in", holdingId = "b", type = MovementType.TRANSFER_IN, sequence = 3))
        val s = setup("b", listOf(quoted("a"), quoted("b")), listOf(buy("b1", LocalDate(2026, 1, 15), 1)) + legs)
        val job = open(s)

        assertEquals(listOf(LotUi("15/01/2026", "1,00 part.", "100,00 €")), s.state.lots)
        assertEquals("Los lotes recibidos por traspaso conservan su fecha de compra original.", s.state.note)
        job.cancel()
    }

    @Test
    fun `a movement added through the repository adds its lot`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a")), listOf(buy("b1", LocalDate(2026, 1, 15), 1)))
        val job = open(s)
        assertEquals(1, s.state.lots.size)

        s.movements.addMovement(
            "a",
            MovementDraft(
                type = MovementType.BUY,
                date = LocalDate(2026, 5, 2),
                units = units(2),
                amount = CurrencyAmount(Money(20_000), Currency.EUR),
            ),
        )
        settle()

        assertEquals(listOf("15/01/2026", "02/05/2026"), s.state.lots.map { it.dateText })
        job.cancel()
    }

    @Test
    fun `a closed position has no open lots and says so`() = runTest(dispatcher) {
        val sale = Movement(
            id = "s1",
            holdingId = "a",
            type = MovementType.SELL,
            date = LocalDate(2026, 6, 1),
            units = units(1),
            amount = CurrencyAmount(Money(12_000), Currency.EUR),
            amountEur = Money(12_000),
            feeEur = Money.ZERO,
            sequence = 2,
        )
        val s = setup("a", listOf(quoted("a")), listOf(buy("b1", LocalDate(2026, 1, 15), 1), sale))
        val job = open(s)

        assertTrue(s.state.loaded)
        assertTrue(s.state.lots.isEmpty())
        assertEquals("No hay lotes abiertos: la posición está cerrada.", s.state.emptyText)
        assertNull(s.state.note)
        job.cancel()
    }

    @Test
    fun `a position without movements has no lots`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a")))
        val job = open(s)

        assertTrue(s.state.loaded)
        assertTrue(s.state.lots.isEmpty())
        assertEquals("No hay lotes abiertos: las participaciones no vienen de movimientos.", s.state.emptyText)
        job.cancel()
    }

    @Test
    fun `a manual position has no lots`() = runTest(dispatcher) {
        val s = setup("art", listOf(manual))
        val job = open(s)

        assertTrue(s.state.loaded)
        assertTrue(s.state.lots.isEmpty())
        assertEquals("No hay lotes abiertos: las participaciones no vienen de movimientos.", s.state.emptyText)
        job.cancel()
    }

    @Test
    fun `a holding that disappears sends the user back`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a")))
        val job = open(s)
        var back = 0
        backgroundScope.launch { s.vm.navigateBack.collect { back++ } }
        settle()

        s.portfolio.delete("a")
        settle()

        assertEquals(1, back)
        assertFalse(s.state.loaded)
        job.cancel()
    }
}
