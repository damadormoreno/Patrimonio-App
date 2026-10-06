package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Subscription
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Local time of day every reminder fires at. */
val RENEWAL_REMINDER_TIME = LocalTime(hour = 9, minute = 0)

/** How far ahead reminders are planned. Re-planning on every app start keeps the window rolling. */
const val RENEWAL_REMINDER_HORIZON_DAYS = 60

/** Cap on planned reminders: iOS keeps at most 64 pending local notifications per app. */
const val RENEWAL_REMINDER_LIMIT = 50

/** One local notification: [subscriptionId]'s charge on [chargeDate], announced at [fireAt]. */
data class RenewalReminder(val subscriptionId: String, val chargeDate: LocalDate, val fireAt: LocalDateTime)

/**
 * Reminders for every ACTIVE subscription whose charge falls within [horizonDays] of [now]'s date,
 * fired [leadDays] before the charge at [RENEWAL_REMINDER_TIME]. Reminders whose fire time has
 * already passed are dropped rather than fired late. Soonest first, at most [limit].
 */
fun planRenewalReminders(
    subscriptions: List<Subscription>,
    now: LocalDateTime,
    leadDays: Int,
    horizonDays: Int = RENEWAL_REMINDER_HORIZON_DAYS,
    limit: Int = RENEWAL_REMINDER_LIMIT,
): List<RenewalReminder> {
    require(leadDays >= 0) { "leadDays must not be negative, was $leadDays" }
    val today = now.date
    val horizonEnd = today.plus(horizonDays, DateTimeUnit.DAY)
    return subscriptions
        .filter { it.active }
        .flatMap { subscription ->
            chargeDatesBetween(subscription, from = today, until = horizonEnd).mapNotNull { chargeDate ->
                val fireAt = chargeDate.minus(leadDays, DateTimeUnit.DAY).atTime(RENEWAL_REMINDER_TIME)
                if (fireAt > now) RenewalReminder(subscription.id, chargeDate, fireAt) else null
            }
        }
        .sortedWith(compareBy({ it.fireAt }, { it.subscriptionId }))
        .take(limit)
}

/** Charge dates in `[from, until]`, each computed from the anchor via [nextChargeDate]. */
private fun chargeDatesBetween(subscription: Subscription, from: LocalDate, until: LocalDate): List<LocalDate> {
    val dates = mutableListOf<LocalDate>()
    var cursor = from
    while (true) {
        val next = nextChargeDate(subscription.firstChargeDate, subscription.cycle, cursor)
        if (next > until) return dates
        dates += next
        cursor = next.plus(1, DateTimeUnit.DAY)
    }
}
