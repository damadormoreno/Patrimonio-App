package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.testing.FakeFxRepository
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
class HoldingDetailAssetClassTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // Classified by its Morningstar category, nothing chosen.
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
            lastQuote = LastQuote(Price(245_120_000, Currency.EUR), LocalDate(2026, 10, 10), fetchedAtEpochMs = 1L),
        ),
        fundamentals = InstrumentFundamentals(category = "RV Global"),
    )
    private val manual = PortfolioHolding(
        id = "art",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Cuadro",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(250_000), Currency.EUR), 1_791_633_600_000L),
    )

    private fun viewModel(id: String, repository: FakePortfolioRepository) = HoldingDetailViewModel(
        holdingId = id,
        portfolioRepository = repository,
        portfolios = FakePortfolioCollectionRepository(holdings = repository),
        fxRepository = FakeFxRepository(FxRates(emptyMap())),
        zoneProvider = { TimeZone.UTC },
    )

    private fun TestScope.open(vm: HoldingDetailViewModel): Job {
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    @Test
    fun `the sheet shows the inferred class of a holding with none chosen`() = runTest(dispatcher) {
        val vm = viewModel("fund", FakePortfolioRepository(listOf(fund)))
        val job = open(vm)

        val choice = vm.state.value.assetClass
        assertEquals("Renta variable (según Morningstar)", choice.text)
        assertEquals("Automática (según Morningstar)", choice.automaticLabel)
        assertFalse(vm.state.value.canSave)
        job.cancel()
    }

    @Test
    fun `a holding without a category is unclassified`() = runTest(dispatcher) {
        val vm = viewModel("art", FakePortfolioRepository(listOf(manual)))
        val job = open(vm)

        assertEquals("Sin clasificar", vm.state.value.assetClass.text)
        job.cancel()
    }

    @Test
    fun `changing the class enables Save and writes only the class`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel("fund", repository)
        val job = open(vm)

        vm.onAssetClassChange(AssetClass.FIXED_INCOME)
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        assertEquals("Renta fija", vm.state.value.assetClass.text)

        vm.onSave()
        advanceUntilIdle()

        assertEquals(listOf<Pair<String, AssetClass?>>("fund" to AssetClass.FIXED_INCOME), repository.assetClassCalls)
        assertEquals(AssetClass.FIXED_INCOME, repository.current.single().assetClass)
        assertEquals(fund.valuation, repository.current.single().valuation)
        job.cancel()
    }

    @Test
    fun `going back to automatic clears the explicit class`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund.copy(assetClass = AssetClass.COMMODITIES)))
        val vm = viewModel("fund", repository)
        val job = open(vm)
        assertEquals("Oro y materias primas", vm.state.value.assetClass.text)

        vm.onAssetClassChange(null)
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        assertEquals("Renta variable (según Morningstar)", vm.state.value.assetClass.text)

        vm.onSave()
        advanceUntilIdle()

        assertEquals(listOf<Pair<String, AssetClass?>>("fund" to null), repository.assetClassCalls)
        assertNull(repository.current.single().assetClass)
        job.cancel()
    }

    @Test
    fun `an unchanged class is not written when something else is saved`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund.copy(assetClass = AssetClass.MIXED)))
        val vm = viewModel("fund", repository)
        val job = open(vm)

        vm.onEditChange("12")
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()

        assertTrue(repository.assetClassCalls.isEmpty())
        assertEquals(AssetClass.MIXED, repository.current.single().assetClass)
        job.cancel()
    }

    @Test
    fun `picking the class it already has is not a change`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund.copy(assetClass = AssetClass.MIXED)))
        val vm = viewModel("fund", repository)
        val job = open(vm)

        vm.onAssetClassChange(AssetClass.FIXED_INCOME)
        vm.onAssetClassChange(AssetClass.MIXED)
        advanceUntilIdle()

        assertFalse(vm.state.value.canSave)
        job.cancel()
    }

    @Test
    fun `a manual holding can be classified from the sheet`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(manual))
        val vm = viewModel("art", repository)
        val job = open(vm)

        vm.onAssetClassChange(AssetClass.OTHER)
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()

        assertEquals(listOf<Pair<String, AssetClass?>>("art" to AssetClass.OTHER), repository.assetClassCalls)
        job.cancel()
    }
}
