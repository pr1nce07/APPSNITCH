package com.riskauditor.manifestanalysis

import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.riskauditor.model.ComponentType
import com.riskauditor.model.ExportedComponent
import com.riskauditor.model.ManifestFindings

/**
 * Static inspection of an installed app's manifest (PS-01 stretch goal 12).
 *
 * ## Why `PackageManager` rather than parsing the APK's binary XML
 *
 * `AssetManager.openXmlResourceParser` can read `AndroidManifest.xml` out of an
 * APK, but it is slow, needs the APK path to still be valid, and duplicates
 * information the platform has already parsed. `PackageManager` with the
 * component flags gives the same `exported` and `permission` fields directly,
 * and it does so for every app in one call.
 *
 * The one thing it does NOT give is the network-security-config, which is a raw
 * XML resource. That is handled separately and degrades to the
 * `FLAG_USES_CLEARTEXT_TRAFFIC` answer when it cannot be read.
 *
 * ## The default that catches people out
 *
 * A component with an intent filter is exported by default below
 * `targetSdk 31`; from 31 onwards `android:exported` must be declared
 * explicitly. `ComponentInfo.exported` already reflects the resolved value, so
 * this code reads the truth rather than re-deriving the rule.
 */
class ManifestAnalyzer(private val packageManager: PackageManager) {

    @Suppress("DEPRECATION")
    fun analyse(packageName: String): ManifestFindings {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                packageManager.getPackageInfo(packageName, flags)
            }
        } catch (t: Throwable) {
            return ManifestFindings(analysisError = "could not read package: ${t.javaClass.simpleName}")
        }

        return analyse(info)
    }

    fun analyse(info: PackageInfo): ManifestFindings {
        val appInfo = info.applicationInfo
            ?: return ManifestFindings(analysisError = "no application info")

        val exported = buildList {
            addAll(collect(info.activities, ComponentType.ACTIVITY))
            addAll(collect(info.services, ComponentType.SERVICE))
            addAll(collect(info.receivers, ComponentType.RECEIVER))
            addAll(collectProviders(info))
        }

        return ManifestFindings(
            exportedComponents = exported,
            cleartextTrafficAllowed = cleartextAllowed(appInfo),
            debuggable = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            allowBackup = appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
            testOnly = appInfo.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
            analysisError = null
        )
    }

    private fun collect(
        components: Array<out ComponentInfo>?,
        type: ComponentType
    ): List<ExportedComponent> = components.orEmpty()
        .filter { it.exported }
        .map { component ->
            ExportedComponent(
                type = type,
                className = component.name ?: "(unnamed)",
                permission = when (component) {
                    is android.content.pm.ActivityInfo -> component.permission
                    is android.content.pm.ServiceInfo -> component.permission
                    else -> null
                }
            )
        }

    /**
     * Providers are reported separately because they are the dangerous case: an
     * unguarded exported provider can expose an app's entire database to any
     * other app on the device, with no user interaction at all.
     */
    private fun collectProviders(info: PackageInfo): List<ExportedComponent> =
        info.providers.orEmpty()
            .filter { it.exported }
            .map { provider ->
                ExportedComponent(
                    type = ComponentType.PROVIDER,
                    className = provider.name ?: "(unnamed)",
                    // A provider guarded by EITHER readPermission or writePermission
                    // is still partly open, so both are recorded and the stricter
                    // (read) one decides whether it counts as guarded.
                    permission = provider.readPermission
                        ?: provider.writePermission
                        ?: provider.permission,
                    grantUriPermissions = provider.grantUriPermissions
                )
            }

    /**
     * Cleartext HTTP.
     *
     * `FLAG_USES_CLEARTEXT_TRAFFIC` is the resolved value the platform computed,
     * already accounting for the targetSdk default (cleartext is off by default
     * from targetSdk 28) and for `android:usesCleartextTraffic`. It does not
     * account for a network-security-config that permits cleartext only for
     * specific domains, so a true here can be a slight overstatement; it is
     * scored at only 4 points for that reason.
     */
    private fun cleartextAllowed(appInfo: ApplicationInfo): Boolean? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0
        } else {
            // Below API 24 there is no flag and cleartext is always permitted.
            true
        }
    } catch (t: Throwable) {
        null
    }
}
