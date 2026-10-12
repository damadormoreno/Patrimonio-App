package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.MAX_BROKER_LENGTH
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.testing.FakeFxRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HoldingDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val rates = FxRates(mapOf(Currency.USD to 920_000L))
    private val quoteDate = LocalDate(2026, 10, 10)

    // 2026-10-10T12:00:00Z
    private val manualValuedAt = 1_791_633_600_000L
    private val stampedAt = 1_791_712_800_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val fund = PortfolioHolding(
        id = "fund",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo Global",
        type = InstrumentType.FUND,
        isin = "IE00B4L5Y983",
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000FUND"),
            currency = Currency.EUR,
            units = Units(10_500_000),
            lastQuote = LastQuote(Price(245_120_000, Currency.EUR), quoteDate, fetchedAtEpochMs = 1L),
        ),
    )
    private val usdStock = PortfolioHolding(
        id = "stock",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Acme Corp",
        type = InstrumentType.STOCK,
        isin = null,
        ticker = "ACME",
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.YAHOO, "ACME"),
            currency = Currency.USD,
            units = Units(10_000_000),
            lastQuote = LastQuote(Price(200_000_000, Currency.USD), quoteDate, fetchedAtEpochMs = 1L),
        ),
    )
    private val unpriced = PortfolioHolding(
        id = "new",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo Nuevo",
        type = InstrumentType.FUND,
        isin = "ES0000000001",
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000NEW"),
            currency = Currency.EUR,
            units = Units(5_000_000),
        ),
    )
    private val manual = PortfolioHolding(
        id = "art",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Cuadro",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(250_000), Currency.EUR), manualValuedAt),
    )

    private fun viewModel(
        id: String,
        repository: FakePortfolioRepository,
        portfolios: FakePortfolioCollectionRepository = FakePortfolioCollectionRepository(holdings = repository),
    ) = HoldingDetailViewModel(
        holdingId = id,
        portfolioRepository = repository,
        portfolios = portfolios,
        fxRepository = FakeFxRepository(rates),
        movementRepository = FakeMovementRepository(repository),
        zoneProvider = { TimeZone.UTC },
    )

    private fun TestScope.collect(vm: HoldingDetailViewModel): Job = launch { vm.state.collect {} }

    private fun TestScope.collectNavigateBack(vm: HoldingDetailViewModel): MutableList<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch { vm.navigateBack.collect { events += it } }
        return events
    }

    @Test
    fun `a quoted holding shows its identity, value, price and the units to edit`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund, manual)))
        val job = collect(vm)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loaded)
        assertEquals("Fondo Global", state.name)
        assertEquals("Fondo", state.typeLabel)
        assertEquals("IE00B4L5Y983", state.identifier)
        assertEquals("Morningstar", state.source)
        assertEquals("2.573,76 €", state.valueText)
        assertNull(state.valueEurText)
        assertEquals("245,12 €", state.priceText)
        assertEquals("10/10/2026", state.priceDateText)
        assertTrue(state.isQuoted)
        assertEquals(Currency.EUR, state.currency)
        assertEquals("10,5", state.editText)
        assertEquals("≈ 2.573,76 €", state.preview)
        assertFalse(state.canSave)
        job.cancel()
    }

    @Test
    fun `a holding in another currency also shows its value in euros`() = runTest(dispatcher) {
        val vm = viewModel("stock", FakePortfolioRepository(listOf(usdStock)))
        val job = collect(vm)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("ACME", state.identifier)
        assertEquals("Yahoo Finance", state.source)
        assertEquals("2.000,00 $", state.valueText)
        assertEquals("≈ 1.840,00 €", state.valueEurText)
        assertEquals("200,00 $", state.priceText)
        job.cancel()
    }

    @Test
    fun `a holding with no price has no value, price or preview yet`() = runTest(dispatcher) {
        val vm = viewModel("new", FakePortfolioRepository(listOf(unpriced)))
        val job = collect(vm)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Sin precio", state.valueText)
        assertNull(state.priceText)
        assertNull(state.priceDateText)
        assertNull(state.preview)
        assertEquals("5", state.editText)
        job.cancel()
    }

    @Test
    fun `a manual holding shows its value and edits it in its currency`() = runTest(dispatcher) {
        val vm = viewModel("art", FakePortfolioRepository(listOf(manual)))
        val job = collect(vm)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isQuoted)
        assertEquals("Otro", state.typeLabel)
        assertEquals("", state.identifier)
        assertEquals("Valor manual", state.source)
        assertEquals("2.500,00 €", state.valueText)
        assertNull(state.priceText)
        assertEquals("10/10/2026", state.priceDateText)
        assertEquals("2500,00", state.editText)
        assertNull(state.preview)
        job.cancel()
    }

    @Test
    fun `editing the units previews the new value and enables saving`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund)))
        val job = collect(vm)
        advanceUntilIdle()

        vm.onEditChange("11")
        advanceUntilIdle()

        assertEquals("11", vm.state.value.editText)
        assertEquals("≈ 2.696,32 €", vm.state.value.preview)
        assertTrue(vm.state.value.canSave)
        job.cancel()
    }

    @Test
    fun `units that are zero or malformed cannot be saved`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund)))
        val job = collect(vm)
        advanceUntilIdle()

        listOf("", "0", "abc", "1,1234567").forEach { text ->
            vm.onEditChange(text)
            advanceUntilIdle()
            assertFalse(vm.state.value.canSave, "canSave for '$text'")
            assertNull(vm.state.value.preview, "preview for '$text'")
        }
        job.cancel()
    }

    @Test
    fun `saving new units updates the holding and closes the sheet`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()
        vm.onEditChange("11,25")
        advanceUntilIdle()

        vm.onSave()
        advanceUntilIdle()

        val valuation = repository.current.single().valuation as HoldingValuation.Quoted
        assertEquals(UnitsBalance(11_250_000), valuation.units)
        runCurrent()
        assertEquals(1, closed.size)
        job.cancel()
    }

    @Test
    fun `saving a new value updates a manual holding`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(manual), valuedAtEpochMs = stampedAt)
        val vm = viewModel("art", repository)
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()
        vm.onEditChange("3000,50")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)

        vm.onSave()
        advanceUntilIdle()

        val valuation = repository.current.single().valuation as HoldingValuation.Manual
        assertEquals(CurrencyAmount(Money(300_050), Currency.EUR), valuation.value)
        assertEquals(stampedAt, valuation.valuedAtEpochMs)
        runCurrent()
        assertEquals(1, closed.size)
        job.cancel()
    }

    @Test
    fun `a manual value of zero or with too many decimals cannot be saved`() = runTest(dispatcher) {
        val vm = viewModel("art", FakePortfolioRepository(listOf(manual)))
        val job = collect(vm)
        advanceUntilIdle()

        listOf("0", "10,555", "abc", "").forEach { text ->
            vm.onEditChange(text)
            advanceUntilIdle()
            assertFalse(vm.state.value.canSave, "canSave for '$text'")
        }
        job.cancel()
    }

    @Test
    fun `saving with invalid input changes nothing`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()
        vm.onEditChange("0")
        advanceUntilIdle()

        vm.onSave()
        advanceUntilIdle()

        assertEquals(listOf(fund), repository.current)
        runCurrent()
        assertTrue(closed.isEmpty())
        job.cancel()
    }

    @Test
    fun `deleting removes the holding and closes the sheet once`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund, manual))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()

        vm.onDelete()
        advanceUntilIdle()

        assertEquals(listOf(manual), repository.current)
        runCurrent()
        assertEquals(1, closed.size)
        job.cancel()
    }

    @Test
    fun `a holding that is gone closes the sheet`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("missing", repository)
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()

        assertFalse(vm.state.value.loaded)
        runCurrent()
        assertEquals(1, closed.size)
        job.cancel()
    }

    @Test
    fun `a holding deleted elsewhere closes the sheet`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()
        runCurrent()
        assertTrue(closed.isEmpty())

        repository.emit(emptyList())
        advanceUntilIdle()

        runCurrent()
        assertEquals(1, closed.size)
        job.cancel()
    }

    @Test
    fun `a holding without a bank or broker shows none and an empty field`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund)))
        val job = collect(vm)
        advanceUntilIdle()

        assertNull(vm.state.value.broker)
        assertEquals("", vm.state.value.brokerText)
        job.cancel()
    }

    @Test
    fun `the bank or broker is shown and fills the field`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund.copy(broker = "Openbank"))))
        val job = collect(vm)
        advanceUntilIdle()

        assertEquals("Openbank", vm.state.value.broker)
        assertEquals("Openbank", vm.state.value.brokerText)
        assertFalse(vm.state.value.canSave)
        job.cancel()
    }

    @Test
    fun `typing a broker alone enables saving and persists it without touching the value`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(manual), valuedAtEpochMs = stampedAt)
        val vm = viewModel("art", repository)
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()

        vm.onBrokerChange("  MyInvestor ")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)

        vm.onSave()
        advanceUntilIdle()

        assertEquals(listOf(manual.copy(broker = "MyInvestor")), repository.current)
        runCurrent()
        assertEquals(1, closed.size)
        job.cancel()
    }

    @Test
    fun `changing only the broker of a quoted holding keeps its units`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        advanceUntilIdle()

        vm.onBrokerChange("Openbank")
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()

        assertEquals(listOf(fund.copy(broker = "Openbank")), repository.current)
        job.cancel()
    }

    @Test
    fun `units and broker edited together are both saved`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        advanceUntilIdle()

        vm.onEditChange("11")
        vm.onBrokerChange("Openbank")
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()

        val saved = repository.current.single()
        assertEquals(UnitsBalance(11_000_000), (saved.valuation as HoldingValuation.Quoted).units)
        assertEquals("Openbank", saved.broker)
        job.cancel()
    }

    @Test
    fun `clearing the field removes the broker`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund.copy(broker = "Openbank")))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        advanceUntilIdle()

        vm.onBrokerChange("")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        vm.onSave()
        advanceUntilIdle()

        assertNull(repository.current.single().broker)
        job.cancel()
    }

    @Test
    fun `a broker that only differs by surrounding spaces is not a change`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund.copy(broker = "Openbank"))))
        val job = collect(vm)
        advanceUntilIdle()

        vm.onBrokerChange(" Openbank ")
        advanceUntilIdle()

        assertFalse(vm.state.value.canSave)
        job.cancel()
    }

    @Test
    fun `a new broker cannot be saved while the units are invalid`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        advanceUntilIdle()

        vm.onEditChange("0")
        vm.onBrokerChange("Openbank")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSave)
        vm.onSave()
        advanceUntilIdle()

        assertEquals(listOf(fund), repository.current)
        job.cancel()
    }

    @Test
    fun `the broker field holds at most the maximum length`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund)))
        val job = collect(vm)
        advanceUntilIdle()

        vm.onBrokerChange("B".repeat(MAX_BROKER_LENGTH + 10))
        advanceUntilIdle()

        assertEquals("B".repeat(MAX_BROKER_LENGTH), vm.state.value.brokerText)
        job.cancel()
    }

    @Test
    fun `a price refresh keeps the broker the user is typing`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        advanceUntilIdle()
        vm.onBrokerChange("Open")
        advanceUntilIdle()

        val quoted = fund.valuation as HoldingValuation.Quoted
        val refreshed = quoted.copy(lastQuote = LastQuote(Price(250_000_000, Currency.EUR), quoteDate, 2L))
        repository.emit(listOf(fund.copy(valuation = refreshed)))
        advanceUntilIdle()

        assertEquals("Open", vm.state.value.brokerText)
        job.cancel()
    }

    @Test
    fun `a price refresh updates the value but keeps what the user is typing`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = collect(vm)
        advanceUntilIdle()
        vm.onEditChange("11")
        advanceUntilIdle()

        val quoted = fund.valuation as HoldingValuation.Quoted
        val refreshed = quoted.copy(lastQuote = LastQuote(Price(250_000_000, Currency.EUR), quoteDate, 2L))
        repository.emit(listOf(fund.copy(valuation = refreshed)))
        advanceUntilIdle()

        assertEquals("11", vm.state.value.editText)
        assertEquals("2.625,00 €", vm.state.value.valueText)
        assertEquals("≈ 2.750,00 €", vm.state.value.preview)
        job.cancel()
    }

    // ---- portfolio -----------------------------------------------------------------------------------------------

    private val hijos = Portfolio("pf-hijos", "Hijos", 1)

    private fun twoPortfolios(repository: FakePortfolioRepository) = FakePortfolioCollectionRepository(
        listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), hijos),
        repository,
    )

    @Test
    fun `the sheet offers the portfolios and starts on the one the holding is in`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository, twoPortfolios(repository))
        val job = collect(vm)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("Mi cartera", "Hijos"), state.portfolios.map { it.name })
        assertEquals(TEST_PORTFOLIO_ID, state.portfolioId)
        assertFalse(state.canSave)
        job.cancel()
    }

    @Test
    fun `with a single portfolio there is only one option`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund)))
        val job = collect(vm)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.portfolios.size)
        job.cancel()
    }

    @Test
    fun `choosing another portfolio enables saving and choosing the same one again disables it`() =
        runTest(dispatcher) {
            val repository = FakePortfolioRepository(listOf(fund))
            val vm = viewModel("fund", repository, twoPortfolios(repository))
            val job = collect(vm)
            advanceUntilIdle()

            vm.onPortfolioChange("pf-hijos")
            advanceUntilIdle()
            assertEquals("pf-hijos", vm.state.value.portfolioId)
            assertTrue(vm.state.value.canSave)

            vm.onPortfolioChange(TEST_PORTFOLIO_ID)
            advanceUntilIdle()
            assertFalse(vm.state.value.canSave)
            job.cancel()
        }

    @Test
    fun `saving only a new portfolio moves the holding and leaves everything else as it was`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(manual), valuedAtEpochMs = stampedAt)
        val vm = viewModel("art", repository, twoPortfolios(repository))
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()
        vm.onPortfolioChange("pf-hijos")
        advanceUntilIdle()

        vm.onSave()
        advanceUntilIdle()

        assertEquals(manual.copy(portfolioId = "pf-hijos"), repository.current.single())
        assertEquals(1, closed.size)
        job.cancel()
    }

    @Test
    fun `a new portfolio, units and broker edited together are all saved`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository, twoPortfolios(repository))
        val job = collect(vm)
        val closed = collectNavigateBack(vm)
        advanceUntilIdle()
        vm.onEditChange("11,25")
        vm.onBrokerChange("Openbank")
        vm.onPortfolioChange("pf-hijos")
        advanceUntilIdle()

        vm.onSave()
        advanceUntilIdle()

        val saved = repository.current.single()
        assertEquals("pf-hijos", saved.portfolioId)
        assertEquals("Openbank", saved.broker)
        assertEquals(UnitsBalance(11_250_000), (saved.valuation as HoldingValuation.Quoted).units)
        assertEquals(1, closed.size)
        job.cancel()
    }

    @Test
    fun `a chosen portfolio is not saved while the units are invalid`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository, twoPortfolios(repository))
        val job = collect(vm)
        advanceUntilIdle()
        vm.onEditChange("0")
        vm.onPortfolioChange("pf-hijos")
        advanceUntilIdle()

        assertFalse(vm.state.value.canSave)
        vm.onSave()
        advanceUntilIdle()

        assertEquals(TEST_PORTFOLIO_ID, repository.current.single().portfolioId)
        job.cancel()
    }

    @Test
    fun `a portfolio that does not exist cannot be chosen`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository, twoPortfolios(repository))
        val job = collect(vm)
        advanceUntilIdle()

        vm.onPortfolioChange("pf-deleted")
        advanceUntilIdle()

        assertEquals(TEST_PORTFOLIO_ID, vm.state.value.portfolioId)
        assertFalse(vm.state.value.canSave)
        job.cancel()
    }
}
