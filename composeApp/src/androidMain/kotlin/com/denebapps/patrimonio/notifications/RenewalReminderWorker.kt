package com.denebapps.patrimonio.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.denebapps.patrimonio.R

/** Posts one renewal notification with the text planned in common code. Needs no repository access. */
class RenewalReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
        showRenewalNotification(applicationContext, id, title, inputData.getString(KEY_BODY).orEmpty())
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
    }
}

private const val CHANNEL_ID = "renewals"

/** No-op when the user has revoked notification permission since the reminder was scheduled. */
@SuppressLint("MissingPermission")
private fun showRenewalNotification(context: Context, id: String, title: String, body: String) {
    val manager = NotificationManagerCompat.from(context)
    if (!canPostNotifications(context)) return

    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Renovaciones", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisos antes de cada cargo de tus suscripciones"
        },
    )
    val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_renewal)
        .setContentTitle(title)
        .setContentText(body)
        .setContentIntent(openApp)
        .setAutoCancel(true)
        .build()
    manager.notify(id.hashCode(), notification)
}

internal fun canPostNotifications(context: Context): Boolean {
    val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}
