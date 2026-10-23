package com.riskauditor.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts monitoring after a reboot or after this app is updated.
 *
 * `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED` are both still
 * delivered to manifest-declared receivers on modern Android — unlike the
 * package-added broadcast this app also wants — which is why this one lives in
 * the manifest and [PackageEventReceiver] does not.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // The periodic sweep is always safe to re-schedule: KEEP means an
                // existing schedule is left alone.
                runCatching { PeriodicSweepWorker.schedule(context) }

                // The foreground service is only restarted if the user opted in.
                if (monitoringEnabled?.invoke(context) == true) {
                    runCatching { MonitorService.start(context) }
                }
            }
        }
    }

    companion object {
        /** Supplied by the application; reads the user's opt-in preference. */
        @Volatile
        var monitoringEnabled: ((Context) -> Boolean)? = null
    }
}
