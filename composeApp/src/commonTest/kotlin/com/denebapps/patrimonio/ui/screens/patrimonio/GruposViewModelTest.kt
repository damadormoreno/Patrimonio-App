package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAccountTypeRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GruposViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun asset(id: String, name: String, minor: Long) = Asset(
        id = id,
        group = Asset.AssetGroup.BANK,
        name = name,
        subtitle = null,
        amount = CurrencyAmount(Money(minor), Currency.EUR),
    )

    private fun goal(
        id: String,
        name: String,
        linkedGroupId: String? = null,
        linkedAssetIds: Set<String> = emptySet(),
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = id,
        name = name,
        target = CurrencyAmount(Money(100_000), Currency.EUR),
        targetDate = null,
        linkedAssetIds = linkedAssetIds,
        lifecycle = lifecycle,
        progress = Money.ZERO,
        linkedGroupId = linkedGroupId,
    )

    private fun groupsWithPersonal() = FakeAccountGroupRepository(
        listOf(
            AccountGroup.allAccounts(),
            AccountGroup("g1", "Personal", showBalance = true, sortOrder = 1, memberAssetIds = emptySet()),
        ),
    )

    private fun viewModel(
        assets: FakeAssetRepository = FakeAssetRepository(),
        accountGroups: FakeAccountGroupRepository = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts())),
        savingsGoals: FakeSavingsGoalRepository = FakeSavingsGoalRepository(),
        fx: FakeFxRepository = FakeFxRepository(FxRates(emptyMap())),
        idProvider: () -> String = { "generated-group-id" },
        editingGroupId: String? = null,
        accountTypes: FakeAccountTypeRepository = FakeAccountTypeRepository(),
    ) = GruposViewModel(
        assetRepository = assets,
        accountGroupRepository = accountGroups,
        savingsGoalRepository = savingsGoals,
        fxRepository = fx,
        accountTypeRepository = accountTypes,
        editingGroupId = editingGroupId,
        idProvider = idProvider,
    )

    @Test
    fun `builtin group is present, non-deletable, and includes every asset`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(asset("a1", "Cuenta 1", 100_000), asset("a2", "Cuenta 2", 200_000)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val builtin = vm.state.value.groups.single { it.builtin }
        assertEquals(AccountGroup.ALL_ACCOUNTS_ID, builtin.id)
        assertEquals(2, builtin.members.size)
        assertEquals(Money(300_000), builtin.total)
        job.cancel()
    }

    @Test
    fun `each group exposes its own member list and EUR total`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(asset("a1", "Cuenta 1", 100_000), asset("a2", "Cuenta 2", 200_000)),
            ),
            accountGroups = FakeAccountGroupRepository(
                listOf(
                    AccountGroup.allAccounts(),
                    AccountGroup("g1", "Personal", showBalance = true, sortOrder = 1, memberAssetIds = setOf("a1")),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val personal = vm.state.value.groups.single { it.id == "g1" }
        assertEquals(listOf("a1"), personal.members.map { it.id })
        assertEquals(Money(100_000), personal.total)
        job.cancel()
    }

    @Test
    fun `deleting a non-builtin group removes it from the repository`() = runTest(dispatcher) {
        val accountGroups = FakeAccountGroupRepository(
            listOf(
                AccountGroup.allAccounts(),
                AccountGroup("g1", "Personal", showBalance = true, sortOrder = 1, memberAssetIds = emptySet()),
            ),
        )
        val vm = viewModel(accountGroups = accountGroups)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onDeleteGroup("g1")
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.none { it.id == "g1" })
        job.cancel()
    }

    @Test
    fun `deleting the builtin group is a no-op`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onDeleteGroup(AccountGroup.ALL_ACCOUNTS_ID)
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.any { it.builtin })
        job.cancel()
    }

    @Test
    fun `deleting a group without linked goals deletes immediately with no pending confirmation`() =
        runTest(dispatcher) {
            val vm = viewModel(accountGroups = groupsWithPersonal())
            val job = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onDeleteGroup("g1")
            advanceUntilIdle()

            assertTrue(vm.state.value.groups.none { it.id == "g1" })
            assertNull(vm.state.value.pendingDeletion)
            job.cancel()
        }

    @Test
    fun `deleting a group with linked goals asks for confirmation and does not delete`() = runTest(dispatcher) {
        val vm = viewModel(
            accountGroups = groupsWithPersonal(),
            savingsGoals = FakeSavingsGoalRepository(
                listOf(
                    goal("goal-1", "Vacaciones", linkedGroupId = "g1"),
                    goal("goal-2", "Coche", linkedGroupId = "g1"),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onDeleteGroup("g1")
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.any { it.id == "g1" })
        assertEquals(
            GroupDeletionConfirmationUi(
                groupId = "g1",
                groupName = "Personal",
                linkedGoalNames = listOf("Vacaciones", "Coche"),
            ),
            vm.state.value.pendingDeletion,
        )
        job.cancel()
    }

    @Test
    fun `goals in any lifecycle linked to the group trigger the confirmation`() = runTest(dispatcher) {
        val vm = viewModel(
            accountGroups = groupsWithPersonal(),
            savingsGoals = FakeSavingsGoalRepository(
                listOf(goal("goal-1", "Cerrada", linkedGroupId = "g1", lifecycle = SavingsGoalLifecycle.CLOSED)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onDeleteGroup("g1")
        advanceUntilIdle()

        assertEquals(listOf("Cerrada"), vm.state.value.pendingDeletion?.linkedGoalNames)
        job.cancel()
    }

    @Test
    fun `confirming the pending deletion deletes the group and clears the state`() = runTest(dispatcher) {
        val vm = viewModel(
            accountGroups = groupsWithPersonal(),
            savingsGoals = FakeSavingsGoalRepository(listOf(goal("goal-1", "Vacaciones", linkedGroupId = "g1"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onDeleteGroup("g1")
        advanceUntilIdle()

        vm.onConfirmDeleteGroup()
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.none { it.id == "g1" })
        assertNull(vm.state.value.pendingDeletion)
        job.cancel()
    }

    @Test
    fun `dismissing the pending deletion keeps the group and clears the state`() = runTest(dispatcher) {
        val vm = viewModel(
            accountGroups = groupsWithPersonal(),
            savingsGoals = FakeSavingsGoalRepository(listOf(goal("goal-1", "Vacaciones", linkedGroupId = "g1"))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onDeleteGroup("g1")
        advanceUntilIdle()

        vm.onDismissDeleteGroup()
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.any { it.id == "g1" })
        assertNull(vm.state.value.pendingDeletion)
        job.cancel()
    }

    @Test
    fun `confirming without a pending deletion is a no-op`() = runTest(dispatcher) {
        val vm = viewModel(accountGroups = groupsWithPersonal())
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onConfirmDeleteGroup()
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.any { it.id == "g1" })
        assertNull(vm.state.value.pendingDeletion)
        job.cancel()
    }

    @Test
    fun `goals linked to another group or to an asset do not trigger the confirmation`() = runTest(dispatcher) {
        val vm = viewModel(
            accountGroups = groupsWithPersonal(),
            savingsGoals = FakeSavingsGoalRepository(
                listOf(
                    goal("goal-1", "Otro grupo", linkedGroupId = "g2"),
                    goal("goal-2", "Una cuenta", linkedAssetIds = setOf("a1")),
                    goal("goal-3", "Sin vincular"),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onDeleteGroup("g1")
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.none { it.id == "g1" })
        assertNull(vm.state.value.pendingDeletion)
        job.cancel()
    }

    @Test
    fun `deleting the builtin group never asks for confirmation`() = runTest(dispatcher) {
        val vm = viewModel(
            savingsGoals = FakeSavingsGoalRepository(
                listOf(goal("goal-1", "Vacaciones", linkedGroupId = AccountGroup.ALL_ACCOUNTS_ID)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onDeleteGroup(AccountGroup.ALL_ACCOUNTS_ID)
        advanceUntilIdle()

        assertNull(vm.state.value.pendingDeletion)
        assertTrue(vm.state.value.groups.any { it.builtin })
        job.cancel()
    }

    @Test
    fun `toggling an asset in the new-group checklist updates live selected count and total`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(asset("a1", "Cuenta 1", 100_000), asset("a2", "Cuenta 2", 200_000)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(0, vm.state.value.selectedCount)
        assertEquals(Money.ZERO, vm.state.value.selectedTotal)

        vm.onAssetToggle("a1")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selectedCount)
        assertEquals(Money(100_000), vm.state.value.selectedTotal)

        vm.onAssetToggle("a2")
        advanceUntilIdle()
        assertEquals(2, vm.state.value.selectedCount)
        assertEquals(Money(300_000), vm.state.value.selectedTotal)

        vm.onAssetToggle("a1")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selectedCount)
        assertEquals(Money(200_000), vm.state.value.selectedTotal)
        job.cancel()
    }

    @Test
    fun `canSaveNewGroup requires a non-empty title and at least one selected account`() = runTest(dispatcher) {
        val vm = viewModel(assets = FakeAssetRepository(listOf(asset("a1", "Cuenta 1", 100_000))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertFalse(vm.state.value.canSaveNewGroup)

        vm.onTitleChange("Fondo emergencia")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSaveNewGroup)

        vm.onAssetToggle("a1")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSaveNewGroup)

        vm.onTitleChange("")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSaveNewGroup)
        job.cancel()
    }

    @Test
    fun `saving the new group creates an overlapping group with the selected members`() = runTest(dispatcher) {
        val accountGroups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts()))
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(asset("a1", "Cuenta 1", 100_000), asset("a2", "Cuenta 2", 200_000)),
            ),
            accountGroups = accountGroups,
            idProvider = { "g-new" },
        )
        val job = launch { vm.state.collect {} }
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        advanceUntilIdle()

        vm.onTitleChange("Fondo emergencia")
        vm.onAssetToggle("a1")
        advanceUntilIdle()
        vm.onSaveNewGroup()
        advanceUntilIdle()

        val created = accountGroups.observeAll()
        var latest: List<AccountGroup> = emptyList()
        val collectJob = launch { created.collect { latest = it } }
        advanceUntilIdle()
        collectJob.cancel()

        val newGroup = latest.single { it.id == "g-new" }
        assertEquals("Fondo emergencia", newGroup.name)
        assertFalse(newGroup.builtin())
        assertEquals(setOf("a1"), newGroup.memberAssetIds)
        assertEquals(1, events)
        job.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `editing a group starts from its values and saving replaces name visibility and members`() =
        runTest(dispatcher) {
            val accountGroups = FakeAccountGroupRepository(
                listOf(
                    AccountGroup.allAccounts(),
                    AccountGroup("g1", "Personal", showBalance = true, sortOrder = 1, memberAssetIds = setOf("a1")),
                ),
            )
            val vm = viewModel(
                assets = FakeAssetRepository(
                    listOf(asset("a1", "Cuenta 1", 100_000), asset("a2", "Cuenta 2", 200_000)),
                ),
                accountGroups = accountGroups,
                editingGroupId = "g1",
            )
            val job = launch { vm.state.collect {} }
            var events = 0
            val eventsJob = launch { vm.navigateBack.collect { events++ } }
            advanceUntilIdle()

            val loaded = vm.state.value
            assertTrue(loaded.isEditing)
            assertEquals("Personal", loaded.title)
            assertEquals(setOf("a1"), loaded.assetChecklist.filter { it.selected }.map { it.id }.toSet())
            assertTrue(loaded.canSaveNewGroup)

            vm.onTitleChange("Día a día")
            vm.onShowBalanceToggle()
            vm.onAssetToggle("a1")
            vm.onAssetToggle("a2")
            advanceUntilIdle()
            vm.onSaveNewGroup()
            advanceUntilIdle()

            val edited = accountGroups.current.single { it.id == "g1" }
            assertEquals("Día a día", edited.name)
            assertFalse(edited.showBalance)
            assertEquals(setOf("a2"), edited.memberAssetIds)
            assertEquals(2, accountGroups.current.size)
            assertEquals(1, events)
            job.cancel()
            eventsJob.cancel()
        }

    @Test
    fun `arrows move a group within the persisted ones and never past the builtin group`() = runTest(dispatcher) {
        val accountGroups = FakeAccountGroupRepository(
            listOf(
                AccountGroup.allAccounts(),
                AccountGroup("g1", "Uno", showBalance = true, sortOrder = 0, memberAssetIds = emptySet()),
                AccountGroup("g2", "Dos", showBalance = true, sortOrder = 0, memberAssetIds = emptySet()),
                AccountGroup("g3", "Tres", showBalance = true, sortOrder = 0, memberAssetIds = emptySet()),
            ),
        )
        val vm = viewModel(accountGroups = accountGroups)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val initial = vm.state.value.groups.associateBy { it.id }
        assertFalse(initial.getValue(AccountGroup.ALL_ACCOUNTS_ID).canMoveUp)
        assertFalse(initial.getValue(AccountGroup.ALL_ACCOUNTS_ID).canMoveDown)
        assertFalse(initial.getValue("g1").canMoveUp)
        assertTrue(initial.getValue("g1").canMoveDown)
        assertFalse(initial.getValue("g3").canMoveDown)

        vm.onMoveGroup("g3", up = true)
        vm.onMoveGroup("g3", up = true)
        vm.onMoveGroup("g3", up = true) // Already first among the persisted groups: no-op.
        advanceUntilIdle()

        assertEquals(
            listOf(AccountGroup.ALL_ACCOUNTS_ID, "g3", "g1", "g2"),
            vm.state.value.groups.map { it.id },
        )
        assertEquals(listOf(0, 1, 2), accountGroups.current.drop(1).map { it.sortOrder })

        vm.onMoveGroup("g3", up = false)
        advanceUntilIdle()
        assertEquals(listOf("g1", "g3", "g2"), vm.state.value.groups.drop(1).map { it.id })
        job.cancel()
    }

    @Test
    fun `a new group goes after the existing ones`() = runTest(dispatcher) {
        val accountGroups = FakeAccountGroupRepository(
            listOf(
                AccountGroup.allAccounts(),
                AccountGroup("g1", "Uno", showBalance = true, sortOrder = 4, memberAssetIds = emptySet()),
            ),
        )
        val vm = viewModel(
            assets = FakeAssetRepository(listOf(asset("a1", "Cuenta 1", 100_000))),
            accountGroups = accountGroups,
            idProvider = { "g-new" },
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onTitleChange("Nuevo")
        vm.onAssetToggle("a1")
        vm.onSaveNewGroup()
        advanceUntilIdle()

        assertEquals(5, accountGroups.current.single { it.id == "g-new" }.sortOrder)
        job.cancel()
    }

    @Test
    fun `editing a group that no longer exists navigates back without saving`() = runTest(dispatcher) {
        val accountGroups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts()))
        val vm = viewModel(accountGroups = accountGroups, editingGroupId = "gone")
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        advanceUntilIdle()

        assertEquals(1, events)
        assertFalse(vm.state.value.canSaveNewGroup)
        assertEquals(listOf(AccountGroup.ALL_ACCOUNTS_ID), accountGroups.current.map { it.id })
        eventsJob.cancel()
    }

    @Test
    fun `the checklist tags accounts with their goals and their other groups`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(listOf(asset("a1", "Cuenta 1", 100_000), asset("a2", "Cuenta 2", 200_000))),
            accountGroups = FakeAccountGroupRepository(
                listOf(
                    AccountGroup.allAccounts(),
                    AccountGroup("g1", "Personal", showBalance = true, sortOrder = 1, memberAssetIds = setOf("a1")),
                    AccountGroup("g2", "Ahorro", showBalance = true, sortOrder = 2, memberAssetIds = setOf("a1", "a2")),
                ),
            ),
            savingsGoals = FakeSavingsGoalRepository(listOf(goal("goal-1", "Viaje", linkedAssetIds = setOf("a2")))),
            editingGroupId = "g1",
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val checklist = vm.state.value.assetChecklist.associateBy { it.id }
        // The edited group itself ("Personal") is not a tag.
        assertEquals(listOf("Ahorro"), checklist.getValue("a1").groupNames)
        assertEquals(emptyList(), checklist.getValue("a1").goalNames)
        assertEquals(listOf("Ahorro"), checklist.getValue("a2").groupNames)
        assertEquals(listOf("Viaje"), checklist.getValue("a2").goalNames)
        job.cancel()
    }
}

private fun AccountGroup.builtin(): Boolean = id == AccountGroup.ALL_ACCOUNTS_ID
