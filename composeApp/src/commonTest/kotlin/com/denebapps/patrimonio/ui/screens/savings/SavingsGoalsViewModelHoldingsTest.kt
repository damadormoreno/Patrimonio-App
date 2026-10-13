package com.denebapps.patrimonio.ui.screens.savings

import com.denebapps.patrimonio.domain.calc.fixedClock
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
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import com.denebapps.patrimonio.testing.manualTestHolding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SavingsGoalsViewModelHoldingsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val account =
        Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(10_000), Currency.EUR))

    private val casa = AccountGroup(
        id = "g1",
        name = "Casa",
        showBalance = true,
        sortOrder = 1,
        memberAssetIds = setOf("a1"),
        memberHoldingIds = setOf("fund", "usd"),
    )

    private fun goal(lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN) = SavingsGoal(
        id = "goal-1",
        name = "Casa",
        target = CurrencyAmount(Money(100_000), Currency.EUR),
        targetDate = null,
        linkedAssetIds = emptySet(),
        linkedGroupIds = setOf("g1"),
        lifecycle = lifecycle,
        progress = Money.ZERO,
    )

    private fun viewModel(portfolio: FakePortfolioRepository, fx: FakeFxRepository) = SavingsGoalsViewModel(
        savingsGoalRepository = FakeSavingsGoalRepository(listOf(goal())),
        assetRepository = FakeAssetRepository(listOf(account)),
        accountGroupRepository = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), casa)),
        portfolioRepository = portfolio,
        portfolios = FakePortfolioCollectionRepository(),
        fxRepository = fx,
        clock = fixedClock("2026-10-09T10:00:00Z"),
        zoneProvider = { TimeZone.UTC },
    )

    @Test
    fun `a group goal counts its account and holdings and follows their price`() = runTest(dispatcher) {
        val portfolio = FakePortfolioRepository(
            listOf(manualTestHolding("fund", 5_000), manualTestHolding("usd", 40_000, Currency.USD)),
        )
        val vm = viewModel(portfolio, FakeFxRepository(FxRates(mapOf(Currency.USD to 500_000L))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.goals.single()
        assertEquals(Money(35_000), row.progress)
        assertEquals(35, row.progressPct)
        assertFalse(row.balanceUnavailable)

        portfolio.updateManualValue("fund", CurrencyAmount(Money(15_000), Currency.EUR))
        advanceUntilIdle()

        assertEquals(Money(45_000), vm.state.value.goals.single().progress)
        job.cancel()
    }

    @Test
    fun `a holding currency without a rate makes the goal balance unavailable`() = runTest(dispatcher) {
        val portfolio = FakePortfolioRepository(
            listOf(manualTestHolding("fund", 5_000), manualTestHolding("usd", 40_000, Currency.USD)),
        )
        val vm = viewModel(portfolio, FakeFxRepository(FxRates(mapOf(Currency.USD to 0L))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val row = vm.state.value.goals.single()
        assertTrue(row.balanceUnavailable)
        assertEquals(Money.ZERO, row.progress)
        job.cancel()
    }
}
