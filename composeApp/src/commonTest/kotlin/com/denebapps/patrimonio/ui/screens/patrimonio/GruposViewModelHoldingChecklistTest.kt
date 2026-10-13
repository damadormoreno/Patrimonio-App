package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAccountTypeRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import com.denebapps.patrimonio.testing.manualTestHolding
import com.denebapps.patrimonio.testing.quotedTestHolding
import com.denebapps.patrimonio.ui.components.PORTFOLIO_LOOK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GruposViewModelHoldingChecklistTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val account =
        Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(10_000), Currency.EUR))

    private fun group(id: String, name: String, vararg holdingIds: String) = AccountGroup(
        id = id,
        name = name,
        showBalance = true,
        sortOrder = 1,
        memberAssetIds = setOf("a1"),
        memberHoldingIds = holdingIds.toSet(),
    )

    private fun goalOn(groupId: String, name: String) = SavingsGoal(
        id = "goal-$groupId",
        name = name,
        target = CurrencyAmount(Money(100_000), Currency.EUR),
        targetDate = null,
        linkedAssetIds = emptySet(),
        linkedGroupIds = setOf(groupId),
        lifecycle = SavingsGoalLifecycle.OPEN,
        progress = Money.ZERO,
    )

    private fun viewModel(
        holdings: FakePortfolioRepository,
        groups: List<AccountGroup> = listOf(AccountGroup.allAccounts()),
        goals: List<SavingsGoal> = emptyList(),
        portfolios: List<Portfolio> = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0)),
        editingGroupId: String? = null,
    ) = GruposViewModel(
        assetRepository = FakeAssetRepository(listOf(account)),
        accountGroupRepository = FakeAccountGroupRepository(groups),
        savingsGoalRepository = FakeSavingsGoalRepository(goals),
        portfolioRepository = holdings,
        portfolios = FakePortfolioCollectionRepository(portfolios, holdings),
        fxRepository = FakeFxRepository(FxRates(mapOf(Currency.USD to 500_000L))),
        accountTypeRepository = FakeAccountTypeRepository(),
        editingGroupId = editingGroupId,
        idProvider = { "g-new" },
    )

    @Test
    fun `the checklist lists the products with their EUR value and no portfolio name when there is one portfolio`() =
        runTest(dispatcher) {
            val vm = viewModel(
                FakePortfolioRepository(
                    listOf(
                        manualTestHolding("fund", 123_456),
                        // 200.00 EUR at 0.5.
                        manualTestHolding("usd", 40_000, Currency.USD),
                    ),
                ),
            )
            val job = launch { vm.state.collect {} }
            advanceUntilIdle()

            val rows = vm.state.value.holdingChecklist
            assertEquals(listOf("fund", "usd"), rows.map { it.id })
            assertEquals(listOf("Holding fund", "Holding usd"), rows.map { it.name })
            assertEquals(listOf(Money(123_456), Money(20_000)), rows.map { it.valueEur })
            assertEquals(listOf(null, null), rows.map { it.portfolioName })
            assertEquals(listOf("1.234,56 €", "200,00 €"), rows.map { it.subtitle })
            job.cancel()
        }

    @Test
    fun `with several portfolios each row names its portfolio`() = runTest(dispatcher) {
        val moved = manualTestHolding("other", 5_000).copy(portfolioId = "p2")
        val vm = viewModel(
            FakePortfolioRepository(listOf(manualTestHolding("fund", 10_000), moved)),
            portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("p2", "Hijos", 1)),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val rows = vm.state.value.holdingChecklist
        assertEquals(listOf("Mi cartera", "Hijos"), rows.map { it.portfolioName })
        assertEquals(listOf("Mi cartera · 100,00 €", "Hijos · 50,00 €"), rows.map { it.subtitle })
        job.cancel()
    }

    @Test
    fun `a product with no price reads sin precio and has no value`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository(listOf(quotedTestHolding("fund", units = 10, price = null))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.holdingChecklist.single()
        assertEquals(null, row.valueEur)
        assertEquals("sin precio", row.subtitle)
        job.cancel()
    }

    @Test
    fun `closed positions go after the open ones`() = runTest(dispatcher) {
        val vm = viewModel(
            FakePortfolioRepository(
                listOf(
                    quotedTestHolding("closed", units = 0, price = 10),
                    manualTestHolding("open", 5_000),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("open", "closed"), vm.state.value.holdingChecklist.map { it.id })
        job.cancel()
    }

    @Test
    fun `there is no checklist when the portfolio has no products`() = runTest(dispatcher) {
        val vm = viewModel(FakePortfolioRepository())
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.holdingChecklist)
        job.cancel()
    }

    @Test
    fun `a row is selected after toggling and its tags name the other groups and the goals that follow them`() =
        runTest(dispatcher) {
            val vm = viewModel(
                FakePortfolioRepository(listOf(manualTestHolding("fund", 10_000), manualTestHolding("free", 1_000))),
                groups = listOf(AccountGroup.allAccounts(), group("g1", "Casa", "fund")),
                goals = listOf(goalOn("g1", "Entrada")),
            )
            val job = launch { vm.state.collect {} }
            advanceUntilIdle()

            val tagged = vm.state.value.holdingChecklist.first { it.id == "fund" }
            assertEquals(listOf("Casa"), tagged.groupNames)
            assertEquals(listOf("Entrada"), tagged.goalNames)
            val free = vm.state.value.holdingChecklist.first { it.id == "free" }
            assertEquals(emptyList(), free.groupNames)
            assertEquals(emptyList(), free.goalNames)
            assertEquals(listOf(false, false), vm.state.value.holdingChecklist.map { it.selected })

            vm.onHoldingToggle("free")
            advanceUntilIdle()
            assertEquals(listOf(false, true), vm.state.value.holdingChecklist.map { it.selected })
            job.cancel()
        }

    @Test
    fun `editing a group does not tag its own products with itself`() = runTest(dispatcher) {
        val vm = viewModel(
            FakePortfolioRepository(listOf(manualTestHolding("fund", 10_000))),
            groups = listOf(AccountGroup.allAccounts(), group("g1", "Casa", "fund")),
            goals = listOf(goalOn("g1", "Entrada")),
            editingGroupId = "g1",
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.holdingChecklist.single()
        assertTrue(row.selected)
        assertEquals(emptyList(), row.groupNames)
        assertEquals(emptyList(), row.goalNames)
        job.cancel()
    }

    @Test
    fun `a group lists its accounts and then its products as portfolio members`() = runTest(dispatcher) {
        val vm = viewModel(
            FakePortfolioRepository(
                listOf(
                    manualTestHolding("fund", 10_000),
                    quotedTestHolding("unpriced", units = 10, price = null),
                    manualTestHolding("outside", 99_999),
                ),
            ),
            groups = listOf(AccountGroup.allAccounts(), group("g1", "Casa", "fund", "unpriced")),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val members = vm.state.value.groups.single { it.id == "g1" }.members
        assertEquals(listOf("a1", "fund", "unpriced"), members.map { it.id })
        assertEquals(listOf(false, true, true), members.map { it.isHolding })
        val fund = members[1]
        assertEquals(Money(10_000), fund.amountEur)
        assertEquals(PORTFOLIO_LOOK, fund.type)
        assertTrue(fund.priced)
        val unpriced = members[2]
        assertEquals(Money.ZERO, unpriced.amountEur)
        assertEquals(false, unpriced.priced)
        job.cancel()
    }

    @Test
    fun `the builtin group still lists accounts only`() = runTest(dispatcher) {
        val vm = viewModel(
            FakePortfolioRepository(listOf(manualTestHolding("fund", 10_000))),
            groups = listOf(AccountGroup.allAccounts(), group("g1", "Casa", "fund")),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("a1"), vm.state.value.groups.single { it.builtin }.members.map { it.id })
        job.cancel()
    }

    @Test
    fun `a group of mixed accounts and products saves both kinds`() = runTest(dispatcher) {
        val groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts()))
        val vm = GruposViewModel(
            assetRepository = FakeAssetRepository(listOf(account)),
            accountGroupRepository = groups,
            savingsGoalRepository = FakeSavingsGoalRepository(),
            portfolioRepository = FakePortfolioRepository(listOf(manualTestHolding("fund", 5_000))),
            portfolios = FakePortfolioCollectionRepository(),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            accountTypeRepository = FakeAccountTypeRepository(),
            idProvider = { "g-new" },
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onTitleChange("Casa")
        vm.onAssetToggle("a1")
        vm.onHoldingToggle("fund")
        advanceUntilIdle()
        vm.onSaveNewGroup()
        advanceUntilIdle()

        val saved = groups.current.single { it.id == "g-new" }
        assertEquals(setOf("a1"), saved.memberAssetIds)
        assertEquals(setOf("fund"), saved.memberHoldingIds)
        job.cancel()
    }

    @Test
    fun `the selection label counts accounts and products`() {
        assertEquals("0 cuentas seleccionadas", selectionLabel(accounts = 0, products = 0))
        assertEquals("1 cuenta seleccionada", selectionLabel(accounts = 1, products = 0))
        assertEquals("3 cuentas seleccionadas", selectionLabel(accounts = 3, products = 0))
        assertEquals("1 producto seleccionado", selectionLabel(accounts = 0, products = 1))
        assertEquals("2 productos seleccionados", selectionLabel(accounts = 0, products = 2))
        assertEquals("1 cuenta y 1 producto seleccionados", selectionLabel(accounts = 1, products = 1))
        assertEquals("2 cuentas y 3 productos seleccionados", selectionLabel(accounts = 2, products = 3))
    }
}
