package com.riskauditor.specialaccess

/**
 * Seams over the four different Android subsystems that between them answer
 * "does this app have special access".
 *
 * There is no single API for this. Accessibility lives in `Settings.Secure` and
 * `AccessibilityManager`, device admins in `DevicePolicyManager`, notification
 * listeners in another `Settings.Secure` key, and overlay / install-packages /
 * usage-stats in `AppOpsManager`. Splitting them into separate interfaces means
 * each can be faked independently, which is the only way to test the
 * combinations without four different device states.
 */

/** Reads `Settings.Secure` string values. */
interface SettingsSource {
    fun secureString(key: String): String?

    companion object {
        const val ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"
        const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
        const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
    }
}

/** Result of an AppOps query. [UNREADABLE] is a first-class outcome, not a failure. */
enum class AppOpsResult {
    ALLOWED,
    DENIED,
    /** MODE_DEFAULT: the op falls back to a permission check we cannot perform here. */
    DEFAULT,
    /**
     * The call threw, or this OEM/API level does not allow reading the op for
     * another package. Must surface as AccessState.UNKNOWN, never as "denied".
     */
    UNREADABLE
}

/**
 * Reads AppOps modes for *other* packages.
 *
 * `unsafeCheckOpNoThrow` is public API from API 29 and `checkOpNoThrow` before
 * it, but behaviour for a package other than your own is inconsistent across
 * OEM builds: some return MODE_DEFAULT for everything, some throw
 * SecurityException. See docs/LIMITATIONS.md L-03. Implementations MUST return
 * [AppOpsResult.UNREADABLE] rather than guessing.
 */
interface AppOpsSource {
    fun checkOp(opString: String, uid: Int, packageName: String): AppOpsResult

    companion object {
        const val OPSTR_SYSTEM_ALERT_WINDOW = "android:system_alert_window"
        const val OPSTR_REQUEST_INSTALL_PACKAGES = "android:request_install_packages"
        const val OPSTR_GET_USAGE_STATS = "android:get_usage_stats"
        const val OPSTR_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage"
    }
}

/** Active device administrators. */
interface DeviceAdminSource {
    /** Package names with at least one active admin component, to the component name. */
    fun activeAdmins(): Map<String, String>
}

/** Enabled accessibility services. */
interface AccessibilitySource {
    /** Package names with an enabled accessibility service, to the service component. */
    fun enabledServices(): Map<String, String>
}

/** Battery-optimization exemptions. */
interface PowerSource {
    fun isIgnoringBatteryOptimizations(packageName: String): Boolean
}

/** Default role holders (default SMS app, dialer, assistant...). */
interface RoleSource {
    fun defaultSmsPackage(): String?
    fun defaultDialerPackage(): String?
    fun defaultAssistantPackage(): String?
    fun defaultBrowserPackage(): String?
    fun defaultHomePackage(): String?
}

/** Apps that declare a VPN service component. */
interface VpnSource {
    fun packagesWithVpnService(): Set<String>
}
