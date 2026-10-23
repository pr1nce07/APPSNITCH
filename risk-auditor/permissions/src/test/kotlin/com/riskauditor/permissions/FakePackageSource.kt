package com.riskauditor.permissions

import com.riskauditor.inventory.PackageSource

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo

/**
 * A hand-built [PackageSource]. Duplicated from :inventory's test sources
 * deliberately: sharing test helpers across Gradle modules needs a testFixtures
 * configuration, which is more machinery than a 100-line fake is worth.
 *
 * A hand-built [PackageSource].
 *
 * Building `PackageInfo` objects directly, rather than going through
 * Robolectric's shadow package manager, is deliberate: it lets a test express
 * exactly the device state it cares about — including states a real device makes
 * awkward to produce, such as a package that throws halfway through a scan.
 */
class FakePackageSource(
    private val packages: MutableList<PackageInfo> = mutableListOf(),
    private val labels: MutableMap<String, String> = mutableMapOf(),
    private val installSources: MutableMap<String, Pair<String?, String?>> = mutableMapOf(),
    private val certificates: MutableMap<String, List<String>> = mutableMapOf(),
    private val launcherIcons: MutableMap<String, Boolean> = mutableMapOf(),
    private val permissionInfos: MutableMap<String, Int> = mutableMapOf(),
    /** Packages whose metadata lookup should blow up, simulating a mid-scan uninstall. */
    private val throwingPackages: MutableSet<String> = mutableSetOf()
) : PackageSource {

    fun addPackage(
        packageName: String,
        label: String = packageName,
        versionName: String = "1.0",
        targetSdk: Int = 34,
        isSystem: Boolean = false,
        isUpdatedSystem: Boolean = false,
        enabled: Boolean = true,
        hasLauncherIcon: Boolean = true,
        installingPackage: String? = "com.android.vending",
        initiatingPackage: String? = null,
        permissions: List<Pair<String, Boolean>> = emptyList(),
        certs: List<String> = listOf("ab".repeat(32)),
        sourceDir: String = "/data/app/$packageName/base.apk",
        uid: Int = 10000
    ): FakePackageSource = apply {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.targetSdkVersion = targetSdk
            this.enabled = enabled
            this.uid = uid
            this.sourceDir = sourceDir
            this.flags = 0
            if (isSystem) this.flags = this.flags or ApplicationInfo.FLAG_SYSTEM
            if (isUpdatedSystem) this.flags = this.flags or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }

        val info = PackageInfo().apply {
            this.packageName = packageName
            this.versionName = versionName
            this.applicationInfo = appInfo
            this.firstInstallTime = 1_700_000_000_000L
            this.lastUpdateTime = 1_700_000_000_000L
            if (permissions.isNotEmpty()) {
                this.requestedPermissions = permissions.map { it.first }.toTypedArray()
                this.requestedPermissionsFlags = permissions.map {
                    if (it.second) PackageInfo.REQUESTED_PERMISSION_GRANTED else 0
                }.toIntArray()
            }
        }

        packages += info
        labels[packageName] = label
        installSources[packageName] = installingPackage to initiatingPackage
        certificates[packageName] = certs
        launcherIcons[packageName] = hasLauncherIcon
    }

    fun declarePermission(name: String, protection: Int): FakePackageSource = apply {
        permissionInfos[name] = protection
    }

    fun makeThrow(packageName: String): FakePackageSource = apply {
        throwingPackages += packageName
    }

    override fun installedPackages(includeUninstalled: Boolean): List<PackageInfo> = packages.toList()

    override fun packageInfo(packageName: String): PackageInfo? =
        packages.firstOrNull { it.packageName == packageName }

    override fun loadLabel(packageName: String): String {
        if (packageName in throwingPackages) throw IllegalStateException("package vanished")
        return labels[packageName] ?: packageName
    }

    override fun permissionInfo(permissionName: String): PermissionInfo? =
        permissionInfos[permissionName]?.let { level ->
            PermissionInfo().apply {
                name = permissionName
                protectionLevel = level
            }
        }

    override fun installSource(packageName: String): Pair<String?, String?> =
        installSources[packageName] ?: (null to null)

    override fun signingCertificates(packageName: String): List<String> =
        certificates[packageName] ?: emptyList()

    override fun hasLauncherIcon(packageName: String): Boolean =
        launcherIcons[packageName] ?: true

    override fun isEnabled(packageName: String): Boolean = true
}
