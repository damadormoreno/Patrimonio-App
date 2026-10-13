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
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.PriceRefreshFailure
import com.denebapps.patrimonio.domain.repository.PriceRefreshResult
import com.denebapps.patrimonio.testing.FakeAllocationTargetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class CarteraViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val rates = FxRates(mapOf(Currency.USD to 920_000L))
    private val quoteDate = LocalDate(2026, 10, 10)

    // 2026-10-10T12:00:00Z
    private val manualValuedAt = 1_791_633_600_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun quoted(
        id: String,
        name: String,
        units: Long,
        price: Price?,
        type: InstrumentType = InstrumentType.FUND,
    ) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = name,
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = price?.currency ?: Currency.EUR,
            units = Units(units),
            lastQuote = price?.let { LastQuote(it, quoteDate, fetchedAtEpochMs = 1L) },
        ),
    )

    private val fund = quoted("fund", "Fondo Global", 12_345_678, Price(100_000_000, Currency.EUR))
    private val usdStock = quoted(
        id = "stock",
        name = "Acme Corp",
        units = 10_000_000,
        price = Price(200_000_000, Currency.USD),
        type = InstrumentType.STOCK,
    )
    private val unpriced = quoted("new", "Fondo Nuevo", 5_000_000, price = null)
    private val manual = PortfolioHolding(
        id = "art",
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Cuadro",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(250_000), Currency.EUR), manualValuedAt),
    )

    private fun viewModel(repository: FakePortfolioRepository) = CarteraViewModel(
        portfolioRepository = repository,
        portfolios = FakePortfolioCollectionRepository(holdings = repository),
        allocationTargets = FakeAllocationTargetRepository(),
        preferences = FakePreferencesRepository(),
        fxRepository = FakeFxRepository(rates),
        zoneProvider = { TimeZone.UTC },
    )

    @Test
    fun `an empty portfolio is empty with a zero total`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository())
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.isEmpty)
        assertTrue(state.rows.isEmpty())
        assertEquals(Money.ZERO, state.total)
        assertEquals("Sin productos", state.countLabel)
        assertFalse(state.isRefreshing)
        assertNull(state.refreshMessage)
        job.cancel()
    }

    @Test
    fun `a quoted row shows its units in the second line and its value in the holding currency`() =
        runTest(dispatcher) {
            val vm = viewModel(FakePortfolioRepository(listOf(fund)))
            val job = launch { vm.state.collect {} }
            advanceUntilIdle()

            val row = vm.state.value.rows.single()
            assertEquals("fund", row.id)
            assertEquals("Fondo Global", row.name)
            assertEquals("Fondo", row.typeLabel)
            assertEquals("Fondo · 12,345678 part.", row.meta)
            assertEquals("1.234,57 €", row.valueText)
            assertNull(row.valueEurText)
            job.cancel()
        }

    @Test
    fun `a holding in another currency also shows its value in euros`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository(listOf(usdStock)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.rows.single()
        assertEquals("Acción", row.typeLabel)
        assertEquals("Acción · Renta variable · 10,00 part.", row.meta)
        assertEquals("2.000,00 $", row.valueText)
        assertEquals("≈ 1.840,00 €", row.valueEurText)
        job.cancel()
    }

    @Test
    fun `a holding with no price yet says so`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository(listOf(unpriced)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.rows.single()
        assertEquals("Fondo · 5,00 part.", row.meta)
        assertEquals("Sin precio", row.valueText)
        assertNull(row.valueEurText)
        job.cancel()
    }

    @Test
    fun `a manual row shows its value and the day it was entered`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository(listOf(manual)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.rows.single()
        assertEquals("Otro", row.typeLabel)
        assertEquals("Otro · Valor manual · 10/10/2026", row.meta)
        assertEquals("2.500,00 €", row.valueText)
        job.cancel()
    }

    @Test
    fun `the second line joins type, class, units and broker and drops what is missing`() = runTest(dispatcher) {
        val full = fund.copy(broker = "Openbank", assetClass = AssetClass.MIXED)
        val noBroker = fund.copy(id = "fund2", assetClass = AssetClass.FIXED_INCOME)
        val bare = unpriced.copy(id = "bare")
        val vm = viewModel(FakePortfolioRepository(listOf(full, noBroker, bare)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            listOf(
                "Fondo · Mixto · 12,345678 part. · Openbank",
                "Fondo · Renta fija · 12,345678 part.",
                "Fondo · 5,00 part.",
            ),
            vm.state.value.rows.map { it.meta },
        )
        job.cancel()
    }

    @Test
    fun `the second line names the portfolio while all of them are shown`() = runTest(dispatcher) {
        val other = fund.copy(id = "other", portfolioId = "pf-hijos")
        val repository = FakePortfolioRepository(listOf(fund.copy(portfolioId = "pf-jub"), other))
        val vm = CarteraViewModel(
            portfolioRepository = repository,
            portfolios = FakePortfolioCollectionRepository(
                listOf(Portfolio("pf-jub", "Jubilación", 0), Portfolio("pf-hijos", "Hijos", 1)),
                repository,
            ),
            allocationTargets = FakeAllocationTargetRepository(),
            preferences = FakePreferencesRepository(),
            fxRepository = FakeFxRepository(rates),
            zoneProvider = { TimeZone.UTC },
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            listOf("Fondo · 12,345678 part. · Jubilación", "Fondo · 12,345678 part. · Hijos"),
            vm.state.value.rows.map { it.meta },
        )
        job.cancel()
    }

    @Test
    fun `a row carries the bank or broker when the holding has one`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository(listOf(fund.copy(broker = "Openbank"), manual)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("Openbank", null), vm.state.value.rows.map { it.broker })
        job.cancel()
    }

    @Test
    fun `a row names the class its type implies`() = runTest(dispatcher) {
        val crypto = quoted("btc", "Bitcoin", 100_000, Price(50_000_000, Currency.EUR), InstrumentType.CRYPTO)
        val vm = viewModel(FakePortfolioRepository(listOf(usdStock, crypto)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val rows = vm.state.value.rows
        assertEquals(listOf(AssetClass.EQUITY, AssetClass.CRYPTO), rows.map { it.assetClass })
        assertEquals(listOf("Renta variable", "Cripto"), rows.map { it.assetClassText })
        job.cancel()
    }

    @Test
    fun `a row names the class the user chose over the one inferred`() = runTest(dispatcher) {
        val chosen = usdStock.copy(assetClass = AssetClass.MIXED)
        val vm = viewModel(FakePortfolioRepository(listOf(chosen)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.rows.single()
        assertEquals(AssetClass.MIXED, row.assetClass)
        assertEquals("Mixto", row.assetClassText)
        job.cancel()
    }

    @Test
    fun `a row names the class the provider category implies`() = runTest(dispatcher) {
        val categorized = fund.copy(fundamentals = InstrumentFundamentals(category = "RF Deuda Corporativa EUR"))
        val vm = viewModel(FakePortfolioRepository(listOf(categorized)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals("Renta fija", vm.state.value.rows.single().assetClassText)
        job.cancel()
    }

    @Test
    fun `an unclassified row shows no class at all`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository(listOf(fund, manual)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val rows = vm.state.value.rows
        assertEquals(listOf(null, null), rows.map { it.assetClass })
        assertEquals(listOf(null, null), rows.map { it.assetClassText })
        job.cancel()
    }

    @Test
    fun `the total sums priced holdings in euros and the count covers all of them`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository(listOf(fund, usdStock, unpriced, manual)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        // 1.234,57 + 1.840,00 + 2.500,00; the unpriced one counts for nothing.
        assertEquals(Money(557_457), state.total)
        assertEquals("4 productos", state.countLabel)
        assertEquals(listOf("fund", "stock", "new", "art"), state.rows.map { it.id })
        assertFalse(state.isEmpty)
        job.cancel()
    }

    @Test
    fun `one holding reads in the singular`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository(listOf(fund)))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals("1 producto", vm.state.value.countLabel)
        job.cancel()
    }

    @Test
    fun `rows follow the repository when a holding changes`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel(repository)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        repository.emit(listOf(fund, manual))
        advanceUntilIdle()

        assertEquals(2, vm.state.value.rows.size)
        job.cancel()
    }

    @Test
    fun `refreshing forces the price fetch and shows progress until it ends`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val gate = CompletableDeferred<Unit>()
        repository.refreshGate = gate
        val vm = viewModel(repository)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onRefresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.isRefreshing)
        assertEquals(listOf(true), repository.refreshCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.isRefreshing)
        assertNull(vm.state.value.refreshMessage)
        job.cancel()
    }

    @Test
    fun `a second refresh while one is running is ignored`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        val vm = viewModel(repository)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onRefresh()
        vm.onRefresh()
        advanceUntilIdle()

        assertEquals(listOf(true), repository.refreshCalls)
        job.cancel()
    }

    @Test
    fun `one failed price names the holding`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        repository.refreshResult = PriceRefreshResult(
            refreshed = 0,
            upToDate = 0,
            failures = listOf(PriceRefreshFailure("fund", "Fondo Global", "HTTP 500")),
        )
        val vm = viewModel(repository)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onRefresh()
        advanceUntilIdle()

        assertEquals("No se pudo actualizar el precio de Fondo Global", vm.state.value.refreshMessage)
        job.cancel()
    }

    @Test
    fun `several failed prices are counted`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund, usdStock, unpriced))
        repository.refreshResult = PriceRefreshResult(
            refreshed = 0,
            upToDate = 0,
            failures = listOf(
                PriceRefreshFailure("fund", "Fondo Global", "HTTP 500"),
                PriceRefreshFailure("stock", "Acme Corp", "timeout"),
                PriceRefreshFailure("new", "Fondo Nuevo", "no data"),
            ),
        )
        val vm = viewModel(repository)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onRefresh()
        advanceUntilIdle()

        assertEquals("No se pudo actualizar el precio de 3 productos", vm.state.value.refreshMessage)
        job.cancel()
    }

    @Test
    fun `a refresh that throws ends cleanly with a generic message`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        repository.refreshFailure = IllegalStateException("boom")
        val vm = viewModel(repository)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onRefresh()
        advanceUntilIdle()

        assertFalse(vm.state.value.isRefreshing)
        assertEquals("No se pudieron actualizar los precios", vm.state.value.refreshMessage)
        job.cancel()
    }

    @Test
    fun `dismissing the message clears it and a new refresh starts without it`() = runTest(dispatcher) {
        val repository = FakePortfolioRepository(listOf(fund))
        repository.refreshResult = PriceRefreshResult(
            refreshed = 0,
            upToDate = 0,
            failures = listOf(PriceRefreshFailure("fund", "Fondo Global", "HTTP 500")),
        )
        val vm = viewModel(repository)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onRefresh()
        advanceUntilIdle()

        vm.onMessageDismiss()
        advanceUntilIdle()
        assertNull(vm.state.value.refreshMessage)

        val gate = CompletableDeferred<Unit>()
        repository.refreshGate = gate
        vm.onRefresh()
        advanceUntilIdle()
        assertNull(vm.state.value.refreshMessage)
        assertTrue(vm.state.value.isRefreshing)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("No se pudo actualizar el precio de Fondo Global", vm.state.value.refreshMessage)
        job.cancel()
    }
}
