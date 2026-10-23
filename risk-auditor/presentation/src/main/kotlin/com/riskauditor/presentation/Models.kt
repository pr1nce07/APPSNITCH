package com.riskauditor.presentation

import com.riskauditor.model.AccessState
import com.riskauditor.model.Category
import com.riskauditor.model.HashVerdict
import com.riskauditor.model.ManifestFindings
import com.riskauditor.model.MlResult
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.Reason
import com.riskauditor.model.RiskBand
import com.riskauditor.model.SpecialAccessKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * UI-ready models. This module contains NO UI: no Compose, no Views, no
 * resources, no Context. It produces exactly the data a screen would bind to, so
 * the screens can be built later (or by someone else, in parallel) without
 * touching any logic.
 *
 * The three PS-01 questions map onto these types directly:
 *   Q1 "Which apps can do the most harm?" -> DeviceReport.ranked
 *   Q2 "Why is each flagged?"             -> AppDetail.whyFlagged
 *   Q3 "What can I do right now?"         -> AppDetail.actions
 */

/** One row in the ranked list on the home screen. Q1. */
@Serializable
data class RankedApp(
    @SerialName("rank") val rank: Int,
    @SerialName("package_name") val packageName: String,
    @SerialName("label") val label: String,
    @SerialName("score") val score: Int,
    @SerialName("band") val band: RiskBand,
    /** The single most important thing to say about this app, for the list row. */
    @SerialName("top_reason") val topReason: String?,
    @SerialName("is_system") val isSystem: Boolean,
    @SerialName("install_source") val installSource: String
)

/** Device-wide rollup for one permission category. */
@Serializable
data class CategoryRollup(
    @SerialName("category") val category: Category,
    @SerialName("display_name") val displayName: String,
    @SerialName("apps_with_granted") val appsWithGranted: Int,
    @SerialName("apps_with_declared_only") val appsWithDeclaredOnly: Int,
    @SerialName("granted_permission_count") val grantedPermissionCount: Int,
    @SerialName("worst_offenders") val worstOffenders: List<String>,
    /** "14 apps can read your text messages" — ready to render. */
    @SerialName("sentence") val sentence: String
)

/** One app holding one special access, for the device-wide special-access roster. */
@Serializable
data class SpecialAccessHolder(
    @SerialName("kind") val kind: SpecialAccessKind,
    @SerialName("package_name") val packageName: String,
    @SerialName("label") val label: String,
    @SerialName("state") val state: AccessState
)

@Serializable
data class BandCounts(
    @SerialName("low") val low: Int = 0,
    @SerialName("medium") val medium: Int = 0,
    @SerialName("high") val high: Int = 0,
    @SerialName("critical") val critical: Int = 0
) {
    val total: Int get() = low + medium + high + critical
    val needingAttention: Int get() = high + critical
}

/** Everything the home screen needs. */
@Serializable
data class DeviceReport(
    @SerialName("scanned_at") val scannedAt: Long,
    @SerialName("app_count") val appCount: Int,
    @SerialName("included_system_apps") val includedSystemApps: Boolean,
    @SerialName("band_counts") val bandCounts: BandCounts,
    /** Q1: apps ranked by risk, worst first. */
    @SerialName("ranked") val ranked: List<RankedApp>,
    @SerialName("category_rollups") val categoryRollups: List<CategoryRollup>,
    @SerialName("special_access_roster") val specialAccessRoster: List<SpecialAccessHolder>,
    /** Packages skipped during the scan, with the reason. Shown as a footnote, never hidden. */
    @SerialName("skipped") val skipped: Map<String, String> = emptyMap(),
    /** One-line device summary, e.g. "3 apps need your attention". */
    @SerialName("headline") val headline: String
)

/** A permission row inside a category group on the detail screen. */
@Serializable
data class PermissionRow(
    @SerialName("name") val name: String,
    @SerialName("short_name") val shortName: String,
    @SerialName("plain_text") val plainText: String,
    @SerialName("granted") val granted: Boolean,
    @SerialName("protection") val protection: String,
    /** True when the app has asked but has not been given it. */
    @SerialName("latent") val latent: Boolean
)

@Serializable
data class CategoryGroup(
    @SerialName("category") val category: Category,
    @SerialName("display_name") val displayName: String,
    @SerialName("points") val points: Int,
    @SerialName("permissions") val permissions: List<PermissionRow>
)

/** One thing the user can do about this app. Q3. */
@Serializable
data class ActionItem(
    @SerialName("id") val id: String,
    @SerialName("order") val order: Int,
    @SerialName("title") val title: String,
    @SerialName("explanation") val explanation: String,
    @SerialName("kind") val kind: ActionKind,
    /** True when this step must be done before later ones can succeed. */
    @SerialName("blocking") val blocking: Boolean = false
)

@Serializable
enum class ActionKind {
    @SerialName("open_app_settings") OPEN_APP_SETTINGS,
    @SerialName("open_accessibility_settings") OPEN_ACCESSIBILITY_SETTINGS,
    @SerialName("open_overlay_settings") OPEN_OVERLAY_SETTINGS,
    @SerialName("open_notification_listener_settings") OPEN_NOTIFICATION_LISTENER_SETTINGS,
    @SerialName("open_usage_access_settings") OPEN_USAGE_ACCESS_SETTINGS,
    @SerialName("open_unknown_sources_settings") OPEN_UNKNOWN_SOURCES_SETTINGS,
    @SerialName("open_all_files_access_settings") OPEN_ALL_FILES_ACCESS_SETTINGS,
    @SerialName("open_device_admin_settings") OPEN_DEVICE_ADMIN_SETTINGS,
    @SerialName("open_vpn_settings") OPEN_VPN_SETTINGS,
    @SerialName("uninstall") UNINSTALL,
    @SerialName("disable_system_app") DISABLE_SYSTEM_APP
}

@Serializable
data class AppHeader(
    @SerialName("package_name") val packageName: String,
    @SerialName("label") val label: String,
    @SerialName("version_name") val versionName: String?,
    @SerialName("first_install_time") val firstInstallTime: Long,
    @SerialName("last_update_time") val lastUpdateTime: Long,
    @SerialName("install_source") val installSource: String,
    @SerialName("target_sdk") val targetSdk: Int,
    @SerialName("is_system") val isSystem: Boolean,
    @SerialName("has_launcher_icon") val hasLauncherIcon: Boolean
)

/** Everything the detail screen needs. */
@Serializable
data class AppDetail(
    @SerialName("header") val header: AppHeader,
    @SerialName("score") val score: Int,
    @SerialName("band") val band: RiskBand,
    /** Q2: ranked plain-language reasons. At least three for High and Critical. */
    @SerialName("why_flagged") val whyFlagged: List<Reason>,
    @SerialName("category_groups") val categoryGroups: List<CategoryGroup>,
    @SerialName("special_access") val specialAccess: List<SpecialAccessRow>,
    /** Q3: ordered remediation steps. */
    @SerialName("actions") val actions: List<ActionItem>,
    /** Shown beside the rule score, never instead of it. */
    @SerialName("ml") val ml: MlResult? = null,
    /** True when the model and the rules disagree, so the UI can say so plainly. */
    @SerialName("ml_disagrees_with_rules") val mlDisagreesWithRules: Boolean = false,
    @SerialName("hash_verdict") val hashVerdict: HashVerdict = HashVerdict.SkippedNoConsent,
    @SerialName("apk_sha256") val apkSha256: String? = null,
    @SerialName("manifest") val manifest: ManifestFindings = ManifestFindings.NOT_ANALYSED,
    /** What changed since the previous scan, if anything. */
    @SerialName("recent_changes") val recentChanges: List<String> = emptyList()
)

@Serializable
data class SpecialAccessRow(
    @SerialName("kind") val kind: SpecialAccessKind,
    @SerialName("display_name") val displayName: String,
    @SerialName("state") val state: AccessState,
    /** Plain sentence: "It can read everything on your screen" vs "It has asked for ...". */
    @SerialName("sentence") val sentence: String,
    @SerialName("evidence") val evidence: String?
)

internal fun PermissionRecord.toRow(plainText: String) = PermissionRow(
    name = name,
    shortName = shortName,
    plainText = plainText,
    granted = granted,
    protection = protection.name.lowercase(),
    latent = isLatent
)
