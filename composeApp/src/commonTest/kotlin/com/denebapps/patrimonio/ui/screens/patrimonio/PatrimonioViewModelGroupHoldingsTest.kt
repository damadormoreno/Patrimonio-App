package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
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

@OptIn(ExperimentalCoroutinesApi::class)
class PatrimonioViewModelGroupHoldingsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(groups: List<AccountGroup>) = PatrimonioViewModel(
        assetRepository = FakeAssetRepository(
            listOf(Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(500_000), Currency.EUR))),
        ),
        liabilityRepository = FakeLiabilityRepository(),
        netWorthRepository = FakeNetWorthRepository(),
        accountGroupRepository = FakeAccountGroupRepository(groups),
        fxRepository = FakeFxRepository(FxRates(mapOf(Currency.USD to 500_000L))),
        savingsGoalRepository = FakeSavingsGoalRepository(),
        accountTypeRepository = FakeAccountTypeRepository(),
        portfolioRepository = FakePortfolioRepository(
            listOf(manualTestHolding("h1", 250_000), manualTestHolding("h2", 100_000, Currency.USD)),
        ),
        clock = fixedClock("2026-05-21T12:00:00Z"),
        zoneProvider = { TimeZone.UTC },
        monthFlow = flowOf(YearMonth(2026, 5)),
    )

    private fun group(vararg holdingIds: String) = AccountGroup(
        id = "g1",
        name = "Casa",
        showBalance = true,
        sortOrder = 1,
        memberAssetIds = setOf("a1"),
        memberHoldingIds = holdingIds.toSet(),
    )

    @Test
    fun `putting holdings in a group does not change net worth or the totals`() = runTest(dispatcher) {
        val without = viewModel(listOf(AccountGroup.allAccounts(), group()))
        val with = viewModel(listOf(AccountGroup.allAccounts(), group("h1", "h2")))
        val withoutJob = launch { without.state.collect {} }
        val withJob = launch { with.state.collect {} }
        advanceUntilIdle()

        // 5,000.00 account + 2,500.00 + (1,000.00 USD at 0.5) portfolio.
        assertEquals(Money(800_000), without.state.value.netWorth)
        assertEquals(without.state.value.netWorth, with.state.value.netWorth)
        assertEquals(without.state.value.totalAssets, with.state.value.totalAssets)
        assertEquals(without.state.value.composition, with.state.value.composition)
        // The Cartera card only gains the group icons: same products, same values, same total.
        assertEquals(without.state.value.portfolio?.total, with.state.value.portfolio?.total)
        assertEquals(
            without.state.value.portfolio?.items?.map { it.id to it.valueEur },
            with.state.value.portfolio?.items?.map { it.id to it.valueEur },
        )
        withoutJob.cancel()
        withJob.cancel()
    }
}
