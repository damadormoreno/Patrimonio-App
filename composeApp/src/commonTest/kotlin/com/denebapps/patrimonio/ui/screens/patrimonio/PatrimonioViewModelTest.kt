package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeLiabilityRepository
import com.denebapps.patrimonio.testing.FakeNetWorthRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PatrimonioViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun asset(id: String, group: Asset.AssetGroup, name: String, minor: Long) = Asset(
        id = id,
        group = group,
        name = name,
        subtitle = null,
        amount = CurrencyAmount(Money(minor), Currency.EUR),
    )

    private fun liability(id: String, group: Liability.LiabilityGroup, name: String, minor: Long) = Liability(
        id = id,
        group = group,
        name = name,
        subtitle = null,
        amount = CurrencyAmount(Money(minor), Currency.EUR),
    )

    private fun viewModel(
        assets: FakeAssetRepository = FakeAssetRepository(),
        liabilities: FakeLiabilityRepository = FakeLiabilityRepository(),
        netWorth: FakeNetWorthRepository = FakeNetWorthRepository(),
        accountGroups: FakeAccountGroupRepository = FakeAccountGroupRepository(),
        fx: FakeFxRepository = FakeFxRepository(FxRates(emptyMap())),
        month: YearMonth = YearMonth(2026, 5),
        goals: FakeSavingsGoalRepository = FakeSavingsGoalRepository(),
    ) = PatrimonioViewModel(
        assetRepository = assets,
        liabilityRepository = liabilities,
        netWorthRepository = netWorth,
        accountGroupRepository = accountGroups,
        fxRepository = fx,
        savingsGoalRepository = goals,
        clock = fixedClock("2026-05-21T12:00:00Z"),
        zoneProvider = { TimeZone.UTC },
        monthFlow = flowOf(month),
    )

    @Test
    fun `net worth is total assets minus total liabilities in EUR`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(listOf(asset("a1", Asset.AssetGroup.BANK, "Cuenta", 500_000))),
            liabilities = FakeLiabilityRepository(
                listOf(liability("l1", Liability.LiabilityGroup.CARD, "Tarjeta", 120_000)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(Money(380_000), vm.state.value.netWorth)
        assertEquals(Money(500_000), vm.state.value.totalAssets)
        assertEquals(Money(120_000), vm.state.value.totalLiabs)
        job.cancel()
    }

    @Test
    fun `positive delta reflects an increase against the most recent snapshot before the current month`() =
        runTest(dispatcher) {
            val vm = viewModel(
                assets = FakeAssetRepository(listOf(asset("a1", Asset.AssetGroup.BANK, "Cuenta", 600_000))),
                netWorth = FakeNetWorthRepository(
                    listOf(
                        NetWorthSnapshot(YearMonth(2026, 4), Money(500_000), Money.ZERO),
                        NetWorthSnapshot(YearMonth(2026, 3), Money(400_000), Money.ZERO),
                    ),
                ),
                month = YearMonth(2026, 5),
            )
            val job = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(Money(100_000), vm.state.value.delta)
            job.cancel()
        }

    @Test
    fun `negative delta reflects a decrease from the previous snapshot`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(listOf(asset("a1", Asset.AssetGroup.BANK, "Cuenta", 300_000))),
            netWorth = FakeNetWorthRepository(
                listOf(NetWorthSnapshot(YearMonth(2026, 4), Money(500_000), Money.ZERO)),
            ),
            month = YearMonth(2026, 5),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(Money(-200_000), vm.state.value.delta)
        job.cancel()
    }

    @Test
    fun `delta is hidden when no snapshot exists strictly before the current month`() = runTest(dispatcher) {
        val vmNone = viewModel(month = YearMonth(2026, 5))
        val jobNone = launch { vmNone.state.collect {} }
        advanceUntilIdle()
        assertNull(vmNone.state.value.delta)
        jobNone.cancel()

        val vmSameMonthOnly = viewModel(
            netWorth = FakeNetWorthRepository(
                listOf(NetWorthSnapshot(YearMonth(2026, 5), Money(500_000), Money.ZERO)),
            ),
            month = YearMonth(2026, 5),
        )
        val jobSame = launch { vmSameMonthOnly.state.collect {} }
        advanceUntilIdle()
        assertNull(vmSameMonthOnly.state.value.delta)
        jobSame.cancel()
    }

    @Test
    fun `view defaults to Activos and switching updates the active view`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(PatrimonioView.ACTIVOS, vm.state.value.view)

        vm.onViewSelect(PatrimonioView.PASIVOS)
        advanceUntilIdle()

        assertEquals(PatrimonioView.PASIVOS, vm.state.value.view)
        job.cancel()
    }

    @Test
    fun `composition orders asset groups by total descending`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(
                    asset("a1", Asset.AssetGroup.CASH, "Efectivo", 10_000),
                    asset("a2", Asset.AssetGroup.BANK, "Cuenta", 300_000),
                    asset("a3", Asset.AssetGroup.INVEST, "Fondo", 150_000),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("BANK", "INVEST", "CASH"), vm.state.value.composition.map { it.groupId })
        job.cancel()
    }

    @Test
    fun `group card exposes singular item count label for a single item`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(listOf(asset("a1", Asset.AssetGroup.BANK, "Cuenta", 500_000))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val group = vm.state.value.groups.single()
        assertEquals("1 elemento", group.itemCountLabel)
        job.cancel()
    }

    @Test
    fun `group card exposes plural item count label for multiple items`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(
                    asset("a1", Asset.AssetGroup.BANK, "Cuenta 1", 500_000),
                    asset("a2", Asset.AssetGroup.BANK, "Cuenta 2", 200_000),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val group = vm.state.value.groups.single()
        assertEquals("2 elementos", group.itemCountLabel)
        job.cancel()
    }

    @Test
    fun `groupsCount reflects the account group repository size`() = runTest(dispatcher) {
        val vm = viewModel(
            accountGroups = FakeAccountGroupRepository(
                listOf(
                    AccountGroup.allAccounts(),
                    AccountGroup("g1", "Ahorro", showBalance = true, sortOrder = 1, memberAssetIds = setOf("a1")),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(2, vm.state.value.groupsCount)
        job.cancel()
    }

    @Test
    fun `isEmpty reflects the active view item count`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(),
            liabilities = FakeLiabilityRepository(
                listOf(liability("l1", Liability.LiabilityGroup.CARD, "Tarjeta", 10_000)),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertTrue(vm.state.value.isEmpty)

        vm.onViewSelect(PatrimonioView.PASIVOS)
        advanceUntilIdle()

        assertFalse(vm.state.value.isEmpty)
        job.cancel()
    }

    @Test
    fun `asset repository emissions recompute totals and composition live`() = runTest(dispatcher) {
        val assets = FakeAssetRepository()
        val vm = viewModel(assets = assets)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertTrue(vm.state.value.isEmpty)

        assets.emit(listOf(asset("a1", Asset.AssetGroup.BANK, "Cuenta", 250_000)))
        advanceUntilIdle()

        assertFalse(vm.state.value.isEmpty)
        assertEquals(Money(250_000), vm.state.value.totalAssets)
        job.cancel()
    }

    @Test
    fun `account rows show whether a goal follows them and whether a group holds them`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = FakeAssetRepository(
                listOf(
                    asset("a1", Asset.AssetGroup.BANK, "Nómina", 100_000),
                    asset("a2", Asset.AssetGroup.BANK, "Hucha", 50_000),
                    asset("a3", Asset.AssetGroup.BANK, "Libre", 10_000),
                ),
            ),
            accountGroups = FakeAccountGroupRepository(
                listOf(
                    AccountGroup.allAccounts(),
                    AccountGroup("g1", "Ahorro", showBalance = true, sortOrder = 1, memberAssetIds = setOf("a2")),
                ),
            ),
            goals = FakeSavingsGoalRepository(
                listOf(
                    SavingsGoal(
                        id = "goal-1",
                        name = "Viaje",
                        target = CurrencyAmount(Money(100_000), Currency.EUR),
                        targetDate = null,
                        linkedAssetIds = setOf("a1", "a2"),
                        lifecycle = SavingsGoalLifecycle.OPEN,
                        progress = Money.ZERO,
                    ),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val items = vm.state.value.groups.flatMap { it.items }.associateBy { it.id }
        assertEquals(true to false, items.getValue("a1").let { it.inGoal to it.inGroup })
        assertEquals(true to true, items.getValue("a2").let { it.inGoal to it.inGroup })
        assertEquals(false to false, items.getValue("a3").let { it.inGoal to it.inGroup })
        job.cancel()
    }
}
