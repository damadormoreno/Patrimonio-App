package com.denebapps.patrimonio.notifications

import com.denebapps.patrimonio.data.platform.PlatformContext
import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Suppress("UNUSED_PARAMETER")
actual fun createReminderScheduler(context: PlatformContext): ReminderScheduler = UserNotificationsReminderScheduler()

/**
 * Calendar-triggered local notifications: iOS fires them with the app closed, so nothing runs in
 * the background. iOS keeps at most 64 pending requests per app, hence the planner's cap.
 */
internal class UserNotificationsReminderScheduler : ReminderScheduler {
    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun replaceAll(notifications: List<LocalNotification>) {
        val stale = pendingIdentifiers().filter { it.startsWith(RENEWAL_NOTIFICATION_ID_PREFIX) }
        if (stale.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(stale)
        notifications.forEach { notification ->
            val content = UNMutableNotificationContent()
            content.setTitle(notification.title)
            content.setBody(notification.body)
            content.setSound(UNNotificationSound.defaultSound())
            val fireAt = NSDateComponents()
            fireAt.year = notification.fireAt.year.toLong()
            fireAt.month = notification.fireAt.monthNumber.toLong()
            fireAt.day = notification.fireAt.dayOfMonth.toLong()
            fireAt.hour = notification.fireAt.hour.toLong()
            fireAt.minute = notification.fireAt.minute.toLong()
            val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(fireAt, repeats = false)
            center.addNotificationRequest(
                UNNotificationRequest.requestWithIdentifier(notification.id, content, trigger),
                withCompletionHandler = null,
            )
        }
    }

    private suspend fun pendingIdentifiers(): List<String> = suspendCoroutine { continuation ->
        center.getPendingNotificationRequestsWithCompletionHandler { requests ->
            continuation.resume(requests.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier })
        }
    }
}
