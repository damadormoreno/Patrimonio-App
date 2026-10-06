package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddPatrimonioSheetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val rates = FxRates(mapOf(Currency.USD to 920_000L))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        isLiability: Boolean = false,
        groupId: String? = null,
        assetRepository: FakeAssetRepository = FakeAssetRepository(),
        liabilityRepository: FakeLiabilityRepository = FakeLiabilityRepository(),
        fxRepository: FakeFxRepository = FakeFxRepository(rates),
        idProvider: () -> String = { "generated-id" },
    ) = AddPatrimonioSheetViewModel(
        assetRepository = assetRepository,
        liabilityRepository = liabilityRepository,
        fxRepository = fxRepository,
        idProvider = idProvider,
        initialIsLiability = isLiability,
        initialGroupId = groupId,
    )

    @Test
    fun `mode defaults to the opened-with mode and its matching group options`() = runTest(dispatcher) {
        val vmAsset = viewModel(isLiability = false)
        val jobAsset = launch { vmAsset.state.collect {} }
        advanceUntilIdle()
        assertFalse(vmAsset.state.value.isLiability)
        assertEquals(Asset.AssetGroup.entries.size, vmAsset.state.value.groupOptions.size)
        jobAsset.cancel()

        val vmLiability = viewModel(isLiability = true)
        val jobLiability = launch { vmLiability.state.collect {} }
        advanceUntilIdle()
        assertTrue(vmLiability.state.value.isLiability)
        assertEquals(Liability.LiabilityGroup.entries.size, vmLiability.state.value.groupOptions.size)
        jobLiability.cancel()
    }

    @Test
    fun `per-group add prefills the group selection`() = runTest(dispatcher) {
        val vm = viewModel(isLiability = true, groupId = Liability.LiabilityGroup.CARD.name)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(Liability.LiabilityGroup.CARD.name, vm.state.value.selectedGroupId)
        job.cancel()
    }

    @Test
    fun `switching mode clears the previously selected group`() = runTest(dispatcher) {
        val vm = viewModel(isLiability = false, groupId = Asset.AssetGroup.BANK.name)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(Asset.AssetGroup.BANK.name, vm.state.value.selectedGroupId)

        vm.onModeChange(true)
        advanceUntilIdle()

        assertTrue(vm.state.value.isLiability)
        assertNull(vm.state.value.selectedGroupId)
        job.cancel()
    }

    @Test
    fun `save is disabled until a group is selected`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNameChange("Cuenta nómina")
        vm.onAmountChange("100,00")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSave)

        vm.onGroupSelect(Asset.AssetGroup.BANK.name)
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        job.cancel()
    }

    @Test
    fun `save is disabled until a name is entered`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onGroupSelect(Asset.AssetGroup.BANK.name)
        vm.onAmountChange("100,00")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSave)

        vm.onNameChange("Cuenta nómina")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        job.cancel()
    }

    @Test
    fun `save is disabled when the value does not parse to a positive amount`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onGroupSelect(Asset.AssetGroup.BANK.name)
        vm.onNameChange("Cuenta nómina")
        vm.onAmountChange("0,00")
        advanceUntilIdle()

        assertFalse(vm.state.value.canSave)
        job.cancel()
    }

    @Test
    fun `eurHint is populated for a non-EUR currency with a valid amount`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onCurrencyChange(Currency.USD)
        vm.onAmountChange("100,00")
        advanceUntilIdle()

        val expectedEur = Money(10000 * 920_000L / RATE_SCALE)
        assertEquals(expectedEur, parseEurHintMoney(vm.state.value.eurHint))
        job.cancel()
    }

    @Test
    fun `eurHint is null for EUR`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onAmountChange("100,00")
        advanceUntilIdle()

        assertNull(vm.state.value.eurHint)
        job.cancel()
    }

    @Test
    fun `saving an asset inserts it via the asset repository and emits navigateBack`() = runTest(dispatcher) {
        val assetRepo = FakeAssetRepository()
        val vm = viewModel(isLiability = false, assetRepository = assetRepo, idProvider = { "asset-1" })
        val job = launch { vm.state.collect {} }
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        advanceUntilIdle()

        vm.onGroupSelect(Asset.AssetGroup.BANK.name)
        vm.onNameChange("Cuenta nómina")
        vm.onAmountChange("1.500,00")
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()

        var latest: List<Asset> = emptyList()
        val collectJob = launch { assetRepo.observeAll().collect { latest = it } }
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals(1, latest.size)
        assertEquals("asset-1", latest.single().id)
        assertEquals(Asset.AssetGroup.BANK, latest.single().group)
        assertEquals("Cuenta nómina", latest.single().name)
        assertEquals(Money(150_000), latest.single().amount.amount)
        assertEquals(1, events)
        job.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `saving a liability inserts it via the liability repository`() = runTest(dispatcher) {
        val liabilityRepo = FakeLiabilityRepository()
        val vm = viewModel(isLiability = true, liabilityRepository = liabilityRepo, idProvider = { "liab-1" })
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onGroupSelect(Liability.LiabilityGroup.CARD.name)
        vm.onNameChange("Visa")
        vm.onAmountChange("250,00")
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()

        var latest: List<Liability> = emptyList()
        val collectJob = launch { liabilityRepo.observeAll().collect { latest = it } }
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals(1, latest.size)
        assertEquals("liab-1", latest.single().id)
        assertEquals(Liability.LiabilityGroup.CARD, latest.single().group)
        assertEquals("Visa", latest.single().name)
        assertEquals(Money(25_000), latest.single().amount.amount)
        job.cancel()
    }

    @Test
    fun `saving an asset trims surrounding whitespace from the name`() = runTest(dispatcher) {
        val assetRepo = FakeAssetRepository()
        val vm = viewModel(isLiability = false, assetRepository = assetRepo)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onGroupSelect(Asset.AssetGroup.BANK.name)
        // Keyboard autocomplete commonly leaves a trailing space after the last word.
        vm.onNameChange("  Cuenta corriente ")
        vm.onAmountChange("100,00")
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()

        var latest: List<Asset> = emptyList()
        val collectJob = launch { assetRepo.observeAll().collect { latest = it } }
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals("Cuenta corriente", latest.single().name)
        job.cancel()
    }

    @Test
    fun `saving a liability trims surrounding whitespace from the name`() = runTest(dispatcher) {
        val liabilityRepo = FakeLiabilityRepository()
        val vm = viewModel(isLiability = true, liabilityRepository = liabilityRepo)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onGroupSelect(Liability.LiabilityGroup.LOAN.name)
        vm.onNameChange("Préstamo coche ")
        vm.onAmountChange("8.200,00")
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()

        var latest: List<Liability> = emptyList()
        val collectJob = launch { liabilityRepo.observeAll().collect { latest = it } }
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals("Préstamo coche", latest.single().name)
        job.cancel()
    }

    /** Extracts the EUR minor units embedded in [eurHint]'s formatted string for assertion. */
    private fun parseEurHintMoney(eurHint: String?): Money {
        requireNotNull(eurHint)
        val numeric = eurHint.substringAfter("≈ ").substringBefore(" €")
        val parts = numeric.split(",")
        val intPart = parts[0].replace(".", "")
        val fracPart = parts.getOrElse(1) { "00" }
        return Money((intPart + fracPart).toLong())
    }
}
