package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.repository.CarteraFilterSelection
import com.denebapps.patrimonio.testing.FakeAllocationTargetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
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

/** The filters of the Cartera tab: options per dimension, OR within / AND across, persistence and scope changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarteraFilterViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val jubilacion = Portfolio("pf-jub", "Jubilación", 0)
    private val hijos = Portfolio("pf-hijos", "Hijos", 1)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun holding(
        id: String,
        portfolioId: String,
        type: InstrumentType,
        euros: Long,
        broker: String? = null,
        assetClass: AssetClass? = null,
    ) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = "Producto $id",
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(euros * 100), Currency.EUR), 0L),
        broker = broker,
        assetClass = assetClass,
    )

    // Jubilación: a (equity fund), b (fixed income ETF), c (stock, no broker, equity by its type), d (unclassified).
    // Hijos: e (crypto by its type), f (money market fund).
    private val a = holding("a", "pf-jub", InstrumentType.FUND, 1_000, "MyInvestor", AssetClass.EQUITY)
    private val b = holding("b", "pf-jub", InstrumentType.ETF, 2_000, "myinvestor", AssetClass.FIXED_INCOME)
    private val c = holding("c", "pf-jub", InstrumentType.STOCK, 500)
    private val d = holding("d", "pf-jub", InstrumentType.FUND, 250, "Degiro")
    private val e = holding("e", "pf-hijos", InstrumentType.CRYPTO, 100, "Binance")
    private val f = holding("f", "pf-hijos", InstrumentType.FUND, 400, "MyInvestor", AssetClass.MONEY_MARKET)

    private class Setup(val preferences: FakePreferencesRepository, val vm: CarteraViewModel)

    private fun setup(
        holdings: List<PortfolioHolding> = listOf(a, b, c, d, e, f),
        selected: String? = null,
        filter: CarteraFilterSelection = CarteraFilterSelection(),
    ): Setup {
        val repository = FakePortfolioRepository(holdings)
        val collection = FakePortfolioCollectionRepository(listOf(jubilacion, hijos), repository)
        val preferences = FakePreferencesRepository(selectedPortfolioId = selected, carteraFilter = filter)
        val vm = CarteraViewModel(
            portfolioRepository = repository,
            portfolios = collection,
            allocationTargets = FakeAllocationTargetRepository(collection),
            preferences = preferences,
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            zoneProvider = { TimeZone.UTC },
        )
        return Setup(preferences, vm)
    }

    private fun TestScope.open(s: Setup): Job {
        val job = launch { s.vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    private fun TestScope.settle() = advanceUntilIdle()

    private fun ids(state: CarteraUiState) = state.rows.map { it.id }

    private fun labels(options: List<FilterOptionUi>) = options.map { it.label }

    // ---- options -----------------------------------------------------------------------------------------------

    @Test
    fun `the class options follow the effective class and end with Sin clasificar`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val options = s.vm.state.value.filter.assetClasses
        assertEquals(
            listOf("Renta variable", "Renta fija", "Monetario", "Cripto", "Sin clasificar"),
            labels(options),
        )
        assertEquals(
            listOf("EQUITY", "FIXED_INCOME", "MONEY_MARKET", "CRYPTO", UNCLASSIFIED_FILTER_KEY),
            options.map { it.key },
        )
        job.cancel()
    }

    @Test
    fun `the broker options group case-insensitively, sort by name and end with Sin broker`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val options = s.vm.state.value.filter.brokers
        assertEquals(listOf("Binance", "Degiro", "MyInvestor", "Sin bróker"), labels(options))
        assertEquals(listOf("binance", "degiro", "myinvestor", NO_BROKER_FILTER_KEY), options.map { it.key })
        job.cancel()
    }

    @Test
    fun `the type options list the types present by their existing label`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val options = s.vm.state.value.filter.types
        assertEquals(listOf("Fondo", "ETF", "Acción", "Cripto"), labels(options))
        assertEquals(listOf("FUND", "ETF", "STOCK", "CRYPTO"), options.map { it.key })
        job.cancel()
    }

    @Test
    fun `options come only from the holdings in the scope`() = runTest(dispatcher) {
        val s = setup(selected = "pf-hijos")
        val job = open(s)

        val filter = s.vm.state.value.filter
        assertEquals(listOf("Monetario", "Cripto"), labels(filter.assetClasses))
        assertEquals(listOf("Binance", "MyInvestor"), labels(filter.brokers))
        assertEquals(listOf("Fondo", "Cripto"), labels(filter.types))
        job.cancel()
    }

    @Test
    fun `a scope without holdings has no options and nothing to filter`() = runTest(dispatcher) {
        val s = setup(holdings = listOf(a, b, c, d))
        s.vm.onSelectPortfolio("pf-hijos")
        val job = open(s)

        val state = s.vm.state.value
        assertTrue(state.isEmpty)
        assertFalse(state.filter.hasOptions)
        assertNull(state.filterSummary)
        job.cancel()
    }

    // ---- semantics ---------------------------------------------------------------------------------------------

    @Test
    fun `without a selection nothing is restricted and nothing is marked as filtered`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val state = s.vm.state.value
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), ids(state))
        assertTrue(state.filter.active.isEmpty())
        assertEquals(0, state.filter.activeCount)
        assertNull(state.filterSummary)
        assertFalse(state.noMatches)
        assertEquals(Money(425_000), state.total)
        job.cancel()
    }

    @Test
    fun `several values of one dimension are an OR`() = runTest(dispatcher) {
        val s = setup(selected = "pf-jub")
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "EQUITY")
        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "FIXED_INCOME")
        settle()

        assertEquals(listOf("a", "b", "c"), ids(s.vm.state.value))
        job.cancel()
    }

    @Test
    fun `different dimensions are an AND`() = runTest(dispatcher) {
        val s = setup(selected = "pf-jub")
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "EQUITY")
        s.vm.onToggleFilter(FilterDimension.TYPE, "STOCK")
        settle()

        assertEquals(listOf("c"), ids(s.vm.state.value))
        job.cancel()
    }

    @Test
    fun `an OR in one dimension and an AND with another combine`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.TYPE, "FUND")
        s.vm.onToggleFilter(FilterDimension.TYPE, "ETF")
        s.vm.onToggleFilter(FilterDimension.BROKER, "myinvestor")
        settle()

        assertEquals(listOf("a", "b", "f"), ids(s.vm.state.value))
        job.cancel()
    }

    @Test
    fun `a broker matches whatever its casing`() = runTest(dispatcher) {
        val s = setup(selected = "pf-jub")
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.BROKER, "myinvestor")
        settle()

        assertEquals(listOf("a", "b"), ids(s.vm.state.value))
        job.cancel()
    }

    @Test
    fun `Sin broker selects the holdings without one`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.BROKER, NO_BROKER_FILTER_KEY)
        settle()

        assertEquals(listOf("c"), ids(s.vm.state.value))
        job.cancel()
    }

    @Test
    fun `Sin clasificar selects the holdings with no effective class`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, UNCLASSIFIED_FILTER_KEY)
        settle()

        assertEquals(listOf("d"), ids(s.vm.state.value))
        job.cancel()
    }

    @Test
    fun `a class implied by the type matches like an explicit one`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "CRYPTO")
        settle()

        assertEquals(listOf("e"), ids(s.vm.state.value))
        job.cancel()
    }

    // ---- state of a filtered list ------------------------------------------------------------------------------

    @Test
    fun `the total, the summary and the options mark show the filtered list`() = runTest(dispatcher) {
        val s = setup(selected = "pf-jub")
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "EQUITY")
        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "FIXED_INCOME")
        settle()

        val state = s.vm.state.value
        assertEquals(Money(350_000), state.total)
        assertEquals("Filtrado · 3 de 4 productos", state.filterSummary)
        assertEquals("4 productos", state.countLabel)
        assertEquals(
            listOf(true, true, false),
            state.filter.assetClasses.map { it.selected },
        )
        assertEquals(
            listOf("Renta variable", "Renta fija"),
            state.filter.active.map { it.label },
        )
        assertTrue(state.filter.active.all { it.dimension == FilterDimension.ASSET_CLASS })
        job.cancel()
    }

    @Test
    fun `active chips follow the dimension order`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.TYPE, "FUND")
        s.vm.onToggleFilter(FilterDimension.BROKER, "degiro")
        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, UNCLASSIFIED_FILTER_KEY)
        settle()

        assertEquals(
            listOf("Sin clasificar", "Degiro", "Fondo"),
            s.vm.state.value.filter.active.map { it.label },
        )
        job.cancel()
    }

    @Test
    fun `toggling a selected value takes it out`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onToggleFilter(FilterDimension.TYPE, "FUND")
        settle()

        s.vm.onToggleFilter(FilterDimension.TYPE, "FUND")
        settle()

        assertEquals(6, s.vm.state.value.rows.size)
        assertTrue(s.vm.state.value.filter.active.isEmpty())
        job.cancel()
    }

    @Test
    fun `removing a chip drops only that value`() = runTest(dispatcher) {
        val s = setup(selected = "pf-jub")
        val job = open(s)
        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "EQUITY")
        s.vm.onToggleFilter(FilterDimension.TYPE, "STOCK")
        settle()

        s.vm.onRemoveFilter(FilterDimension.TYPE, "STOCK")
        settle()

        val state = s.vm.state.value
        assertEquals(listOf("a", "c"), ids(state))
        assertEquals(listOf("Renta variable"), state.filter.active.map { it.label })
        job.cancel()
    }

    @Test
    fun `clearing the filters shows everything again and forgets them`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onToggleFilter(FilterDimension.TYPE, "FUND")
        s.vm.onToggleFilter(FilterDimension.BROKER, "degiro")
        settle()

        s.vm.onClearFilters()
        settle()

        val state = s.vm.state.value
        assertEquals(6, state.rows.size)
        assertNull(state.filterSummary)
        assertEquals(CarteraFilterSelection(), s.preferences.observeCarteraFilter().first())
        job.cancel()
    }

    @Test
    fun `a filter that matches nothing is told apart from an empty scope`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "CRYPTO")
        s.vm.onToggleFilter(FilterDimension.TYPE, "FUND")
        settle()

        val state = s.vm.state.value
        assertTrue(state.rows.isEmpty())
        assertTrue(state.noMatches)
        assertFalse(state.isEmpty)
        assertEquals(Money.ZERO, state.total)
        assertEquals("Filtrado · 0 de 6 productos", state.filterSummary)
        assertEquals(2, state.filter.active.size)
        assertEquals(2, state.filter.activeCount)
        job.cancel()
    }

    @Test
    fun `closed positions keep going after the open ones inside the filtered list`() = runTest(dispatcher) {
        fun quoted(id: String, units: Long) = holding(id, "pf-jub", InstrumentType.FUND, 0, "Degiro").copy(
            valuation = HoldingValuation.Quoted(
                ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
                currency = Currency.EUR,
                units = UnitsBalance(units * UNITS_SCALE),
                lastQuote = LastQuote(Price(10_000_000, Currency.EUR), LocalDate(2026, 10, 10), 1L),
            ),
        )
        val s = setup(holdings = listOf(quoted("x", 0), a, quoted("y", 2), quoted("z", 0), quoted("w", 3)))
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.BROKER, "degiro")
        settle()

        val state = s.vm.state.value
        assertEquals(listOf("y", "w", "x", "z"), ids(state))
        assertEquals(listOf(false, false, true, true), state.rows.map { it.isClosed })
        assertEquals("Filtrado · 4 de 5 productos", state.filterSummary)
        job.cancel()
    }

    // ---- persistence -------------------------------------------------------------------------------------------

    @Test
    fun `a change is remembered through the preferences`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onToggleFilter(FilterDimension.ASSET_CLASS, "EQUITY")
        s.vm.onToggleFilter(FilterDimension.BROKER, "degiro")
        s.vm.onToggleFilter(FilterDimension.TYPE, "FUND")
        settle()

        assertEquals(
            CarteraFilterSelection(
                assetClasses = setOf("EQUITY"),
                brokers = setOf("degiro"),
                types = setOf("FUND"),
            ),
            s.preferences.observeCarteraFilter().first(),
        )
        job.cancel()
    }

    @Test
    fun `the remembered filters apply when the tab opens`() = runTest(dispatcher) {
        val s = setup(filter = CarteraFilterSelection(brokers = setOf("myinvestor"), types = setOf("FUND")))
        val job = open(s)

        val state = s.vm.state.value
        assertEquals(listOf("a", "f"), ids(state))
        assertEquals("Filtrado · 2 de 6 productos", state.filterSummary)
        job.cancel()
    }

    @Test
    fun `remembered values that no longer exist are ignored silently`() = runTest(dispatcher) {
        val s = setup(filter = CarteraFilterSelection(brokers = setOf("gone", "degiro"), types = setOf("OTHER")))
        val job = open(s)

        val state = s.vm.state.value
        assertEquals(listOf("d"), ids(state))
        assertEquals(listOf("Degiro"), state.filter.active.map { it.label })
        assertEquals("Filtrado · 1 de 6 productos", state.filterSummary)
        job.cancel()
    }

    @Test
    fun `a dimension whose remembered values all vanished does not restrict`() = runTest(dispatcher) {
        val s = setup(filter = CarteraFilterSelection(brokers = setOf("gone")))
        val job = open(s)

        val state = s.vm.state.value
        assertEquals(6, state.rows.size)
        assertNull(state.filterSummary)
        assertTrue(state.filter.active.isEmpty())
        job.cancel()
    }

    // ---- scope changes -----------------------------------------------------------------------------------------

    @Test
    fun `changing the scope keeps the filters and applies only the values present there`() = runTest(dispatcher) {
        val s = setup(filter = CarteraFilterSelection(assetClasses = setOf("EQUITY", "CRYPTO")))
        val job = open(s)
        assertEquals(listOf("a", "c", "e"), ids(s.vm.state.value))

        s.vm.onSelectPortfolio("pf-jub")
        settle()
        assertEquals(listOf("a", "c"), ids(s.vm.state.value))
        assertEquals(listOf("Renta variable"), s.vm.state.value.filter.active.map { it.label })

        s.vm.onSelectPortfolio("pf-hijos")
        settle()
        assertEquals(listOf("e"), ids(s.vm.state.value))
        job.cancel()
    }

    @Test
    fun `values absent from a scope stay stored and come back with the scope that has them`() = runTest(dispatcher) {
        val s = setup(filter = CarteraFilterSelection(assetClasses = setOf("CRYPTO")))
        val job = open(s)

        s.vm.onSelectPortfolio("pf-jub")
        settle()

        // Nothing in Jubilación is crypto, so the filter does not restrict there...
        assertEquals(listOf("a", "b", "c", "d"), ids(s.vm.state.value))
        assertNull(s.vm.state.value.filterSummary)
        // ...but the remembered value is still stored...
        assertEquals(setOf("CRYPTO"), s.preferences.observeCarteraFilter().first().assetClasses)

        s.vm.onSelectPortfolio(null)
        settle()
        // ...and applies again where it exists.
        assertEquals(listOf("e"), ids(s.vm.state.value))
        job.cancel()
    }
}
