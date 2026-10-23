package com.riskauditor.monitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The safety net behind [PackageEventReceiver].
 *
 * Package broadcasts are not reliably delivered when the app has no running
 * component, and OEM power management makes that worse. A periodic diff of the
 * stored snapshot against the live device catches anything the receiver missed,
 * which turns "sometimes notifies" into "notifies, possibly a few hours late".
 *
 * The actual scan is injected rather than implemented here: this module has no
 * dependency on `:inventory` or `:app`, so the worker calls back into whatever
 * the application set up.
 */
class PeriodicSweepWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val sweep = sweepAction
        if (sweep == null) {
            // Nothing wired yet (e.g. the process was restarted for the worker
            // alone). Retry rather than reporting success for work not done.
            Result.retry()
        } else {
            sweep.invoke(applicationContext)
            Result.success()
        }
    } catch (t: Throwable) {
        Result.retry()
    }

    companion object {
        const val WORK_NAME = "risk_auditor_periodic_sweep"

        /**
         * Set by the application on startup. A plain function reference keeps
         * this module free of a DI framework and of any dependency on the
         * scanning modules.
         */
        @Volatile
        var sweepAction: (suspend (Context) -> Unit)? = null

        /**
         * WorkManager's minimum period is 15 minutes; anything shorter is
         * silently clamped. Six hours is used because a missed install surfacing
         * within a few hours is enough, and a more frequent full scan would be a
         * noticeable battery cost for a tool that is not a live antivirus.
         */
        fun schedule(context: Context, repeatHours: Long = 6) {
            val request = PeriodicWorkRequestBuilder<PeriodicSweepWorker>(
                repeatHours, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)  // scanning is offline
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
