package com.denebapps.patrimonio.notifications

import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.calc.RenewalReminder
import com.denebapps.patrimonio.domain.calc.planRenewalReminders
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.SubscriptionRepository
import com.denebapps.patrimonio.ui.components.formatDayMonth
import com.denebapps.patrimonio.ui.components.formatMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Keeps the platform's pending renewal notifications in line with the subscriptions and the
 * reminder setting: every change re-plans from "now" and replaces the whole set (an empty set when
 * reminders are off). Runs while the app's UI is alive; reminders already scheduled keep firing
 * after the app closes, and opening the app rolls the planning window forward.
 */
class RenewalReminderSync(
    private val subscriptionRepository: SubscriptionRepository,
    private val preferencesRepository: PreferencesRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
) {
    suspend fun run() {
        combine(
            subscriptionRepository.observeAll(),
            preferencesRepository.observeRenewalReminders(),
        ) { subscriptions, settings ->
            if (!settings.enabled) return@combine emptyList()
            val byId = subscriptions.associateBy { it.id }
            val now = clock.now().toLocalDateTime(zoneProvider())
            planRenewalReminders(subscriptions, now, settings.leadDays).map { reminder ->
                renewalNotification(byId.getValue(reminder.subscriptionId), reminder, settings.leadDays)
            }
        }
            .distinctUntilChanged()
            .collectLatest { notifications ->
                try {
                    scheduler.replaceAll(notifications)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLogger.error("RenewalReminderSync", "Failed to schedule renewal reminders", error)
                }
            }
    }
}

/** "Netflix se cobra mañana" / "12,99 € · 14 oct". The id is stable per subscription and charge. */
internal fun renewalNotification(
    subscription: Subscription,
    reminder: RenewalReminder,
    leadDays: Int,
): LocalNotification {
    val amount = formatMoney(subscription.amount.amount, subscription.amount.currency)
    return LocalNotification(
        id = "$RENEWAL_NOTIFICATION_ID_PREFIX${subscription.id}-${reminder.chargeDate}",
        fireAt = reminder.fireAt,
        title = "${subscription.name} se cobra ${whenLabel(leadDays)}",
        body = "$amount · ${formatDayMonth(reminder.chargeDate)}",
    )
}

private fun whenLabel(leadDays: Int): String = when (leadDays) {
    0 -> "hoy"
    1 -> "mañana"
    else -> "en $leadDays días"
}
