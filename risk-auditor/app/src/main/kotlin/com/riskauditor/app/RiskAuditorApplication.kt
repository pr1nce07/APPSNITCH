package com.riskauditor.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.riskauditor.hashintel.ConsentStore
import com.riskauditor.monitor.BootReceiver
import com.riskauditor.monitor.InstallMonitor
import com.riskauditor.monitor.MonitorEvent
import com.riskauditor.monitor.MonitorService
import com.riskauditor.monitor.Notifier
import com.riskauditor.monitor.PeriodicSweepWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** SharedPreferences-backed consent, so the choice survives a restart. */
class SharedPrefsConsentStore(context: Context) : ConsentStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isGranted(): Boolean = prefs.getBoolean(KEY_GRANTED, false)

    override fun setGranted(granted: Boolean) {
        prefs.edit().putBoolean(KEY_GRANTED, granted).apply()
    }

    override fun grantedAtMillis(): Long? =
        prefs.getLong(KEY_GRANTED_AT, 0L).takeIf { it > 0L }

    override fun setGrantedAt(millis: Long?) {
        prefs.edit().apply {
            if (millis == null) remove(KEY_GRANTED_AT) else putLong(KEY_GRANTED_AT, millis)
        }.apply()
    }

    companion object {
        private const val PREFS = "risk_auditor_prefs"
        private const val KEY_GRANTED = "hash_lookup_consent"
        private const val KEY_GRANTED_AT = "hash_lookup_consent_at"
        const val KEY_MONITORING = "monitoring_enabled"

        fun monitoringEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MONITORING, false)
    }
}

/**
 * Wires the monitoring callbacks that `:monitor` deliberately leaves open.
 *
 * `:monitor` has no dependency on `:inventory` or `:scoring`, so it cannot scan
 * anything itself. The application supplies the behaviour here, which keeps the
 * module graph acyclic and lets `:monitor`'s own logic be tested with no
 * scanning stack at all.
 */
class RiskAuditorApplication : Application() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        MonitorService.smallIconRes = android.R.drawable.ic_dialog_alert

        BootReceiver.monitoringEnabled = { ctx ->
            SharedPrefsConsentStore.monitoringEnabled(ctx)
        }

        // Called when a package is installed or updated while the service runs.
        MonitorService.eventHandler = { ctx, packageName, event ->
            scope.launch { handlePackageEvent(ctx, packageName, event) }
        }

        // The WorkManager safety net: a full rescan whose diff catches anything
        // the broadcast receiver missed.
        PeriodicSweepWorker.sweepAction = { ctx ->
            val facade = AuditorFacade(ctx)
            val outcome = facade.scan(ScanOptions())
            val notifier = Notifier(ctx)
            outcome.alerts.forEach { alert ->
                val spec = InstallMonitor.specFor(alert)
                notifier.post(spec, detailIntentFor(ctx, alert.packageName), MonitorService.smallIconRes)
            }
        }

        PeriodicSweepWorker.schedule(this)
    }

    private suspend fun handlePackageEvent(
        context: Context,
        packageName: String,
        event: MonitorEvent
    ) {
        try {
            val scored = AuditorFacade(context).scanOne(packageName) ?: return
            val spec = InstallMonitor.specFor(scored.snapshot, scored.risk, event) ?: return
            Notifier(context).post(
                spec,
                detailIntentFor(context, packageName),
                MonitorService.smallIconRes
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not evaluate $packageName after $event", t)
        }
    }

    /**
     * What a notification opens. With no UI in this run of the project it
     * re-runs the headless dump for that package; a UI build would deep-link to
     * the detail screen instead.
     */
    private fun detailIntentFor(context: Context, packageName: String) =
        android.content.Intent(context, DebugDumpActivity::class.java)
            .putExtra("package_name", packageName)

    companion object {
        private const val TAG = "RiskAuditorApp"
    }
}
