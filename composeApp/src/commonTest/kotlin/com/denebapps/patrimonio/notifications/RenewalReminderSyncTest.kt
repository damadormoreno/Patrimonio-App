package com.denebapps.patrimonio.notifications

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.domain.repository.RenewalReminderSettings
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.FakeReminderScheduler
import com.denebapps.patrimonio.testing.FakeSubscriptionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RenewalReminderSyncTest {
    private val netflix = Subscription(
        id = "netflix",
        name = "Netflix",
        amount = CurrencyAmount(Money(1_299), Currency.EUR),
        cycle = BillingCycle.MONTHLY,
        firstChargeDate = LocalDate(2026, 1, 20),
        paidFromAssetId = null,
        active = true,
    )

    private val subscriptions = FakeSubscriptionRepository(listOf(netflix))
    private val preferences = FakePreferencesRepository()
    private val scheduler = FakeReminderScheduler()

    private fun TestScope.startSync() = backgroundScope.launch {
        RenewalReminderSync(
            subscriptionRepository = subscriptions,
            preferencesRepository = preferences,
            scheduler = scheduler,
            clock = fixedClock("2026-10-06T10:00:00Z"),
            zoneProvider = { TimeZone.UTC },
        ).run()
    }

    @Test
    fun `reminders stay unscheduled while the setting is off`() = runTest {
        startSync()
        runCurrent()

        assertEquals(listOf(emptyList()), scheduler.calls)
    }

    @Test
    fun `enabling schedules the planned reminders with readable text`() = runTest {
        startSync()
        preferences.setRenewalReminders(RenewalReminderSettings(enabled = true, leadDays = 1))
        runCurrent()

        val first = scheduler.scheduled.first()
        assertEquals("renewal-netflix-2026-10-20", first.id)
        assertEquals(LocalDateTime(2026, 10, 19, 9, 0), first.fireAt)
        assertEquals("Netflix se cobra mañana", first.title)
        assertEquals("12,99 € · 20 oct", first.body)
        assertEquals(2, scheduler.scheduled.size)
    }

    @Test
    fun `subscription changes and disabling re-plan the whole set`() = runTest {
        preferences.setRenewalReminders(RenewalReminderSettings(enabled = true, leadDays = 3))
        startSync()
        runCurrent()
        assertEquals("Netflix se cobra en 3 días", scheduler.scheduled.first().title)

        subscriptions.update(netflix.copy(active = false))
        runCurrent()
        assertTrue(scheduler.scheduled.isEmpty())

        subscriptions.update(netflix)
        preferences.setRenewalReminders(RenewalReminderSettings(enabled = false, leadDays = 3))
        runCurrent()
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `an unchanged plan is not rescheduled`() = runTest {
        preferences.setRenewalReminders(RenewalReminderSettings(enabled = true, leadDays = 1))
        startSync()
        runCurrent()
        val callsBefore = scheduler.calls.size

        // A paused subscription changes the list but not the plan.
        subscriptions.insert(netflix.copy(id = "gym", name = "Gimnasio", active = false))
        runCurrent()

        assertEquals(callsBefore, scheduler.calls.size)
    }

    @Test
    fun `a scheduler failure is logged and later changes still sync`() = runTest {
        scheduler.failure = IllegalStateException("no permission")
        startSync()
        runCurrent()

        scheduler.failure = null
        preferences.setRenewalReminders(RenewalReminderSettings(enabled = true, leadDays = 0))
        runCurrent()

        assertEquals("Netflix se cobra hoy", scheduler.scheduled.first().title)
    }
}
