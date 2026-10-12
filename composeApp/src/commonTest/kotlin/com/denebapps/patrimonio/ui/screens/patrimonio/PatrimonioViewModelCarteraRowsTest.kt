package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.calc.AccountAssignment
import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAccountTypeRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeLiabilityRepository
import com.denebapps.patrimonio.testing.FakeNetWorthRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import com.denebapps.patrimonio.testing.manualTestHolding
import com.denebapps.patrimonio.testing.quotedTestHolding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PatrimonioViewModelCarteraRowsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val account =
        Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(500_000), Currency.EUR))

    // "inGroup" is in the group Casa, which the goal Entrada follows; "inGroupOnly" is in Ahorro, which no goal follows.
    private val groups = listOf(
        AccountGroup.allAccounts(),
        AccountGroup("g1", "Casa", true, 1, memberAssetIds = setOf("a1"), memberHoldingIds = setOf("inGroup")),
        AccountGroup("g2", "Ahorro", true, 2, memberAssetIds = emptySet(), memberHoldingIds = setOf("inGroupOnly")),
    )
    private val goal = SavingsGoal(
        id = "goal-1",
        name = "Entrada",
        target = CurrencyAmount(Money(100_000), Currency.EUR),
        targetDate = null,
        linkedAssetIds = emptySet(),
        linkedGroupId = "g1",
        lifecycle = SavingsGoalLifecycle.OPEN,
        progress = Money.ZERO,
    )

    private fun holdings() = FakePortfolioRepository(
        listOf(
            manualTestHolding("free", 10_000),
            manualTestHolding("inGroup", 20_000),
            manualTestHolding("inGroupOnly", 30_000),
        ),
    )

    private fun viewModel(
        portfolio: FakePortfolioRepository = holdings(),
        accountGroups: List<AccountGroup> = groups,
        goals: List<SavingsGoal> = listOf(goal),
    ) = PatrimonioViewModel(
        assetRepository = FakeAssetRepository(listOf(account)),
        liabilityRepository = FakeLiabilityRepository(),
        netWorthRepository = FakeNetWorthRepository(),
        accountGroupRepository = FakeAccountGroupRepository(accountGroups),
        fxRepository = FakeFxRepository(FxRates(mapOf(Currency.USD to 500_000L))),
        savingsGoalRepository = FakeSavingsGoalRepository(goals),
        accountTypeRepository = FakeAccountTypeRepository(),
        portfolioRepository = portfolio,
        clock = fixedClock("2026-05-21T12:00:00Z"),
        zoneProvider = { TimeZone.UTC },
        monthFlow = flowOf(YearMonth(2026, 5)),
    )

    private fun TestScope.open(vm: PatrimonioViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
    }

    @Test
    fun `the Cartera card lists one row per product with its EUR value`() = runTest(dispatcher) {
        val vm = viewModel(
            FakePortfolioRepository(
                listOf(
                    manualTestHolding("eur", 123_456),
                    // 200.00 EUR at 0.5.
                    manualTestHolding("usd", 40_000, Currency.USD),
                ),
            ),
        )
        open(vm)

        val portfolio = assertNotNull(vm.state.value.portfolio)
        assertEquals(listOf("eur", "usd"), portfolio.items.map { it.id })
        assertEquals(listOf("Holding eur", "Holding usd"), portfolio.items.map { it.name })
        assertEquals(listOf(Money(123_456), Money(20_000)), portfolio.items.map { it.valueEur })
        assertEquals(Money(143_456), portfolio.total)
        assertEquals(2, portfolio.holdingCount)
        assertFalse(portfolio.filtered)
    }

    @Test
    fun `a product with no price has no value and a closed position is worth zero and goes last`() =
        runTest(dispatcher) {
            val vm = viewModel(
                FakePortfolioRepository(
                    listOf(
                        quotedTestHolding("closed", units = 0, price = 10),
                        quotedTestHolding("unpriced", units = 10, price = null),
                        manualTestHolding("open", 5_000),
                    ),
                ),
            )
            open(vm)

            val items = assertNotNull(vm.state.value.portfolio).items
            assertEquals(listOf("unpriced", "open", "closed"), items.map { it.id })
            assertNull(items[0].valueEur)
            assertEquals(Money(5_000), items[1].valueEur)
            assertEquals(Money.ZERO, items[2].valueEur)
            assertEquals(listOf(false, false, true), items.map { it.closed })
            assertEquals("Cerrada", items[2].subtitle)
            assertEquals("Fondo", items[0].subtitle)
        }

    @Test
    fun `rows carry the group and goal icons from the holding usage`() = runTest(dispatcher) {
        val vm = viewModel()
        open(vm)

        val items = assertNotNull(vm.state.value.portfolio).items.associateBy { it.id }
        assertEquals(false, items.getValue("free").inGroup)
        assertEquals(false, items.getValue("free").inGoal)
        // In a group a goal follows: both icons, as the usage says.
        assertEquals(true, items.getValue("inGroup").inGroup)
        assertEquals(true, items.getValue("inGroup").inGoal)
        assertEquals(true, items.getValue("inGroupOnly").inGroup)
        assertEquals(false, items.getValue("inGroupOnly").inGoal)
    }

    @Test
    fun `a group filter keeps the products in groups and totals only those`() = runTest(dispatcher) {
        val vm = viewModel()
        open(vm)

        vm.onAssignmentFilterChange(AccountAssignment.IN_GROUPS)
        advanceUntilIdle()

        val portfolio = assertNotNull(vm.state.value.portfolio)
        assertEquals(listOf("inGroup", "inGroupOnly"), portfolio.items.map { it.id })
        assertEquals(Money(50_000), portfolio.total)
        assertEquals(2, portfolio.holdingCount)
        assertEquals(3, portfolio.totalCount)
        assertTrue(portfolio.filtered)
        // The header totals stay global.
        assertEquals(Money(560_000), vm.state.value.totalAssets)
    }

    @Test
    fun `the unassigned filter keeps the products in no group`() = runTest(dispatcher) {
        val vm = viewModel()
        open(vm)

        vm.onAssignmentFilterChange(AccountAssignment.UNASSIGNED)
        advanceUntilIdle()

        assertEquals(listOf("free"), assertNotNull(vm.state.value.portfolio).items.map { it.id })
    }

    @Test
    fun `the goal filter keeps the products in a group a goal follows`() = runTest(dispatcher) {
        val vm = viewModel()
        open(vm)

        vm.onAssignmentFilterChange(AccountAssignment.IN_GOALS)
        advanceUntilIdle()

        assertEquals(listOf("inGroup"), assertNotNull(vm.state.value.portfolio).items.map { it.id })
    }

    @Test
    fun `the filter summary counts the visible products with the accounts`() = runTest(dispatcher) {
        val vm = viewModel()
        open(vm)

        vm.onAssignmentFilterChange(AccountAssignment.IN_GROUPS)
        advanceUntilIdle()

        // The account is in Casa: 5,000.00 + the two products in groups.
        assertEquals(FilterSummaryUi(Money(550_000), count = 1, productCount = 2), vm.state.value.filterSummary)
    }

    @Test
    fun `the Cartera card disappears when no product matches the filter`() = runTest(dispatcher) {
        val vm = viewModel(accountGroups = listOf(AccountGroup.allAccounts()), goals = emptyList())
        open(vm)

        vm.onAssignmentFilterChange(AccountAssignment.IN_GROUPS)
        advanceUntilIdle()

        assertNull(vm.state.value.portfolio)
        assertEquals(FilterSummaryUi(Money.ZERO, count = 0, productCount = 0), vm.state.value.filterSummary)
    }

    @Test
    fun `a type chip hides the Cartera card because products have no account type`() = runTest(dispatcher) {
        val vm = viewModel()
        open(vm)

        vm.onTypeFilterToggle(Asset.AssetGroup.BANK)
        advanceUntilIdle()

        assertNull(vm.state.value.portfolio)
        assertEquals(0, vm.state.value.filterSummary?.productCount)

        vm.onClearFilters()
        advanceUntilIdle()
        assertEquals(3, assertNotNull(vm.state.value.portfolio).items.size)
    }

    @Test
    fun `an unfiltered state is not filtered even when the filter would match everything`() = runTest(dispatcher) {
        val vm = viewModel(accountGroups = listOf(AccountGroup.allAccounts()), goals = emptyList())
        open(vm)

        vm.onAssignmentFilterChange(AccountAssignment.UNASSIGNED)
        advanceUntilIdle()

        val portfolio = assertNotNull(vm.state.value.portfolio)
        assertEquals(3, portfolio.items.size)
        assertFalse(portfolio.filtered)
    }

    @Test
    fun `the product count label says how many of how many are shown`() {
        assertEquals("1 producto", productCountLabel(shown = 1, total = 1))
        assertEquals("5 productos", productCountLabel(shown = 5, total = 5))
        assertEquals("2 de 5 productos", productCountLabel(shown = 2, total = 5))
        assertEquals("1 de 3 productos", productCountLabel(shown = 1, total = 3))
    }

    @Test
    fun `the filter summary line names accounts and products`() {
        assertEquals("2 cuentas", filterSummaryCounts(FilterSummaryUi(Money.ZERO, count = 2)))
        assertEquals("1 cuenta", filterSummaryCounts(FilterSummaryUi(Money.ZERO, count = 1)))
        assertEquals("0 cuentas", filterSummaryCounts(FilterSummaryUi(Money.ZERO, count = 0)))
        assertEquals("1 cuenta · 3 productos", filterSummaryCounts(FilterSummaryUi(Money.ZERO, 1, productCount = 3)))
        assertEquals("3 productos", filterSummaryCounts(FilterSummaryUi(Money.ZERO, 0, productCount = 3)))
        assertEquals("1 producto", filterSummaryCounts(FilterSummaryUi(Money.ZERO, 0, productCount = 1)))
    }

    private fun <T : Any> assertNotNull(value: T?): T = kotlin.test.assertNotNull(value)
}
