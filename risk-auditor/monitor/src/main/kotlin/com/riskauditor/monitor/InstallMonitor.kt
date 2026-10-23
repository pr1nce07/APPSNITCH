package com.riskauditor.monitor

import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.RiskBand
import com.riskauditor.model.RiskResult
import com.riskauditor.model.Severity
import com.riskauditor.diff.DiffAlert

/** A notification the app wants to post. Pure data, so it is testable without Android. */
data class NotificationSpec(
    val id: Int,
    val channelId: String,
    val title: String,
    val body: String,
    val packageName: String,
    val priorityHigh: Boolean
)

/** What triggered a check. */
enum class MonitorEvent { INSTALLED, UPDATED, PERIODIC_SWEEP }

/**
 * Decides whether a newly installed or updated app is worth interrupting the
 * user about (PS-01 should-have 09 and 10).
 *
 * Pure logic, deliberately separated from the receiver and the
 * `NotificationManager` call, because the interesting part — *when do we
 * notify* — is exactly the part that is painful to test on a device.
 *
 * The threshold is High or Critical. Notifying on Medium would fire for a large
 * share of ordinary apps on a first sweep, and a notification channel people mute
 * protects nobody.
 */
object InstallMonitor {

    const val CHANNEL_ID = "risk_alerts"
    const val CHANNEL_NAME = "Risky app alerts"

    fun shouldNotify(risk: RiskResult): Boolean = risk.band.requiresMinimumReasons

    fun specFor(snapshot: AppSnapshot, risk: RiskResult, event: MonitorEvent): NotificationSpec? {
        if (!shouldNotify(risk)) return null

        val verb = when (event) {
            MonitorEvent.INSTALLED -> "was just installed"
            MonitorEvent.UPDATED -> "was just updated"
            MonitorEvent.PERIODIC_SWEEP -> "is on your phone"
        }

        val title = when (risk.band) {
            RiskBand.CRITICAL -> "${snapshot.app.label} $verb and looks dangerous"
            else -> "${snapshot.app.label} $verb and can do a lot"
        }

        // The top reason is already the highest-scoring one, so the notification
        // leads with the strongest thing we can truthfully say.
        val body = risk.topReason?.headline
            ?: "Tap to see what this app can do."

        return NotificationSpec(
            id = notificationId(snapshot.packageName),
            channelId = CHANNEL_ID,
            title = title,
            body = body,
            packageName = snapshot.packageName,
            priorityHigh = risk.band == RiskBand.CRITICAL
        )
    }

    fun specFor(alert: DiffAlert): NotificationSpec = NotificationSpec(
        id = notificationId(alert.packageName + ":diff"),
        channelId = CHANNEL_ID,
        title = alert.headline,
        body = alert.detail,
        packageName = alert.packageName,
        priorityHigh = alert.severity == Severity.CRITICAL
    )

    /**
     * Stable per-package notification id, so a second alert about the same app
     * replaces the first rather than stacking up.
     */
    fun notificationId(key: String): Int = key.hashCode() and 0x7FFFFFFF
}
