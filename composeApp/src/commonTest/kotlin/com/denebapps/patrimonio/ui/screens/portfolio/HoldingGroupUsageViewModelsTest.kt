package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import com.denebapps.patrimonio.testing.manualTestHolding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The group and goal usage of a holding on its detail screen and in the delete confirmation. */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldingGroupUsageViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-11T12:00:00Z")
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val casa = group("g1", "Casa", 1)
    private val coche = group("g2", "Coche", 2)
    private fun group(id: String, name: String, sortOrder: Int) =
        AccountGroup(id, name, true, sortOrder, memberAssetIds = emptySet(), memberHoldingIds = setOf("h1"))

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

    private fun usage(groups: List<AccountGroup>, goals: List<SavingsGoal>) = observeHoldingUsage(
        FakeAccountGroupRepository(listOf(AccountGroup.allAccounts()) + groups),
        FakeSavingsGoalRepository(goals),
    )

    private fun positionDetail(
        id: String,
        groups: List<AccountGroup>,
        goals: List<SavingsGoal>,
    ): PositionDetailViewModel {
        val repository = FakePortfolioRepository(listOf(manualTestHolding("h1", 5_000), manualTestHolding("h2", 1_000)))
        return PositionDetailViewModel(
            holdingId = id,
            portfolioRepository = repository,
            portfolios = FakePortfolioCollectionRepository(holdings = repository),
            movementRepository = FakeMovementRepository(repository),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            fxHistoryRepository = FakeFxHistoryRepository(),
            historyRepository = FakeInstrumentHistoryRepository(),
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
            usageByHolding = usage(groups, goals),
        )
    }

    private fun holdingDetail(
        id: String,
        groups: List<AccountGroup>,
        goals: List<SavingsGoal>,
    ): HoldingDetailViewModel {
        val repository = FakePortfolioRepository(listOf(manualTestHolding("h1", 5_000), manualTestHolding("h2", 1_000)))
        return HoldingDetailViewModel(
            holdingId = id,
            portfolioRepository = repository,
            portfolios = FakePortfolioCollectionRepository(holdings = repository),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            movementRepository = FakeMovementRepository(repository),
            zoneProvider = { TimeZone.UTC },
            usageByHolding = usage(groups, goals),
        )
    }

    private fun TestScope.collect(vm: PositionDetailViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
    }

    private fun TestScope.collect(vm: HoldingDetailViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
    }

    @Test
    fun `the position detail says which group holds the product and which goal follows it`() = runTest(dispatcher) {
        val vm = positionDetail("h1", listOf(casa), listOf(goal))
        collect(vm)

        assertEquals("En el grupo Casa · Cuenta para la meta Entrada", vm.state.value.header.usageText)
    }

    @Test
    fun `the position detail lists several groups`() = runTest(dispatcher) {
        val vm = positionDetail("h1", listOf(casa, coche), emptyList())
        collect(vm)

        assertEquals("En los grupos Casa y Coche", vm.state.value.header.usageText)
    }

    @Test
    fun `the position detail has no usage line for a product in no group`() = runTest(dispatcher) {
        val vm = positionDetail("h2", listOf(casa), listOf(goal))
        collect(vm)

        assertNull(vm.state.value.header.usageText)
    }

    @Test
    fun `deleting a product in groups warns with the groups and the goals`() = runTest(dispatcher) {
        val vm = holdingDetail("h1", listOf(casa, coche), listOf(goal))
        collect(vm)

        assertEquals(
            "Está en la meta «Entrada» y en los grupos «Casa» y «Coche»; se quitará de ellos.",
            vm.state.value.deleteWarning,
        )
    }

    @Test
    fun `deleting a product in no group has no extra warning`() = runTest(dispatcher) {
        val vm = holdingDetail("h2", listOf(casa), listOf(goal))
        collect(vm)

        assertNull(vm.state.value.deleteWarning)
    }
}
