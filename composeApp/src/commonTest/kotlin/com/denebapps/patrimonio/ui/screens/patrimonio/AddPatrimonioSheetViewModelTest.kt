package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.calc.AccountUsage
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeLiabilityRepository
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
        editingItemId: String? = null,
        goals: FakeSavingsGoalRepository = FakeSavingsGoalRepository(),
        accountGroups: FakeAccountGroupRepository = FakeAccountGroupRepository(),
    ) = AddPatrimonioSheetViewModel(
        assetRepository = assetRepository,
        liabilityRepository = liabilityRepository,
        fxRepository = fxRepository,
        savingsGoalRepository = goals,
        accountGroupRepository = accountGroups,
        idProvider = idProvider,
        initialIsLiability = isLiability,
        initialGroupId = groupId,
        editingItemId = editingItemId,
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
    fun `save is disabled when the value does not parse, and a zero balance is valid`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onGroupSelect(Asset.AssetGroup.BANK.name)
        vm.onNameChange("Cuenta nómina")
        vm.onAmountChange("12,3,4")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSave)

        // An emptied account or a paid-off loan is still worth keeping.
        vm.onAmountChange("0,00")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
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
    fun `editing an asset loads it, keeps its kind and updates it in place`() = runTest(dispatcher) {
        val assets = FakeAssetRepository(listOf(asset("a1", subtitle = "IBAN ES12")))
        val vm = viewModel(assetRepository = assets, editingItemId = "a1")
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val loaded = vm.state.value
        assertTrue(loaded.isEditing)
        assertEquals("Nómina", loaded.name)
        assertEquals("1234,50", loaded.amountText)
        assertEquals(Currency.USD, loaded.currency)
        assertEquals(Asset.AssetGroup.BANK.name, loaded.selectedGroupId)
        assertNull(loaded.deleteWarning)

        vm.onModeChange(true)
        vm.onGroupSelect(Asset.AssetGroup.INVEST.name)
        vm.onNameChange(" Broker ")
        vm.onAmountChange("0")
        vm.onCurrencyChange(Currency.EUR)
        advanceUntilIdle()
        assertFalse(vm.state.value.isLiability)
        vm.onSave()
        advanceUntilIdle()

        assertEquals(
            listOf(
                Asset(
                    id = "a1",
                    group = Asset.AssetGroup.INVEST,
                    name = "Broker",
                    subtitle = "IBAN ES12",
                    amount = CurrencyAmount(Money.ZERO, Currency.EUR),
                ),
            ),
            assets.list(),
        )
        assertEquals(1, events)
        job.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `editing a liability updates it and deleting removes it`() = runTest(dispatcher) {
        val loan = Liability(
            id = "l1",
            group = Liability.LiabilityGroup.LOAN,
            name = "Coche",
            subtitle = null,
            amount = CurrencyAmount(Money(500_000), Currency.EUR),
        )
        val liabilities = FakeLiabilityRepository(listOf(loan))
        val vm = viewModel(isLiability = true, liabilityRepository = liabilities, editingItemId = "l1")
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onAmountChange("4.500,00")
        vm.onSave()
        advanceUntilIdle()
        assertEquals(Money(450_000), liabilities.list().single().amount.amount)

        vm.onDelete()
        advanceUntilIdle()
        assertTrue(liabilities.list().isEmpty())
        assertEquals(2, events)
        job.cancel()
        eventsJob.cancel()
    }

    @Test
    fun `deleting an asset warns about the goals and groups it leaves`() = runTest(dispatcher) {
        val vm = viewModel(
            assetRepository = FakeAssetRepository(listOf(asset("a1"))),
            editingItemId = "a1",
            goals = FakeSavingsGoalRepository(listOf(goal("Colchón", setOf("a1")), goal("Viaje", setOf("a1")))),
            accountGroups = FakeAccountGroupRepository(
                listOf(
                    AccountGroup.allAccounts(),
                    AccountGroup("g1", "Ahorro", showBalance = true, sortOrder = 1, memberAssetIds = setOf("a1")),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            "Está en las metas «Colchón» y «Viaje» y en el grupo «Ahorro»; se quitará de ellos.",
            vm.state.value.deleteWarning,
        )
        job.cancel()
    }

    @Test
    fun `the delete warning reads well for a single goal or group`() {
        assertEquals(
            "Está en la meta «Colchón»; se quitará de él.",
            deleteWarningFor(AccountUsage(goalNames = listOf("Colchón"))),
        )
        assertEquals(
            "Está en los grupos «A», «B» y «C»; se quitará de ellos.",
            deleteWarningFor(AccountUsage(groupNames = listOf("A", "B", "C"))),
        )
        assertNull(deleteWarningFor(AccountUsage()))
    }

    @Test
    fun `editing an item that no longer exists just goes back`() = runTest(dispatcher) {
        val vm = viewModel(editingItemId = "gone")
        var events = 0
        val eventsJob = launch { vm.navigateBack.collect { events++ } }
        advanceUntilIdle()

        assertEquals(1, events)
        eventsJob.cancel()
    }

    private fun asset(id: String, subtitle: String? = null) = Asset(
        id = id,
        group = Asset.AssetGroup.BANK,
        name = "Nómina",
        subtitle = subtitle,
        amount = CurrencyAmount(Money(123_450), Currency.USD),
    )

    private fun goal(name: String, assetIds: Set<String>) = SavingsGoal(
        id = "goal-$name",
        name = name,
        target = CurrencyAmount(Money(100_000), Currency.EUR),
        targetDate = null,
        linkedAssetIds = assetIds,
        lifecycle = SavingsGoalLifecycle.OPEN,
        progress = Money.ZERO,
    )

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
