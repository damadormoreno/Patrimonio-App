package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakeQuoteSource
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import com.denebapps.patrimonio.testing.settle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class TransferFormViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate(2026, 10, 11)
    private val clock = fixedClock("2026-10-11T10:00:00Z")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun fund(
        id: String,
        type: InstrumentType = InstrumentType.FUND,
        units: Long = 0,
        portfolioId: String = TEST_PORTFOLIO_ID,
        isin: String? = null,
    ) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = "Fondo $id",
        type = type,
        isin = isin,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = Currency.EUR,
            units = UnitsBalance(units * UNITS_SCALE),
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

    private val boughtA = Movement(
        id = "buy-a",
        holdingId = "a",
        type = MovementType.BUY,
        date = LocalDate(2026, 1, 15),
        units = units(10),
        amount = CurrencyAmount(Money(100_000), Currency.EUR),
        amountEur = Money(100_000),
        sequence = 1,
    )

    private val newRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000NEW")
    private val newFund = InstrumentCandidate(
        ref = newRef,
        name = "Fondo Nuevo",
        isin = "ES0000000009",
        ticker = null,
        type = InstrumentType.FUND,
        currency = Currency.EUR,
        exchange = null,
    )
    private val heldAsCandidate = InstrumentCandidate(
        ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "b"),
        name = "Fondo b",
        isin = null,
        ticker = null,
        type = InstrumentType.FUND,
        currency = Currency.EUR,
        exchange = null,
    )
    private val heldByIsin = InstrumentCandidate(
        ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "other-ref"),
        name = "Fondo a otro listado",
        isin = "IE00A",
        ticker = null,
        type = InstrumentType.FUND,
        currency = Currency.EUR,
        exchange = null,
    )
    private val anEtf = InstrumentCandidate(
        ref = InstrumentRef(QuoteProvider.YAHOO, "IWDA"),
        name = "ETF Mundo",
        isin = "IE00ETF",
        ticker = "IWDA",
        type = InstrumentType.ETF,
        currency = null,
        exchange = null,
    )

    private class Setup(
        val holdings: FakePortfolioRepository,
        val movements: FakeMovementRepository,
        val source: FakeQuoteSource,
        val vm: TransferFormViewModel,
    ) {
        val state get() = vm.state.value
    }

    private fun TestScope.setup(
        holdingId: String = "a",
        transferId: String? = null,
        holdings: List<PortfolioHolding> = listOf(
            fund("a", units = 10, isin = "IE00A"),
            fund("b"),
            fund("c", units = 5),
            fund("etf", InstrumentType.ETF),
            manual,
        ),
        initial: List<Movement> = listOf(boughtA),
        portfolios: List<Portfolio> = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0)),
    ): Setup {
        val portfolio = FakePortfolioRepository(holdings)
        val movements = FakeMovementRepository(portfolio, initial)
        val source = FakeQuoteSource().apply {
            searchResults = listOf(heldAsCandidate, heldByIsin, anEtf, newFund)
            quotes[newRef] = Quote(newRef, Price(10_000_000, Currency.EUR), today)
        }
        val vm = TransferFormViewModel(
            holdingId = holdingId,
            transferId = transferId,
            portfolioRepository = portfolio,
            portfolios = FakePortfolioCollectionRepository(portfolios, portfolio),
            movementRepository = movements,
            quoteSource = source,
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            idProvider = { "new-id" },
        )
        settle()
        return Setup(portfolio, movements, source, vm)
    }

    private fun TestScope.collectBack(vm: TransferFormViewModel): MutableList<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch { vm.navigateBack.collect { events += it } }
        return events
    }

    private fun unitsOf(holdings: FakePortfolioRepository, id: String) =
        (holdings.current.first { it.id == id }.valuation as HoldingValuation.Quoted).units

    // ---- new transfer --------------------------------------------------------------------------------------------

    @Test
    fun `a new transfer offers the other funds that can receive units`() = runTest(dispatcher) {
        val s = setup()

        assertTrue(s.state.loaded)
        assertFalse(s.state.isEditing)
        assertEquals("Fondo a", s.state.fromName)
        // "c" holds units typed by hand and "etf" and the painting are not funds: only "b" can receive.
        assertEquals(listOf(TransferDestinationUi("b", "Fondo b")), s.state.destinations)
        assertNotNull(s.state.excludedHint)
        assertNull(s.state.destinationId)
        assertFalse(s.state.canSave)
    }

    @Test
    fun `the destinations name their portfolio when there are several`() = runTest(dispatcher) {
        val s = setup(
            holdings = listOf(fund("a"), fund("b", portfolioId = "hijos")),
            portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("hijos", "Hijos", 1)),
        )

        assertEquals(listOf(TransferDestinationUi("b", "Fondo b · Hijos")), s.state.destinations)
    }

    @Test
    fun `it starts moving everything on today and the incoming side follows`() = runTest(dispatcher) {
        val s = setup()

        assertEquals("Disponibles: 10,00 part.", s.state.availableText)
        assertEquals("10", s.state.unitsOutText)
        assertEquals("10", s.state.unitsInText)
        assertEquals(today, s.state.outDate)
        assertEquals(today, s.state.inDate)

        s.vm.onUnitsOutChange("4")
        assertEquals("4", s.state.unitsInText)
        s.vm.onUnitsInChange("40")
        s.vm.onUnitsOutChange("5")
        assertEquals("40", s.state.unitsInText)

        s.vm.onOutDateChange(LocalDate(2026, 10, 1))
        assertEquals(LocalDate(2026, 10, 1), s.state.inDate)
        s.vm.onInDateChange(LocalDate(2026, 10, 3))
        s.vm.onOutDateChange(LocalDate(2026, 10, 2))
        assertEquals(LocalDate(2026, 10, 3), s.state.inDate)
    }

    @Test
    fun `the all shortcut puts every unit held in the outgoing side`() = runTest(dispatcher) {
        val s = setup()
        s.vm.onUnitsOutChange("3")

        s.vm.onAllUnits()

        assertEquals("10", s.state.unitsOutText)
    }

    @Test
    fun `an existing fund is chosen and the transfer is saved on both legs`() = runTest(dispatcher) {
        val s = setup()
        val back = collectBack(s.vm)
        s.vm.onDestinationChange("b")
        assertEquals("b", s.state.destinationId)
        assertTrue(s.state.canSave)

        s.vm.onUnitsOutChange("4")
        s.vm.onUnitsInChange("40")
        s.vm.onNoteChange("Cambio de gestora")
        s.vm.onSave()
        settle()

        val legs = s.movements.current.filter { it.transferId != null }
        assertEquals(setOf(MovementType.TRANSFER_OUT, MovementType.TRANSFER_IN), legs.map { it.type }.toSet())
        assertEquals(units(4), legs.single { it.holdingId == "a" }.units)
        assertEquals(units(40), legs.single { it.holdingId == "b" }.units)
        assertEquals(UnitsBalance(6 * UNITS_SCALE), unitsOf(s.holdings, "a"))
        assertEquals(UnitsBalance(40 * UNITS_SCALE), unitsOf(s.holdings, "b"))
        assertEquals(1, back.size)
    }

    @Test
    fun `moving more than is held shows the reason and keeps the form open`() = runTest(dispatcher) {
        val s = setup()
        val back = collectBack(s.vm)
        s.vm.onDestinationChange("b")
        s.vm.onUnitsOutChange("11")

        s.vm.onSave()
        settle()

        assertTrue("solo tienes 10" in assertNotNull(s.state.error))
        assertTrue(back.isEmpty())
        assertEquals(1, s.movements.current.size)
    }

    @Test
    fun `invalid units cannot be saved`() = runTest(dispatcher) {
        val s = setup()
        s.vm.onDestinationChange("b")

        s.vm.onUnitsOutChange("0")
        assertFalse(s.state.canSave)
        s.vm.onUnitsOutChange("2")
        s.vm.onUnitsInChange("")
        assertFalse(s.state.canSave)
        s.vm.onUnitsInChange("2")
        assertTrue(s.state.canSave)
    }

    @Test
    fun `a fund whose units were typed by hand needs an opening balance before it can be transferred`() =
        runTest(dispatcher) {
            val s = setup(holdings = listOf(fund("a", units = 10), fund("b")), initial = emptyList())
            s.vm.onDestinationChange("b")

            assertNotNull(s.state.blockedText)
            assertFalse(s.state.canSave)
        }

    // ---- a fund that is not held yet -----------------------------------------------------------------------------

    @Test
    fun `another fund is searched among the ones not held yet`() = runTest(dispatcher) {
        val s = setup()

        s.vm.onOtherFund()
        s.vm.onQueryChange("fondo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()

        assertTrue(s.state.searchingNew)
        assertEquals(SearchStatus.RESULTS, s.state.searchStatus)
        // The ones already held (by reference or by ISIN) and the ETF are left out.
        assertEquals(listOf(newRef), s.state.results.map { it.ref })
        assertEquals(listOf("fondo"), s.source.searches)
    }

    @Test
    fun `a search with nothing new says so`() = runTest(dispatcher) {
        val s = setup()
        s.source.searchResults = listOf(heldAsCandidate, anEtf)

        s.vm.onOtherFund()
        s.vm.onQueryChange("fondo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()

        assertEquals(SearchStatus.EMPTY, s.state.searchStatus)
    }

    @Test
    fun `a picked new fund is created together with the transfer in the chosen portfolio`() = runTest(dispatcher) {
        val s = setup(
            portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("hijos", "Hijos", 1)),
        )
        val back = collectBack(s.vm)
        s.vm.onOtherFund()
        s.vm.onQueryChange("nuevo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()

        s.vm.onPickCandidate(newRef)
        settle()

        assertEquals("Fondo Nuevo", s.state.newFund?.name)
        assertNull(s.state.destinationId)
        assertTrue(s.state.canPickPortfolio)
        assertEquals(TEST_PORTFOLIO_ID, s.state.portfolioId)
        s.vm.onPortfolioChange("hijos")
        s.vm.onUnitsOutChange("10")
        s.vm.onUnitsInChange("100")
        assertTrue(s.state.canSave)
        s.vm.onSave()
        settle()

        val created = s.holdings.current.single { it.id == "new-id" }
        assertEquals("hijos", created.portfolioId)
        assertEquals("Fondo Nuevo", created.name)
        assertEquals(InstrumentType.FUND, created.type)
        assertEquals(UnitsBalance(100 * UNITS_SCALE), unitsOf(s.holdings, "new-id"))
        assertEquals(UnitsBalance.ZERO, unitsOf(s.holdings, "a"))
        assertEquals(1, back.size)
    }

    @Test
    fun `a new fund whose transfer is refused is not created`() = runTest(dispatcher) {
        val s = setup()
        s.vm.onOtherFund()
        s.vm.onQueryChange("nuevo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()
        s.vm.onPickCandidate(newRef)
        settle()
        s.vm.onUnitsOutChange("11")

        s.vm.onSave()
        settle()

        assertNotNull(s.state.error)
        assertTrue(s.holdings.current.none { it.id == "new-id" })
    }

    @Test
    fun `a fund without a price cannot be picked`() = runTest(dispatcher) {
        val s = setup()
        s.source.quoteFailure = QuoteUnavailableException("sin precio")
        s.vm.onOtherFund()
        s.vm.onQueryChange("nuevo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()

        s.vm.onPickCandidate(newRef)
        settle()

        assertNull(s.state.newFund)
        assertNotNull(s.state.newFundError)
        assertTrue(s.state.searchingNew)
    }

    @Test
    fun `choosing an existing fund or going back leaves the search`() = runTest(dispatcher) {
        val s = setup()
        s.vm.onOtherFund()
        s.vm.onQueryChange("nuevo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()
        s.vm.onPickCandidate(newRef)
        settle()

        s.vm.onClearNewFund()
        assertNull(s.state.newFund)
        assertTrue(s.state.searchingNew)

        s.vm.onDestinationChange("b")
        assertFalse(s.state.searchingNew)
        assertNull(s.state.newFund)
        assertEquals("b", s.state.destinationId)
    }

    // ---- editing and deleting ------------------------------------------------------------------------------------

    private fun TestScope.withTransfer(openFrom: String = "a"): Setup {
        val out = Movement(
            id = "out",
            holdingId = "a",
            type = MovementType.TRANSFER_OUT,
            date = LocalDate(2026, 2, 1),
            units = units(4),
            transferId = "t1",
            note = "Antes",
            sequence = 2,
        )
        val inn = out.copy(
            id = "in",
            holdingId = "b",
            type = MovementType.TRANSFER_IN,
            date = LocalDate(2026, 2, 3),
            sequence = 3,
        )
        return setup(
            holdingId = openFrom,
            transferId = "t1",
            holdings = listOf(fund("a", units = 6), fund("b", units = 4)),
            initial = listOf(boughtA, out, inn),
        )
    }

    @Test
    fun `an existing transfer is loaded with both legs and its destination cannot change`() = runTest(dispatcher) {
        val s = withTransfer()

        assertTrue(s.state.isEditing)
        assertEquals("Fondo a", s.state.fromName)
        assertEquals("Fondo b", s.state.destinationName)
        assertTrue(s.state.destinations.isEmpty())
        assertEquals(LocalDate(2026, 2, 1), s.state.outDate)
        assertEquals(LocalDate(2026, 2, 3), s.state.inDate)
        assertEquals("4", s.state.unitsOutText)
        assertEquals("4", s.state.unitsInText)
        assertEquals("Antes", s.state.noteText)
        assertTrue(s.state.canDelete)
        assertTrue(s.state.canSave)
    }

    @Test
    fun `the transfer can be opened from either of its funds`() = runTest(dispatcher) {
        val s = withTransfer(openFrom = "b")

        assertEquals("Fondo a", s.state.fromName)
        assertEquals("Fondo b", s.state.destinationName)
    }

    @Test
    fun `editing a transfer changes both legs together`() = runTest(dispatcher) {
        val s = withTransfer()
        val back = collectBack(s.vm)

        s.vm.onUnitsOutChange("6")
        s.vm.onUnitsInChange("60")
        s.vm.onOutDateChange(LocalDate(2026, 2, 2))
        s.vm.onInDateChange(LocalDate(2026, 2, 4))
        s.vm.onNoteChange("Después")
        s.vm.onSave()
        settle()

        val legs = s.movements.current.filter { it.transferId == "t1" }
        assertEquals(units(6), legs.single { it.type == MovementType.TRANSFER_OUT }.units)
        assertEquals(units(60), legs.single { it.type == MovementType.TRANSFER_IN }.units)
        assertEquals(LocalDate(2026, 2, 4), legs.single { it.type == MovementType.TRANSFER_IN }.date)
        assertEquals(setOf<String?>("Después"), legs.map { it.note }.toSet())
        assertEquals(1, back.size)
    }

    @Test
    fun `an edit the history refuses keeps the sheet open with the reason`() = runTest(dispatcher) {
        val s = withTransfer()
        val back = collectBack(s.vm)

        s.vm.onUnitsOutChange("11")
        s.vm.onSave()
        settle()

        assertNotNull(s.state.error)
        assertTrue(back.isEmpty())
        assertEquals(units(4), s.movements.current.single { it.id == "out" }.units)
    }

    @Test
    fun `deleting a transfer warns that both legs go and removes them`() = runTest(dispatcher) {
        val s = withTransfer()
        val back = collectBack(s.vm)

        assertEquals(
            "Se eliminarán las dos partes del traspaso: la salida de «Fondo a» y la entrada en «Fondo b».",
            s.state.deleteMessage,
        )
        s.vm.onDelete()
        settle()

        assertTrue(s.movements.current.none { it.transferId != null })
        assertEquals(1, back.size)
    }

    @Test
    fun `a holding that is not a fund or a transfer that is gone closes the sheet`() = runTest(dispatcher) {
        val etf = setup(holdingId = "etf")
        val etfBack = collectBack(etf.vm)
        val missing = setup(transferId = "ghost")
        val missingBack = collectBack(missing.vm)
        settle()

        assertEquals(1, etfBack.size)
        assertEquals(1, missingBack.size)
    }
}
