package com.riskauditor.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Opt-in foreground service that keeps [PackageEventReceiver] registered.
 *
 * A runtime-registered receiver only lives as long as the component that
 * registered it. To catch an install while the user is elsewhere, something must
 * be running — hence a foreground service, which Android requires to show an
 * ongoing notification.
 *
 * **Opt-in, never automatic.** A permanent notification is intrusive, so the
 * user chooses this. With it off, install monitoring still works through
 * [PeriodicSweepWorker], just later.
 */
class MonitorService : Service() {

    private var receiver: PackageEventReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createOngoingChannel()

        val r = PackageEventReceiver { packageName, event ->
            eventHandler?.invoke(applicationContext, packageName, event)
        }

        // RECEIVER_NOT_EXPORTED is required from API 34 for runtime-registered
        // receivers of non-system broadcasts; omitting it throws at registration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, PackageEventReceiver.intentFilter(), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, PackageEventReceiver.intentFilter())
        }
        receiver = r
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildOngoingNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ONGOING_ID, notification)
        }

        // START_STICKY: if the system kills us for memory, come back. The whole
        // point of this service is to be running when an install happens.
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOngoingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ONGOING_CHANNEL_ID,
            "App monitoring",
            // LOW so the ongoing notice is silent and collapsed. The alerts
            // themselves use the separate high-importance channel.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while this app is watching for newly installed apps."
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.createNotificationChannel(channel)
    }

    private fun buildOngoingNotification(): Notification =
        NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setContentTitle("Watching for new apps")
            .setContentText("Checks each newly installed app on this device. Nothing leaves your phone.")
            .setSmallIcon(smallIconRes)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val ONGOING_ID = 1
        const val ONGOING_CHANNEL_ID = "risk_monitor_ongoing"

        /**
         * Wired by the application module. Keeps this module independent of
         * `:inventory`, `:scoring` and the UI.
         */
        @Volatile
        var eventHandler: ((Context, String, MonitorEvent) -> Unit)? = null

        /** Set by the application so the ongoing notification has an icon. */
        @Volatile
        var smallIconRes: Int = android.R.drawable.ic_dialog_info

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
