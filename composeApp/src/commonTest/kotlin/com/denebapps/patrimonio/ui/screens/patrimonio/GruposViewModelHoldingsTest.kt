package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAccountTypeRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import com.denebapps.patrimonio.testing.manualTestHolding
import com.denebapps.patrimonio.testing.quotedTestHolding
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
class GruposViewModelHoldingsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val account =
        Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(10_000), Currency.EUR))

    private fun group(vararg holdingIds: String, assetIds: Set<String> = setOf("a1")) = AccountGroup(
        id = "g1",
        name = "Casa",
        showBalance = true,
        sortOrder = 1,
        memberAssetIds = assetIds,
        memberHoldingIds = holdingIds.toSet(),
    )

    private fun viewModel(
        portfolio: FakePortfolioRepository,
        accountGroups: FakeAccountGroupRepository,
        fx: FakeFxRepository = FakeFxRepository(FxRates(mapOf(Currency.USD to 500_000L))),
        editingGroupId: String? = null,
    ) = GruposViewModel(
        assetRepository = FakeAssetRepository(listOf(account)),
        accountGroupRepository = accountGroups,
        savingsGoalRepository = FakeSavingsGoalRepository(),
        portfolioRepository = portfolio,
        portfolios = FakePortfolioCollectionRepository(),
        fxRepository = fx,
        accountTypeRepository = FakeAccountTypeRepository(),
        editingGroupId = editingGroupId,
        idProvider = { "g-new" },
    )

    @Test
    fun `a group total includes its holdings and follows their price`() = runTest(dispatcher) {
        val portfolio = FakePortfolioRepository(
            listOf(
                manualTestHolding("fund", 5_000),
                // 200.00 EUR
                manualTestHolding("usd", 40_000, Currency.USD),
                manualTestHolding("outside", 99_999),
            ),
        )
        val vm =
            viewModel(portfolio, FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("fund", "usd"))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(Money(35_000), vm.state.value.groups.single { it.id == "g1" }.total)

        portfolio.updateManualValue("fund", CurrencyAmount(Money(6_000), Currency.EUR))
        advanceUntilIdle()

        assertEquals(Money(36_000), vm.state.value.groups.single { it.id == "g1" }.total)
        job.cancel()
    }

    @Test
    fun `the builtin group total stays accounts only`() = runTest(dispatcher) {
        val portfolio = FakePortfolioRepository(listOf(manualTestHolding("fund", 5_000)))
        val vm = viewModel(portfolio, FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("fund"))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(Money(10_000), vm.state.value.groups.single { it.builtin }.total)
        job.cancel()
    }

    @Test
    fun `an unpriced holding adds nothing to the group total`() = runTest(dispatcher) {
        val portfolio = FakePortfolioRepository(listOf(quotedTestHolding("fund", units = 10, price = null)))
        val vm = viewModel(portfolio, FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("fund"))))
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(Money(10_000), vm.state.value.groups.single { it.id == "g1" }.total)
        job.cancel()
    }

    @Test
    fun `saving an edited group keeps the holdings it already had`() = runTest(dispatcher) {
        val accountGroups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group("fund")))
        val vm = viewModel(
            FakePortfolioRepository(listOf(manualTestHolding("fund", 5_000))),
            accountGroups,
            editingGroupId = "g1",
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(2, vm.state.value.selectedCount)
        assertEquals(Money(15_000), vm.state.value.selectedTotal)

        vm.onTitleChange("Reforma")
        advanceUntilIdle()
        vm.onSaveNewGroup()
        advanceUntilIdle()

        val saved = accountGroups.current.single { it.id == "g1" }
        assertEquals("Reforma", saved.name)
        assertEquals(setOf("a1"), saved.memberAssetIds)
        assertEquals(setOf("fund"), saved.memberHoldingIds)
        job.cancel()
    }

    @Test
    fun `toggling a holding counts it and lets a group of only holdings be saved`() = runTest(dispatcher) {
        val accountGroups = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts()))
        val vm = viewModel(FakePortfolioRepository(listOf(manualTestHolding("fund", 5_000))), accountGroups)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onTitleChange("Inversión")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSaveNewGroup)

        vm.onHoldingToggle("fund")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selectedCount)
        assertEquals(Money(5_000), vm.state.value.selectedTotal)
        assertTrue(vm.state.value.canSaveNewGroup)

        vm.onSaveNewGroup()
        advanceUntilIdle()
        val created = accountGroups.current.single { it.id == "g-new" }
        assertEquals(emptySet(), created.memberAssetIds)
        assertEquals(setOf("fund"), created.memberHoldingIds)

        vm.onHoldingToggle("fund")
        advanceUntilIdle()
        assertEquals(0, vm.state.value.selectedCount)
        assertFalse(vm.state.value.canSaveNewGroup)
        job.cancel()
    }
}
