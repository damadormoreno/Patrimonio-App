package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentPreviewData
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.PortfolioScopeSeries
import com.denebapps.patrimonio.testing.FakeInstrumentPreviewLoader
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioSeriesLoader
import com.denebapps.patrimonio.testing.FakePreferencesRepository
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class InstrumentPreviewAssetClassTest {
    private val dispatcher = StandardTestDispatcher()
    private val lastFriday = LocalDate(2026, 10, 9)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Sixty Friday closes ending on [lastFriday] that move in a fixed zig-zag. */
    private val history: List<PricePoint> = (0 until WEEKS).map { index ->
        val price = 100.0 + index * 0.5 + (index % 3) * 2.0
        PricePoint(
            lastFriday.minus((WEEKS - 1 - index) * WEEK_DAYS, DateTimeUnit.DAY),
            Price.fromDouble(price, Currency.EUR),
        )
    }

    private val equityFund = InstrumentCandidate(
        ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000CAND"),
        name = "Fondo Global",
        isin = "IE00B4L5Y983",
        ticker = null,
        type = InstrumentType.FUND,
        currency = Currency.EUR,
        exchange = null,
        fundamentals = InstrumentFundamentals(category = "RV Global", riskLevel = 5),
    )
    private val plainEtf = InstrumentCandidate(
        ref = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"),
        name = "iShares MSCI World",
        isin = null,
        ticker = "IWDA",
        type = InstrumentType.ETF,
        currency = null,
        exchange = "Amsterdam",
    )
    private val bitcoin = InstrumentCandidate(
        ref = InstrumentRef(QuoteProvider.COINGECKO, "bitcoin"),
        name = "Bitcoin",
        isin = null,
        ticker = "BTC",
        type = InstrumentType.CRYPTO,
        currency = Currency.EUR,
        exchange = null,
    )

    private fun held(id: String, euros: Long, assetClass: AssetClass?) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Holding $id",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(euros * 100), Currency.EUR), 1L),
        assetClass = assetClass,
    )

    private fun viewModel(
        candidate: InstrumentCandidate,
        vararg holdings: PortfolioHolding,
    ): InstrumentPreviewViewModel {
        val loader = FakeInstrumentPreviewLoader().apply {
            data = InstrumentPreviewData(
                candidate = candidate,
                currency = Currency.EUR,
                history = history,
                eurHistory = history,
                fundamentals = candidate.fundamentals,
            )
        }
        return InstrumentPreviewViewModel(
            candidate = candidate,
            previewLoader = loader,
            seriesLoader = FakePortfolioSeriesLoader(
                PortfolioScopeSeries(holdings.toList(), emptyMap(), FxRates(emptyMap())),
            ),
            portfolios = FakePortfolioCollectionRepository(listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0))),
            preferences = FakePreferencesRepository(),
            clock = fixedClock("2026-10-11T12:00:00Z"),
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
    }

    private fun TestScope.open(vm: InstrumentPreviewViewModel, amount: String? = null): Job {
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        if (amount != null) {
            vm.onAmountChange(amount)
            advanceUntilIdle()
        }
        return job
    }

    private fun InstrumentPreviewViewModel.projection() = assertNotNull(assertNotNull(state.value.fit).projection)

    @Test
    fun `the projection shows the class mix before and after, the product under its inferred class`() =
        runTest(dispatcher) {
            val vm = viewModel(equityFund, held("a", 1_000, AssetClass.FIXED_INCOME))
            val job = open(vm, amount = "3000")

            val mix = assertNotNull(vm.projection().classMix)
            assertEquals(
                listOf(
                    ClassMixRowUi("Renta variable", "—", "75,0 %"),
                    ClassMixRowUi("Renta fija", "100,0 %", "25,0 %"),
                ),
                mix.rows,
            )
            assertEquals("Se contaría como Renta variable (según Morningstar).", mix.note)
            job.cancel()
        }

    @Test
    fun `a product with no class counts as unclassified and the note says how to fix it`() = runTest(dispatcher) {
        val vm = viewModel(plainEtf, held("a", 1_000, AssetClass.EQUITY))
        val job = open(vm, amount = "1000")

        val mix = assertNotNull(vm.projection().classMix)
        assertEquals(
            listOf(
                ClassMixRowUi("Renta variable", "100,0 %", "50,0 %"),
                ClassMixRowUi("Sin clasificar", "—", "50,0 %"),
            ),
            mix.rows,
        )
        assertEquals("Se contaría como Sin clasificar: elige su clase al añadirlo.", mix.note)
        job.cancel()
    }

    @Test
    fun `a crypto product counts as Cripto by its type`() = runTest(dispatcher) {
        val vm = viewModel(bitcoin, held("a", 3_000, AssetClass.EQUITY))
        val job = open(vm, amount = "1000")

        val mix = assertNotNull(vm.projection().classMix)
        assertEquals(ClassMixRowUi("Cripto", "—", "25,0 %"), mix.rows.last())
        assertEquals("Se contaría como Cripto (según el tipo).", mix.note)
        job.cancel()
    }

    @Test
    fun `there is no class mix without an amount`() = runTest(dispatcher) {
        val vm = viewModel(equityFund, held("a", 1_000, AssetClass.FIXED_INCOME))
        val job = open(vm)

        assertNull(assertNotNull(vm.state.value.fit).projection)
        job.cancel()
    }

    @Test
    fun `a CoinGecko preview has no product data and says its history is one year`() = runTest(dispatcher) {
        val vm = viewModel(bitcoin, held("a", 1_000, AssetClass.EQUITY))
        val job = open(vm)

        val state = vm.state.value
        assertEquals(PreviewStatus.READY, state.status)
        assertEquals("Cripto", state.header.typeLabel)
        assertEquals("BTC · CoinGecko", state.header.identifier)
        assertNull(state.product)
        assertEquals("Histórico del último año (límite de CoinGecko).", state.chartNote)
        job.cancel()
    }

    @Test
    fun `other providers have no history note`() = runTest(dispatcher) {
        val vm = viewModel(equityFund)
        val job = open(vm)

        assertNull(vm.state.value.chartNote)
        job.cancel()
    }

    private companion object {
        const val WEEKS = 60
        const val WEEK_DAYS = 7
    }
}
