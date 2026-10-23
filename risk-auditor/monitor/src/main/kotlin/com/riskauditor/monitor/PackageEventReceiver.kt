package com.riskauditor.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Listens for app installs and updates.
 *
 * ## Why this is registered at runtime and not in the manifest
 *
 * From Android 8.0, manifest-declared receivers no longer receive most implicit
 * broadcasts. `ACTION_PACKAGE_ADDED` and friends are *package* broadcasts
 * carrying a `package:` data scheme, and their delivery to manifest-declared
 * receivers has varied across platform versions and OEM power-management layers.
 * Relying on a manifest entry alone produces a feature that works on the
 * developer's phone and silently does nothing on the judging device.
 *
 * So delivery is belt-and-braces, as PS-01 requires:
 *   1. this receiver, registered at runtime from [MonitorService] while it runs;
 *   2. a periodic WorkManager sweep ([PeriodicSweepWorker]) that diffs the store
 *      and catches anything missed while the service was not running;
 *   3. restart on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, which ARE still
 *      delivered to manifest-declared receivers.
 *
 * See docs/LIMITATIONS.md L-08 for what was verified and what was not.
 */
class PackageEventReceiver(
    private val onEvent: (packageName: String, event: MonitorEvent) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return

        // EXTRA_REPLACING distinguishes an update from a fresh install. Without
        // it an update looks like an uninstall immediately followed by an
        // install, and the user gets two notifications for one event.
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED ->
                onEvent(packageName, if (replacing) MonitorEvent.UPDATED else MonitorEvent.INSTALLED)

            Intent.ACTION_PACKAGE_REPLACED ->
                onEvent(packageName, MonitorEvent.UPDATED)

            else -> Unit
        }
    }

    companion object {
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // Required: without the data scheme these actions are never matched.
            addDataScheme("package")
        }
    }
}
