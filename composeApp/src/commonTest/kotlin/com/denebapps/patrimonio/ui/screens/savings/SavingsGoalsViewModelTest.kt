package com.denebapps.patrimonio.ui.screens.savings

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
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
        linkedAssetIds: Set<String> = emptySet(),
        linkedGroupId: String? = null,
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = id,
        name = "Goal $id",
        target = CurrencyAmount(Money(targetMinor), currency),
        targetDate = null,
        linkedAssetIds = linkedAssetIds,
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
    fun `every asset is linkable whatever the goal currency`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(asset("eur-1", Currency.EUR), asset("usd-1", Currency.USD)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            listOf(
                LinkableAssetUi("eur-1", "Asset eur-1", Currency.EUR),
                LinkableAssetUi("usd-1", "Asset usd-1", Currency.USD),
            ),
            vm.state.value.linkableAssets,
        )
        job.cancel()
    }

    @Test
    fun `several accounts can be picked and switching currency keeps them`() = runTest(dispatcher) {
        val vm = viewModel(assets = FakeAssetRepository(listOf(asset("eur-1"), asset("usd-1", Currency.USD))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalAssetToggle("eur-1")
        vm.onNewGoalAssetToggle("usd-1")
        vm.onNewGoalCurrencyChange(Currency.USD)
        advanceUntilIdle()
        assertEquals(setOf("eur-1", "usd-1"), vm.state.value.newGoalLinkedAssetIds)

        vm.onNewGoalAssetToggle("eur-1")
        advanceUntilIdle()
        assertEquals(setOf("usd-1"), vm.state.value.newGoalLinkedAssetIds)
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
    fun `picking a group replaces the picked accounts and vice versa`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(listOf(asset("eur-1"), asset("eur-2"))),
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "eur-1"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalAssetToggle("eur-1")
        vm.onNewGoalAssetToggle("eur-2")
        vm.onNewGoalGroupLinkChange("g1")
        advanceUntilIdle()
        assertEquals(emptySet(), vm.state.value.newGoalLinkedAssetIds)
        assertEquals("g1", vm.state.value.newGoalLinkedGroupId)

        vm.onNewGoalAssetToggle("eur-1")
        advanceUntilIdle()
        assertEquals(setOf("eur-1"), vm.state.value.newGoalLinkedAssetIds)
        assertNull(vm.state.value.newGoalLinkedGroupId)

        vm.onNewGoalUnlink()
        advanceUntilIdle()
        assertEquals(emptySet(), vm.state.value.newGoalLinkedAssetIds)
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
        assertEquals(emptySet(), saved.linkedAssetIds)
        job.cancel()
    }

    @Test
    fun `editing a linked goal loads it and saves name target date and a new group link`() = runTest(dispatcher) {
        val goals = FakeSavingsGoalRepository(
            listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 0, linkedAssetIds = setOf("a1"))),
            knownAssetIds = setOf("a1"),
            persistedGroupIds = setOf("g1"),
        )
        val vm = viewModel(
            goals = goals,
            assets = FakeAssetRepository(listOf(asset("a1"))),
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "a1"))),
        )
        val job = launch { vm.state.collect {} }
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        advanceUntilIdle()

        vm.onStartEditing("goal-1")
        advanceUntilIdle()
        val loaded = vm.state.value
        assertEquals("Goal goal-1", loaded.newGoalName)
        assertEquals("100,00", loaded.newGoalTargetText)
        assertEquals(setOf("a1"), loaded.newGoalLinkedAssetIds)
        assertTrue(loaded.canSaveNewGoal)

        vm.onNewGoalNameChange("Viaje a Japón")
        vm.onNewGoalTargetChange("3000")
        vm.onNewGoalDateChange(LocalDate(2027, 6, 1))
        vm.onNewGoalGroupLinkChange("g1")
        // Starting the edit again (e.g. after rotation) must not reset what was typed.
        vm.onStartEditing("goal-1")
        advanceUntilIdle()
        vm.onSaveNewGoal()
        advanceUntilIdle()

        val saved = vm.state.value.goals.single()
        assertEquals("goal-1", saved.id)
        assertEquals("Viaje a Japón", saved.name)
        assertEquals(Money(3_000_00), saved.target.amount)
        assertEquals("g1", saved.linkedGroupId)
        assertEquals(emptySet(), saved.linkedAssetIds)
        assertEquals(
            listOf(SavingsGoalLinkEventKind.UNLINK, SavingsGoalLinkEventKind.LINK),
            goals.observeLinkHistory("goal-1").first().map { it.kind },
        )
        assertEquals(1, events)
        job.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `editing an unlinked goal can link it to several accounts of any currency`() = runTest(dispatcher) {
        val goals = FakeSavingsGoalRepository(
            listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 20_00)),
            knownAssetIds = setOf("a1", "a2"),
        )
        val vm = viewModel(
            goals = goals,
            assets = FakeAssetRepository(listOf(asset("a1", minor = 80_00), asset("a2", Currency.USD, minor = 40_00))),
            // 1 USD = 0.5 EUR
            fx = FakeFxRepository(FxRates(mapOf(Currency.USD to 500_000L))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onStartEditing("goal-1")
        advanceUntilIdle()
        vm.onNewGoalAssetToggle("a1")
        vm.onNewGoalAssetToggle("a2")
        vm.onSaveNewGoal()
        advanceUntilIdle()

        val saved = vm.state.value.goals.single()
        assertEquals(setOf("a1", "a2"), saved.linkedAssetIds)
        assertTrue(saved.tracksBalance)
        assertEquals(Money(100_00), saved.progress)
        assertEquals("«Asset a1» y «Asset a2»", saved.linkedTargetLabel)
        assertEquals(
            listOf(SavingsGoalLinkEventKind.LINK, SavingsGoalLinkEventKind.LINK),
            goals.observeLinkHistory("goal-1").first().map { it.kind },
        )
        job.cancel()
    }

    @Test
    fun `a goal linked to an asset shows its balance as progress and takes no allocations`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 0, linkedAssetIds = setOf("a1"))),
            ),
            assets = FakeAssetRepository(listOf(asset("a1", minor = 125_00))),
            initialGoalId = "goal-1",
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.goals.single()
        assertEquals(Money(125_00), row.progress)
        assertEquals(125, row.progressPct)
        assertTrue(row.targetReached)
        assertTrue(row.tracksBalance)
        assertEquals("«Asset a1»", row.linkedTargetLabel)
        assertEquals("Sigue el saldo de «Asset a1»", trackedBalanceCaption(row))

        vm.onAllocateAmountChange("10")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSubmitAllocate)
        job.cancel()
    }

    @Test
    fun `a goal linked to a group follows the converted group balance live`() = runTest(dispatcher) {
        val assets = FakeAssetRepository(listOf(asset("a1", minor = 50_00), asset("a2", Currency.USD, minor = 50_00)))
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(goal(id = "goal-1", targetMinor = 100_00, progressMinor = 0, linkedGroupId = "g1")),
            ),
            assets = assets,
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "a1", "a2"))),
            // 1 USD = 0.5 EUR, so the group balance is 50.00 + 25.00 EUR
            fx = FakeFxRepository(FxRates(mapOf(Currency.USD to 500_000L))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(Money(75_00), vm.state.value.goals.single().progress)

        assets.update(asset("a1", minor = 80_00))
        advanceUntilIdle()

        assertEquals(Money(105_00), vm.state.value.goals.single().progress)
        assertEquals("«Group g1»", vm.state.value.goals.single().linkedTargetLabel)
        job.cancel()
    }

    @Test
    fun `unlinked and cancelled goals keep showing their own allocations`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(
                    goal(id = "goal-1", targetMinor = 100_00, progressMinor = 30_00),
                    goal(
                        id = "goal-2",
                        targetMinor = 100_00,
                        progressMinor = 0,
                        linkedAssetIds = setOf("a1"),
                        lifecycle = SavingsGoalLifecycle.CANCELLED,
                    ),
                ),
            ),
            assets = FakeAssetRepository(listOf(asset("a1", minor = 500_00))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val rows = vm.state.value.goals.associateBy { it.id }
        assertEquals(Money(30_00), rows.getValue("goal-1").progress)
        assertFalse(rows.getValue("goal-1").tracksBalance)
        assertEquals(Money.ZERO, rows.getValue("goal-2").progress)
        assertFalse(rows.getValue("goal-2").tracksBalance)
        assertNull(trackedBalanceCaption(rows.getValue("goal-2")))
        job.cancel()
    }

    @Test
    fun `goals following the same balance get one notice naming them`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(
                    goal(id = "goal-1", targetMinor = 100_00, progressMinor = 0, linkedAssetIds = setOf("a1")),
                    goal(id = "goal-2", targetMinor = 100_00, progressMinor = 0, linkedAssetIds = setOf("a1")),
                    goal(id = "goal-3", targetMinor = 100_00, progressMinor = 0, linkedGroupId = "g1"),
                ),
            ),
            assets = FakeAssetRepository(listOf(asset("a1", minor = 100_00))),
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "a1"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            listOf(
                SharedBalanceNoticeUi(targetLabel = "«Asset a1»", goalNames = listOf("Goal goal-1", "Goal goal-2")),
            ),
            vm.state.value.sharedBalanceNotices,
        )
        job.cancel()
    }

    @Test
    fun `linked target labels name up to three targets and count the rest`() {
        assertNull(linkedTargetLabel(emptyList()))
        assertEquals("«A»", linkedTargetLabel(listOf("A")))
        assertEquals("«A» y «B»", linkedTargetLabel(listOf("A", "B")))
        assertEquals("«A», «B» y «C»", linkedTargetLabel(listOf("A", "B", "C")))
        assertEquals("4 cuentas", linkedTargetLabel(listOf("A", "B", "C", "D")))
    }

    @Test
    fun `the link picker tags accounts and groups with the other goals using them`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = FakeSavingsGoalRepository(
                listOf(
                    goal(id = "goal-1", targetMinor = 100_00, progressMinor = 0, linkedAssetIds = setOf("a1")),
                    goal(id = "goal-2", targetMinor = 100_00, progressMinor = 0, linkedAssetIds = setOf("a1")),
                    goal(id = "goal-3", targetMinor = 100_00, progressMinor = 0, linkedGroupId = "g1"),
                ),
            ),
            assets = FakeAssetRepository(listOf(asset("a1"), asset("a2"))),
            groups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("g1", "a1"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onStartEditing("goal-1")
        advanceUntilIdle()

        val assets = vm.state.value.linkableAssets.associateBy { it.id }
        // The goal being edited does not tag its own account.
        assertEquals(listOf("Goal goal-2"), assets.getValue("a1").goalNames)
        assertEquals(listOf("Group g1"), assets.getValue("a1").groupNames)
        assertEquals(emptyList(), assets.getValue("a2").goalNames)
        assertEquals(listOf("Goal goal-3"), vm.state.value.linkableGroups.single().goalNames)
        job.cancel()
    }
}
