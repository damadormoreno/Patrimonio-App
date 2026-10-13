package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.computeLedgers
import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.FxHistory
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
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FxSyncCall
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

@OptIn(ExperimentalCoroutinesApi::class)
class MovementFormViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate(2026, 10, 11)
    private val clock = fixedClock("2026-10-11T10:00:00Z")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun quoted(
        id: String,
        type: InstrumentType = InstrumentType.FUND,
        currency: Currency = Currency.EUR,
        units: UnitsBalance = UnitsBalance(10 * UNITS_SCALE),
    ) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Producto $id",
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = currency,
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

    private val usdRates = FxHistory(mapOf(Currency.USD to listOf(DatedFxRate(LocalDate(2026, 10, 9), 920_000))))

    private class Setup(
        val holdings: FakePortfolioRepository,
        val movements: FakeMovementRepository,
        val fx: FakeFxHistoryRepository,
        val vm: MovementFormViewModel,
    ) {
        val state get() = vm.state.value
    }

    private fun TestScope.setup(
        holdingId: String,
        holdings: List<PortfolioHolding>,
        initial: List<Movement> = emptyList(),
        movementId: String? = null,
        openingBalance: Boolean = false,
        fxHistory: FxHistory = FxHistory(emptyMap()),
    ): Setup {
        val portfolio = FakePortfolioRepository(holdings)
        val movements = FakeMovementRepository(portfolio, initial)
        val fx = FakeFxHistoryRepository(fxHistory)
        val vm = MovementFormViewModel(
            holdingId = holdingId,
            movementId = movementId,
            openingBalance = openingBalance,
            portfolioRepository = portfolio,
            movementRepository = movements,
            fxHistoryRepository = fx,
            clock = clock,
            zoneProvider = { TimeZone.UTC },
        )
        settle()
        return Setup(portfolio, movements, fx, vm)
    }

    private fun TestScope.collectBack(vm: MovementFormViewModel): MutableList<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch { vm.navigateBack.collect { events += it } }
        return events
    }

    private fun units(whole: Long) = Units(whole * UNITS_SCALE)

    private fun eur(cents: Long) = CurrencyAmount(Money(cents), Currency.EUR)

    private fun existingBuy(id: String = "m1", holding: String = "f", sequence: Long = 1) = Movement(
        id = id,
        holdingId = holding,
        type = MovementType.BUY,
        date = LocalDate(2026, 1, 15),
        units = units(10),
        amount = eur(100_000),
        fee = Money(500),
        amountEur = Money(100_000),
        feeEur = Money(500),
        note = "Primera",
        sequence = sequence,
    )

    // ---- new movement --------------------------------------------------------------------------------------------

    @Test
    fun `a new movement starts as a buy dated today and cannot be saved empty`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f", InstrumentType.ETF)))

        assertTrue(s.state.loaded)
        assertEquals("Nuevo movimiento", s.state.title)
        assertEquals("Producto f", s.state.holdingName)
        assertEquals(MovementChoice.BUY, s.state.choice)
        assertEquals(today, s.state.date)
        assertTrue(s.state.showUnits)
        assertTrue(s.state.showFee)
        assertFalse(s.state.showEur)
        assertFalse(s.state.canSave)
        assertFalse(s.state.canDelete)
    }

    @Test
    fun `only a fund can choose a transfer`() = runTest(dispatcher) {
        val fund = setup("f", listOf(quoted("f")))
        val etf = setup("f", listOf(quoted("f", InstrumentType.ETF)))

        assertEquals(
            listOf(
                MovementChoice.BUY,
                MovementChoice.SELL,
                MovementChoice.DIVIDEND,
                MovementChoice.FEE,
                MovementChoice.TRANSFER,
            ),
            fund.state.choices,
        )
        assertEquals(
            listOf(MovementChoice.BUY, MovementChoice.SELL, MovementChoice.DIVIDEND, MovementChoice.FEE),
            etf.state.choices,
        )
    }

    @Test
    fun `dividends and fees have neither units nor a fee field`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f")))

        s.vm.onChoiceChange(MovementChoice.DIVIDEND)

        assertFalse(s.state.showUnits)
        assertFalse(s.state.showFee)
        s.vm.onAmountChange("12,50")
        assertTrue(s.state.canSave)
    }

    @Test
    fun `a buy is saved with its units, amount and fee and closes the sheet`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f", units = UnitsBalance.ZERO)))
        val back = collectBack(s.vm)

        s.vm.onUnitsChange("10")
        s.vm.onAmountChange("647,61")
        s.vm.onFeeChange("2")
        s.vm.onNoteChange("  Aportación  ")
        assertEquals("≈ 64,761 €/part.", s.state.impliedPriceText)
        assertTrue(s.state.canSave)
        s.vm.onSave()
        settle()

        val stored = s.movements.current.single()
        assertEquals(
            MovementDraft(
                type = MovementType.BUY,
                date = today,
                units = units(10),
                amount = eur(64_761),
                fee = Money(200),
                note = "Aportación",
            ),
            MovementDraft(stored.type, stored.date, stored.units, stored.amount, stored.fee, note = stored.note),
        )
        assertEquals(1, back.size)
    }

    @Test
    fun `a movement the history cannot take shows the reason and keeps the form open`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f", units = UnitsBalance.ZERO)), listOf(existingBuy()))
        val back = collectBack(s.vm)

        s.vm.onChoiceChange(MovementChoice.SELL)
        s.vm.onUnitsChange("11")
        s.vm.onAmountChange("100")
        s.vm.onSave()
        settle()

        val error = assertNotNull(s.state.error)
        assertTrue("solo tienes 10" in error, error)
        assertTrue(s.state.canSave)
        assertTrue(back.isEmpty())
        assertEquals(1, s.movements.current.size)
        s.vm.onUnitsChange("5")
        assertNull(s.state.error)
    }

    @Test
    fun `a sell dated before the buy is refused with its reason`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f")), listOf(existingBuy()))

        s.vm.onChoiceChange(MovementChoice.SELL)
        s.vm.onDateChange(LocalDate(2026, 1, 1))
        s.vm.onUnitsChange("1")
        s.vm.onAmountChange("10")
        s.vm.onSave()
        settle()

        assertNotNull(s.state.error)
        assertEquals(1, s.movements.current.size)
    }

    @Test
    fun `choosing a transfer asks to open the transfer form and keeps the type`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f")))
        val events = mutableListOf<Unit>()
        backgroundScope.launch { s.vm.openTransfer.collect { events += it } }

        s.vm.onChoiceChange(MovementChoice.TRANSFER)
        settle()

        assertEquals(1, events.size)
        assertEquals(MovementChoice.BUY, s.state.choice)
    }

    @Test
    fun `the note is limited in length`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f")))

        s.vm.onNoteChange("x".repeat(500))

        assertEquals(200, s.state.noteText.length)
    }

    // ---- other currencies ----------------------------------------------------------------------------------------

    @Test
    fun `a holding in dollars prefills the euro equivalent from the rate of that day and syncs it`() =
        runTest(dispatcher) {
            val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)

            assertEquals(listOf(FxSyncCall(setOf(Currency.USD), today, false)), s.fx.syncCalls)
            assertTrue(s.state.showEur)
            s.vm.onUnitsChange("1")
            s.vm.onAmountChange("100")
            s.vm.onFeeCurrencyChange(false)
            s.vm.onFeeChange("10")

            assertEquals("92,00", s.state.eurText)
            assertNull(s.state.eurHint)
            assertTrue(s.state.canSave)
            s.vm.onSave()
            settle()
            val stored = s.movements.current.single()
            assertEquals(Money(9_200), stored.amountEur)
            assertEquals(Money(920), stored.feeEur)
        }

    @Test
    fun `the euro equivalent can be overridden and the date picks its own rate`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
        s.vm.onUnitsChange("1")
        s.vm.onAmountChange("100")

        s.vm.onEurChange("95")
        assertEquals("95", s.state.eurText)
        s.vm.onSave()
        settle()
        assertEquals(Money(9_500), s.movements.current.single().amountEur)

        s.vm.onDateChange(LocalDate(2026, 10, 9))
        settle()
        assertEquals(2, s.fx.syncCalls.size)
        assertEquals(LocalDate(2026, 10, 9), s.fx.syncCalls.last().from)
    }

    @Test
    fun `without a rate for the day the euro equivalent has to be typed`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)))
        s.vm.onUnitsChange("1")
        s.vm.onAmountChange("100")

        assertEquals("", s.state.eurText)
        assertNotNull(s.state.eurHint)
        assertFalse(s.state.canSave)
        s.vm.onEurChange("90")
        assertTrue(s.state.canSave)
    }

    @Test
    fun `a failed rate download does not stop the form`() = runTest(dispatcher) {
        val portfolio = FakePortfolioRepository(listOf(quoted("u", currency = Currency.USD)))
        val fx = FakeFxHistoryRepository().apply { syncFailure = IllegalStateException("offline") }
        val vm = MovementFormViewModel(
            holdingId = "u",
            movementId = null,
            openingBalance = false,
            portfolioRepository = portfolio,
            movementRepository = FakeMovementRepository(portfolio),
            fxHistoryRepository = fx,
            clock = clock,
            zoneProvider = { TimeZone.UTC },
        )
        settle()

        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.showEur)
        assertNotNull(vm.state.value.eurHint)
    }

    // ---- two-way amounts -----------------------------------------------------------------------------------------

    private fun usd(cents: Long) = CurrencyAmount(Money(cents), Currency.USD)

    private val usdRatesTwoDays = FxHistory(
        mapOf(
            Currency.USD to listOf(
                DatedFxRate(LocalDate(2026, 9, 1), 900_000),
                DatedFxRate(LocalDate(2026, 10, 9), 920_000),
            ),
        ),
    )

    @Test
    fun `typing the euros of a dollar holding fills in the amount from the rate of the day`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
        s.vm.onUnitsChange("1")

        s.vm.onEurChange("50")

        // 5.000 cents x 1.000.000 / 920.000 = 5.434,78 -> 54,35
        assertEquals("54,35", s.state.amountText)
        assertEquals("50", s.state.eurText)
        assertEquals("≈ 54,35 $/part.", s.state.impliedPriceText)
        assertEquals("1 USD = 0,9200 €", s.state.impliedRateText)
        assertNull(s.state.amountHint)
        assertTrue(s.state.canSave)
        s.vm.onSave()
        settle()
        val stored = s.movements.current.single()
        assertEquals(usd(5_435), stored.amount)
        assertEquals(Money(5_000), stored.amountEur)
    }

    @Test
    fun `the field that is not typed follows the one that is as it changes`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)

        s.vm.onAmountChange("100")
        assertEquals("92,00", s.state.eurText)
        s.vm.onAmountChange("200")
        assertEquals("184,00", s.state.eurText)

        s.vm.onAmountChange("")
        s.vm.onEurChange("50")
        assertEquals("54,35", s.state.amountText)
        s.vm.onEurChange("60")
        assertEquals("65,22", s.state.amountText)
    }

    @Test
    fun `when both amounts are typed both are kept and the implied rate is shown`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
        s.vm.onUnitsChange("1")

        s.vm.onAmountChange("100")
        s.vm.onEurChange("86,12")
        assertEquals("100", s.state.amountText)
        assertEquals("86,12", s.state.eurText)
        assertEquals("1 USD = 0,8612 €", s.state.impliedRateText)

        // Typing the amount again no longer resets the euros: that is what the broker charged.
        s.vm.onAmountChange("200")
        assertEquals("86,12", s.state.eurText)
        assertEquals("1 USD = 0,4306 €", s.state.impliedRateText)
        s.vm.onSave()
        settle()
        val stored = s.movements.current.single()
        assertEquals(usd(20_000), stored.amount)
        assertEquals(Money(8_612), stored.amountEur)
    }

    @Test
    fun `clearing a typed field makes it follow the other one again`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
        s.vm.onUnitsChange("1")
        s.vm.onAmountChange("100")
        s.vm.onEurChange("95")

        // The field stays empty while it is being edited, so the user can type a new value, but it already follows.
        s.vm.onEurChange("")
        assertEquals("", s.state.eurText)
        assertTrue(s.state.canSave)
        // As soon as anything else changes it shows the value it follows.
        s.vm.onUnitsChange("2")
        assertEquals("92,00", s.state.eurText)
        s.vm.onSave()
        settle()
        assertEquals(Money(9_200), s.movements.current.single().amountEur)
    }

    @Test
    fun `a field held blank shows the value it follows as a placeholder, and a shown value needs none`() =
        runTest(dispatcher) {
            val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
            s.vm.onEurChange("50")
            assertEquals("54,35", s.state.amountText)
            assertNull(s.state.amountPlaceholder)
            assertNull(s.state.eurPlaceholder)

            s.vm.onAmountChange("100")
            s.vm.onAmountChange("")

            assertEquals("", s.state.amountText)
            assertEquals("54,35", s.state.amountPlaceholder)
            assertNull(s.state.eurPlaceholder)
            s.vm.onUnitsChange("1")
            assertEquals("54,35", s.state.amountText)
            assertNull(s.state.amountPlaceholder)
        }

    @Test
    fun `the euro field held blank shows the euros of the typed amount as a placeholder`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
        s.vm.onAmountChange("100")
        s.vm.onEurChange("95")

        s.vm.onEurChange("")

        assertEquals("", s.state.eurText)
        assertEquals("92,00", s.state.eurPlaceholder)
        assertNull(s.state.amountPlaceholder)
    }

    @Test
    fun `a blank field with nothing to follow has no placeholder`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)

        assertNull(s.state.amountPlaceholder)
        assertNull(s.state.eurPlaceholder)
        s.vm.onAmountChange("100")
        s.vm.onAmountChange("")
        assertNull(s.state.amountPlaceholder)
        assertNull(s.state.eurPlaceholder)
    }

    @Test
    fun `clearing the typed amount derives it from the typed euros`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
        s.vm.onUnitsChange("1")
        s.vm.onAmountChange("100")
        s.vm.onEurChange("50")

        s.vm.onAmountChange("")
        assertTrue(s.state.canSave)
        s.vm.onFeeChange("1")

        assertEquals("54,35", s.state.amountText)
        assertEquals("50", s.state.eurText)
    }

    @Test
    fun `a new date recomputes the field that is derived and leaves the typed one alone`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRatesTwoDays)
        s.vm.onEurChange("90")
        assertEquals("97,83", s.state.amountText) // 9.000 x 1.000.000 / 920.000 = 9.782,6

        s.vm.onDateChange(LocalDate(2026, 9, 1))

        assertEquals("90", s.state.eurText)
        assertEquals("100,00", s.state.amountText) // at 0,90 EUR per USD

        s.vm.onEurChange("")
        s.vm.onAmountChange("100")
        s.vm.onDateChange(LocalDate(2026, 10, 9))
        assertEquals("100", s.state.amountText)
        assertEquals("92,00", s.state.eurText)
        s.vm.onDateChange(LocalDate(2026, 9, 1))
        assertEquals("90,00", s.state.eurText)
    }

    @Test
    fun `without a rate typing only the euros asks for the amount in the holding currency`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)))
        s.vm.onUnitsChange("1")

        s.vm.onEurChange("90")

        assertEquals("No hay cambio de esa fecha: escribe el importe en USD.", s.state.amountHint)
        assertNull(s.state.eurHint)
        assertEquals("", s.state.amountText)
        assertFalse(s.state.canSave)
        s.vm.onAmountChange("100")
        assertNull(s.state.amountHint)
        assertEquals("1 USD = 0,9000 €", s.state.impliedRateText)
        assertTrue(s.state.canSave)
    }

    @Test
    fun `a holding in euros has no second amount, hint or rate`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f")))
        s.vm.onUnitsChange("1")
        s.vm.onAmountChange("100")

        assertFalse(s.state.showEur)
        assertNull(s.state.amountHint)
        assertNull(s.state.impliedRateText)
        assertEquals("100", s.state.amountText)
    }

    @Test
    fun `an opening balance in dollars may take its cost in euros and stays unknown with neither`() =
        runTest(dispatcher) {
            val holding = quoted("u", currency = Currency.USD, units = UnitsBalance(2 * UNITS_SCALE))
            val known = setup("u", listOf(holding), openingBalance = true, fxHistory = usdRates)
            val unknown = setup("u", listOf(holding), openingBalance = true, fxHistory = usdRates)

            assertTrue(known.state.showEur)
            assertNull(known.state.eurHint)
            known.vm.onEurChange("50")
            assertEquals("54,35", known.state.amountText)
            known.vm.onSave()
            settle()
            assertEquals(usd(5_435), known.movements.current.single().amount)
            assertEquals(Money(5_000), known.movements.current.single().amountEur)

            unknown.vm.onSave()
            settle()
            assertNull(unknown.movements.current.single().amount)
            assertNull(unknown.movements.current.single().amountEur)
        }

    // ---- editing and deleting ------------------------------------------------------------------------------------

    @Test
    fun `editing a movement in dollars loads both stored amounts as typed`() = runTest(dispatcher) {
        val stored = existingBuy(holding = "u").copy(
            amount = usd(10_000),
            amountEur = Money(9_500),
            fee = Money(500),
            feeEur = Money(475),
        )
        // Rates of other days: none for 15 January, so a recomputation would leave the form without euros.
        val s = setup(
            "u",
            listOf(quoted("u", currency = Currency.USD)),
            listOf(stored),
            movementId = "m1",
            fxHistory = usdRates,
        )

        assertEquals("100,00", s.state.amountText)
        assertEquals("95,00", s.state.eurText)
        assertEquals("1 USD = 0,9500 €", s.state.impliedRateText)
        assertNull(s.state.eurHint)
        assertNull(s.state.amountHint)
        assertTrue(s.state.canSave)

        s.vm.onDateChange(LocalDate(2026, 10, 9))
        assertEquals("100,00", s.state.amountText)
        assertEquals("95,00", s.state.eurText)
        s.vm.onSave()
        settle()
        val saved = s.movements.current.single()
        assertEquals(usd(10_000), saved.amount)
        assertEquals(Money(9_500), saved.amountEur)
        assertEquals(Money(475), saved.feeEur)
    }

    @Test
    fun `an existing movement is loaded into the form and saved over itself`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f")), listOf(existingBuy()), movementId = "m1")
        val back = collectBack(s.vm)

        assertEquals("Editar movimiento", s.state.title)
        assertEquals(MovementChoice.BUY, s.state.choice)
        assertEquals(LocalDate(2026, 1, 15), s.state.date)
        assertEquals("10", s.state.unitsText)
        assertEquals("1000,00", s.state.amountText)
        assertEquals("5,00", s.state.feeText)
        assertEquals("Primera", s.state.noteText)
        assertTrue(s.state.canDelete)

        s.vm.onUnitsChange("12")
        s.vm.onSave()
        settle()

        val stored = s.movements.current.single()
        assertEquals("m1", stored.id)
        assertEquals(units(12), stored.units)
        assertEquals(1, back.size)
    }

    @Test
    fun `deleting the only movement warns that the position goes back to manual units`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f")), listOf(existingBuy()), movementId = "m1")
        val back = collectBack(s.vm)

        assertEquals(
            "La posición quedará con 0 participaciones y volverá a editarse a mano.",
            s.state.deleteMessage,
        )
        s.vm.onDelete()
        settle()

        assertTrue(s.movements.current.isEmpty())
        assertEquals(1, back.size)
    }

    @Test
    fun `deleting one of several movements gives the usual warning`() = runTest(dispatcher) {
        val s = setup(
            "f",
            listOf(quoted("f")),
            listOf(existingBuy(), existingBuy(id = "m2", sequence = 2)),
            movementId = "m1",
        )

        assertEquals("Se recalcularán las participaciones con los demás movimientos.", s.state.deleteMessage)
    }

    @Test
    fun `a delete the history needs is refused with its reason`() = runTest(dispatcher) {
        val sell = existingBuy(id = "m2", sequence = 2).copy(
            type = MovementType.SELL,
            date = LocalDate(2026, 2, 1),
            units = units(10),
            fee = Money.ZERO,
            feeEur = Money.ZERO,
        )
        val s = setup("f", listOf(quoted("f")), listOf(existingBuy(), sell), movementId = "m1")
        val back = collectBack(s.vm)

        s.vm.onDelete()
        settle()

        assertNotNull(s.state.error)
        assertEquals(2, s.movements.current.size)
        assertTrue(back.isEmpty())
    }

    // ---- opening balance -----------------------------------------------------------------------------------------

    @Test
    fun `an opening balance starts with the units the position has and an optional cost`() = runTest(dispatcher) {
        val s = setup(
            "f",
            listOf(quoted("f", units = UnitsBalance(200 * UNITS_SCALE))),
            openingBalance = true,
        )
        val back = collectBack(s.vm)

        assertTrue(s.state.isOpening)
        assertEquals("Saldo inicial", s.state.title)
        assertEquals("200", s.state.unitsText)
        assertEquals("Coste total (opcional)", s.state.amountLabel)
        assertEquals(today, s.state.date)
        assertTrue(s.state.showUnits)
        assertFalse(s.state.showFee)
        assertTrue(s.state.choices.isEmpty())
        assertTrue(s.state.canSave)

        s.vm.onSave()
        settle()

        val stored = s.movements.current.single()
        assertEquals(MovementType.OPENING_BALANCE, stored.type)
        assertEquals(units(200), stored.units)
        assertNull(stored.amount)
        assertEquals(1, back.size)
        val held = (s.holdings.current.single().valuation as HoldingValuation.Quoted).units
        assertEquals(UnitsBalance(200 * UNITS_SCALE), held)
    }

    @Test
    fun `an opening balance keeps the cost that was typed`() = runTest(dispatcher) {
        val s = setup("f", listOf(quoted("f", units = UnitsBalance(200 * UNITS_SCALE))), openingBalance = true)

        s.vm.onAmountChange("1000")
        assertEquals("≈ 5,00 €/part.", s.state.impliedPriceText)
        s.vm.onSave()
        settle()

        assertEquals(eur(100_000), s.movements.current.single().amount)
    }

    @Test
    fun `an opening balance with a cost in dollars needs the euro equivalent too`() = runTest(dispatcher) {
        val s = setup(
            "u",
            listOf(quoted("u", currency = Currency.USD, units = UnitsBalance(2 * UNITS_SCALE))),
            openingBalance = true,
        )

        assertTrue(s.state.canSave) // no cost, nothing to convert
        s.vm.onAmountChange("100")
        assertFalse(s.state.canSave)
        assertTrue(s.state.showEur)
        s.vm.onEurChange("90")
        assertTrue(s.state.canSave)
    }

    @Test
    fun `an existing opening balance is edited with the opening form`() = runTest(dispatcher) {
        val opening = Movement(
            id = "o1",
            holdingId = "f",
            type = MovementType.OPENING_BALANCE,
            date = LocalDate(2026, 1, 1),
            units = units(200),
            sequence = 1,
        )
        val s = setup("f", listOf(quoted("f", units = UnitsBalance(200 * UNITS_SCALE))), listOf(opening), "o1")

        assertTrue(s.state.isOpening)
        assertEquals("Editar saldo inicial", s.state.title)
        assertEquals("", s.state.amountText)
        s.vm.onAmountChange("800")
        s.vm.onSave()
        settle()

        assertEquals(eur(80_000), s.movements.current.single().amount)
    }

    // ---- the fee in euros ----------------------------------------------------------------------------------------

    @Test
    fun `the fee of a holding in dollars is typed in euros by default, a holding in euros has no choice`() =
        runTest(dispatcher) {
            val dollars = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
            val euros = setup("f", listOf(quoted("f")))

            assertTrue(dollars.state.showFeeCurrency)
            assertTrue(dollars.state.feeInEur)
            assertEquals(Currency.EUR, dollars.state.feeCurrency)
            assertFalse(euros.state.showFeeCurrency)
            assertFalse(euros.state.feeInEur)
            assertEquals(Currency.EUR, euros.state.feeCurrency)
            euros.vm.onFeeCurrencyChange(true)
            assertFalse(euros.state.feeInEur)
        }

    @Test
    fun `a fee in euros is stored exactly, the fee in dollars follows the rate and the cost adds the euros`() =
        runTest(dispatcher) {
            val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
            s.vm.onUnitsChange("1")
            s.vm.onAmountChange("100")

            s.vm.onFeeChange("1,38")

            // 138 cents x 1.000.000 / 920.000 = 150 cents.
            assertEquals("≈ 1,50 $", s.state.feePreviewText)
            assertTrue(s.state.canSave)
            s.vm.onSave()
            settle()
            val stored = s.movements.current.single()
            assertEquals(Money(150), stored.fee)
            assertEquals(Money(138), stored.feeEur)
            assertEquals(Money(9_200), stored.amountEur)
            // The buy costs the 92,00 EUR of the amount plus the 1,38 EUR of the fee.
            val lot = computeLedgers(s.movements.current).getValue("u").lots.single()
            assertEquals(Money(9_338), lot.costEur)
        }

    @Test
    fun `switching the fee currency keeps the typed number and rereads it`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
        s.vm.onUnitsChange("1")
        s.vm.onAmountChange("100")
        s.vm.onFeeChange("1,5")
        assertEquals("≈ 1,63 $", s.state.feePreviewText)

        s.vm.onFeeCurrencyChange(false)

        assertFalse(s.state.feeInEur)
        assertEquals(Currency.USD, s.state.feeCurrency)
        assertEquals("1,5", s.state.feeText)
        assertEquals("≈ 1,38 €", s.state.feePreviewText)
        s.vm.onSave()
        settle()
        val stored = s.movements.current.single()
        assertEquals(Money(150), stored.fee)
        assertEquals(Money(138), stored.feeEur)
    }

    @Test
    fun `a fee in euros follows the rate the typed amounts imply`() = runTest(dispatcher) {
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), fxHistory = usdRates)
        s.vm.onUnitsChange("1")
        s.vm.onAmountChange("100")
        s.vm.onEurChange("86,12")

        s.vm.onFeeChange("1")

        // 100 cents x 10.000 / 8.612 = 116,12 -> 116 cents.
        assertEquals("≈ 1,16 $", s.state.feePreviewText)
        s.vm.onSave()
        settle()
        val stored = s.movements.current.single()
        assertEquals(Money(116), stored.fee)
        assertEquals(Money(100), stored.feeEur)
        assertEquals(Money(8_612), stored.amountEur)
    }

    @Test
    fun `a sell with a fee in euros takes exactly those euros off the proceeds`() = runTest(dispatcher) {
        val bought = existingBuy(holding = "u").copy(
            units = units(10),
            amount = usd(10_000),
            amountEur = Money(9_500),
            fee = Money.ZERO,
            feeEur = Money.ZERO,
        )
        val s = setup("u", listOf(quoted("u", currency = Currency.USD)), listOf(bought), fxHistory = usdRates)
        s.vm.onChoiceChange(MovementChoice.SELL)
        s.vm.onUnitsChange("5")
        s.vm.onAmountChange("120")
        s.vm.onEurChange("110")

        s.vm.onFeeChange("1,5")
        s.vm.onSave()
        settle()

        val sale = s.movements.current.first { it.type == MovementType.SELL }
        assertEquals(Money(150), sale.feeEur)
        // The two amounts were typed, so the dollar fee follows their ratio: 150 x 12.000 / 11.000 = 163,64 -> 164.
        assertEquals(Money(164), sale.fee)
        val ledger = computeLedgers(s.movements.current).getValue("u")
        // Proceeds 110,00 - 1,50 = 108,50 against half of the 95,00 cost: 108,50 - 47,50 = 61,00.
        assertEquals(Money(6_100), ledger.realized.knownGainEur)
        assertEquals(Money(150), ledger.tradeFeesEur)
    }

    @Test
    fun `without a rate a fee in euros waits for the equivalent and then follows the typed amounts`() =
        runTest(dispatcher) {
            val s = setup("u", listOf(quoted("u", currency = Currency.USD)))
            s.vm.onUnitsChange("1")
            s.vm.onAmountChange("100")

            s.vm.onFeeChange("1")

            assertFalse(s.state.canSave)
            assertNotNull(s.state.eurHint)
            assertNull(s.state.feePreviewText)
            s.vm.onEurChange("90")
            assertTrue(s.state.canSave)
            assertEquals("≈ 1,11 $", s.state.feePreviewText)
            s.vm.onSave()
            settle()
            assertEquals(Money(111), s.movements.current.single().fee)
            assertEquals(Money(100), s.movements.current.single().feeEur)
        }

    @Test
    fun `editing restores the currency the fee was typed in when the stored amounts tell`() = runTest(dispatcher) {
        val inEuros = existingBuy(holding = "u").copy(
            amount = usd(10_000),
            amountEur = Money(9_500),
            fee = Money(500),
            feeEur = Money(475),
        )
        val s = setup(
            "u",
            listOf(quoted("u", currency = Currency.USD)),
            listOf(inEuros),
            movementId = "m1",
            fxHistory = usdRates,
        )

        assertTrue(s.state.feeInEur)
        assertEquals("4,75", s.state.feeText)
        assertEquals("≈ 5,00 $", s.state.feePreviewText)
        s.vm.onSave()
        settle()
        assertEquals(Money(475), s.movements.current.single().feeEur)
        assertEquals(Money(500), s.movements.current.single().fee)
    }

    @Test
    fun `editing a fee that the euros could not have produced shows it in the holding currency`() =
        runTest(dispatcher) {
            val inDollars = existingBuy(holding = "u").copy(
                amount = usd(10_000),
                amountEur = Money(5_000),
                fee = Money(3),
                feeEur = Money(2),
            )
            val s = setup(
                "u",
                listOf(quoted("u", currency = Currency.USD)),
                listOf(inDollars),
                movementId = "m1",
                fxHistory = usdRates,
            )

            assertFalse(s.state.feeInEur)
            assertEquals("0,03", s.state.feeText)
            assertEquals("≈ 0,02 €", s.state.feePreviewText)
            s.vm.onSave()
            settle()
            assertEquals(Money(3), s.movements.current.single().fee)
            assertEquals(Money(2), s.movements.current.single().feeEur)
        }

    // ---- not applicable ------------------------------------------------------------------------------------------

    @Test
    fun `a holding that is manual or gone closes the sheet`() = runTest(dispatcher) {
        val portfolio = FakePortfolioRepository(listOf(manual))
        val vm = MovementFormViewModel(
            holdingId = "art",
            movementId = null,
            openingBalance = false,
            portfolioRepository = portfolio,
            movementRepository = FakeMovementRepository(portfolio),
            fxHistoryRepository = FakeFxHistoryRepository(),
            clock = clock,
            zoneProvider = { TimeZone.UTC },
        )
        val back = collectBack(vm)
        settle()
        assertEquals(1, back.size)

        val gone = setup("ghost", emptyList())
        val goneBack = collectBack(gone.vm)
        settle()
        assertEquals(1, goneBack.size)
        assertFalse(gone.state.loaded)
    }
}
