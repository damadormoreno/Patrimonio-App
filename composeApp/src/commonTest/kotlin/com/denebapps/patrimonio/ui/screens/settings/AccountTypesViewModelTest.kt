package com.denebapps.patrimonio.ui.screens.settings

import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.testing.FakeAccountTypeRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeLiabilityRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class AccountTypesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val watches = CustomAccountType("watch", AccountKind.ASSET, "Relojes", "⌚", TypeColor.GOLD, 0)
    private val family = CustomAccountType("family", AccountKind.LIABILITY, "Familia", "👪", TypeColor.BLUE, 0)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `types are split by kind with how many accounts use them`() = runTest(dispatcher) {
        val assets = FakeAssetRepository(
            listOf(
                Asset("a1", "watch", "Rolex", null, CurrencyAmount(Money(1), Currency.EUR)),
                Asset("a2", "watch", "Omega", null, CurrencyAmount(Money(1), Currency.EUR)),
                Asset("a3", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(1), Currency.EUR)),
            ),
        )
        val vm = AccountTypesViewModel(FakeAccountTypeRepository(watches, family), assets, FakeLiabilityRepository())
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("watch" to 2), vm.state.value.assetTypes.map { it.type.id to it.accountCount })
        assertEquals(listOf("family" to 0), vm.state.value.liabilityTypes.map { it.type.id to it.accountCount })
        assertEquals("⌚", vm.state.value.assetTypes.single().look.emoji)
        job.cancel()
    }

    @Test
    fun `create, edit and delete go to the repository`() = runTest(dispatcher) {
        val types = FakeAccountTypeRepository(watches)
        val vm = AccountTypesViewModel(types, FakeAssetRepository(), FakeLiabilityRepository())
        val job = launch { vm.state.collect {} }

        vm.onCreate(AccountKind.LIABILITY, "Familia", "👪", TypeColor.BLUE)
        vm.onUpdate("watch", "Relojes de lujo", "⌚", TypeColor.PURPLE)
        vm.onDelete("watch")
        advanceUntilIdle()

        assertEquals(listOf("watch"), types.deleted)
        assertEquals(listOf("Familia"), vm.state.value.liabilityTypes.map { it.type.name })
        assertEquals(emptyList(), vm.state.value.assetTypes)
        job.cancel()
    }
}
