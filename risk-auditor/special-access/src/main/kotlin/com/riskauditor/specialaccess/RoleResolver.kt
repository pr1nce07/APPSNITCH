package com.riskauditor.specialaccess

import com.riskauditor.trust.DeviceRole
import com.riskauditor.trust.RoleHolders

/**
 * Turns the device's default-app settings into the [RoleHolders] snapshot that
 * scoring consumes.
 *
 * Read once per scan rather than per app: these change rarely, and reading them
 * once means every app in a scan is judged against the same device state.
 */
object RoleResolver {

    fun resolve(source: RoleSource): RoleHolders {
        val byPackage = mutableMapOf<String, MutableSet<DeviceRole>>()

        fun add(packageName: String?, role: DeviceRole) {
            val pkg = packageName?.takeIf { it.isNotBlank() } ?: return
            byPackage.getOrPut(pkg) { mutableSetOf() } += role
        }

        add(runCatching { source.defaultSmsPackage() }.getOrNull(), DeviceRole.DEFAULT_SMS)
        add(runCatching { source.defaultDialerPackage() }.getOrNull(), DeviceRole.DEFAULT_DIALER)
        add(runCatching { source.defaultAssistantPackage() }.getOrNull(), DeviceRole.DEFAULT_ASSISTANT)
        add(runCatching { source.defaultBrowserPackage() }.getOrNull(), DeviceRole.DEFAULT_BROWSER)
        add(runCatching { source.defaultHomePackage() }.getOrNull(), DeviceRole.DEFAULT_HOME)

        return RoleHolders(byPackage.mapValues { it.value.toSet() })
    }
}
