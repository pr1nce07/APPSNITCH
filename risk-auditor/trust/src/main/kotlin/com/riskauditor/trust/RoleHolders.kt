package com.riskauditor.trust

import com.riskauditor.model.Category

/**
 * The device roles that make otherwise-alarming access legitimate.
 *
 * The default SMS app *must* hold SMS permissions; the default dialer *must* hold
 * call log and phone permissions. Scoring them as if they were a torch app would
 * flood the High band and make the ranking useless, which is the failure mode
 * PS-01 cares most about.
 *
 * Roles are read from the device by `:special-access` (RoleManager on API 29+,
 * falling back to `Telephony.Sms.getDefaultSmsPackage` and the default-dialer
 * lookup below that). This class is the pure half: it says what a role *excuses*,
 * not how to discover it.
 */
enum class DeviceRole {
    DEFAULT_SMS,
    DEFAULT_DIALER,
    DEFAULT_ASSISTANT,
    DEFAULT_BROWSER,
    DEFAULT_HOME;

    /** Categories this role makes expected rather than suspicious. */
    val excusedCategories: Set<Category>
        get() = when (this) {
            DEFAULT_SMS -> setOf(Category.SMS, Category.CONTACTS_AND_CALL_LOGS)
            DEFAULT_DIALER -> setOf(Category.PHONE_AND_IDENTITY, Category.CONTACTS_AND_CALL_LOGS)
            DEFAULT_ASSISTANT -> setOf(Category.CAMERA_AND_MICROPHONE, Category.PHONE_AND_IDENTITY)
            DEFAULT_BROWSER -> setOf(Category.NETWORK, Category.STORAGE_AND_MEDIA)
            DEFAULT_HOME -> setOf(Category.SPECIAL_ACCESS)
        }

    val displayName: String
        get() = when (this) {
            DEFAULT_SMS -> "your default messaging app"
            DEFAULT_DIALER -> "your default phone app"
            DEFAULT_ASSISTANT -> "your default assistant"
            DEFAULT_BROWSER -> "your default browser"
            DEFAULT_HOME -> "your home screen"
        }
}

/**
 * Which packages hold which roles on this device.
 *
 * A snapshot, passed into scoring. Empty is a safe default: no reductions apply,
 * so an unknown device never *under*-reports risk.
 */
data class RoleHolders(
    val rolesByPackage: Map<String, Set<DeviceRole>> = emptyMap()
) {
    fun rolesOf(packageName: String): Set<DeviceRole> = rolesByPackage[packageName] ?: emptySet()

    fun holds(packageName: String, role: DeviceRole): Boolean = role in rolesOf(packageName)

    /**
     * True when this app's access in [category] is explained by a role it holds.
     * Note this never excuses special access other than for the home-screen role.
     */
    fun excuses(packageName: String, category: Category): Boolean =
        rolesOf(packageName).any { category in it.excusedCategories }

    /** The role that explains [category], for use in reason text. */
    fun excusingRole(packageName: String, category: Category): DeviceRole? =
        rolesOf(packageName).firstOrNull { category in it.excusedCategories }

    companion object {
        val EMPTY = RoleHolders()

        fun of(vararg pairs: Pair<String, Set<DeviceRole>>): RoleHolders =
            RoleHolders(pairs.toMap())
    }
}
