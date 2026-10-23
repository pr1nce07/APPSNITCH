package com.riskauditor.specialaccess

import com.riskauditor.model.AccessState
import com.riskauditor.model.AppRecord
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.SpecialAccessEntry
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.model.SpecialAccessState

/**
 * Detects special access for one app (PS-01 must-have 04).
 *
 * ## The distinction this class exists to preserve
 *
 * For every kind of access there are three different answers, and collapsing
 * them is the most common way an app like this misleads people:
 *
 *  - **ENABLED** — the access is switched on. We can say "it can read your screen".
 *  - **DECLARED_ONLY** — it asked, the user has not granted it. We must say
 *    "it has asked for this", not "it can do this".
 *  - **UNKNOWN** — we could not read the setting on this device. We must say
 *    "we could not check", and NOT quietly report it as denied.
 *
 * That last one is why [AppOpsResult.UNREADABLE] exists. Reporting "no overlay
 * access" when the truth is "this OEM would not tell us" is worse than saying
 * nothing, because the user acts on it.
 *
 * ## Why accessibility, admins and listeners are read in bulk
 *
 * `Settings.Secure` and `DevicePolicyManager` return device-wide lists. Reading
 * them once per scan and indexing by package is both faster and more consistent
 * than querying per app, which could otherwise observe the state changing
 * mid-scan.
 */
class SpecialAccessDetector(
    private val settings: SettingsSource,
    private val appOps: AppOpsSource,
    private val admins: DeviceAdminSource,
    private val accessibility: AccessibilitySource,
    private val power: PowerSource,
    private val vpn: VpnSource
) {

    /** Device-wide state, read once and reused for every app in a scan. */
    class DeviceState(
        val enabledAccessibility: Map<String, String>,
        val activeAdmins: Map<String, String>,
        val enabledNotificationListeners: Map<String, String>,
        val vpnServicePackages: Set<String>
    )

    fun readDeviceState(): DeviceState = DeviceState(
        enabledAccessibility = accessibility.enabledServices(),
        activeAdmins = admins.activeAdmins(),
        enabledNotificationListeners = parseComponentList(
            settings.secureString(SettingsSource.ENABLED_NOTIFICATION_LISTENERS)
        ),
        vpnServicePackages = vpn.packagesWithVpnService()
    )

    fun detect(
        app: AppRecord,
        permissions: List<PermissionRecord>,
        state: DeviceState = readDeviceState()
    ): SpecialAccessState {
        val declared = permissions.map { it.name }.toSet()
        // Nullable element type: appOpEntry returns null when the permission is
        // not declared at all, and those are filtered out at the end.
        val entries = mutableListOf<SpecialAccessEntry?>()

        // ---- Accessibility: Settings.Secure is the source of truth ----
        state.enabledAccessibility[app.packageName]?.let { component ->
            entries += SpecialAccessEntry(
                SpecialAccessKind.ACCESSIBILITY, AccessState.ENABLED,
                evidence = "settings:${SettingsSource.ENABLED_ACCESSIBILITY_SERVICES}",
                component = component
            )
        } ?: run {
            if ("android.permission.BIND_ACCESSIBILITY_SERVICE" in declared) {
                entries += SpecialAccessEntry(
                    SpecialAccessKind.ACCESSIBILITY, AccessState.DECLARED_ONLY,
                    evidence = "manifest:BIND_ACCESSIBILITY_SERVICE"
                )
            }
        }

        // ---- Device admin ----
        state.activeAdmins[app.packageName]?.let { component ->
            entries += SpecialAccessEntry(
                SpecialAccessKind.DEVICE_ADMIN, AccessState.ENABLED,
                evidence = "devicepolicy:getActiveAdmins", component = component
            )
        } ?: run {
            if ("android.permission.BIND_DEVICE_ADMIN" in declared) {
                entries += SpecialAccessEntry(
                    SpecialAccessKind.DEVICE_ADMIN, AccessState.DECLARED_ONLY,
                    evidence = "manifest:BIND_DEVICE_ADMIN"
                )
            }
        }

        // ---- Notification listener ----
        state.enabledNotificationListeners[app.packageName]?.let { component ->
            entries += SpecialAccessEntry(
                SpecialAccessKind.NOTIFICATION_LISTENER, AccessState.ENABLED,
                evidence = "settings:${SettingsSource.ENABLED_NOTIFICATION_LISTENERS}",
                component = component
            )
        } ?: run {
            if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" in declared) {
                entries += SpecialAccessEntry(
                    SpecialAccessKind.NOTIFICATION_LISTENER, AccessState.DECLARED_ONLY,
                    evidence = "manifest:BIND_NOTIFICATION_LISTENER_SERVICE"
                )
            }
        }

        // ---- AppOps-backed: overlay, install packages, usage stats, all files ----
        entries += appOpEntry(
            app, declared,
            kind = SpecialAccessKind.OVERLAY,
            permission = "android.permission.SYSTEM_ALERT_WINDOW",
            op = AppOpsSource.OPSTR_SYSTEM_ALERT_WINDOW
        )
        entries += appOpEntry(
            app, declared,
            kind = SpecialAccessKind.INSTALL_PACKAGES,
            permission = "android.permission.REQUEST_INSTALL_PACKAGES",
            op = AppOpsSource.OPSTR_REQUEST_INSTALL_PACKAGES
        )
        entries += appOpEntry(
            app, declared,
            kind = SpecialAccessKind.USAGE_STATS,
            permission = "android.permission.PACKAGE_USAGE_STATS",
            op = AppOpsSource.OPSTR_GET_USAGE_STATS
        )
        entries += appOpEntry(
            app, declared,
            kind = SpecialAccessKind.ALL_FILES_ACCESS,
            permission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            op = AppOpsSource.OPSTR_MANAGE_EXTERNAL_STORAGE
        )

        // ---- VPN ----
        // Declaring a VpnService is NOT the same as being the device's active
        // VPN, and there is no public API to ask which app currently holds the
        // VPN without being that app. So this is reported as DECLARED_ONLY and
        // never as ENABLED. See docs/LIMITATIONS.md L-06.
        if (app.packageName in state.vpnServicePackages ||
            "android.permission.BIND_VPN_SERVICE" in declared
        ) {
            entries += SpecialAccessEntry(
                kind = SpecialAccessKind.VPN_SERVICE,
                state = AccessState.DECLARED_ONLY,
                evidence = if (app.packageName in state.vpnServicePackages) {
                    "manifest:VpnService component"
                } else {
                    "manifest:BIND_VPN_SERVICE"
                }
            )
        }

        // ---- Battery optimization exemption ----
        if ("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in declared) {
            val exempt = runCatching { power.isIgnoringBatteryOptimizations(app.packageName) }
                .getOrNull()
            entries += SpecialAccessEntry(
                SpecialAccessKind.BATTERY_OPTIMIZATION_EXEMPT,
                when (exempt) {
                    true -> AccessState.ENABLED
                    false -> AccessState.DECLARED_ONLY
                    null -> AccessState.UNKNOWN
                },
                evidence = "powermanager:isIgnoringBatteryOptimizations"
            )
        }

        return SpecialAccessState(entries.filterNotNull())
    }

    /**
     * Resolve one AppOps-backed access.
     *
     * The fallback chain is deliberate:
     *  - op ALLOWED                       -> ENABLED
     *  - op DENIED                        -> DECLARED_ONLY (it asked, it was refused)
     *  - op DEFAULT or UNREADABLE, declared -> UNKNOWN (we genuinely cannot tell)
     *  - not declared                     -> no entry at all
     */
    private fun appOpEntry(
        app: AppRecord,
        declared: Set<String>,
        kind: SpecialAccessKind,
        permission: String,
        op: String
    ): SpecialAccessEntry? {
        if (permission !in declared) return null

        val result = runCatching { appOps.checkOp(op, app.uid, app.packageName) }
            .getOrDefault(AppOpsResult.UNREADABLE)

        val state = when (result) {
            AppOpsResult.ALLOWED -> AccessState.ENABLED
            AppOpsResult.DENIED -> AccessState.DECLARED_ONLY
            AppOpsResult.DEFAULT, AppOpsResult.UNREADABLE -> AccessState.UNKNOWN
        }

        return SpecialAccessEntry(
            kind = kind,
            state = state,
            evidence = "appops:$op=${result.name.lowercase()}"
        )
    }

    /**
     * `Settings.Secure` component lists are colon-separated flattened
     * ComponentNames: "pkg/.Service:pkg2/.Other". Some OEMs use a different
     * separator or leave trailing separators, so parsing is defensive.
     */
    private fun parseComponentList(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(':', ';')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('/') }
            .mapNotNull { component ->
                val pkg = component.substringBefore('/').trim()
                if (pkg.isEmpty()) null else pkg to component
            }
            .toMap()
    }

    companion object {
        /** Expose the parser so tests can pin OEM-shaped inputs. */
        fun parseComponentListForTest(raw: String?): Map<String, String> =
            SpecialAccessDetector(
                object : SettingsSource { override fun secureString(key: String) = null },
                object : AppOpsSource {
                    override fun checkOp(opString: String, uid: Int, packageName: String) =
                        AppOpsResult.UNREADABLE
                },
                object : DeviceAdminSource { override fun activeAdmins() = emptyMap<String, String>() },
                object : AccessibilitySource { override fun enabledServices() = emptyMap<String, String>() },
                object : PowerSource {
                    override fun isIgnoringBatteryOptimizations(packageName: String) = false
                },
                object : VpnSource { override fun packagesWithVpnService() = emptySet<String>() }
            ).parseComponentList(raw)
    }
}
