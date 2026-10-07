package com.denebapps.patrimonio.ui.screens.savings

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SavingsGoalsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun asset(id: String, currency: Currency = Currency.EUR, minor: Long = 100_00) = Asset(
        id = id,
        group = Asset.AssetGroup.BANK,
        name = "Asset $id",
        subtitle = null,
        amount = CurrencyAmount(Money(minor), currency),
    )

    private fun goal(
        id: String,
        targetMinor: Long,
        progressMinor: Long,
        currency: Currency = Currency.EUR,
        linkedAssetId: String? = null,
        linkedGroupId: String? = null,
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = id,
        name = "Goal $id",
        target = CurrencyAmount(Money(targetMinor), currency),
        targetDate = null,
        linkedAssetId = linkedAssetId,
        lifecycle = lifecycle,
        progress = Money(progressMinor),
        linkedGroupId = linkedGroupId,
    )

    private fun group(id: String, vararg memberIds: String) = AccountGroup(
        id = id,
        name = "Group $id",
        showBalance = true,
        sortOrder = 0,
        memberAssetIds = memberIds.toSet(),
    )

    private fun viewModel(
        goals: FakeSavingsGoalRepository = FakeSavingsGoalRepository(),
        assets: FakeAssetRepository = FakeAssetRepository(),
        groups: FakeAccountGroupRepository = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts())),
        fx: FakeFxRepository = FakeFxRepository(),
        initialGoalId: String? = null,
        initialWithdraw: Boolean = false,
    ) = SavingsGoalsViewModel(
        savingsGoalRepository = goals,
        assetRepository = assets,
        accountGroupRepository = groups,
        fxRepository = fx,
        initialGoalId = initialGoalId,
        initialWithdraw = initialWithdraw,
    )

    @Test
    fun `list shows name target and progress percentage`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(listOf(goal(id = "goal-1", targetMinor = 200_00, progressMinor = 50_00))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.goals.single()
        assertEquals("Goal goal-1", row.name)
        assertEquals(CurrencyAmount(Money(200_00), Currency.EUR), row.target)
        assertEquals(Money(50_00), row.progress)
        assertEquals(25, row.progressPct)
        job.cancel()
    }

    @Test
    fun `progress beyond target shows meta alcanzada while the goal stays open and listed`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 130_00)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.goals.single()
        assertTrue(row.targetReached)
        assertEquals(130, row.progressPct)
        assertFalse(row.closed)
        job.cancel()
    }

    @Test
    fun `create pre-filters linkable assets to the entered currency`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(asset("eur-1", Currency.EUR), asset("usd-1", Currency.USD)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("eur-1"), vm.state.value.linkableAssets.map { it.id })

        vm.onNewGoalCurrencyChange(Currency.USD)
        advanceUntilIdle()
        assertEquals(listOf("usd-1"), vm.state.value.linkableAssets.map { it.id })
        job.cancel()
    }

    @Test
    fun `switching currency clears a previously selected link`() = runTest(dispatcher) {
        val vm = viewModel(assets = FakeAssetRepository(listOf(asset("eur-1", Currency.EUR))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalLinkChange("eur-1")
        advanceUntilIdle()
        assertEquals("eur-1", vm.state.value.newGoalLinkedAssetId)

        vm.onNewGoalCurrencyChange(Currency.USD)
        advanceUntilIdle()
        assertNull(vm.state.value.newGoalLinkedAssetId)
        job.cancel()
    }

    @Test
    fun `saving a valid unlinked goal creates it and emits navigateBack`() = runTest(dispatcher) {
        val goals = FakeSavingsGoalRepository()
        val vm = viewModel(goals = goals)
        val job = launch { vm.state.collect {} }
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        advanceUntilIdle()

        vm.onNewGoalNameChange("Fondo emergencia")
        vm.onNewGoalTargetChange("500,00")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSaveNewGoal)
        vm.onSaveNewGoal()
        advanceUntilIdle()

        var latest: List<SavingsGoal> = emptyList()
        val collectJob = launch { goals.observeAll().collect { latest = it } }
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals(1, latest.size)
        assertEquals("Fondo emergencia", latest.single().name)
        assertEquals(1, events)
        job.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `allocating calls the repository atomic command and updates progress live`() = runTest(dispatcher) {
        val goals = FakeSavingsGoalRepository(listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 20_00)))
        val vm = viewModel(goals = goals, initialGoalId = "goal-1")
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onAllocateAmountChange("30,00")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSubmitAllocate)
        vm.onSubmitAllocate()
        advanceUntilIdle()

        assertEquals(Money(50_00), vm.state.value.selectedGoal?.progress)
        assertNull(vm.state.value.errorMessage)
        job.cancel()
    }

    @Test
    fun `over-withdrawal is rejected atomically and surfaces an error without changing progress`() = runTest(
        dispatcher,
    ) {
        val goals = FakeSavingsGoalRepository(listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 20_00)))
        val vm = viewModel(goals = goals, initialGoalId = "goal-1", initialWithdraw = true)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onAllocateAmountChange("50,00")
        advanceUntilIdle()
        vm.onSubmitAllocate()
        advanceUntilIdle()

        assertEquals(Money(20_00), vm.state.value.selectedGoal?.progress)
        assertNotNull(vm.state.value.errorMessage)
        job.cancel()
    }

    @Test
    fun `cancelling a funded goal appends the exact release delta and closes it`() = runTest(dispatcher) {
        // savings-goals-core: "Cancel funded goal" appends exactly -P atomically, so the ledger-derived
        // progress becomes ZERO after cancel (confirmed by SavingsGoalRepositoryTest's
        // `cancel funded appends exact release...` — `assertEquals(Money.ZERO, ...progress)`). "Preserved
        // progress" in the UI spec applies to explicit `close()` (no event appended), not `cancel()`.
        val goals = FakeSavingsGoalRepository(listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 40_00)))
        val vm = viewModel(goals = goals, initialGoalId = "goal-1")
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onCancelGoal()
        advanceUntilIdle()

        val cancelled = vm.state.value.selectedGoal
        assertNotNull(cancelled)
        assertTrue(cancelled.closed)
        assertEquals(Money.ZERO, cancelled.progress)
        job.cancel()
    }

    @Test
    fun `deleting a goal in any lifecycle removes it and navigates back`() = runTest(dispatcher) {
        val goals = FakeSavingsGoalRepository(
            listOf(
                goal(id = "goal-1", targetMinor = 100_00, progressMinor = 40_00),
                goal(
                    id = "goal-2",
                    targetMinor = 100_00,
                    progressMinor = 0,
                    lifecycle = SavingsGoalLifecycle.CANCELLED,
                ),
            ),
        )
        val vm = viewModel(goals = goals, initialGoalId = "goal-2")
        val job = launch { vm.state.collect {} }
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        advanceUntilIdle()

        vm.onDeleteGoal()
        advanceUntilIdle()

        assertEquals(listOf("goal-1"), vm.state.value.goals.map { it.id })
        assertNull(vm.state.value.selectedGoal)
        assertEquals(1, events)
        job.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `coverage renders as one shared warning, not one per linked goal`() = runTest(dispatcher) {
        val sharedAsset = asset("shared", Currency.EUR, minor = 100_00)
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(
                    goal(id = "goal-1", targetMinor = 100_00, progressMinor = 70_00, linkedAssetId = "shared"),
                    goal(id = "goal-2", targetMinor = 100_00, progressMinor = 50_00, linkedAssetId = "shared"),
                ),
            ),
            assets = FakeAssetRepository(listOf(sharedAsset)),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(PatrimonioCoverageUi.Warning, vm.state.value.coverageWarning)
        job.cancel()
    }

    @Test
    fun `coverage within balance shows no warning`() = runTest(dispatcher) {
        val sharedAsset = asset("shared", Currency.EUR, minor = 100_00)
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 40_00, linkedAssetId = "shared")),
            ),
            assets = FakeAssetRepository(listOf(sharedAsset)),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(PatrimonioCoverageUi.None, vm.state.value.coverageWarning)
        job.cancel()
    }

    @Test
    fun `create offers the persisted groups regardless of currency and never the builtin one`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(listOf(asset("eur-1"), asset("usd-1", Currency.USD))),
            groups = FakeAccountGroupRepository(
                listOf(AccountGroup.allAccounts(), group("g1", "eur-1", "usd-1")),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("g1"), vm.state.value.linkableGroups.map { it.id })
        assertEquals("Group g1", vm.state.value.linkableGroups.single().name)

        vm.onNewGoalCurrencyChange(Currency.GBP)
        advanceUntilIdle()
        assertEquals(listOf("g1"), vm.state.value.linkableGroups.map { it.id })
        job.cancel()
    }

    @Test
    fun `picking a group replaces the asset link and vice versa`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(listOf(asset("eur-1"))),
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "eur-1"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalLinkChange("eur-1")
        vm.onNewGoalGroupLinkChange("g1")
        advanceUntilIdle()
        assertNull(vm.state.value.newGoalLinkedAssetId)
        assertEquals("g1", vm.state.value.newGoalLinkedGroupId)

        vm.onNewGoalLinkChange("eur-1")
        advanceUntilIdle()
        assertEquals("eur-1", vm.state.value.newGoalLinkedAssetId)
        assertNull(vm.state.value.newGoalLinkedGroupId)

        vm.onNewGoalLinkChange(null)
        advanceUntilIdle()
        assertNull(vm.state.value.newGoalLinkedAssetId)
        assertNull(vm.state.value.newGoalLinkedGroupId)
        job.cancel()
    }

    @Test
    fun `switching currency keeps a group link because groups have no currency restriction`() = runTest(dispatcher) {
        val vm = viewModel(groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1"))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalGroupLinkChange("g1")
        vm.onNewGoalCurrencyChange(Currency.USD)
        advanceUntilIdle()

        assertEquals("g1", vm.state.value.newGoalLinkedGroupId)
        job.cancel()
    }

    @Test
    fun `saving a goal linked to a group persists the group link`() = runTest(dispatcher) {
        val goals = FakeSavingsGoalRepository(persistedGroupIds = setOf("g1"))
        val vm = viewModel(
            goals = goals,
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalNameChange("Colchón")
        vm.onNewGoalTargetChange("500,00")
        vm.onNewGoalGroupLinkChange("g1")
        advanceUntilIdle()
        vm.onSaveNewGoal()
        advanceUntilIdle()

        val saved = vm.state.value.goals.single()
        assertEquals("g1", saved.linkedGroupId)
        assertNull(saved.linkedAssetId)
        job.cancel()
    }

    @Test
    fun `group coverage shows one shared group warning when the reservations exceed the group balance`() = runTest(
        dispatcher,
    ) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(
                    goal(id = "goal-1", targetMinor = 100_00, progressMinor = 70_00, linkedGroupId = "g1"),
                    goal(id = "goal-2", targetMinor = 100_00, progressMinor = 50_00, linkedGroupId = "g1"),
                ),
            ),
            assets = FakeAssetRepository(listOf(asset("a1", minor = 60_00), asset("a2", minor = 40_00))),
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "a1", "a2"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            PatrimonioCoverageUi.GroupWarning(convertedAtCurrentRate = false),
            vm.state.value.groupCoverageWarning,
        )
        assertEquals(PatrimonioCoverageUi.None, vm.state.value.coverageWarning)
        job.cancel()
    }

    @Test
    fun `group coverage flags the conversion when members use other currencies`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 90_00, linkedGroupId = "g1")),
            ),
            assets = FakeAssetRepository(listOf(asset("a1", minor = 50_00), asset("a2", Currency.USD, minor = 50_00))),
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "a1", "a2"))),
            // 1 USD = 0.5 EUR, so the group balance is 50.00 + 25.00 EUR
            fx = FakeFxRepository(FxRates(mapOf(Currency.USD to 500_000L))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            PatrimonioCoverageUi.GroupWarning(convertedAtCurrentRate = true),
            vm.state.value.groupCoverageWarning,
        )
        job.cancel()
    }

    @Test
    fun `group coverage within the group balance shows no warning`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 40_00, linkedGroupId = "g1")),
            ),
            assets = FakeAssetRepository(listOf(asset("a1", minor = 100_00))),
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "a1"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(PatrimonioCoverageUi.None, vm.state.value.groupCoverageWarning)
        job.cancel()
    }
}
