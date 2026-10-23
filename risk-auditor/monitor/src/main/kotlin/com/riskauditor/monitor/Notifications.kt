package com.riskauditor.monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Posts the notifications [InstallMonitor] decides on.
 *
 * Handles the two things that silently break notifications on modern Android:
 *
 *  - **The channel must exist first.** From API 26 a notification posted to a
 *    channel that was never created is dropped with no error at all.
 *  - **POST_NOTIFICATIONS must be granted.** From API 33 it is a runtime
 *    permission. Posting without it also fails silently, so [canPost] is checked
 *    and reported rather than assumed.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            InstallMonitor.CHANNEL_ID,
            InstallMonitor.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Warns you when a newly installed app can do a lot of harm."
            enableVibration(true)
        }

        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        system?.createNotificationChannel(channel)
    }

    /** False when the user has not granted POST_NOTIFICATIONS on API 33+. */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return manager.areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled()
    }

    /**
     * @param detailIntent what tapping the notification opens. Supplied by the
     *        app module so this module needs no knowledge of the UI.
     * @return true if the notification was posted.
     */
    fun post(spec: NotificationSpec, detailIntent: Intent, smallIconRes: Int): Boolean {
        if (!canPost()) return false
        ensureChannel()

        val pending = PendingIntent.getActivity(
            context,
            spec.id,
            detailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            .setPriority(
                if (spec.priorityHigh) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        return try {
            manager.notify(spec.id, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
