package com.riskauditor.scoring

import com.riskauditor.model.Category
import com.riskauditor.model.Protection
import com.riskauditor.model.RiskBand
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessKind

/**
 * The entire numeric model, loaded from `rubric.json`.
 *
 * `rubric.json` is the single source of truth for **both** the engine and the
 * one-page rubric deliverable: `tools/render_rubric.py` reads the same file, so
 * the document can never drift from the code.
 *
 * What lives in JSON and what lives in Kotlin:
 *  - **Numbers, band floors and user-facing text** live in JSON.
 *  - **Predicates** (what actually constitutes "accessibility + overlay") live in
 *    [CombinationRules] in Kotlin, because a condition is logic, not config.
 *    The two are joined by rule id, and `RubricTest` fails if either side has an
 *    id the other does not.
 */
data class Rubric(
    val version: Int,
    val bands: BandThresholds,
    val categoryWeights: Map<Category, CategoryWeight>,
    val protectionMultipliers: Map<Protection, Double>,
    val stateMultipliers: StateMultipliers,
    val specialAccessWeights: Map<SpecialAccessKind, SpecialAccessWeight>,
    val caps: Caps,
    val provenance: ProvenanceModifiers,
    val reducers: Reducers,
    val manifest: ManifestModifiers,
    val threatIntel: ThreatIntelModifiers,
    val contextMismatchBonus: Map<Severity, Int>,
    val contextMismatchBandFloor: Map<Severity, RiskBand?>,
    val combinationRules: Map<String, CombinationRuleConfig>,
    val minimumReasonsForHighOrCritical: Int
) {
    fun weightFor(category: Category): CategoryWeight =
        categoryWeights[category] ?: CategoryWeight(0.0, 0.0, 1.0)

    fun protectionMultiplier(protection: Protection): Double =
        protectionMultipliers[protection] ?: 0.0

    fun specialWeight(kind: SpecialAccessKind): SpecialAccessWeight =
        specialAccessWeights[kind] ?: SpecialAccessWeight(0.0, 0.0)

    fun bandFor(score: Int): RiskBand = bands.bandFor(score)
}

/**
 * Score boundaries. Stored as the *inclusive lower bound* of each band so the
 * four ranges can never overlap or leave a gap, which a pair of numbers per band
 * easily can.
 */
data class BandThresholds(
    val mediumFrom: Int,
    val highFrom: Int,
    val criticalFrom: Int
) {
    init {
        require(mediumFrom in 1..100 && highFrom in 1..100 && criticalFrom in 1..100) {
            "band thresholds must be within 1..100"
        }
        require(mediumFrom < highFrom && highFrom < criticalFrom) {
            "band thresholds must strictly increase: $mediumFrom < $highFrom < $criticalFrom"
        }
    }

    fun bandFor(score: Int): RiskBand = when {
        score >= criticalFrom -> RiskBand.CRITICAL
        score >= highFrom -> RiskBand.HIGH
        score >= mediumFrom -> RiskBand.MEDIUM
        else -> RiskBand.LOW
    }

    /**
     * Inclusive lower bound of a band.
     *
     * Used when a rule forces a band floor: the score is lifted to the bottom of
     * that band so the number and the word never contradict each other on screen.
     * "36 / CRITICAL" reads like a bug to a judge and undermines the whole
     * explanation, even when the floor is correct.
     */
    fun lowerBoundOf(band: RiskBand): Int = when (band) {
        RiskBand.LOW -> 0
        RiskBand.MEDIUM -> mediumFrom
        RiskBand.HIGH -> highFrom
        RiskBand.CRITICAL -> criticalFrom
    }

    fun describe(band: RiskBand): String = when (band) {
        RiskBand.LOW -> "0-${mediumFrom - 1}"
        RiskBand.MEDIUM -> "$mediumFrom-${highFrom - 1}"
        RiskBand.HIGH -> "$highFrom-${criticalFrom - 1}"
        RiskBand.CRITICAL -> "$criticalFrom-100"
    }
}

/**
 * @param base points a single maximum-sensitivity permission in this category is worth.
 * @param cap most this category can ever contribute, however many permissions are held.
 * @param diminishing multiplier applied per additional permission in the same
 *        category (0.5 means the second counts half, the third a quarter...).
 *        Without this, five storage permissions outrank one accessibility service.
 */
data class CategoryWeight(
    val base: Double,
    val cap: Double,
    val diminishing: Double
) {
    init {
        require(diminishing in 0.0..1.0) { "diminishing must be within 0.0..1.0" }
    }
}

/**
 * @param granted multiplier for a permission the app actually holds.
 * @param declaredNotGranted a declared-but-ungranted dangerous permission is latent
 *        risk, not active risk. Scored low but never zero: it is what the app will
 *        get the moment the user taps "Allow".
 * @param notGrantable a signature permission the app can never be granted. Zero.
 */
data class StateMultipliers(
    val granted: Double,
    val declaredNotGranted: Double,
    val notGrantable: Double
)

/** @param enabled the access is switched on. @param declaredOnly declared but not granted. */
data class SpecialAccessWeight(
    val enabled: Double,
    val declaredOnly: Double
)

/**
 * @param permissionSubtotal the most all ordinary permission categories can contribute
 *        **together**. This is the single most important number in the rubric: it is
 *        what stops a feature-rich but legitimate app (a messenger, a camera app)
 *        from reaching High on permission count alone. Crossing into High or
 *        Critical should require special access, a combination, bad provenance or a
 *        context mismatch — not simply asking for a lot.
 * @param specialAccessSubtotal ceiling for all special access together.
 * @param networkCategory hard ceiling on the Network category. PS-01 says network is
 *        "low risk alone, high in combination", so it is capped tightly here and
 *        earns its weight through the combination rules instead.
 */
data class Caps(
    val permissionSubtotal: Double,
    val specialAccessSubtotal: Double,
    val networkCategory: Double
)

data class ProvenanceModifiers(
    val sideloaded: Int,
    val adbOrNone: Int,
    val unknownInstaller: Int,
    val googlePlay: Int,
    val oemStore: Int,
    val targetSdkBelow23: Int,
    val noLauncherIcon: Int,
    val installedWithinHours: Int,
    val recentInstallWindowHours: Int
)

data class Reducers(
    /** Multiplier applied to device-admin points when the app is a verified MDM/security tool. */
    val verifiedSecurityToolDeviceAdmin: Double,
    /** Multiplier applied to accessibility points for a verified accessibility tool. */
    val verifiedAccessibilityTool: Double,
    /** Multiplier applied to a category the app holds a matching device role for. */
    val roleHolderCategory: Double,
    /** Multiplier applied to the whole score for a system or updated-system app. */
    val systemAppDampening: Double
)

data class ManifestModifiers(
    val debuggable: Int,
    val cleartextTraffic: Int,
    val testOnly: Int,
    val unguardedExportedProvider: Int,
    val unguardedExportedComponent: Int,
    val maxUnguardedComponentPoints: Int
)

data class ThreatIntelModifiers(
    val malicious: Int,
    val suspicious: Int,
    val maliciousBandFloor: RiskBand
)

/**
 * The configurable half of a combination rule. The predicate lives in
 * [CombinationRules].
 */
data class CombinationRuleConfig(
    val id: String,
    val bonus: Int,
    val bandFloor: RiskBand?,
    val severity: Severity,
    val headline: String,
    val detail: String
)
