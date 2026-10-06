package com.denebapps.patrimonio.ui.screens.subscriptions

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeSubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate(2026, 10, 6)
    private val usdAt90Cents = FxRates(mapOf(Currency.USD to RATE_SCALE * 9 / 10))

    private fun subscription(
        id: String,
        amountMinor: Long,
        cycle: BillingCycle,
        first: LocalDate,
        currency: Currency = Currency.EUR,
        paidFrom: String? = null,
        active: Boolean = true,
    ) = Subscription(id, id, CurrencyAmount(Money(amountMinor), currency), cycle, first, paidFrom, active)

    @Test
    fun `active subscriptions are ordered by next charge and paused ones listed apart`() {
        val state = buildSubscriptionsState(
            subscriptions = listOf(
                subscription("gym", 4_000, BillingCycle.MONTHLY, LocalDate(2026, 1, 20)),
                subscription("netflix", 1_299, BillingCycle.MONTHLY, LocalDate(2026, 1, 7)),
                subscription("press", 999, BillingCycle.MONTHLY, LocalDate(2026, 1, 1), active = false),
            ),
            assetNames = emptyMap(),
            rates = FxRates(emptyMap()),
            today = today,
        )

        assertEquals(listOf("netflix", "gym"), state.upcoming.map { it.id })
        assertEquals(LocalDate(2026, 10, 7), state.upcoming.first().nextCharge)
        assertEquals(1, state.upcoming.first().daysUntilNextCharge)
        assertEquals(listOf("press"), state.paused.map { it.id })
        assertEquals(Money(5_299), state.totals.monthlyEur)
        assertEquals(2, state.totals.activeCount)
    }

    @Test
    fun `rows carry the paying asset name and the monthly cost in EUR`() {
        val state = buildSubscriptionsState(
            subscriptions = listOf(
                subscription("icloud", 12_000, BillingCycle.YEARLY, LocalDate(2026, 3, 1), Currency.USD, "a1"),
            ),
            assetNames = mapOf("a1" to "Cuenta"),
            rates = usdAt90Cents,
            today = today,
        )

        val row = state.upcoming.single()
        assertEquals("Cuenta", row.paidFrom)
        assertEquals(Money(900), row.monthlyEur)
        assertEquals(Money(12_000), row.amount)
        assertEquals(Currency.USD, row.currency)
        assertEquals(LocalDate(2027, 3, 1), row.nextCharge)
    }

    @Test
    fun `view model combines repositories and follows changes`() = runTest(dispatcher) {
        val subscriptions = FakeSubscriptionRepository()
        val viewModel = SubscriptionsViewModel(
            subscriptionRepository = subscriptions,
            assetRepository = FakeAssetRepository(
                listOf(Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(1), Currency.EUR))),
            ),
            fxRepository = FakeFxRepository(),
            clock = fixedClock("2026-10-06T10:00:00Z"),
            zoneProvider = { TimeZone.UTC },
        )
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isEmpty)

        subscriptions.insert(
            subscription("netflix", 1_299, BillingCycle.MONTHLY, LocalDate(2026, 1, 7), paidFrom = "a1"),
        )
        advanceUntilIdle()

        val row = viewModel.state.value.upcoming.single()
        assertEquals("Cuenta", row.paidFrom)
        assertEquals(Money(1_299), viewModel.state.value.totals.monthlyEur)
        job.cancel()
    }

    @Test
    fun `labels read naturally`() {
        assertEquals("Hoy", nextChargeLabel(0))
        assertEquals("Mañana", nextChargeLabel(1))
        assertEquals("En 12 días", nextChargeLabel(12))
        assertEquals("1 activa", activeCountLabel(1))
        assertEquals("0 activas", activeCountLabel(0))
        assertEquals("Trimestral", cycleLabel(BillingCycle.QUARTERLY))
    }
}
