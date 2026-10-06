package com.denebapps.patrimonio.ui.screens.subscriptions

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeSubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionSheetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeSubscriptionRepository()
    private val assets = FakeAssetRepository(
        listOf(Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(1), Currency.EUR))),
    )

    private val netflix = Subscription(
        id = "sub-1",
        name = "Netflix",
        amount = CurrencyAmount(Money(1_299), Currency.EUR),
        cycle = BillingCycle.MONTHLY,
        firstChargeDate = LocalDate(2026, 1, 31),
        paidFromAssetId = "a1",
        active = true,
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(subscriptionId: String? = null) = SubscriptionSheetViewModel(
        subscriptionRepository = repository,
        assetRepository = assets,
        clock = fixedClock("2026-10-06T22:30:00Z"),
        zoneProvider = { TimeZone.UTC },
        subscriptionId = subscriptionId,
        idProvider = { "new-id" },
    )

    private fun TestScope.collect(viewModel: SubscriptionSheetViewModel) = launch { viewModel.state.collect {} }

    @Test
    fun `a new subscription starts monthly in EUR charged today`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val job = collect(viewModel)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isEdit)
        assertTrue(state.loaded)
        assertEquals(BillingCycle.MONTHLY, state.cycle)
        assertEquals(Currency.EUR, state.currency)
        assertEquals(LocalDate(2026, 10, 6), state.firstChargeDate)
        assertEquals(listOf(PayingAssetOptionUi("a1", "Cuenta")), state.assetOptions)
        assertFalse(state.canSave)
        job.cancel()
    }

    @Test
    fun `saving a new subscription trims the name and closes the sheet`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val job = collect(viewModel)
        viewModel.onNameChange("  Spotify ")
        viewModel.onAmountChange("10,99")
        viewModel.onCycleChange(BillingCycle.YEARLY)
        viewModel.onPaidFromChange("a1")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canSave)

        viewModel.onSave()
        viewModel.navigateBack.first()

        assertEquals(
            Subscription(
                id = "new-id",
                name = "Spotify",
                amount = CurrencyAmount(Money(1_099), Currency.EUR),
                cycle = BillingCycle.YEARLY,
                firstChargeDate = LocalDate(2026, 10, 6),
                paidFromAssetId = "a1",
                active = true,
            ),
            repository.current.single(),
        )
        job.cancel()
    }

    @Test
    fun `invalid input cannot be saved`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val job = collect(viewModel)
        viewModel.onNameChange("   ")
        viewModel.onAmountChange("10")
        advanceUntilIdle()
        assertFalse(viewModel.state.value.canSave)

        viewModel.onSave()
        advanceUntilIdle()

        assertTrue(repository.current.isEmpty())
        job.cancel()
    }

    @Test
    fun `editing loads the stored values and updates in place`() = runTest(dispatcher) {
        repository.insert(netflix)
        val viewModel = viewModel("sub-1")
        val job = collect(viewModel)
        advanceUntilIdle()

        val loaded = viewModel.state.value
        assertTrue(loaded.isEdit)
        assertEquals("Netflix", loaded.name)
        assertEquals("12,99", loaded.amountText)
        assertEquals(LocalDate(2026, 1, 31), loaded.firstChargeDate)
        assertEquals("a1", loaded.paidFromAssetId)

        viewModel.onActiveChange(false)
        viewModel.onSave()
        viewModel.navigateBack.first()

        assertEquals(listOf(netflix.copy(active = false)), repository.current)
        job.cancel()
    }

    @Test
    fun `a subscription that no longer exists closes the sheet`() = runTest(dispatcher) {
        val viewModel = viewModel("gone")

        viewModel.navigateBack.first()

        assertFalse(viewModel.state.value.loaded)
    }

    @Test
    fun `delete removes the subscription and closes the sheet`() = runTest(dispatcher) {
        repository.insert(netflix)
        val viewModel = viewModel("sub-1")
        advanceUntilIdle()

        viewModel.onDelete()
        viewModel.navigateBack.first()

        assertTrue(repository.current.isEmpty())
    }

    @Test
    fun `a failed save keeps the sheet open with an error`() = runTest(dispatcher) {
        repository.failure = IllegalStateException("db closed")
        val viewModel = viewModel()
        val job = collect(viewModel)
        viewModel.onNameChange("Spotify")
        viewModel.onAmountChange("10,99")
        advanceUntilIdle()

        viewModel.onSave()
        advanceUntilIdle()

        assertNotNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.state.value.canSave)
        job.cancel()
    }
}
