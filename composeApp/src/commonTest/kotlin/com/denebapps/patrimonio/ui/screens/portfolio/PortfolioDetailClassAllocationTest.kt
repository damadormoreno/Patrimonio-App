package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.testing.FakeAllocationTargetRepository
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioDetailClassAllocationTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-11T12:00:00Z")
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun manual(
        id: String,
        euros: Long,
        assetClass: AssetClass? = null,
        portfolioId: String = TEST_PORTFOLIO_ID,
        type: InstrumentType = InstrumentType.OTHER,
        fundamentals: InstrumentFundamentals? = null,
    ) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = "Holding $id",
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(euros * 100), Currency.EUR), 1L),
        assetClass = assetClass,
        fundamentals = fundamentals,
    )

    private val bondCategory = InstrumentFundamentals(category = "RF Bonos")

    private fun viewModel(holdings: List<PortfolioHolding>, portfolioId: String? = null): PortfolioDetailViewModel {
        val repository = FakePortfolioRepository(holdings)
        return PortfolioDetailViewModel(
            portfolioId = portfolioId,
            portfolios = FakePortfolioCollectionRepository(
                listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("pf-hijos", "Hijos", 1)),
                repository,
            ),
            allocationTargets = FakeAllocationTargetRepository(),
            portfolioRepository = repository,
            movementRepository = FakeMovementRepository(repository),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            fxHistoryRepository = FakeFxHistoryRepository(FxHistory(emptyMap())),
            historyRepository = FakeInstrumentHistoryRepository(),
            preferences = FakePreferencesRepository(),
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
    }

    private fun TestScope.open(vm: PortfolioDetailViewModel): Job {
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    @Test
    fun `the portfolio is split by asset class, largest first`() = runTest(dispatcher) {
        val vm = viewModel(
            listOf(
                manual("a", 2_000, AssetClass.FIXED_INCOME),
                manual("b", 7_000, AssetClass.EQUITY),
                manual("c", 1_000, AssetClass.CRYPTO),
            ),
        )
        val job = open(vm)

        val allocation = assertNotNull(vm.state.value.classAllocation)
        assertEquals(
            listOf(
                ClassRowUi(AssetClass.EQUITY, "Renta variable", "7.000,00 €", "70,0 %", 0.7f),
                ClassRowUi(AssetClass.FIXED_INCOME, "Renta fija", "2.000,00 €", "20,0 %", 0.2f),
                ClassRowUi(AssetClass.CRYPTO, "Cripto", "1.000,00 €", "10,0 %", 0.1f),
            ),
            allocation.rows,
        )
        assertEquals(1.0, allocation.rows.sumOf { it.share.toDouble() }, 0.0001)
        assertNull(allocation.unclassifiedHint)
        job.cancel()
    }

    @Test
    fun `a class inferred from the category counts without the user choosing it`() = runTest(dispatcher) {
        val vm = viewModel(
            listOf(
                manual("a", 1_000, type = InstrumentType.FUND, fundamentals = bondCategory),
                manual("b", 1_000, type = InstrumentType.STOCK),
            ),
        )
        val job = open(vm)

        val rows = assertNotNull(vm.state.value.classAllocation).rows
        assertEquals(setOf(AssetClass.FIXED_INCOME, AssetClass.EQUITY), rows.map { it.assetClass }.toSet())
        job.cancel()
    }

    @Test
    fun `unclassified holdings are listed last with a hint, however large they are`() = runTest(dispatcher) {
        val vm = viewModel(listOf(manual("a", 8_000), manual("b", 2_000, AssetClass.EQUITY)))
        val job = open(vm)

        val allocation = assertNotNull(vm.state.value.classAllocation)
        assertEquals(
            listOf(AssetClass.EQUITY, null),
            allocation.rows.map { it.assetClass },
        )
        val unclassified = allocation.rows.last()
        assertEquals("Sin clasificar", unclassified.label)
        assertEquals("8.000,00 €", unclassified.amountText)
        assertEquals("80,0 %", unclassified.shareText)
        assertEquals("Asigna su clase desde la ficha del producto.", allocation.unclassifiedHint)
        job.cancel()
    }

    @Test
    fun `a portfolio only splits its own holdings and all of them split everything`() = runTest(dispatcher) {
        val holdings = listOf(
            manual("a", 3_000, AssetClass.EQUITY),
            manual("b", 1_000, AssetClass.COMMODITIES, portfolioId = "pf-hijos"),
        )

        val mine = viewModel(holdings, TEST_PORTFOLIO_ID)
        val job = open(mine)
        val mineRows = assertNotNull(mine.state.value.classAllocation).rows
        assertEquals(listOf(AssetClass.EQUITY), mineRows.map { it.assetClass })
        job.cancel()

        val all = viewModel(holdings, null)
        val allJob = open(all)
        assertEquals(
            listOf(AssetClass.EQUITY, AssetClass.COMMODITIES),
            assertNotNull(all.state.value.classAllocation).rows.map { it.assetClass },
        )
        allJob.cancel()
    }

    @Test
    fun `an empty portfolio has no class split`() = runTest(dispatcher) {
        val vm = viewModel(emptyList())
        val job = open(vm)

        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.classAllocation)
        job.cancel()
    }
}
