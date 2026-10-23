package com.riskauditor.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.riskauditor.model.ModelJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Headless debug entry point. **No layout, no UI** — this run of the project is
 * engine-only, and this activity exists so the engine can be driven and
 * inspected on a real device before any screens exist.
 *
 * It runs a scan, writes the full JSON to the app's files directory, prints a
 * summary to logcat, and finishes.
 *
 * ```
 * # default scan
 * adb shell am start -n com.riskauditor.app/.DebugDumpActivity
 *
 * # include system apps and compute APK hashes
 * adb shell am start -n com.riskauditor.app/.DebugDumpActivity \
 *     --ez include_system true --ez compute_hashes true
 *
 * # grant consent for fingerprint lookups for this run only
 * adb shell am start -n com.riskauditor.app/.DebugDumpActivity --ez consent true
 *
 * adb logcat -s RiskAuditorDump
 * adb shell run-as com.riskauditor.app cat files/scan.json > scan.json
 * ```
 */
class DebugDumpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val includeSystem = intent?.getBooleanExtra(EXTRA_INCLUDE_SYSTEM, false) ?: false
        val computeHashes = intent?.getBooleanExtra(EXTRA_COMPUTE_HASHES, false) ?: false
        val consentExtra = intent?.getBooleanExtra(EXTRA_CONSENT, false) ?: false

        val facade = AuditorFacade(applicationContext)

        // Consent is still explicit: the extra simply stands in for the dialog
        // that a UI build would show. It is never granted by default.
        if (consentExtra) {
            facade.consent.grant(System.currentTimeMillis())
            Log.w(TAG, "fingerprint lookup consent granted for this run via intent extra")
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val started = System.currentTimeMillis()
                val outcome = facade.scan(
                    ScanOptions(
                        includeSystemApps = includeSystem,
                        computeHashes = computeHashes
                    )
                )
                val elapsed = System.currentTimeMillis() - started

                val json = ModelJson.pretty.encodeToString(outcome.scan)
                val out = File(filesDir, "scan.json")
                out.writeText(json)

                val reportJson = ModelJson.pretty.encodeToString(outcome.report)
                File(filesDir, "report.json").writeText(reportJson)

                Log.i(TAG, "─".repeat(60))
                Log.i(TAG, "scanned ${outcome.scan.apps.size} apps in ${elapsed}ms")
                Log.i(TAG, outcome.report.headline)
                Log.i(TAG, "bands: ${outcome.report.bandCounts}")
                Log.i(TAG, "skipped: ${outcome.scan.skipped.size}")
                Log.i(TAG, "diff alerts: ${outcome.alerts.size}")
                Log.i(TAG, "─".repeat(60))

                outcome.report.ranked.take(15).forEach { app ->
                    Log.i(
                        TAG,
                        "%3d  %-42s %3d  %-8s %s".format(
                            app.rank,
                            app.label.take(42),
                            app.score,
                            app.band.name,
                            app.topReason.orEmpty().take(60)
                        )
                    )
                }

                Log.i(TAG, "─".repeat(60))
                outcome.report.categoryRollups.forEach { Log.i(TAG, "  ${it.sentence}") }
                Log.i(TAG, "─".repeat(60))
                Log.i(TAG, "wrote ${out.absolutePath} (${out.length()} bytes)")
            } catch (t: Throwable) {
                Log.e(TAG, "scan failed", t)
            } finally {
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "RiskAuditorDump"
        const val EXTRA_INCLUDE_SYSTEM = "include_system"
        const val EXTRA_COMPUTE_HASHES = "compute_hashes"
        const val EXTRA_CONSENT = "consent"
    }
}
