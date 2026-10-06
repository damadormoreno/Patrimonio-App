package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Subscription
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenewalRemindersTest {
    private val now = LocalDateTime(2026, 10, 6, 12, 0)

    private fun subscription(id: String, first: LocalDate, cycle: BillingCycle, active: Boolean = true) =
        Subscription(id, id, CurrencyAmount(Money(999), Currency.EUR), cycle, first, null, active)

    @Test
    fun `reminders fire the chosen number of days before each charge at nine`() {
        val plan = planRenewalReminders(
            listOf(subscription("netflix", LocalDate(2026, 1, 20), BillingCycle.MONTHLY)),
            now = now,
            leadDays = 1,
        )

        assertEquals(
            listOf(
                RenewalReminder("netflix", LocalDate(2026, 10, 20), LocalDateTime(2026, 10, 19, 9, 0)),
                RenewalReminder("netflix", LocalDate(2026, 11, 20), LocalDateTime(2026, 11, 19, 9, 0)),
            ),
            plan,
        )
    }

    @Test
    fun `a reminder whose time already passed is dropped, not fired late`() {
        // Charge tomorrow, one day ahead -> today at 9:00, which is before noon.
        val plan = planRenewalReminders(
            listOf(subscription("gym", LocalDate(2026, 9, 7), BillingCycle.MONTHLY)),
            now = now,
            leadDays = 1,
        )

        assertTrue(plan.none { it.chargeDate == LocalDate(2026, 10, 7) })
        assertEquals(LocalDate(2026, 11, 7), plan.first().chargeDate)
    }

    @Test
    fun `same-day reminders fire on the charge date`() {
        val plan = planRenewalReminders(
            listOf(subscription("gym", LocalDate(2026, 9, 7), BillingCycle.MONTHLY)),
            now = now,
            leadDays = 0,
        )

        assertEquals(LocalDateTime(2026, 10, 7, 9, 0), plan.first().fireAt)
    }

    @Test
    fun `paused subscriptions and charges beyond the horizon get no reminder`() {
        val plan = planRenewalReminders(
            listOf(
                subscription("paused", LocalDate(2026, 1, 20), BillingCycle.MONTHLY, active = false),
                subscription("yearly", LocalDate(2026, 3, 1), BillingCycle.YEARLY),
            ),
            now = now,
            leadDays = 1,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `weekly charges produce one reminder per week, soonest first across subscriptions`() {
        val plan = planRenewalReminders(
            listOf(
                subscription("monthly", LocalDate(2026, 1, 9), BillingCycle.MONTHLY),
                subscription("weekly", LocalDate(2026, 10, 8), BillingCycle.WEEKLY),
            ),
            now = now,
            leadDays = 0,
            horizonDays = 14,
        )

        assertEquals(
            listOf("weekly" to 8, "monthly" to 9, "weekly" to 15),
            plan.map { it.subscriptionId to it.chargeDate.dayOfMonth },
        )
    }

    @Test
    fun `the plan is capped`() {
        val plan = planRenewalReminders(
            listOf(subscription("weekly", LocalDate(2026, 10, 8), BillingCycle.WEEKLY)),
            now = now,
            leadDays = 0,
            horizonDays = 365,
            limit = 5,
        )

        assertEquals(5, plan.size)
    }
}
