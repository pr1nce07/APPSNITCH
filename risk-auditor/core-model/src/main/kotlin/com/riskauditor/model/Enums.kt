package com.riskauditor.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The nine permission categories mandated by PS-01 section 2, plus one extra
 * grouping for access that does not fit them and an explicit fallback.
 *
 * Order matters: [SPECIAL_ACCESS] is first because it carries the highest weight
 * and should sort first in every device-wide summary.
 */
@Serializable
enum class Category(val displayName: String, val mandatedByProblemStatement: Boolean) {
    @SerialName("special_access")
    SPECIAL_ACCESS("Special access", true),

    @SerialName("sms")
    SMS("SMS", true),

    @SerialName("contacts_and_call_logs")
    CONTACTS_AND_CALL_LOGS("Contacts and call logs", true),

    @SerialName("camera_and_microphone")
    CAMERA_AND_MICROPHONE("Camera and microphone", true),

    @SerialName("location")
    LOCATION("Location", true),

    @SerialName("phone_and_identity")
    PHONE_AND_IDENTITY("Phone and identity", true),

    @SerialName("storage_and_media")
    STORAGE_AND_MEDIA("Storage and media", true),

    @SerialName("background_and_persistence")
    BACKGROUND_AND_PERSISTENCE("Background and persistence", true),

    @SerialName("network")
    NETWORK("Network", true),

    /** Tenth category — not mandated, but keeps genuinely sensitive access out of UNCATEGORIZED. */
    @SerialName("accounts_calendar_sensors_nearby")
    ACCOUNTS_CALENDAR_SENSORS_NEARBY("Accounts, calendar, sensors and nearby devices", false),

    /** Anything the catalog does not know: custom/vendor permissions. Never scored above 0. */
    @SerialName("uncategorized")
    UNCATEGORIZED("Uncategorized", false);

    companion object {
        /** The nine categories every submission must cover. */
        val MANDATED: List<Category> get() = entries.filter { it.mandatedByProblemStatement }
    }
}

/**
 * Android permission protection level.
 *
 * [SPECIAL] is not an AOSP protection level in its own right — it is how this app
 * labels permissions that are granted through a dedicated Settings screen or an
 * AppOps flag rather than a runtime dialog (SYSTEM_ALERT_WINDOW,
 * REQUEST_INSTALL_PACKAGES, PACKAGE_USAGE_STATS, MANAGE_EXTERNAL_STORAGE...).
 * See docs/ASSUMPTIONS.md A-03.
 */
@Serializable
enum class Protection {
    @SerialName("normal") NORMAL,
    @SerialName("dangerous") DANGEROUS,
    @SerialName("signature") SIGNATURE,
    @SerialName("special") SPECIAL,

    /** Custom, vendor-defined or unresolvable permission. Scored as 0. */
    @SerialName("unknown") UNKNOWN
}

/** Where an app came from. Drives the provenance modifiers in the rubric. */
@Serializable
enum class InstallerKind {
    @SerialName("google_play") GOOGLE_PLAY,

    /** Samsung Galaxy Store, Huawei AppGallery, Xiaomi GetApps, Amazon Appstore, ... */
    @SerialName("oem_store") OEM_STORE,

    /** A recognised third-party store that is neither Play nor an OEM store (e.g. F-Droid). */
    @SerialName("other_store") OTHER_STORE,

    /** Installed by the system package-installer UI, a file manager or a browser download. */
    @SerialName("sideloaded") SIDELOADED,

    /** Installer attribution is null — adb install, or an OS that dropped the record. */
    @SerialName("adb_or_none") ADB_OR_NONE,

    /** An installing package we have never seen. Treated as untrusted, not as sideloaded. */
    @SerialName("unknown") UNKNOWN;

    /** True when the install source gives us no positive trust signal. */
    val isUntrustedSource: Boolean
        get() = this == SIDELOADED || this == ADB_OR_NONE || this == UNKNOWN
}

/** Risk bands. Thresholds live in rubric.json, not here. */
@Serializable
enum class RiskBand {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
    @SerialName("critical") CRITICAL;

    /** Ordinal comparison helper so a band floor can be applied without ordinal arithmetic leaking out. */
    fun atLeast(other: RiskBand): RiskBand = if (this.ordinal >= other.ordinal) this else other

    /** PS-01 requires >= 3 plain-language reasons for every High or Critical app. */
    val requiresMinimumReasons: Boolean get() = this == HIGH || this == CRITICAL
}

/** Severity of a single [Reason]. Independent of the app's overall band. */
@Serializable
enum class Severity {
    @SerialName("info") INFO,
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
    @SerialName("critical") CRITICAL
}

/**
 * The special-access types PS-01 section 2 row 01 calls out, plus three more that
 * carry comparable power and are detectable without root.
 */
@Serializable
enum class SpecialAccessKind(val displayName: String) {
    @SerialName("accessibility")
    ACCESSIBILITY("Accessibility service"),

    @SerialName("overlay")
    OVERLAY("Draw over other apps"),

    @SerialName("device_admin")
    DEVICE_ADMIN("Device administrator"),

    @SerialName("notification_listener")
    NOTIFICATION_LISTENER("Notification access"),

    @SerialName("install_packages")
    INSTALL_PACKAGES("Install unknown apps"),

    @SerialName("usage_stats")
    USAGE_STATS("Usage access"),

    @SerialName("all_files_access")
    ALL_FILES_ACCESS("All files access"),

    @SerialName("vpn_service")
    VPN_SERVICE("VPN service"),

    @SerialName("battery_optimization_exempt")
    BATTERY_OPTIMIZATION_EXEMPT("Unrestricted background battery use")
}

/**
 * Whether a special access is actually switched on, merely declared, or absent.
 *
 * The distinction is load-bearing: an app that *declares* SYSTEM_ALERT_WINDOW but
 * has not been granted it is latent risk, and the UI must not claim otherwise.
 * [UNKNOWN] is used when AppOps could not be read on this device — see
 * docs/LIMITATIONS.md L-03.
 */
@Serializable
enum class AccessState {
    @SerialName("enabled") ENABLED,
    @SerialName("declared_only") DECLARED_ONLY,
    @SerialName("not_present") NOT_PRESENT,
    @SerialName("unknown") UNKNOWN;

    val isActive: Boolean get() = this == ENABLED

    /** True when the app could plausibly use this access (enabled, or we could not tell). */
    val isPotentiallyActive: Boolean get() = this == ENABLED || this == UNKNOWN
}
