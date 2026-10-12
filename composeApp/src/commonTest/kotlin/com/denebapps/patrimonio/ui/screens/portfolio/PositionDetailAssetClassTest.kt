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
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PositionDetailAssetClassTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-11T12:00:00Z")
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000FUND")
    private val bitcoinRef = InstrumentRef(QuoteProvider.COINGECKO, "bitcoin")

    private fun quoted(
        id: String,
        ref: InstrumentRef,
        type: InstrumentType,
        fundamentals: InstrumentFundamentals? = null,
        assetClass: AssetClass? = null,
    ) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Holding $id",
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = ref,
            currency = Currency.EUR,
            units = Units(10_000_000),
            lastQuote = LastQuote(Price(100_000_000, Currency.EUR), LocalDate(2026, 10, 9), fetchedAtEpochMs = 1L),
        ),
        fundamentals = fundamentals,
        assetClass = assetClass,
    )

    private val fund = quoted(
        "fund",
        fundRef,
        InstrumentType.FUND,
        InstrumentFundamentals(category = "RV Global", riskLevel = 5),
    )
    private val bitcoin = quoted("btc", bitcoinRef, InstrumentType.CRYPTO)
    private val manual = PortfolioHolding(
        id = "art",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Cuadro",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(250_000), Currency.EUR), 1_791_633_600_000L),
    )

    private val history = listOf(
        PricePoint(LocalDate(2025, 3, 14), Price(100_000_000, Currency.EUR)),
        PricePoint(LocalDate(2026, 10, 9), Price(132_000_000, Currency.EUR)),
    )

    private fun viewModel(id: String, vararg holdings: PortfolioHolding): PositionDetailViewModel {
        val repository = FakePortfolioRepository(holdings.toList())
        val histories = FakeInstrumentHistoryRepository().apply {
            set(fundRef, history)
            set(bitcoinRef, history)
        }
        return PositionDetailViewModel(
            holdingId = id,
            portfolioRepository = repository,
            portfolios = FakePortfolioCollectionRepository(holdings = repository),
            movementRepository = FakeMovementRepository(repository),
            fxHistoryRepository = FakeFxHistoryRepository(),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            historyRepository = histories,
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
    }

    private fun TestScope.open(vm: PositionDetailViewModel): Job {
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    @Test
    fun `an inferred class is shown as automatic`() = runTest(dispatcher) {
        val vm = viewModel("fund", fund)
        val job = open(vm)

        assertEquals("Renta variable · automática", vm.state.value.header.classText)
        job.cancel()
    }

    @Test
    fun `a class the user chose is shown as chosen`() = runTest(dispatcher) {
        val vm = viewModel("fund", fund.copy(assetClass = AssetClass.COMMODITIES))
        val job = open(vm)

        assertEquals("Oro y materias primas · elegida", vm.state.value.header.classText)
        job.cancel()
    }

    @Test
    fun `a holding without a class reads unclassified`() = runTest(dispatcher) {
        val vm = viewModel("art", manual)
        val job = open(vm)

        assertEquals("Sin clasificar", vm.state.value.header.classText)
        job.cancel()
    }

    @Test
    fun `a CoinGecko holding is Cripto, has no Morningstar data and notes its one-year history`() =
        runTest(dispatcher) {
            val vm = viewModel("btc", bitcoin)
            val job = open(vm)

            val state = vm.state.value
            assertEquals("Cripto · automática", state.header.classText)
            assertEquals("Cripto", state.header.typeLabel)
            assertEquals("Holding btc", state.header.name)
            assertNull(state.product)
            assertEquals("Histórico del último año (límite de CoinGecko).", state.chartNote)
            job.cancel()
        }

    @Test
    fun `other products have no history note`() = runTest(dispatcher) {
        val vm = viewModel("fund", fund)
        val job = open(vm)

        assertNull(vm.state.value.chartNote)
        job.cancel()
    }
}
