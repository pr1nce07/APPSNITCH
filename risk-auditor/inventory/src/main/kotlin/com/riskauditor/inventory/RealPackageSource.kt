package com.riskauditor.inventory

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import java.security.MessageDigest

/**
 * The real `PackageManager`-backed implementation.
 *
 * Every method swallows [PackageManager.NameNotFoundException] and returns a
 * safe default. This is not laziness: packages genuinely disappear between the
 * call that enumerated them and the call that queries one, because the user can
 * uninstall something mid-scan. Letting that propagate would abort a whole-device
 * scan over one app, which is the single worst failure mode on a judging device.
 */
class RealPackageSource(context: Context) : PackageSource {

    private val pm: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    override fun installedPackages(includeUninstalled: Boolean): List<PackageInfo> {
        var flags = PackageFlags.permissionFlags() or PackageFlags.signingFlags()
        if (includeUninstalled) flags = flags or PackageManager.MATCH_UNINSTALLED_PACKAGES

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getInstalledPackages(flags)
            }
        } catch (t: Throwable) {
            // Some OEM builds throw TransactionTooLargeException when the device
            // has a very large number of packages. Retry without signing data,
            // which is by far the largest field.
            try {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageFlags.permissionFlags())
            } catch (t2: Throwable) {
                emptyList()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun packageInfo(packageName: String): PackageInfo? = try {
        val flags = PackageFlags.permissionFlags() or PackageFlags.signingFlags()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (t: Throwable) {
        null
    }

    override fun loadLabel(packageName: String): String = try {
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString().ifBlank { packageName }
    } catch (t: Throwable) {
        packageName
    }

    override fun permissionInfo(permissionName: String): PermissionInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPermissionInfo(permissionName, PackageManager.PermissionInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPermissionInfo(permissionName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        // Custom or vendor-defined permission. Expected, not exceptional.
        null
    } catch (t: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    override fun installSource(packageName: String): Pair<String?, String?> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(packageName)
            info.installingPackageName to info.initiatingPackageName
        } else {
            pm.getInstallerPackageName(packageName) to null
        }
    } catch (t: Throwable) {
        null to null
    }

    @Suppress("DEPRECATION")
    override fun signingCertificates(packageName: String): List<String> = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            when {
                signing == null -> emptyArray()
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
        } else {
            info.signatures
        }

        signatures.orEmpty().mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }
    } catch (t: Throwable) {
        emptyList()
    }

    override fun hasLauncherIcon(packageName: String): Boolean = try {
        pm.getLaunchIntentForPackage(packageName) != null ||
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName),
                0
            ).isNotEmpty()
    } catch (t: Throwable) {
        // When in doubt, assume it HAS an icon. "No icon" adds risk points, and
        // inventing risk from a failed lookup would be worse than missing one.
        true
    }

    override fun isEnabled(packageName: String): Boolean = try {
        pm.getApplicationInfo(packageName, 0).enabled
    } catch (t: Throwable) {
        true
    }
}
