package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.repository.CarteraFilterSelection
import com.denebapps.patrimonio.testing.FakeAllocationTargetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
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
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The notice under the total of Cartera that says a portfolio has drifted from its targets. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarteraRebalanceBannerTest {
    private val dispatcher = StandardTestDispatcher()

    private val jubilacion = Portfolio("pf-jub", "Jubilación", 0)
    private val hijos = Portfolio("pf-hijos", "Hijos", 1)
    private val ocio = Portfolio("pf-ocio", "Ocio", 2)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun holding(id: String, portfolioId: String, euros: Long, assetClass: AssetClass?) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = "Producto $id",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(euros * 100), Currency.EUR), 0L),
        assetClass = assetClass,
    )

    // 92 / 6 / 2 % against 80 / 15 / 5: equity +12 pts, the rest under; 1.500 € bring it back without selling.
    private fun drifted(portfolioId: String) = listOf(
        holding("$portfolioId-a", portfolioId, 9_200, AssetClass.EQUITY),
        holding("$portfolioId-b", portfolioId, 600, AssetClass.FIXED_INCOME),
        holding("$portfolioId-c", portfolioId, 200, AssetClass.MONEY_MARKET),
    )

    // 82 / 13 / 5 %: every class within 5 points of its target.
    private fun onTarget(portfolioId: String) = listOf(
        holding("$portfolioId-a", portfolioId, 8_200, AssetClass.EQUITY),
        holding("$portfolioId-b", portfolioId, 1_300, AssetClass.FIXED_INCOME),
        holding("$portfolioId-c", portfolioId, 500, AssetClass.MONEY_MARKET),
    )

    private val targetsBp = mapOf(
        AssetClass.EQUITY to 8_000,
        AssetClass.FIXED_INCOME to 1_500,
        AssetClass.MONEY_MARKET to 500,
    )

    private class Setup(val targets: FakeAllocationTargetRepository, val vm: CarteraViewModel)

    private fun setup(
        holdings: List<PortfolioHolding>,
        portfolios: List<Portfolio> = listOf(jubilacion, hijos, ocio),
        selected: String? = null,
        filter: CarteraFilterSelection = CarteraFilterSelection(),
        withTargets: List<String> = emptyList(),
    ): Setup {
        val repository = FakePortfolioRepository(holdings)
        val collection = FakePortfolioCollectionRepository(portfolios, repository)
        val targets = FakeAllocationTargetRepository(collection)
        withTargets.forEach { targets.seed(it, targetsBp) }
        val vm = CarteraViewModel(
            portfolioRepository = repository,
            portfolios = collection,
            allocationTargets = targets,
            preferences = FakePreferencesRepository(selectedPortfolioId = selected, carteraFilter = filter),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            zoneProvider = { TimeZone.UTC },
        )
        return Setup(targets, vm)
    }

    private fun TestScope.open(s: Setup): Job {
        val job = launch { s.vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    // ---- one portfolio -----------------------------------------------------------------------------------------

    @Test
    fun `a selected portfolio out of band shows the worst drift and the contribution that fixes it`() =
        runTest(dispatcher) {
            val s = setup(drifted("pf-jub"), selected = "pf-jub", withTargets = listOf("pf-jub"))
            val job = open(s)

            assertEquals(
                RebalanceBannerUi(
                    title = "Tu cartera se ha desviado del objetivo",
                    detail = "Renta variable +12 pts",
                    suggestion = "Aporta 1.500,00 € para volver al objetivo sin vender",
                    portfolioId = "pf-jub",
                ),
                s.vm.state.value.rebalanceBanner,
            )
            job.cancel()
        }

    @Test
    fun `the banner names the class that drifted most, whichever way`() = runTest(dispatcher) {
        // Fixed income 3 % against 15 % is 12 points under, equity 90 % against 80 % only 10 over.
        val holdings = listOf(
            holding("a", "pf-jub", 9_000, AssetClass.EQUITY),
            holding("b", "pf-jub", 300, AssetClass.FIXED_INCOME),
            holding("c", "pf-jub", 700, AssetClass.MONEY_MARKET),
        )
        val s = setup(holdings, selected = "pf-jub", withTargets = listOf("pf-jub"))
        val job = open(s)

        assertEquals("Renta fija −12 pts", s.vm.state.value.rebalanceBanner?.detail)
        job.cancel()
    }

    @Test
    fun `a portfolio within its band shows no banner`() = runTest(dispatcher) {
        val s = setup(onTarget("pf-jub"), selected = "pf-jub", withTargets = listOf("pf-jub"))
        val job = open(s)

        assertNull(s.vm.state.value.rebalanceBanner)
        job.cancel()
    }

    @Test
    fun `a portfolio without targets shows no banner however far it is from any`() = runTest(dispatcher) {
        val s = setup(drifted("pf-jub"), selected = "pf-jub")
        val job = open(s)

        assertNull(s.vm.state.value.rebalanceBanner)
        job.cancel()
    }

    @Test
    fun `the banner is that of the selected portfolio, not of the others`() = runTest(dispatcher) {
        val s = setup(
            drifted("pf-jub") + onTarget("pf-hijos"),
            selected = "pf-hijos",
            withTargets = listOf("pf-jub", "pf-hijos"),
        )
        val job = open(s)

        assertNull(s.vm.state.value.rebalanceBanner)
        job.cancel()
    }

    @Test
    fun `the filters of the list do not change the banner`() = runTest(dispatcher) {
        val plain = setup(drifted("pf-jub"), selected = "pf-jub", withTargets = listOf("pf-jub"))
        val filtered = setup(
            drifted("pf-jub"),
            selected = "pf-jub",
            filter = CarteraFilterSelection(assetClasses = setOf("FIXED_INCOME")),
            withTargets = listOf("pf-jub"),
        )
        val jobs = listOf(open(plain), open(filtered))

        val expected = plain.vm.state.value.rebalanceBanner
        assertEquals(expected, filtered.vm.state.value.rebalanceBanner)
        assertEquals("Renta variable +12 pts", expected?.detail)
        // The list does follow the filter.
        assertEquals(listOf("pf-jub-b"), filtered.vm.state.value.rows.map { it.id })
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `the banner follows the targets as they change`() = runTest(dispatcher) {
        val s = setup(drifted("pf-jub"), selected = "pf-jub")
        val job = open(s)
        assertNull(s.vm.state.value.rebalanceBanner)

        s.targets.setTargets("pf-jub", targetsBp, toleranceBp = 500)
        advanceUntilIdle()
        val withTargets = s.vm.state.value.rebalanceBanner
        s.targets.setTargets("pf-jub", targetsBp, toleranceBp = 1_500)
        advanceUntilIdle()
        val wideBand = s.vm.state.value.rebalanceBanner

        assertEquals("pf-jub", withTargets?.portfolioId)
        assertNull(wideBand)
        job.cancel()
    }

    @Test
    fun `a contribution of nothing leaves the sentence out`() = runTest(dispatcher) {
        // Crypto is held but has no target: out of band, and no contribution reduces it.
        val holdings = listOf(
            holding("a", "pf-jub", 8_000, AssetClass.EQUITY),
            holding("b", "pf-jub", 1_500, AssetClass.FIXED_INCOME),
            holding("c", "pf-jub", 500, AssetClass.MONEY_MARKET),
            holding("d", "pf-jub", 1_000, AssetClass.CRYPTO),
        )
        val s = setup(holdings, selected = "pf-jub", withTargets = listOf("pf-jub"))
        val job = open(s)

        val banner = s.vm.state.value.rebalanceBanner
        assertEquals("Cripto +9,09 pts", banner?.detail)
        // 8000 / .8 = 10000 is the total of the targeted classes already: nothing to add.
        assertNull(banner?.suggestion)
        job.cancel()
    }

    @Test
    fun `with a single portfolio the banner is that portfolio's`() = runTest(dispatcher) {
        val s = setup(
            drifted("pf-jub"),
            portfolios = listOf(jubilacion),
            withTargets = listOf("pf-jub"),
        )
        val job = open(s)

        assertEquals("pf-jub", s.vm.state.value.rebalanceBanner?.portfolioId)
        assertEquals("Tu cartera se ha desviado del objetivo", s.vm.state.value.rebalanceBanner?.title)
        job.cancel()
    }

    // ---- all the portfolios ------------------------------------------------------------------------------------

    @Test
    fun `all the portfolios list those that need rebalancing and open the first`() = runTest(dispatcher) {
        val s = setup(
            drifted("pf-jub") + drifted("pf-hijos") + onTarget("pf-ocio"),
            withTargets = listOf("pf-jub", "pf-hijos", "pf-ocio"),
        )
        val job = open(s)

        assertEquals(
            RebalanceBannerUi(
                title = "Necesitan rebalanceo: Jubilación, Hijos",
                detail = null,
                suggestion = null,
                portfolioId = "pf-jub",
            ),
            s.vm.state.value.rebalanceBanner,
        )
        job.cancel()
    }

    @Test
    fun `a single portfolio that needs it is named in the singular`() = runTest(dispatcher) {
        val s = setup(
            onTarget("pf-jub") + drifted("pf-hijos"),
            withTargets = listOf("pf-jub", "pf-hijos"),
        )
        val job = open(s)

        val banner = s.vm.state.value.rebalanceBanner
        assertEquals("Necesita rebalanceo: Hijos", banner?.title)
        assertEquals("pf-hijos", banner?.portfolioId)
        job.cancel()
    }

    @Test
    fun `all the portfolios show nothing when none needs rebalancing`() = runTest(dispatcher) {
        val s = setup(
            onTarget("pf-jub") + drifted("pf-hijos"),
            withTargets = listOf("pf-jub"),
        )
        val job = open(s)

        assertNull(s.vm.state.value.rebalanceBanner)
        job.cancel()
    }

    @Test
    fun `long lists of portfolios are shortened`() = runTest(dispatcher) {
        val names = listOf("Uno", "Dos", "Tres", "Cuatro", "Cinco")
        val portfolios = names.mapIndexed { index, name -> Portfolio("pf-$index", name, index) }
        val s = setup(
            portfolios.flatMap { drifted(it.id) },
            portfolios = portfolios,
            withTargets = portfolios.map { it.id },
        )
        val job = open(s)

        assertEquals("Necesitan rebalanceo: Uno, Dos, Tres y 2 más", s.vm.state.value.rebalanceBanner?.title)
        job.cancel()
    }
}
