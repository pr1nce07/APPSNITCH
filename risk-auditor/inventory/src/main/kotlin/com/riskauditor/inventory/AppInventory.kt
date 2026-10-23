package com.riskauditor.inventory

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import com.riskauditor.model.AppRecord
import com.riskauditor.model.InstallerInfo
import com.riskauditor.trust.InstallerClassifier

/** What to include in a scan. */
data class InventoryOptions(
    /**
     * Default OFF. PS-01 asks for user-installed apps with a toggle for system
     * ones. Leaving system apps in by default fills the list with OEM packages
     * that are both alarming-looking and impossible to remove, which buries the
     * apps the user can actually do something about.
     */
    val includeSystemApps: Boolean = false,
    val includeDisabledApps: Boolean = true
)

/** Result of enumerating the device, including anything that failed. */
data class InventoryResult(
    val apps: List<AppRecord>,
    /** package -> why it was skipped. Surfaced in the UI, never silently dropped. */
    val skipped: Map<String, String> = emptyMap()
)

/**
 * Builds [AppRecord]s for every installed app (PS-01 must-have 01).
 *
 * Two things worth noting:
 *
 *  - **No icons.** [AppRecord] holds no Drawable. Decoding 150+ icons during a
 *    scan is both slow and a memory problem; [IconLoader] fetches them lazily
 *    per visible row instead.
 *  - **One bad package never aborts a scan.** Each app is built inside its own
 *    try/catch and failures are collected into [InventoryResult.skipped].
 */
class AppInventory(private val source: PackageSource) {

    fun scan(options: InventoryOptions = InventoryOptions()): InventoryResult {
        val apps = mutableListOf<AppRecord>()
        val skipped = LinkedHashMap<String, String>()

        source.installedPackages().forEach { info ->
            val packageName = info.packageName ?: return@forEach
            try {
                val appInfo = info.applicationInfo
                if (appInfo == null) {
                    skipped[packageName] = "no application info"
                    return@forEach
                }

                val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

                if ((isSystem || isUpdatedSystem) && !options.includeSystemApps) return@forEach
                if (!appInfo.enabled && !options.includeDisabledApps) return@forEach

                apps += toRecord(info, appInfo, isSystem, isUpdatedSystem)
            } catch (t: Throwable) {
                // The app may have been uninstalled between enumeration and here.
                skipped[packageName] = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            }
        }

        return InventoryResult(apps, skipped)
    }

    /** Re-read one package, e.g. after an install broadcast. */
    fun scanOne(packageName: String): AppRecord? {
        val info = source.packageInfo(packageName) ?: return null
        val appInfo = info.applicationInfo ?: return null
        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return try {
            toRecord(info, appInfo, isSystem, isUpdatedSystem)
        } catch (t: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun toRecord(
        info: PackageInfo,
        appInfo: ApplicationInfo,
        isSystem: Boolean,
        isUpdatedSystem: Boolean
    ): AppRecord {
        val packageName = info.packageName

        val (installing, initiating) = source.installSource(packageName)

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

        val apkPaths = buildList {
            appInfo.sourceDir?.let { add(it) }
            appInfo.splitSourceDirs?.forEach { add(it) }
        }

        return AppRecord(
            packageName = packageName,
            label = source.loadLabel(packageName),
            versionName = info.versionName,
            versionCode = versionCode,
            firstInstallTime = info.firstInstallTime,
            lastUpdateTime = info.lastUpdateTime,
            targetSdk = appInfo.targetSdkVersion,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0,
            installer = InstallerClassifier.enrich(
                InstallerInfo(
                    kind = com.riskauditor.model.InstallerKind.UNKNOWN,
                    installingPackage = installing,
                    initiatingPackage = initiating
                )
            ),
            isSystem = isSystem,
            isUpdatedSystem = isUpdatedSystem,
            isEnabled = appInfo.enabled,
            hasLauncherIcon = source.hasLauncherIcon(packageName),
            signingCertSha256 = source.signingCertificates(packageName),
            apkPaths = apkPaths,
            appCategory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category
            } else {
                -1
            },
            uid = appInfo.uid
        )
    }
}
