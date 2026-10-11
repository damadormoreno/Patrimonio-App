package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** A position that sold everything stays in Cartera as "Cerrada", worth nothing and after the open ones. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarteraClosedRowsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun fund(id: String, name: String, units: Long, currency: Currency = Currency.EUR) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = name,
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = currency,
            units = UnitsBalance(units * UNITS_SCALE),
            lastQuote = LastQuote(Price(10_000_000, currency), LocalDate(2026, 10, 10), 1L),
        ),
    )

    private fun TestScope.rows(vararg holdings: PortfolioHolding): CarteraUiState {
        val repository = FakePortfolioRepository(holdings.toList())
        val vm = CarteraViewModel(
            portfolioRepository = repository,
            portfolios = FakePortfolioCollectionRepository(holdings = repository),
            preferences = FakePreferencesRepository(),
            fxRepository = FakeFxRepository(FxRates(mapOf(Currency.USD to 920_000))),
            zoneProvider = { TimeZone.UTC },
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm.state.value.also { job.cancel() }
    }

    @Test
    fun `a closed position reads Cerrada with a zero value`() = runTest(dispatcher) {
        val state = rows(fund("a", "Fondo A", units = 0))

        val row = state.rows.single()
        assertTrue(row.isClosed)
        assertEquals("Cerrada", row.detail)
        assertEquals("0,00 €", row.valueText)
        assertNull(row.valueEurText)
        assertEquals(Money.ZERO, state.total)
    }

    @Test
    fun `a closed position in another currency is still worth zero euros`() = runTest(dispatcher) {
        val row = rows(fund("a", "Fondo A", units = 0, currency = Currency.USD)).rows.single()

        assertEquals("0,00 €", row.valueText)
        assertNull(row.valueEurText)
    }

    @Test
    fun `closed positions go after the open ones`() = runTest(dispatcher) {
        val state = rows(
            fund("a", "Aaa cerrada", units = 0),
            fund("b", "Bbb", units = 2),
            fund("c", "Ccc cerrada", units = 0),
            fund("d", "Ddd", units = 3),
        )

        assertEquals(listOf("b", "d", "a", "c"), state.rows.map { it.id })
        assertEquals(listOf(false, false, true, true), state.rows.map { it.isClosed })
        assertFalse(state.rows.first().isClosed)
        assertEquals("4 productos", state.countLabel)
    }
}
