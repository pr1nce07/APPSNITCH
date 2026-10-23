package com.riskauditor.permissions

import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.os.Build
import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.catalog.PermissionCatalog
import com.riskauditor.inventory.PackageSource
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.Protection

/**
 * Extracts declared-versus-granted permissions with protection levels
 * (PS-01 must-have 02 and 03).
 *
 * The declared/granted distinction comes from two parallel arrays on
 * [PackageInfo]: `requestedPermissions` and `requestedPermissionsFlags`. They
 * are index-aligned, and a surprising amount of code in the wild ignores the
 * flags array entirely and reports everything an app *asks for* as something it
 * *has*. That overstates risk on every modern app, because runtime permissions
 * mean most requests are not granted.
 */
class PermissionExtractor(
    private val source: PackageSource,
    private val catalog: PermissionCatalog = CatalogLoader.default
) {

    /** Permissions that are granted through a Settings screen or AppOps flag. */
    private val specialPermissions: Set<String> = catalog.specialAccessPermissions

    fun extract(info: PackageInfo): List<PermissionRecord> {
        val declared = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags

        return declared.mapIndexed { index, name ->
            val granted = flags != null &&
                index < flags.size &&
                (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0

            val protection = resolveProtection(name)

            PermissionRecord(
                name = name,
                declared = true,
                granted = granted,
                protection = protection,
                category = catalog.categoryOf(name),
                grantable = isGrantable(protection, granted)
            )
        }.distinctBy { it.name }
    }

    fun extract(packageName: String): List<PermissionRecord> =
        source.packageInfo(packageName)?.let { extract(it) } ?: emptyList()

    /**
     * Resolve a permission's protection level from the device.
     *
     * The device is authoritative because OEMs do redefine permissions. When it
     * cannot resolve one — custom or vendor-defined — the result is
     * [Protection.UNKNOWN], which the rubric scores at zero. Guessing DANGEROUS
     * for an unknown permission would let any app inflate its own score, and
     * guessing NORMAL would hide real vendor permissions. Zero is the honest
     * answer for "we do not know what this does".
     */
    private fun resolveProtection(name: String): Protection {
        if (name in specialPermissions) {
            // The catalog already classifies these as special access; the
            // platform reports several of them as "signature" or "normal",
            // which understates what they actually allow.
            return Protection.SPECIAL
        }

        val info = source.permissionInfo(name) ?: return Protection.UNKNOWN

        @Suppress("DEPRECATION")
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection
        } else {
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }

        return when (base) {
            PermissionInfo.PROTECTION_DANGEROUS -> Protection.DANGEROUS
            PermissionInfo.PROTECTION_SIGNATURE -> Protection.SIGNATURE
            PermissionInfo.PROTECTION_NORMAL -> Protection.NORMAL
            else -> {
                @Suppress("DEPRECATION")
                if (base == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM) {
                    Protection.SIGNATURE
                } else {
                    Protection.UNKNOWN
                }
            }
        }
    }

    /**
     * A signature permission the app does not already hold can never be granted
     * to it, because it is not signed with the defining app's certificate.
     * Scoring it as latent risk would permanently inflate the score of any app
     * that optimistically declares one.
     */
    private fun isGrantable(protection: Protection, granted: Boolean): Boolean = when {
        granted -> true
        protection == Protection.SIGNATURE -> false
        protection == Protection.UNKNOWN -> false
        else -> true
    }

    /** Permissions grouped by category, for the per-app breakdown. */
    fun extractGrouped(packageName: String): Map<com.riskauditor.model.Category, List<PermissionRecord>> =
        extract(packageName).groupBy { it.category }
}
