package com.denebapps.patrimonio.ui.screens.portfolio

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
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import com.denebapps.patrimonio.testing.settle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The edit sheet of a position whose units come from its movements. */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldingDetailMovementsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun fund(id: String, units: Long = 10) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo $id",
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = Currency.EUR,
            units = UnitsBalance(units * UNITS_SCALE),
        ),
    )

    private fun units(whole: Long) = Units(whole * UNITS_SCALE)

    private val buyA = Movement(
        id = "buy-a",
        holdingId = "a",
        type = MovementType.BUY,
        date = LocalDate(2026, 1, 15),
        units = units(10),
        amount = CurrencyAmount(Money(100_000), Currency.EUR),
        amountEur = Money(100_000),
        sequence = 1,
    )

    private class Setup(
        val holdings: FakePortfolioRepository,
        val movements: FakeMovementRepository,
        val vm: HoldingDetailViewModel,
    ) {
        val state get() = vm.state.value
    }

    private fun TestScope.setup(id: String, vararg initial: Movement, holdings: List<PortfolioHolding>): Setup {
        val portfolio = FakePortfolioRepository(holdings)
        val movements = FakeMovementRepository(portfolio, initial.toList())
        val vm = HoldingDetailViewModel(
            holdingId = id,
            portfolioRepository = portfolio,
            portfolios = FakePortfolioCollectionRepository(holdings = portfolio),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            movementRepository = movements,
            zoneProvider = { TimeZone.UTC },
        )
        backgroundScope.launch { vm.state.collect {} }
        settle()
        return Setup(portfolio, movements, vm)
    }

    @Test
    fun `a position with movements shows its units read-only with the reason`() = runTest(dispatcher) {
        val s = setup("a", buyA, holdings = listOf(fund("a")))

        assertTrue(s.state.unitsLocked)
        assertEquals("Las participaciones salen de tus movimientos.", s.state.unitsNote)
        assertEquals("10,00", s.state.editText)
        assertFalse(s.state.canSave)
    }

    @Test
    fun `a position without movements still edits its units`() = runTest(dispatcher) {
        val s = setup("a", holdings = listOf(fund("a")))

        assertFalse(s.state.unitsLocked)
        assertNull(s.state.unitsNote)
        assertEquals("10", s.state.editText)
    }

    @Test
    fun `the broker of a locked position can still be saved without touching the units`() = runTest(dispatcher) {
        val s = setup("a", buyA, holdings = listOf(fund("a")))
        val back = mutableListOf<Unit>()
        backgroundScope.launch { s.vm.navigateBack.collect { back += it } }

        s.vm.onBrokerChange("MyInvestor")
        s.vm.onEditChange("3") // ignored: the units are not editable
        settle()
        assertTrue(s.state.canSave)
        s.vm.onSave()
        settle()

        assertEquals("MyInvestor", s.holdings.current.single().broker)
        val held = (s.holdings.current.single().valuation as HoldingValuation.Quoted).units
        assertEquals(UnitsBalance(10 * UNITS_SCALE), held)
        assertEquals(1, back.size)
    }

    @Test
    fun `deleting a fund that has transfers is refused with the reason and the sheet stays open`() =
        runTest(dispatcher) {
            val out = Movement(
                id = "out",
                holdingId = "a",
                type = MovementType.TRANSFER_OUT,
                date = LocalDate(2026, 2, 1),
                units = units(4),
                transferId = "t1",
                sequence = 2,
            )
            val s = setup(
                "b",
                buyA,
                out,
                out.copy(id = "in", holdingId = "b", type = MovementType.TRANSFER_IN, sequence = 3),
                holdings = listOf(fund("a", 6), fund("b", 4)),
            )
            val back = mutableListOf<Unit>()
            backgroundScope.launch { s.vm.navigateBack.collect { back += it } }

            s.vm.onDelete()
            settle()

            assertNotNull(s.state.error)
            assertTrue(back.isEmpty())
            assertEquals(2, s.holdings.current.size)
        }

    @Test
    fun `deleting a fund with plain movements removes it and closes the sheet`() = runTest(dispatcher) {
        val s = setup("a", buyA, holdings = listOf(fund("a")))
        val back = mutableListOf<Unit>()
        backgroundScope.launch { s.vm.navigateBack.collect { back += it } }

        s.vm.onDelete()
        settle()

        assertTrue(s.holdings.current.isEmpty())
        assertTrue(s.movements.current.isEmpty())
        assertEquals(1, back.size)
    }
}
