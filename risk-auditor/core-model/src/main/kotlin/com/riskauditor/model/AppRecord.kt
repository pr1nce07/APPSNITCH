package com.riskauditor.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current schema version of every serialized model in this module. */
const val SCHEMA_VERSION: Int = 1

/**
 * Where an app came from, as four separate facts.
 *
 * Android exposes three distinct attributions on API 30+ and only one below that;
 * keeping them apart matters because `initiatingPackage` is the one that is hard to
 * spoof, while `installingPackage` can be set by the installer itself.
 */
@Serializable
data class InstallerInfo(
    @SerialName("kind") val kind: InstallerKind,
    /** The package that is recorded as having installed the app. May be null. */
    @SerialName("installing_package") val installingPackage: String? = null,
    /** API 30+: the package that initiated the install. Null below API 30. */
    @SerialName("initiating_package") val initiatingPackage: String? = null,
    /** API 30+: the package the APK originally came from, when the installer set it. */
    @SerialName("originating_package") val originatingPackage: String? = null
) {
    companion object {
        val UNKNOWN = InstallerInfo(InstallerKind.UNKNOWN)
    }
}

/**
 * Identity and provenance of a single installed package.
 *
 * Deliberately holds **no** Drawable, Bitmap or Context. Icons are loaded lazily by
 * the UI through `IconLoader` in :inventory so that a whole-device scan stays cheap
 * and this model stays serializable and testable off-device.
 */
@Serializable
data class AppRecord(
    @SerialName("package_name") val packageName: String,
    @SerialName("label") val label: String,
    @SerialName("version_name") val versionName: String? = null,
    /** `PackageInfo.getLongVersionCode()`. Long, not Int — versionCode overflowed Int years ago. */
    @SerialName("version_code") val versionCode: Long = 0L,
    @SerialName("first_install_time") val firstInstallTime: Long = 0L,
    @SerialName("last_update_time") val lastUpdateTime: Long = 0L,
    @SerialName("target_sdk") val targetSdk: Int = 0,
    @SerialName("min_sdk") val minSdk: Int = 0,
    @SerialName("installer") val installer: InstallerInfo = InstallerInfo.UNKNOWN,
    @SerialName("is_system") val isSystem: Boolean = false,
    /** A system app that has been updated from a store. Scored as a system app, not a user app. */
    @SerialName("is_updated_system") val isUpdatedSystem: Boolean = false,
    @SerialName("is_enabled") val isEnabled: Boolean = true,
    /**
     * False when the app has no launcher entry. On its own this is innocuous
     * (many legitimate apps are headless); combined with sensitive access it is a
     * classic stalkerware trait, which is why it is a modifier and never a verdict.
     */
    @SerialName("has_launcher_icon") val hasLauncherIcon: Boolean = true,
    /** SHA-256 of each signing certificate, lowercase hex. Used for trust pinning, never displayed raw. */
    @SerialName("signing_cert_sha256") val signingCertSha256: List<String> = emptyList(),
    /** Absolute path to the base APK, plus any split APKs. Used by :hash-intel and :manifest-analysis. */
    @SerialName("apk_paths") val apkPaths: List<String> = emptyList(),
    /** `ApplicationInfo.category` (API 26+), or -1 for CATEGORY_UNDEFINED. */
    @SerialName("app_category") val appCategory: Int = -1,
    @SerialName("uid") val uid: Int = 0
) {
    /** Treat updated-system apps as system apps everywhere. */
    val isSystemOrUpdatedSystem: Boolean get() = isSystem || isUpdatedSystem

    val baseApkPath: String? get() = apkPaths.firstOrNull()

    /** True when nothing vouches for where this APK came from. */
    val isFromUntrustedSource: Boolean get() = installer.kind.isUntrustedSource

    /**
     * Apps targeting below API 23 predate runtime permissions: every dangerous
     * permission they declare is granted at install time with no user prompt.
     */
    val predatesRuntimePermissions: Boolean get() = targetSdk in 1..22
}

/**
 * One permission an app asked for.
 *
 * [declared] is always true for records produced by a scan (we only enumerate what
 * the manifest requested); it is kept explicit so the model can also represent a
 * permission observed in a *previous* snapshot that has since been dropped.
 */
@Serializable
data class PermissionRecord(
    @SerialName("name") val name: String,
    @SerialName("declared") val declared: Boolean = true,
    @SerialName("granted") val granted: Boolean = false,
    @SerialName("protection") val protection: Protection = Protection.UNKNOWN,
    @SerialName("category") val category: Category = Category.UNCATEGORIZED,
    /**
     * False when the permission can never be granted to this app — e.g. a signature
     * permission held by a differently-signed app. Those score 0 even as "declared".
     */
    @SerialName("grantable") val grantable: Boolean = true
) {
    val shortName: String get() = name.substringAfterLast('.')

    /** Declared, grantable, but not currently granted: latent rather than active risk. */
    val isLatent: Boolean get() = declared && grantable && !granted
}

/** One special access, with the evidence that produced the verdict. */
@Serializable
data class SpecialAccessEntry(
    @SerialName("kind") val kind: SpecialAccessKind,
    @SerialName("state") val state: AccessState,
    /**
     * Human-readable provenance of this verdict, e.g. "enabled_accessibility_services"
     * or "appops:OPSTR_SYSTEM_ALERT_WINDOW=MODE_ALLOWED". Shown in the technical panel,
     * never in a headline.
     */
    @SerialName("evidence") val evidence: String? = null,
    /** The component that holds the access, where one applies (service or admin receiver). */
    @SerialName("component") val component: String? = null
)

/** All special access for one app. */
@Serializable
data class SpecialAccessState(
    @SerialName("entries") val entries: List<SpecialAccessEntry> = emptyList()
) {
    fun stateOf(kind: SpecialAccessKind): AccessState =
        entries.firstOrNull { it.kind == kind }?.state ?: AccessState.NOT_PRESENT

    fun isEnabled(kind: SpecialAccessKind): Boolean = stateOf(kind) == AccessState.ENABLED

    val enabledKinds: Set<SpecialAccessKind>
        get() = entries.filter { it.state == AccessState.ENABLED }.map { it.kind }.toSet()

    val declaredOnlyKinds: Set<SpecialAccessKind>
        get() = entries.filter { it.state == AccessState.DECLARED_ONLY }.map { it.kind }.toSet()

    companion object {
        val EMPTY = SpecialAccessState()
    }
}
