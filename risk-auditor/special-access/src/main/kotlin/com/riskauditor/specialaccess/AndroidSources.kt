package com.riskauditor.specialaccess

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityManager

/** Real `Settings.Secure` reader. */
class AndroidSettingsSource(context: Context) : SettingsSource {
    private val resolver = context.applicationContext.contentResolver

    override fun secureString(key: String): String? = try {
        Settings.Secure.getString(resolver, key)
    } catch (t: Throwable) {
        null
    }
}

/**
 * Real AppOps reader.
 *
 * `unsafeCheckOpNoThrow` is the public call from API 29; `checkOpNoThrow` before
 * that. Neither is reliable for *another* package across all OEM builds: some
 * return MODE_DEFAULT for everything and some throw SecurityException. Both are
 * mapped to a result the detector turns into AccessState.UNKNOWN, so the UI says
 * "we could not check" instead of silently claiming the access is absent.
 */
class AndroidAppOpsSource(context: Context) : AppOpsSource {

    private val appOps = context.applicationContext
        .getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager

    @Suppress("DEPRECATION")
    override fun checkOp(opString: String, uid: Int, packageName: String): AppOpsResult {
        val manager = appOps ?: return AppOpsResult.UNREADABLE

        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                manager.unsafeCheckOpNoThrow(opString, uid, packageName)
            } else {
                manager.checkOpNoThrow(opString, uid, packageName)
            }

            when (mode) {
                AppOpsManager.MODE_ALLOWED -> AppOpsResult.ALLOWED
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> AppOpsResult.DENIED
                AppOpsManager.MODE_DEFAULT -> AppOpsResult.DEFAULT
                else -> AppOpsResult.UNREADABLE
            }
        } catch (e: SecurityException) {
            AppOpsResult.UNREADABLE
        } catch (t: Throwable) {
            AppOpsResult.UNREADABLE
        }
    }
}

/** Real device-admin reader. */
class AndroidDeviceAdminSource(context: Context) : DeviceAdminSource {

    private val dpm = context.applicationContext
        .getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    override fun activeAdmins(): Map<String, String> = try {
        dpm?.activeAdmins
            ?.associate { it.packageName to it.flattenToShortString() }
            ?: emptyMap()
    } catch (t: Throwable) {
        emptyMap()
    }
}

/**
 * Real accessibility reader.
 *
 * `AccessibilityManager.getEnabledAccessibilityServiceList` is the documented
 * API but returns an empty list on some builds when the caller is not itself an
 * accessibility service. `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` is
 * readable without any permission and is used as a fallback so a real service is
 * not missed.
 */
class AndroidAccessibilitySource(
    context: Context,
    private val settings: SettingsSource = AndroidSettingsSource(context)
) : AccessibilitySource {

    private val manager = context.applicationContext
        .getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    override fun enabledServices(): Map<String, String> {
        val fromManager = try {
            manager?.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )?.mapNotNull { info ->
                info.resolveInfo?.serviceInfo?.let { it.packageName to "${it.packageName}/${it.name}" }
            }?.toMap().orEmpty()
        } catch (t: Throwable) {
            emptyMap()
        }

        val fromSettings = parseComponents(
            settings.secureString(SettingsSource.ENABLED_ACCESSIBILITY_SERVICES)
        )

        // Union: either source finding a service is enough to report it.
        return fromSettings + fromManager
    }

    private fun parseComponents(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(':', ';')
            .mapNotNull { entry ->
                val trimmed = entry.trim()
                if (!trimmed.contains('/')) return@mapNotNull null
                val pkg = trimmed.substringBefore('/').trim()
                if (pkg.isEmpty()) null else pkg to trimmed
            }
            .toMap()
    }
}

/** Real battery-optimization reader. */
class AndroidPowerSource(context: Context) : PowerSource {

    private val power = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun isIgnoringBatteryOptimizations(packageName: String): Boolean = try {
        power?.isIgnoringBatteryOptimizations(packageName) ?: false
    } catch (t: Throwable) {
        false
    }
}

/** Finds packages that declare a `VpnService`. */
class AndroidVpnSource(context: Context) : VpnSource {

    private val pm: PackageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override fun packagesWithVpnService(): Set<String> = try {
        val intent = Intent(VpnService.SERVICE_INTERFACE)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentServices(intent, 0)
        }
        resolved.mapNotNull { it.serviceInfo?.packageName }.toSet()
    } catch (t: Throwable) {
        emptySet()
    }
}

/**
 * Real role reader.
 *
 * `RoleManager` is API 29+. Below that, the default SMS app comes from
 * `Telephony.Sms.getDefaultSmsPackage` and the dialer from `TelecomManager`.
 * Roles matter because they are what keeps the default messaging app out of the
 * High band for holding SMS permissions it is required to hold.
 */
class AndroidRoleSource(private val context: Context) : RoleSource {

    override fun defaultSmsPackage(): String? = try {
        Telephony.Sms.getDefaultSmsPackage(context)
    } catch (t: Throwable) {
        null
    }

    override fun defaultDialerPackage(): String? = try {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE)
            as? android.telecom.TelecomManager
        telecom?.defaultDialerPackage
    } catch (t: Throwable) {
        null
    }

    override fun defaultAssistantPackage(): String? = try {
        Settings.Secure.getString(context.contentResolver, "assistant")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    override fun defaultBrowserPackage(): String? = try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (t: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    override fun defaultHomePackage(): String? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (t: Throwable) {
        null
    }
}
