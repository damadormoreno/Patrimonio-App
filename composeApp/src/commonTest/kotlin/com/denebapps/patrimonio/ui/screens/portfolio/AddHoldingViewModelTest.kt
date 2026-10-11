package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.MAX_BROKER_LENGTH
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakeQuoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class AddHoldingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = fixedClock("2026-10-11T10:00:00Z")
    private val nowMs = 1_791_712_800_000L

    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000FUND")
    private val fund = InstrumentCandidate(
        ref = fundRef,
        name = "Fondo Global",
        isin = "IE00B4L5Y983",
        ticker = null,
        type = InstrumentType.FUND,
        currency = Currency.EUR,
        exchange = null,
    )
    private val etfRef = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS")
    private val etf = InstrumentCandidate(
        ref = etfRef,
        name = "iShares MSCI World",
        isin = "IE00B4L5Y983",
        ticker = "IWDA",
        type = InstrumentType.ETF,
        currency = null,
        exchange = "Amsterdam",
    )
    private val tickerOnlyRef = InstrumentRef(QuoteProvider.YAHOO, "ACME")
    private val tickerOnly = InstrumentCandidate(
        ref = tickerOnlyRef,
        name = "Acme Corp",
        isin = null,
        ticker = "ACME",
        type = InstrumentType.STOCK,
        currency = null,
        exchange = null,
    )
    private val quoteDate = LocalDate(2026, 10, 10)
    private val fundQuote = Quote(fundRef, Price(245_120_000, Currency.EUR), quoteDate)

    private val source = FakeQuoteSource().apply {
        searchResults = listOf(fund, etf)
        quotes[fundRef] = fundQuote
    }
    private val repository = FakePortfolioRepository()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AddHoldingViewModel(
        quoteSource = source,
        portfolioRepository = repository,
        clock = clock,
        idProvider = { "new-id" },
    )

    private fun TestScope.search(vm: AddHoldingViewModel, query: String = "global") {
        vm.onQueryChange(query)
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()
    }

    private fun TestScope.pickFund(vm: AddHoldingViewModel) {
        search(vm)
        vm.onPick(fundRef)
        advanceUntilIdle()
    }

    private fun TestScope.collectNavigateBack(vm: AddHoldingViewModel): MutableList<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch { vm.navigateBack.collect { events += it } }
        return events
    }

    @Test
    fun `starts on the search step with nothing to save`() = runTest(dispatcher) {
        val state = viewModel().state.value

        assertEquals(AddHoldingStage.SEARCH, state.stage)
        assertEquals(SearchStatus.IDLE, state.searchStatus)
        assertEquals("", state.query)
        assertFalse(state.canSave)
    }

    @Test
    fun `a query under two characters searches nothing`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onQueryChange("a")
        advanceTimeBy(SEARCH_DEBOUNCE_MS * 2)
        runCurrent()
        assertTrue(source.searches.isEmpty())
        assertEquals(SearchStatus.IDLE, vm.state.value.searchStatus)

        vm.onQueryChange("a ")
        advanceTimeBy(SEARCH_DEBOUNCE_MS * 2)
        runCurrent()
        assertTrue(source.searches.isEmpty())
        assertEquals("a ", vm.state.value.query)
    }

    @Test
    fun `the search waits for the debounce, then lists the results`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onQueryChange("glo")
        runCurrent()
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(source.searches.isEmpty())
        assertEquals(SearchStatus.LOADING, vm.state.value.searchStatus)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("glo"), source.searches)
        assertEquals(SearchStatus.RESULTS, vm.state.value.searchStatus)
        assertEquals(listOf(fundRef, etfRef), vm.state.value.results.map { it.ref })
    }

    @Test
    fun `typing again restarts the debounce and only the last query is searched`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onQueryChange("gl")
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        vm.onQueryChange("glo")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()

        assertEquals(listOf("glo"), source.searches)
    }

    @Test
    fun `the query is trimmed before it reaches the source`() = runTest(dispatcher) {
        val vm = viewModel()

        search(vm, "  glo  ")

        assertEquals(listOf("glo"), source.searches)
    }

    @Test
    fun `results show the name, identifiers, type and source`() = runTest(dispatcher) {
        source.searchResults = listOf(fund, etf, tickerOnly)
        val vm = viewModel()

        search(vm)

        val (fundRow, etfRow, tickerRow) = vm.state.value.results
        assertEquals(CandidateUi(fundRef, "Fondo Global", "IE00B4L5Y983", "Fondo", "Morningstar"), fundRow)
        assertEquals(CandidateUi(etfRef, "iShares MSCI World", "IE00B4L5Y983 · IWDA", "ETF", "Amsterdam"), etfRow)
        assertEquals(CandidateUi(tickerOnlyRef, "Acme Corp", "ACME", "Acción", "Yahoo Finance"), tickerRow)
    }

    @Test
    fun `no match is reported as empty`() = runTest(dispatcher) {
        source.searchResults = emptyList()
        val vm = viewModel()

        search(vm)

        assertEquals(SearchStatus.EMPTY, vm.state.value.searchStatus)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun `a failed search is reported as an error`() = runTest(dispatcher) {
        source.searchFailure = QuoteUnavailableException("offline")
        val vm = viewModel()

        search(vm)

        assertEquals(SearchStatus.ERROR, vm.state.value.searchStatus)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun `clearing the query drops the results and cancels a pending search`() = runTest(dispatcher) {
        val vm = viewModel()
        search(vm)
        assertEquals(SearchStatus.RESULTS, vm.state.value.searchStatus)

        vm.onQueryChange("glo")
        runCurrent()
        vm.onQueryChange("")
        advanceTimeBy(SEARCH_DEBOUNCE_MS * 2)
        runCurrent()

        assertEquals(1, source.searches.size)
        assertEquals(SearchStatus.IDLE, vm.state.value.searchStatus)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun `picking a result fetches its quote and opens the entry form`() = runTest(dispatcher) {
        val vm = viewModel()
        search(vm)

        vm.onPick(fundRef)
        assertEquals(AddHoldingStage.LOADING_QUOTE, vm.state.value.stage)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(fundRef), source.quoteRequests)
        assertEquals(AddHoldingStage.ENTRY, state.stage)
        assertEquals("Fondo Global", state.picked?.name)
        assertEquals(QuoteUi("245,12 €", "10/10/2026", Currency.EUR), state.quote)
        assertEquals(EntryMode.UNITS, state.entryMode)
        assertFalse(state.canSave)
    }

    @Test
    fun `picking something that is not in the results is ignored`() = runTest(dispatcher) {
        val vm = viewModel()
        search(vm)

        vm.onPick(tickerOnlyRef)
        advanceUntilIdle()

        assertEquals(AddHoldingStage.SEARCH, vm.state.value.stage)
        assertTrue(source.quoteRequests.isEmpty())
    }

    @Test
    fun `units mode previews the value of the units entered`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)

        vm.onUnitsChange("10,5")

        assertEquals("≈ 2.573,76 €", vm.state.value.preview)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `units mode without valid units has no preview and cannot be saved`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)

        listOf("", "0", "abc", "1,1234567").forEach { text ->
            vm.onUnitsChange(text)
            assertNull(vm.state.value.preview, "preview for '$text'")
            assertFalse(vm.state.value.canSave, "canSave for '$text'")
        }
    }

    @Test
    fun `amount mode previews the units that the current total buys`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)

        vm.onEntryModeChange(EntryMode.AMOUNT)
        vm.onAmountChange("1000")

        assertEquals(EntryMode.AMOUNT, vm.state.value.entryMode)
        assertEquals("≈ 4,079634 participaciones", vm.state.value.preview)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `amount mode rejects a total that is zero, malformed or buys no units`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onEntryModeChange(EntryMode.AMOUNT)

        listOf("", "0", "12,345", "x").forEach { text ->
            vm.onAmountChange(text)
            assertNull(vm.state.value.preview, "preview for '$text'")
            assertFalse(vm.state.value.canSave, "canSave for '$text'")
        }
    }

    @Test
    fun `each entry mode keeps its own text when switching back and forth`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onUnitsChange("10,5")
        vm.onEntryModeChange(EntryMode.AMOUNT)
        vm.onAmountChange("1000")

        vm.onEntryModeChange(EntryMode.UNITS)

        assertEquals("10,5", vm.state.value.unitsText)
        assertEquals("1000", vm.state.value.amountText)
        assertEquals("≈ 2.573,76 €", vm.state.value.preview)
    }

    @Test
    fun `saving by units stores a quoted holding with the quote as its last price`() = runTest(dispatcher) {
        val vm = viewModel()
        val closed = collectNavigateBack(vm)
        pickFund(vm)
        vm.onUnitsChange("10,5")

        vm.onSave()
        advanceUntilIdle()

        assertEquals(
            listOf(
                PortfolioHolding(
                    id = "new-id",
                    name = "Fondo Global",
                    type = InstrumentType.FUND,
                    isin = "IE00B4L5Y983",
                    ticker = null,
                    valuation = HoldingValuation.Quoted(
                        ref = fundRef,
                        currency = Currency.EUR,
                        units = Units(10_500_000),
                        lastQuote = LastQuote(Price(245_120_000, Currency.EUR), quoteDate, nowMs),
                    ),
                ),
            ),
            repository.current,
        )
        runCurrent()
        assertEquals(1, closed.size)
    }

    @Test
    fun `saving by total derives the units from the price`() = runTest(dispatcher) {
        source.quotes[fundRef] = Quote(fundRef, Price(250_000_000, Currency.EUR), quoteDate)
        val vm = viewModel()
        pickFund(vm)
        vm.onEntryModeChange(EntryMode.AMOUNT)
        vm.onAmountChange("1000")

        vm.onSave()
        advanceUntilIdle()

        val valuation = repository.current.single().valuation as HoldingValuation.Quoted
        assertEquals(Units(4_000_000), valuation.units)
    }

    @Test
    fun `the holding is kept in the currency the quote came in`() = runTest(dispatcher) {
        source.searchResults = listOf(etf)
        source.quotes[etfRef] = Quote(etfRef, Price(120_500_000, Currency.USD), quoteDate)
        val vm = viewModel()
        search(vm)
        vm.onPick(etfRef)
        advanceUntilIdle()

        vm.onUnitsChange("2")
        assertEquals("≈ 241,00 $", vm.state.value.preview)
        vm.onSave()
        advanceUntilIdle()

        val holding = repository.current.single()
        assertEquals("IWDA", holding.ticker)
        assertEquals(InstrumentType.ETF, holding.type)
        assertEquals(Currency.USD, (holding.valuation as HoldingValuation.Quoted).currency)
    }

    @Test
    fun `saving with invalid input stores nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        val closed = collectNavigateBack(vm)
        pickFund(vm)

        vm.onSave()
        advanceUntilIdle()

        assertTrue(repository.current.isEmpty())
        runCurrent()
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a quote that cannot be fetched offers the manual value instead`() = runTest(dispatcher) {
        source.quoteFailure = QuoteUnavailableException("no price")
        val vm = viewModel()
        search(vm)

        vm.onPick(fundRef)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(AddHoldingStage.QUOTE_FAILED, state.stage)
        assertNull(state.quote)
        assertEquals("Fondo Global", state.picked?.name)
        assertFalse(state.canSave)
    }

    @Test
    fun `adding manually after a failed quote prefills the product`() = runTest(dispatcher) {
        source.quoteFailure = QuoteUnavailableException("no price")
        val vm = viewModel()
        search(vm)
        vm.onPick(fundRef)
        advanceUntilIdle()

        vm.onAddManuallyFromPicked()

        val state = vm.state.value
        assertEquals(AddHoldingStage.MANUAL, state.stage)
        assertEquals("Fondo Global", state.name)
        assertEquals(InstrumentType.FUND, state.type)
        assertEquals(Currency.EUR, state.currency)
        assertFalse(state.canSave)
    }

    @Test
    fun `a manual holding from a failed quote keeps the isin and ticker`() = runTest(dispatcher) {
        source.quoteFailure = QuoteUnavailableException("no price")
        val vm = viewModel()
        val closed = collectNavigateBack(vm)
        search(vm)
        vm.onPick(etfRef)
        advanceUntilIdle()
        vm.onAddManuallyFromPicked()
        vm.onCurrencyChange(Currency.USD)
        vm.onAmountChange("1500,25")

        vm.onSave()
        advanceUntilIdle()

        assertEquals(
            listOf(
                PortfolioHolding(
                    id = "new-id",
                    name = "iShares MSCI World",
                    type = InstrumentType.ETF,
                    isin = "IE00B4L5Y983",
                    ticker = "IWDA",
                    valuation = HoldingValuation.Manual(CurrencyAmount(Money(150_025), Currency.USD), nowMs),
                ),
            ),
            repository.current,
        )
        runCurrent()
        assertEquals(1, closed.size)
    }

    private val fundamentals = InstrumentFundamentals(
        ongoingChargePercentScaled = 1_800L,
        riskLevel = 6,
        category = "RV Global",
        managementCompany = "Vanguard",
    )

    @Test
    fun `a quoted holding is saved with the fundamentals of the picked product`() = runTest(dispatcher) {
        source.searchResults = listOf(fund.copy(fundamentals = fundamentals), etf)
        val vm = viewModel()
        pickFund(vm)
        vm.onUnitsChange("10")

        vm.onSave()
        advanceUntilIdle()

        assertEquals(fundamentals, repository.current.single().fundamentals)
    }

    @Test
    fun `a product without fundamentals is saved with none`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onUnitsChange("10")

        vm.onSave()
        advanceUntilIdle()

        assertNull(repository.current.single().fundamentals)
    }

    @Test
    fun `a manual holding from a failed quote keeps the fundamentals of the product`() = runTest(dispatcher) {
        source.searchResults = listOf(fund.copy(fundamentals = fundamentals), etf)
        source.quoteFailure = QuoteUnavailableException("no price")
        val vm = viewModel()
        search(vm)
        vm.onPick(fundRef)
        advanceUntilIdle()
        vm.onAddManuallyFromPicked()
        vm.onAmountChange("1500")

        vm.onSave()
        advanceUntilIdle()

        assertEquals(fundamentals, repository.current.single().fundamentals)
    }

    @Test
    fun `a manual holding added from scratch has no fundamentals`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStartManual()
        vm.onNameChange("Cuadro")
        vm.onAmountChange("2500")

        vm.onSave()
        advanceUntilIdle()

        assertNull(repository.current.single().fundamentals)
    }

    @Test
    fun `a manual holding can be added from scratch`() = runTest(dispatcher) {
        val vm = viewModel()
        val closed = collectNavigateBack(vm)

        vm.onStartManual()
        assertEquals(AddHoldingStage.MANUAL, vm.state.value.stage)
        assertEquals(InstrumentType.OTHER, vm.state.value.type)
        vm.onNameChange("  Plan de pensiones  ")
        vm.onTypeChange(InstrumentType.FUND)
        vm.onAmountChange("12500")
        assertTrue(vm.state.value.canSave)

        vm.onSave()
        advanceUntilIdle()

        assertEquals(
            listOf(
                PortfolioHolding(
                    id = "new-id",
                    name = "Plan de pensiones",
                    type = InstrumentType.FUND,
                    isin = null,
                    ticker = null,
                    valuation = HoldingValuation.Manual(CurrencyAmount(Money(1_250_000), Currency.EUR), nowMs),
                ),
            ),
            repository.current,
        )
        runCurrent()
        assertEquals(1, closed.size)
    }

    @Test
    fun `a quoted holding is saved with the trimmed bank or broker`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onUnitsChange("10")
        vm.onBrokerChange("  Openbank ")

        vm.onSave()
        advanceUntilIdle()

        assertEquals("Openbank", repository.current.single().broker)
    }

    @Test
    fun `a quoted holding without a bank or broker is saved with none`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onUnitsChange("10")

        vm.onSave()
        advanceUntilIdle()

        assertNull(repository.current.single().broker)
    }

    @Test
    fun `a blank broker is saved as none`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onUnitsChange("10")
        vm.onBrokerChange("   ")

        vm.onSave()
        advanceUntilIdle()

        assertNull(repository.current.single().broker)
    }

    @Test
    fun `a manual holding is saved with its bank or broker`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStartManual()
        vm.onNameChange("Plan de pensiones")
        vm.onAmountChange("12500")
        vm.onBrokerChange("MyInvestor")

        vm.onSave()
        advanceUntilIdle()

        assertEquals("MyInvestor", repository.current.single().broker)
    }

    @Test
    fun `the bank or broker is optional and never decides whether the form can be saved`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onBrokerChange("Openbank")
        assertFalse(vm.state.value.canSave)

        vm.onUnitsChange("10")
        assertTrue(vm.state.value.canSave)
        vm.onBrokerChange("")
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `the bank or broker field holds at most the maximum length`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)

        vm.onBrokerChange("B".repeat(MAX_BROKER_LENGTH + 5))

        assertEquals("B".repeat(MAX_BROKER_LENGTH), vm.state.value.broker)
    }

    @Test
    fun `going back to the search drops the bank or broker typed so far`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onBrokerChange("Openbank")

        vm.onBackToSearch()

        assertEquals("", vm.state.value.broker)
    }

    @Test
    fun `a manual holding needs a name and a positive value in its currency`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStartManual()

        vm.onAmountChange("100")
        assertFalse(vm.state.value.canSave, "no name")

        vm.onNameChange("Cuadro")
        assertTrue(vm.state.value.canSave)

        vm.onAmountChange("0")
        assertFalse(vm.state.value.canSave, "zero")

        vm.onAmountChange("100,5")
        assertTrue(vm.state.value.canSave)

        vm.onCurrencyChange(Currency.JPY)
        assertFalse(vm.state.value.canSave, "yen has no decimals")
        vm.onAmountChange("100")
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `going back to the search keeps the query and results and forgets the product`() = runTest(dispatcher) {
        val vm = viewModel()
        pickFund(vm)
        vm.onUnitsChange("10")

        vm.onBackToSearch()

        val state = vm.state.value
        assertEquals(AddHoldingStage.SEARCH, state.stage)
        assertEquals("global", state.query)
        assertEquals(SearchStatus.RESULTS, state.searchStatus)
        assertEquals(2, state.results.size)
        assertNull(state.picked)
        assertNull(state.quote)
        assertEquals("", state.unitsText)
        assertFalse(state.canSave)
    }

    @Test
    fun `a quote that arrives after going back is dropped`() = runTest(dispatcher) {
        val vm = viewModel()
        search(vm)

        vm.onPick(fundRef)
        vm.onBackToSearch()
        advanceUntilIdle()

        assertEquals(AddHoldingStage.SEARCH, vm.state.value.stage)
        assertNull(vm.state.value.quote)
    }
}
