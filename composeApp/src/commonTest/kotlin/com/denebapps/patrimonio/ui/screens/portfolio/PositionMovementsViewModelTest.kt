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

/** The full list of the movements of a position: grouped by year, newest first, kept up to date and editable. */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionMovementsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun quoted(id: String, units: UnitsBalance = UnitsBalance(10 * UNITS_SCALE)) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo $id",
        type = InstrumentType.FUND,
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

    private fun buy(id: String, date: LocalDate, sequence: Long, holding: String = "a") = Movement(
        id = id,
        holdingId = holding,
        type = MovementType.BUY,
        date = date,
        units = units(1),
        amount = CurrencyAmount(Money(10_000), Currency.EUR),
        amountEur = Money(10_000),
        feeEur = Money.ZERO,
        sequence = sequence,
    )

    private class Setup(
        val vm: PositionMovementsViewModel,
        val portfolio: FakePortfolioRepository,
        val movements: FakeMovementRepository,
    ) {
        val state get() = vm.state.value
    }

    private fun setup(id: String, holdings: List<PortfolioHolding>, movements: List<Movement> = emptyList()): Setup {
        val portfolio = FakePortfolioRepository(holdings)
        val repository = FakeMovementRepository(portfolio, movements)
        return Setup(PositionMovementsViewModel(id, portfolio, repository), portfolio, repository)
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
    fun `the screen names the product and groups the movements by year, newest first`() = runTest(dispatcher) {
        val s = setup(
            "a",
            listOf(quoted("a")),
            listOf(
                buy("m2024", LocalDate(2024, 12, 31), 1),
                buy("m2025a", LocalDate(2025, 1, 2), 2),
                buy("m2025b", LocalDate(2025, 3, 5), 3),
                buy("m2026", LocalDate(2026, 1, 1), 4),
            ),
        )
        val job = open(s)

        assertTrue(s.state.loaded)
        assertEquals("Fondo a", s.state.holdingName)
        assertEquals(listOf(2026, 2025, 2024), s.state.groups.map { it.year })
        assertEquals(
            listOf(listOf("m2026"), listOf("m2025b", "m2025a"), listOf("m2024")),
            s.state.groups.map { group -> group.rows.map { it.id } },
        )
        assertNull(s.state.emptyText)
        job.cancel()
    }

    @Test
    fun `movements of the same day keep the order they are shown in the detail, the last one first`() =
        runTest(dispatcher) {
            val day = LocalDate(2026, 3, 1)
            val s = setup("a", listOf(quoted("a")), listOf(buy("first", day, 1), buy("second", day, 2)))
            val job = open(s)

            assertEquals(listOf("second", "first"), s.state.groups.single().rows.map { it.id })
            job.cancel()
        }

    @Test
    fun `the rows are the same as the ones of the detail, with the counterpart of a transfer`() = runTest(dispatcher) {
        val out = Movement(
            id = "out",
            holdingId = "a",
            type = MovementType.TRANSFER_OUT,
            date = LocalDate(2026, 4, 1),
            units = units(1),
            transferId = "t1",
            sequence = 2,
        )
        val legs = listOf(out, out.copy(id = "in", holdingId = "b", type = MovementType.TRANSFER_IN, sequence = 3))
        val s = setup("a", listOf(quoted("a"), quoted("b")), listOf(buy("b1", LocalDate(2026, 3, 1), 1)) + legs)
        val job = open(s)

        val rows = s.state.groups.single().rows
        assertEquals(listOf("out", "b1"), rows.map { it.id })
        assertEquals("Traspaso a «Fondo b»", rows[0].title)
        assertEquals("−1,00 part.", rows[0].unitsText)
        assertEquals("Compra", rows[1].title)
        job.cancel()
    }

    @Test
    fun `a movement added through the repository appears at the top of its year`() = runTest(dispatcher) {
        val s = setup(
            "a",
            listOf(quoted("a")),
            listOf(buy("old", LocalDate(2025, 6, 1), 1), buy("recent", LocalDate(2026, 2, 1), 2)),
        )
        val job = open(s)
        assertEquals(listOf(2026, 2025), s.state.groups.map { it.year })

        val added = s.movements.addMovement(
            "a",
            MovementDraft(
                type = MovementType.BUY,
                date = LocalDate(2027, 1, 15),
                units = units(2),
                amount = CurrencyAmount(Money(20_000), Currency.EUR),
            ),
        )
        settle()

        assertEquals(listOf(2027, 2026, 2025), s.state.groups.map { it.year })
        assertEquals(listOf(added.id), s.state.groups.first().rows.map { it.id })
        job.cancel()
    }

    @Test
    fun `a deleted movement leaves the list and an emptied year disappears`() = runTest(dispatcher) {
        val s = setup(
            "a",
            listOf(quoted("a")),
            listOf(buy("old", LocalDate(2025, 6, 1), 1), buy("recent", LocalDate(2026, 2, 1), 2)),
        )
        val job = open(s)

        s.movements.deleteMovement("recent")
        settle()

        assertEquals(listOf(2025), s.state.groups.map { it.year })
        job.cancel()
    }

    // ---- empty states --------------------------------------------------------------------------------------------

    @Test
    fun `a position without movements says its units are edited by hand`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a")))
        val job = open(s)

        assertTrue(s.state.loaded)
        assertTrue(s.state.groups.isEmpty())
        assertEquals("Sin movimientos: las participaciones se editan a mano.", s.state.emptyText)
        assertTrue(s.state.canAdd)
        job.cancel()
    }

    @Test
    fun `a closed position still lists its movements`() = runTest(dispatcher) {
        val s = setup(
            "a",
            listOf(quoted("a", units = UnitsBalance.ZERO)),
            listOf(buy("m1", LocalDate(2026, 1, 1), 1)),
        )
        val job = open(s)

        assertEquals(listOf("m1"), s.state.groups.single().rows.map { it.id })
        assertTrue(s.state.canAdd)
        job.cancel()
    }

    @Test
    fun `a manual position has no movements and nothing to add`() = runTest(dispatcher) {
        val s = setup("art", listOf(manual))
        val job = open(s)

        assertTrue(s.state.loaded)
        assertTrue(s.state.groups.isEmpty())
        assertEquals("Este producto se valora a mano: no tiene movimientos.", s.state.emptyText)
        assertFalse(s.state.canAdd)
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

    // ---- editing and adding --------------------------------------------------------------------------------------

    @Test
    fun `opening a row opens the sheet of that movement, and a transfer leg its transfer`() = runTest(dispatcher) {
        val out = Movement(
            id = "out",
            holdingId = "a",
            type = MovementType.TRANSFER_OUT,
            date = LocalDate(2026, 4, 1),
            units = units(1),
            transferId = "t1",
            sequence = 2,
        )
        val legs = listOf(out, out.copy(id = "in", holdingId = "b", type = MovementType.TRANSFER_IN, sequence = 3))
        val s = setup("a", listOf(quoted("a"), quoted("b")), listOf(buy("b1", LocalDate(2026, 3, 1), 1)) + legs)
        val job = open(s)
        val nav = events(s)

        s.vm.onOpenMovement("b1")
        s.vm.onOpenMovement("out")
        settle()

        assertEquals(
            listOf(PositionNavigation.MovementForm("b1", openingBalance = false), PositionNavigation.Transfer("t1")),
            nav,
        )
        job.cancel()
    }

    @Test
    fun `an opening balance opens its own form`() = runTest(dispatcher) {
        val opening = Movement(
            id = "open",
            holdingId = "a",
            type = MovementType.OPENING_BALANCE,
            date = LocalDate(2026, 1, 1),
            units = units(5),
            sequence = 1,
        )
        val s = setup("a", listOf(quoted("a")), listOf(opening))
        val job = open(s)
        val nav = events(s)

        s.vm.onOpenMovement("open")
        settle()

        assertEquals(listOf<PositionNavigation>(PositionNavigation.MovementForm("open", openingBalance = true)), nav)
        job.cancel()
    }

    @Test
    fun `adding to a position with movements goes straight to the movement form`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a")), listOf(buy("m1", LocalDate(2026, 1, 1), 1)))
        val job = open(s)
        val nav = events(s)

        s.vm.onAddMovement()
        settle()

        assertEquals(listOf<PositionNavigation>(PositionNavigation.MovementForm(null, openingBalance = false)), nav)
        assertNull(s.state.choice)
        job.cancel()
    }

    @Test
    fun `adding to a position with units but no movements asks how to start`() = runTest(dispatcher) {
        val s = setup("a", listOf(quoted("a", units = UnitsBalance(200 * UNITS_SCALE))))
        val job = open(s)
        val nav = events(s)

        s.vm.onAddMovement()
        settle()

        assertEquals("Saldo inicial con tus 200,00 part.", s.state.choice?.openingLabel)
        assertTrue(nav.isEmpty())

        s.vm.onChooseOpeningBalance()
        settle()

        assertEquals(listOf<PositionNavigation>(PositionNavigation.MovementForm(null, openingBalance = true)), nav)
        assertNull(s.state.choice)
        job.cancel()
    }

    @Test
    fun `a manual position cannot add a movement`() = runTest(dispatcher) {
        val s = setup("art", listOf(manual))
        val job = open(s)
        val nav = events(s)

        s.vm.onAddMovement()
        settle()

        assertTrue(nav.isEmpty())
        job.cancel()
    }
}
