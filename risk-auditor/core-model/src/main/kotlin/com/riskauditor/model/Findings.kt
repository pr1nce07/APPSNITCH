package com.riskauditor.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One exported component found during static manifest analysis. */
@Serializable
data class ExportedComponent(
    @SerialName("type") val type: ComponentType,
    @SerialName("class_name") val className: String,
    /** The permission a caller must hold, or null when the component is unguarded. */
    @SerialName("permission") val permission: String? = null,
    /** Providers only: whether grantUriPermissions is set. */
    @SerialName("grant_uri_permissions") val grantUriPermissions: Boolean = false
) {
    val isUnguarded: Boolean get() = permission.isNullOrBlank()
}

@Serializable
enum class ComponentType {
    @SerialName("activity") ACTIVITY,
    @SerialName("service") SERVICE,
    @SerialName("receiver") RECEIVER,
    @SerialName("provider") PROVIDER
}

/**
 * Results of static inspection of the installed APK (PS-01 stretch goal 12).
 * Everything here is read from the already-installed package — no APK is copied,
 * uploaded or modified.
 */
@Serializable
data class ManifestFindings(
    @SerialName("exported_components") val exportedComponents: List<ExportedComponent> = emptyList(),
    /**
     * True when the app permits unencrypted HTTP. Derived from
     * `ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC` plus, where readable, the
     * network-security-config. Null when it could not be determined.
     */
    @SerialName("cleartext_traffic_allowed") val cleartextTrafficAllowed: Boolean? = null,
    @SerialName("debuggable") val debuggable: Boolean = false,
    @SerialName("allow_backup") val allowBackup: Boolean? = null,
    @SerialName("test_only") val testOnly: Boolean = false,
    /** Set when analysis was attempted but could not complete, with the reason. */
    @SerialName("analysis_error") val analysisError: String? = null
) {
    val unguardedExported: List<ExportedComponent> get() = exportedComponents.filter { it.isUnguarded }

    val unguardedExportedProviders: List<ExportedComponent>
        get() = unguardedExported.filter { it.type == ComponentType.PROVIDER }

    val analysed: Boolean get() = analysisError == null

    companion object {
        val NOT_ANALYSED = ManifestFindings(analysisError = "not_analysed")
    }
}

/**
 * Outcome of an optional threat-intelligence lookup of an APK's SHA-256.
 *
 * [SKIPPED_NO_CONSENT] is the default for every app. Nothing reaches the network
 * until the user explicitly opts in, and the opt-in is revocable.
 */
@Serializable
sealed interface HashVerdict {
    @Serializable
    @SerialName("skipped_no_consent")
    data object SkippedNoConsent : HashVerdict

    @Serializable
    @SerialName("not_looked_up")
    data object NotLookedUp : HashVerdict

    @Serializable
    @SerialName("unknown")
    data class Unknown(@SerialName("provider") val provider: String) : HashVerdict

    @Serializable
    @SerialName("clean")
    data class Clean(@SerialName("provider") val provider: String) : HashVerdict

    @Serializable
    @SerialName("suspicious")
    data class Suspicious(
        @SerialName("provider") val provider: String,
        @SerialName("detections") val detections: Int
    ) : HashVerdict

    @Serializable
    @SerialName("malicious")
    data class Malicious(
        @SerialName("provider") val provider: String,
        @SerialName("detections") val detections: Int
    ) : HashVerdict

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("provider") val provider: String,
        @SerialName("message") val message: String
    ) : HashVerdict
}

/**
 * Output of the optional on-device ML model (PS-01 stretch goal 13).
 *
 * This NEVER overrides the rule score and is NEVER the source of a user-facing
 * explanation; it is displayed beside the rule score so a judge can compare them.
 */
@Serializable
data class MlResult(
    @SerialName("probability") val probability: Double,
    @SerialName("label") val label: MlLabel,
    /** Permissions that pushed the probability most, strongest first. */
    @SerialName("top_contributors") val topContributors: List<MlContributor> = emptyList(),
    @SerialName("model_version") val modelVersion: String = "unknown",
    @SerialName("threshold") val threshold: Double = 0.5
)

@Serializable
enum class MlLabel {
    @SerialName("benign") BENIGN,
    @SerialName("flagged") FLAGGED
}

@Serializable
data class MlContributor(
    @SerialName("feature") val feature: String,
    @SerialName("weight") val weight: Double
)
