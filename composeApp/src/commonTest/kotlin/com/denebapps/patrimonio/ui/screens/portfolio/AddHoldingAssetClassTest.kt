package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.FakeQuoteSource
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AddHoldingAssetClassTest {
    private val dispatcher = StandardTestDispatcher()
    private val quoteDate = LocalDate(2026, 10, 10)

    private val equityFundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000FUND")
    private val equityFund = InstrumentCandidate(
        ref = equityFundRef,
        name = "Fondo Global",
        isin = "IE00B4L5Y983",
        ticker = null,
        type = InstrumentType.FUND,
        currency = Currency.EUR,
        exchange = null,
        fundamentals = InstrumentFundamentals(category = "RV Global"),
    )
    private val etfRef = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS")
    private val plainEtf = InstrumentCandidate(
        ref = etfRef,
        name = "iShares MSCI World",
        isin = null,
        ticker = "IWDA",
        type = InstrumentType.ETF,
        currency = null,
        exchange = "Amsterdam",
    )
    private val bitcoinRef = InstrumentRef(QuoteProvider.COINGECKO, "bitcoin")
    private val bitcoin = InstrumentCandidate(
        ref = bitcoinRef,
        name = "Bitcoin",
        isin = null,
        ticker = "BTC",
        type = InstrumentType.CRYPTO,
        currency = Currency.EUR,
        exchange = null,
    )

    private val source = FakeQuoteSource().apply {
        searchResults = listOf(equityFund, plainEtf, bitcoin)
        quotes[equityFundRef] = Quote(equityFundRef, Price(245_120_000, Currency.EUR), quoteDate)
        quotes[etfRef] = Quote(etfRef, Price(100_000_000, Currency.EUR), quoteDate)
        quotes[bitcoinRef] = Quote(bitcoinRef, Price(60_000_000_000, Currency.EUR), quoteDate)
    }
    private val repository = FakePortfolioRepository()
    private val portfolios = FakePortfolioCollectionRepository(listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0)))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AddHoldingViewModel(
        quoteSource = source,
        portfolioRepository = repository,
        portfolios = portfolios,
        preferences = FakePreferencesRepository(),
        movementRepository = FakeMovementRepository(repository),
        fxHistoryRepository = FakeFxHistoryRepository(),
        clock = fixedClock("2026-10-11T10:00:00Z"),
        zoneProvider = { TimeZone.UTC },
        idProvider = { "new-id" },
    )

    private fun TestScope.pick(vm: AddHoldingViewModel, ref: InstrumentRef) {
        vm.onQueryChange("global")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()
        vm.onPick(ref)
        advanceUntilIdle()
    }

    private fun TestScope.save(vm: AddHoldingViewModel) {
        vm.onSave()
        advanceUntilIdle()
    }

    @Test
    fun `a picked product starts on the class its category implies`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, equityFundRef)

        val choice = vm.state.value.assetClass
        assertEquals(AssetClass.EQUITY, choice.effective)
        assertEquals("Renta variable (según Morningstar)", choice.text)
    }

    @Test
    fun `a class left as inferred is not stored, so the inference keeps following the category`() =
        runTest(dispatcher) {
            val vm = viewModel()
            pick(vm, equityFundRef)
            vm.onUnitsChange("10")

            save(vm)

            val holding = repository.current.single()
            assertNull(holding.assetClass)
            assertEquals(AssetClass.EQUITY, holding.effectiveAssetClass())
        }

    @Test
    fun `choosing the inferred class again stores nothing either`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, equityFundRef)
        vm.onAssetClassChange(AssetClass.FIXED_INCOME)
        vm.onAssetClassChange(AssetClass.EQUITY)
        vm.onUnitsChange("10")

        save(vm)

        assertNull(repository.current.single().assetClass)
    }

    @Test
    fun `a class changed from the inferred one is stored`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, equityFundRef)
        vm.onAssetClassChange(AssetClass.FIXED_INCOME)
        assertEquals("Renta fija", vm.state.value.assetClass.text)
        vm.onUnitsChange("10")

        save(vm)

        assertEquals(AssetClass.FIXED_INCOME, repository.current.single().assetClass)
    }

    @Test
    fun `going back to automatic returns to the inferred class`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, equityFundRef)
        vm.onAssetClassChange(AssetClass.MIXED)
        vm.onAssetClassChange(null)

        assertEquals("Renta variable (según Morningstar)", vm.state.value.assetClass.text)
    }

    @Test
    fun `a product with no category is unclassified until the user picks a class`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, etfRef)
        assertEquals("Sin clasificar", vm.state.value.assetClass.text)

        vm.onAssetClassChange(AssetClass.COMMODITIES)
        vm.onUnitsChange("2")
        save(vm)

        assertEquals(AssetClass.COMMODITIES, repository.current.single().assetClass)
    }

    @Test
    fun `a crypto result is already Cripto and stores no explicit class`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, bitcoinRef)
        assertEquals(AssetClass.CRYPTO, vm.state.value.assetClass.effective)
        vm.onUnitsChange("0,5")

        save(vm)

        val holding = repository.current.single()
        assertNull(holding.assetClass)
        assertEquals(AssetClass.CRYPTO, holding.effectiveAssetClass())
    }

    @Test
    fun `a crypto search result shows the Cripto pill and its data source`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("bitcoin")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()

        val row = vm.state.value.results.single { it.ref == bitcoinRef }
        assertEquals("Cripto", row.typeLabel)
        assertEquals("BTC", row.identifier)
        assertEquals("CoinGecko", row.source)
    }

    @Test
    fun `the class choice does not survive going back to the search`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, equityFundRef)
        vm.onAssetClassChange(AssetClass.FIXED_INCOME)

        vm.onBackToSearch()
        vm.onPick(etfRef)
        advanceUntilIdle()

        assertEquals("Sin clasificar", vm.state.value.assetClass.text)
    }

    @Test
    fun `a manual holding is unclassified until the user picks a class`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStartManual()
        assertEquals("Sin clasificar", vm.state.value.assetClass.text)
        vm.onNameChange("Plan de pensiones")
        vm.onAmountChange("12500")

        save(vm)

        assertNull(repository.current.single().assetClass)
        assertNull(repository.current.single().effectiveAssetClass())
    }

    @Test
    fun `a manual holding stores the class the user picked`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStartManual()
        vm.onNameChange("Piso")
        vm.onAmountChange("150000")
        vm.onAssetClassChange(AssetClass.REAL_ESTATE)

        save(vm)

        assertEquals(AssetClass.REAL_ESTATE, repository.current.single().assetClass)
    }

    @Test
    fun `the Cripto type preselects the Cripto class in the manual form`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStartManual()
        vm.onNameChange("Moneda propia")
        vm.onAmountChange("300")

        vm.onTypeChange(InstrumentType.CRYPTO)
        assertEquals(AssetClass.CRYPTO, vm.state.value.assetClass.effective)

        save(vm)
        assertEquals(AssetClass.CRYPTO, repository.current.single().effectiveAssetClass())
    }

    @Test
    fun `a manual holding made from a product without a price keeps its inferred class`() = runTest(dispatcher) {
        source.quoteFailure = IllegalStateException("no price")
        val vm = viewModel()
        pick(vm, equityFundRef)
        vm.onAddManuallyFromPicked()
        vm.onAmountChange("1000")

        save(vm)

        assertEquals(AssetClass.EQUITY, vm.state.value.assetClass.effective)
        assertEquals(AssetClass.EQUITY, repository.current.single().effectiveAssetClass())
    }
}
