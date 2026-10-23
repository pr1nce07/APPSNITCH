package com.riskauditor.diff

import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.catalog.PermissionCatalog
import com.riskauditor.model.AccessState
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.Category
import com.riskauditor.model.Protection
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.store.SnapshotStore

/** What changed about one app between two scans. */
data class AppDiff(
    val packageName: String,
    val label: String,
    /** Permissions the app did not previously request at all. */
    val newlyDeclared: List<String> = emptyList(),
    /** Permissions it previously requested but did not hold, and now holds. */
    val newlyGranted: List<String> = emptyList(),
    /** Permissions it has stopped requesting. Informational; never an alert. */
    val removed: List<String> = emptyList(),
    /** Special access switched on since the last scan. */
    val newlyEnabledSpecialAccess: List<SpecialAccessKind> = emptyList(),
    val versionChanged: Pair<String?, String?>? = null,
    val installerChanged: Pair<String?, String?>? = null
) {
    val hasAnyChange: Boolean
        get() = newlyDeclared.isNotEmpty() || newlyGranted.isNotEmpty() ||
            removed.isNotEmpty() || newlyEnabledSpecialAccess.isNotEmpty() ||
            versionChanged != null || installerChanged != null
}

/**
 * A change worth interrupting the user about.
 *
 * Deliberately narrower than [AppDiff]: an app gaining `VIBRATE` is a diff but
 * not an alert. Notifying on every change trains people to dismiss the
 * notification, which costs more than the few genuine alerts it would surface.
 */
data class DiffAlert(
    val packageName: String,
    val label: String,
    val severity: Severity,
    val headline: String,
    val detail: String,
    val evidence: List<String>
)

/**
 * Compares the previous scan with the current one (PS-01 should-have 10).
 *
 * Two behaviours matter more than the mechanics:
 *
 *  - **First run never alerts.** On a fresh install every app looks like it has
 *    "just gained" everything it holds. [diffAll] detects the empty store and
 *    returns no alerts while still writing the baseline.
 *  - **Uninstalled apps are cleaned up**, so a package that comes back later is
 *    treated as a new install rather than diffed against a stale record.
 */
class DiffEngine(
    private val catalog: PermissionCatalog = CatalogLoader.default,
    /** Catalog sensitivity at or above which a permission counts as sensitive. */
    private val sensitivityThreshold: Double = 0.5
) {

    /** Compare one app against its previous snapshot. */
    fun diff(previous: AppSnapshot?, current: AppSnapshot): AppDiff? {
        if (previous == null) return null

        val before = previous.permissions.associateBy { it.name }
        val after = current.permissions.associateBy { it.name }

        val newlyDeclared = after.keys.filter { it !in before.keys }.sorted()
        val removed = before.keys.filter { it !in after.keys }.sorted()

        val newlyGranted = after.values
            .filter { it.granted }
            .filter { before[it.name]?.granted == false }
            .map { it.name }
            .sorted()

        val newSpecial = current.specialAccess.entries
            .filter { it.state == AccessState.ENABLED }
            .map { it.kind }
            .filter { previous.specialAccess.stateOf(it) != AccessState.ENABLED }

        val versionChanged = if (previous.app.versionName != current.app.versionName) {
            previous.app.versionName to current.app.versionName
        } else null

        val installerChanged = if (
            previous.app.installer.installingPackage != current.app.installer.installingPackage
        ) {
            previous.app.installer.installingPackage to current.app.installer.installingPackage
        } else null

        val d = AppDiff(
            packageName = current.packageName,
            label = current.app.label,
            newlyDeclared = newlyDeclared,
            newlyGranted = newlyGranted,
            removed = removed,
            newlyEnabledSpecialAccess = newSpecial,
            versionChanged = versionChanged,
            installerChanged = installerChanged
        )

        return if (d.hasAnyChange) d else null
    }

    /**
     * Turn a diff into user-facing alerts, dropping changes not worth a notification.
     */
    fun alertsFor(diff: AppDiff): List<DiffAlert> {
        val alerts = mutableListOf<DiffAlert>()

        if (diff.newlyEnabledSpecialAccess.isNotEmpty()) {
            alerts += DiffAlert(
                packageName = diff.packageName,
                label = diff.label,
                severity = Severity.CRITICAL,
                headline = "${diff.label} was just given powerful access",
                detail = "This app now has access that is normally reserved for system apps. " +
                    "If you did not turn this on yourself, open the app's settings and review it.",
                evidence = diff.newlyEnabledSpecialAccess.map { "special_access=${it.name}" }
            )
        }

        val sensitiveGranted = diff.newlyGranted.filter(::isSensitive)
        if (sensitiveGranted.isNotEmpty()) {
            alerts += DiffAlert(
                packageName = diff.packageName,
                label = diff.label,
                severity = Severity.HIGH,
                headline = "${diff.label} can now do more than before",
                detail = "It has been given access it did not have at the last check: " +
                    describe(sensitiveGranted) + ".",
                evidence = sensitiveGranted
            )
        }

        val sensitiveDeclared = diff.newlyDeclared.filter(::isSensitive)
        if (sensitiveDeclared.isNotEmpty()) {
            alerts += DiffAlert(
                packageName = diff.packageName,
                label = diff.label,
                severity = Severity.MEDIUM,
                headline = "An update to ${diff.label} asks for new access",
                detail = "The latest version asks for " + describe(sensitiveDeclared) +
                    ". It does not have this yet.",
                evidence = sensitiveDeclared
            )
        }

        if (diff.installerChanged != null && sensitiveGranted.isEmpty() && sensitiveDeclared.isEmpty()) {
            alerts += DiffAlert(
                packageName = diff.packageName,
                label = diff.label,
                severity = Severity.MEDIUM,
                headline = "${diff.label} was reinstalled from somewhere else",
                detail = "This app now reports a different install source than before. " +
                    "That can simply mean you reinstalled it, but it is worth knowing.",
                evidence = listOf(
                    "before=${diff.installerChanged.first ?: "none"}",
                    "after=${diff.installerChanged.second ?: "none"}"
                )
            )
        }

        return alerts
    }

    /**
     * Diff a whole scan against the store, then update the store.
     *
     * @return alerts to raise; always empty on the first ever run.
     */
    fun diffAll(store: SnapshotStore, current: List<AppSnapshot>): List<DiffAlert> {
        val baseline = store.isEmpty()

        val alerts = if (baseline) {
            emptyList()
        } else {
            current.flatMap { snapshot ->
                diff(store.get(snapshot.packageName), snapshot)?.let { alertsFor(it) } ?: emptyList()
            }
        }

        store.putAll(current)
        store.retainOnly(current.map { it.packageName }.toSet())
        return alerts
    }

    /** Sensitive = dangerous or special protection, or a catalog category that carries weight. */
    private fun isSensitive(permission: String): Boolean {
        val entry = catalog.find(permission) ?: return false
        if (entry.protection == Protection.DANGEROUS || entry.protection == Protection.SPECIAL) return true
        if (entry.special) return true
        if (entry.category == Category.UNCATEGORIZED) return false
        return entry.sensitivity >= sensitivityThreshold
    }

    /** Join permission plain-text into one readable clause. */
    private fun describe(permissions: List<String>): String {
        val phrases = permissions
            .mapNotNull { catalog.find(it)?.plainText }
            .map { it.removePrefix("Lets the app ").removeSuffix(".") }
            .distinct()

        if (phrases.isEmpty()) return "access we could not describe"
        return when (phrases.size) {
            1 -> phrases[0]
            2 -> "${phrases[0]} and ${phrases[1]}"
            else -> phrases.dropLast(1).joinToString(", ") + " and " + phrases.last()
        }
    }
}
