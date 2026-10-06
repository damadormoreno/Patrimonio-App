package com.denebapps.patrimonio.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.denebapps.patrimonio.data.platform.PlatformContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.util.concurrent.TimeUnit

actual fun createReminderScheduler(context: PlatformContext): ReminderScheduler =
    WorkManagerReminderScheduler(context.value as Context)

/**
 * One delayed [RenewalReminderWorker] per notification, all under [RENEWAL_WORK_TAG]. WorkManager
 * persists them across process death and reboots, so no boot receiver is needed. Delays are computed
 * from the current zone; a later zone change is corrected the next time the app re-plans.
 */
internal class WorkManagerReminderScheduler(
    private val context: Context,
    private val clock: Clock = Clock.System,
    private val zoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : ReminderScheduler {
    override suspend fun replaceAll(notifications: List<LocalNotification>) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(RENEWAL_WORK_TAG).await()
        val now = clock.now()
        notifications.forEach { notification ->
            val delayMillis = (notification.fireAt.toInstant(zoneProvider()) - now).inWholeMilliseconds
            val request = OneTimeWorkRequestBuilder<RenewalReminderWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        RenewalReminderWorker.KEY_ID to notification.id,
                        RenewalReminderWorker.KEY_TITLE to notification.title,
                        RenewalReminderWorker.KEY_BODY to notification.body,
                    ),
                )
                .addTag(RENEWAL_WORK_TAG)
                .build()
            workManager.enqueueUniqueWork(notification.id, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

internal const val RENEWAL_WORK_TAG = "renewal-reminder"
