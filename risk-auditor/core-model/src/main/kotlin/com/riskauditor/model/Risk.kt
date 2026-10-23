package com.riskauditor.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One plain-language explanation of why an app scored what it scored.
 *
 * Reasons are emitted *by the scoring rules themselves*, never written in a
 * second pass. That is what keeps the number and the words from drifting apart.
 *
 * @param code stable machine identifier, e.g. `COMBO_ACCESSIBILITY_OVERLAY`.
 *             Used by tests and traceability; never shown to the user.
 * @param headline one short sentence, plain language, no permission constants.
 * @param detail one or two sentences saying what the app could actually do.
 * @param evidence the technical backing (permission names, AppOps results).
 *                 Shown only in the collapsible technical panel.
 * @param points how much this contributed to the score. Used to rank reasons.
 */
@Serializable
data class Reason(
    @SerialName("code") val code: String,
    @SerialName("severity") val severity: Severity,
    @SerialName("headline") val headline: String,
    @SerialName("detail") val detail: String,
    @SerialName("evidence") val evidence: List<String> = emptyList(),
    @SerialName("points") val points: Int = 0
)

/** Points attributed to one category, after caps and diminishing returns. */
@Serializable
data class CategoryScore(
    @SerialName("category") val category: Category,
    @SerialName("points") val points: Int,
    @SerialName("granted_count") val grantedCount: Int,
    @SerialName("declared_count") val declaredCount: Int
)

/**
 * The full scoring verdict for one app.
 *
 * Invariants enforced by :scoring and asserted in its tests:
 *  - [score] is always within 0..100.
 *  - when [band] is HIGH or CRITICAL, [reasons] has at least three distinct entries.
 *  - [reasons] is sorted by [Reason.points] descending.
 */
@Serializable
data class RiskResult(
    @SerialName("score") val score: Int,
    @SerialName("band") val band: RiskBand,
    @SerialName("reasons") val reasons: List<Reason> = emptyList(),
    @SerialName("category_scores") val categoryScores: List<CategoryScore> = emptyList(),
    /** Set when a rule forced a minimum band regardless of the raw number. */
    @SerialName("band_floor_rule") val bandFloorRule: String? = null,
    /** Raw total before clamping to 0..100. Kept for rubric calibration, never shown to users. */
    @SerialName("raw_score") val rawScore: Int = score
) {
    val topReason: Reason? get() = reasons.firstOrNull()

    init {
        require(score in 0..100) { "score must be within 0..100, was $score" }
    }
}

/**
 * Everything known about one app at one point in time: the scan unit.
 *
 * This is what :store persists and :diff compares. It is also exactly what the
 * headless debug dump writes out as JSON.
 */
@Serializable
data class AppSnapshot(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("app") val app: AppRecord,
    @SerialName("permissions") val permissions: List<PermissionRecord> = emptyList(),
    @SerialName("special_access") val specialAccess: SpecialAccessState = SpecialAccessState.EMPTY,
    @SerialName("manifest") val manifest: ManifestFindings = ManifestFindings.NOT_ANALYSED,
    @SerialName("apk_sha256") val apkSha256: String? = null,
    @SerialName("hash_verdict") val hashVerdict: HashVerdict = HashVerdict.SkippedNoConsent,
    @SerialName("ml") val ml: MlResult? = null,
    @SerialName("scanned_at") val scannedAt: Long = 0L
) {
    val packageName: String get() = app.packageName

    fun granted(): List<PermissionRecord> = permissions.filter { it.granted }

    fun permissionsIn(category: Category): List<PermissionRecord> =
        permissions.filter { it.category == category }

    fun hasGrantedIn(category: Category): Boolean =
        permissions.any { it.granted && it.category == category }

    fun hasGranted(permissionName: String): Boolean =
        permissions.any { it.name == permissionName && it.granted }
}

/** An app plus its verdict. The unit the UI ranks. */
@Serializable
data class ScoredApp(
    @SerialName("snapshot") val snapshot: AppSnapshot,
    @SerialName("risk") val risk: RiskResult
) {
    val packageName: String get() = snapshot.packageName
    val label: String get() = snapshot.app.label
}

/** A complete device scan: the root object of the JSON dump. */
@Serializable
data class DeviceScan(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("scanned_at") val scannedAt: Long = 0L,
    @SerialName("included_system_apps") val includedSystemApps: Boolean = false,
    @SerialName("apps") val apps: List<ScoredApp> = emptyList(),
    /** Packages that threw during scanning and were skipped, with the reason. One bad app never aborts a scan. */
    @SerialName("skipped") val skipped: Map<String, String> = emptyMap()
)
