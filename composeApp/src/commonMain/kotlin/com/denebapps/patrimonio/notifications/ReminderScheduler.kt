package com.denebapps.patrimonio.notifications

import com.denebapps.patrimonio.data.platform.PlatformContext
import kotlinx.datetime.LocalDateTime

/** Every renewal notification id starts with this, so a platform can find and replace its own. */
const val RENEWAL_NOTIFICATION_ID_PREFIX = "renewal-"

/** A local notification to show at [fireAt] (device-local time). */
data class LocalNotification(val id: String, val fireAt: LocalDateTime, val title: String, val body: String)

/** Platform scheduler for local notifications that fire without the app running. */
interface ReminderScheduler {
    /** Cancels every renewal notification still pending and schedules [notifications] instead. */
    suspend fun replaceAll(notifications: List<LocalNotification>)
}

/** Android: WorkManager one-time work per notification. iOS: `UNCalendarNotificationTrigger`s. */
expect fun createReminderScheduler(context: PlatformContext): ReminderScheduler
