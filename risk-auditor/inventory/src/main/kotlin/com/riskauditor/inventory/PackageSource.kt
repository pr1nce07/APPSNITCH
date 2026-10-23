package com.riskauditor.inventory

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build

/**
 * The thin seam over `PackageManager`.
 *
 * Every Android package API this project touches goes through here, which is
 * what makes `:inventory` and `:permissions` testable with a fake rather than an
 * emulator. The interface is deliberately narrow: if a new call is needed,
 * adding it here forces a decision about how it is faked.
 */
interface PackageSource {

    /** Every installed package, with permission metadata populated. */
    fun installedPackages(includeUninstalled: Boolean = false): List<PackageInfo>

    fun packageInfo(packageName: String): PackageInfo?

    /** Human-readable label. Falls back to the package name, never to null. */
    fun loadLabel(packageName: String): String

    /** Protection level, or null when the permission cannot be resolved on this device. */
    fun permissionInfo(permissionName: String): PermissionInfo?

    /**
     * API 30+: `getInstallSourceInfo`; below: `getInstallerPackageName`.
     * @return installing package to initiating package.
     */
    fun installSource(packageName: String): Pair<String?, String?>

    /** SHA-256 of each signing certificate, lowercase hex. */
    fun signingCertificates(packageName: String): List<String>

    /** True when the package has an activity in the launcher. */
    fun hasLauncherIcon(packageName: String): Boolean

    fun isEnabled(packageName: String): Boolean
}

/** Flags used consistently wherever packages are enumerated. */
internal object PackageFlags {

    @Suppress("DEPRECATION")
    fun permissionFlags(): Int = PackageManager.GET_PERMISSIONS

    @Suppress("DEPRECATION")
    fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    fun componentFlags(): Int =
        PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
}
