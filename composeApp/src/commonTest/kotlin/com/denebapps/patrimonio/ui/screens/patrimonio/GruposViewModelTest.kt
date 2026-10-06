package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
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

    private fun viewModel(
        assets: FakeAssetRepository = FakeAssetRepository(),
        accountGroups: FakeAccountGroupRepository = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts())),
        fx: FakeFxRepository = FakeFxRepository(FxRates(emptyMap())),
        idProvider: () -> String = { "generated-group-id" },
    ) = GruposViewModel(
        assetRepository = assets,
        accountGroupRepository = accountGroups,
        fxRepository = fx,
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
}

private fun AccountGroup.builtin(): Boolean = id == AccountGroup.ALL_ACCOUNTS_ID
