package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.FakeQuoteSource
import com.denebapps.patrimonio.testing.FxSyncCall
import com.denebapps.patrimonio.testing.settle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The "Una compra" way of adding a product: it is created with a purchase instead of with the units it has now. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddHoldingPurchaseTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = fixedClock("2026-10-11T10:00:00Z")
    private val today = LocalDate(2026, 10, 11)
    private val quoteDate = LocalDate(2026, 10, 10)

    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000FUND")
    private val fund =
        InstrumentCandidate(fundRef, "Fondo Global", "IE00B4L5Y983", null, InstrumentType.FUND, null, null)
    private val usdRef = InstrumentRef(QuoteProvider.YAHOO, "ACME")
    private val usdStock = InstrumentCandidate(usdRef, "Acme Corp", null, "ACME", InstrumentType.STOCK, null, null)

    private val source = FakeQuoteSource().apply {
        searchResults = listOf(fund, usdStock)
        quotes[fundRef] = Quote(fundRef, Price(245_120_000, Currency.EUR), quoteDate)
        quotes[usdRef] = Quote(usdRef, Price(200_000_000, Currency.USD), quoteDate)
    }
    private val holdings = FakePortfolioRepository()
    private val movements = FakeMovementRepository(holdings)
    private val fxHistory = FakeFxHistoryRepository(
        FxHistory(mapOf(Currency.USD to listOf(DatedFxRate(LocalDate(2026, 10, 9), 920_000)))),
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AddHoldingViewModel(
        quoteSource = source,
        portfolioRepository = holdings,
        portfolios = FakePortfolioCollectionRepository(),
        preferences = FakePreferencesRepository(),
        movementRepository = movements,
        fxHistoryRepository = fxHistory,
        clock = clock,
        zoneProvider = { TimeZone.UTC },
        idProvider = { "new-id" },
    )

    private fun TestScope.pick(vm: AddHoldingViewModel, ref: InstrumentRef) {
        vm.onQueryChange("global")
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        runCurrent()
        vm.onPick(ref)
        settle()
    }

    private fun TestScope.collectBack(vm: AddHoldingViewModel): MutableList<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch { vm.navigateBack.collect { events += it } }
        return events
    }

    @Test
    fun `the entry starts as what is held now and can be switched to a purchase`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, fundRef)

        assertEquals(EntryKind.CURRENT, vm.state.value.entryKind)
        assertEquals(today, vm.state.value.purchase.date)

        vm.onEntryKindChange(EntryKind.PURCHASE)

        assertEquals(EntryKind.PURCHASE, vm.state.value.entryKind)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `a purchase in euros shows the implied price and is saved as a holding with its first buy`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val back = collectBack(vm)
            pick(vm, fundRef)
            vm.onEntryKindChange(EntryKind.PURCHASE)

            vm.onPurchaseDateChange(LocalDate(2026, 5, 3))
            vm.onPurchaseUnitsChange("10")
            vm.onPurchaseAmountChange("647,61")
            vm.onPurchaseFeeChange("2")

            assertEquals("≈ 64,761 €/part.", vm.state.value.purchase.impliedPriceText)
            assertFalse(vm.state.value.purchase.showEur)
            assertTrue(vm.state.value.canSave)
            vm.onSave()
            settle()

            val holding = holdings.current.single()
            assertEquals("new-id", holding.id)
            assertEquals(UnitsBalance(10 * UNITS_SCALE), (holding.valuation as HoldingValuation.Quoted).units)
            val buy = movements.current.single()
            assertEquals(MovementType.BUY, buy.type)
            assertEquals(LocalDate(2026, 5, 3), buy.date)
            assertEquals(Units(10 * UNITS_SCALE), buy.units)
            assertEquals(CurrencyAmount(Money(64_761), Currency.EUR), buy.amount)
            assertEquals(Money(200), buy.fee)
            assertEquals(1, back.size)
        }

    @Test
    fun `the purchase needs its units and amount before it can be saved`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, fundRef)
        vm.onEntryKindChange(EntryKind.PURCHASE)

        vm.onPurchaseUnitsChange("10")
        assertFalse(vm.state.value.canSave)
        vm.onPurchaseAmountChange("100")
        assertTrue(vm.state.value.canSave)
        vm.onPurchaseUnitsChange("")
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `what is held now is unaffected by the purchase fields and saves without movements`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, fundRef)
        vm.onPurchaseUnitsChange("5")

        vm.onUnitsChange("10,5")
        vm.onSave()
        settle()

        assertEquals(1, holdings.current.size)
        assertTrue(movements.current.isEmpty())
    }

    @Test
    fun `a purchase in dollars prefills the euro equivalent from the rate of the day and syncs the rates`() =
        runTest(dispatcher) {
            val vm = viewModel()
            pick(vm, usdRef)
            vm.onEntryKindChange(EntryKind.PURCHASE)
            settle()

            assertTrue(vm.state.value.purchase.showEur)
            assertTrue(FxSyncCall(setOf(Currency.USD), today, false) in fxHistory.syncCalls)
            vm.onPurchaseUnitsChange("2")
            vm.onPurchaseAmountChange("100")
            assertEquals("92,00", vm.state.value.purchase.eurText)
            assertTrue(vm.state.value.canSave)

            vm.onPurchaseEurChange("95")
            vm.onSave()
            settle()

            assertEquals(Money(9_500), movements.current.single().amountEur)
        }

    @Test
    fun `a purchase in dollars without a rate needs the euro equivalent typed`() = runTest(dispatcher) {
        fxHistory.set(FxHistory(emptyMap()))
        val vm = viewModel()
        pick(vm, usdRef)
        vm.onEntryKindChange(EntryKind.PURCHASE)
        vm.onPurchaseUnitsChange("2")
        vm.onPurchaseAmountChange("100")

        assertFalse(vm.state.value.canSave)
        assertNotNull(vm.state.value.purchase.eurHint)
        vm.onPurchaseEurChange("90")
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `a purchase date picks the rate of that day`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, usdRef)
        vm.onEntryKindChange(EntryKind.PURCHASE)
        vm.onPurchaseUnitsChange("2")
        vm.onPurchaseAmountChange("100")

        vm.onPurchaseDateChange(LocalDate(2020, 1, 1))
        settle()

        assertTrue(fxHistory.syncCalls.any { it.from == LocalDate(2020, 1, 1) })
        assertFalse(vm.state.value.canSave) // no rate within a week of that day
    }

    @Test
    fun `going back to the search forgets the purchase`() = runTest(dispatcher) {
        val vm = viewModel()
        pick(vm, fundRef)
        vm.onEntryKindChange(EntryKind.PURCHASE)
        vm.onPurchaseUnitsChange("2")

        vm.onBackToSearch()

        assertEquals(EntryKind.CURRENT, vm.state.value.entryKind)
        assertEquals("", vm.state.value.purchase.unitsText)
    }
}
