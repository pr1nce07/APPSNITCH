package com.riskauditor.scoring

import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.Category
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.trust.TrustRegistry

/**
 * Everything a combination predicate is allowed to look at.
 *
 * Passing a prepared fact object rather than raw snapshots keeps every predicate
 * to one readable line and makes it impossible for a rule to reach for something
 * the engine has not already normalised.
 */
data class CombinationFacts(
    val accessibility: Boolean,
    val overlay: Boolean,
    val deviceAdmin: Boolean,
    val notificationListener: Boolean,
    val installPackages: Boolean,
    val usageStats: Boolean,
    val allFilesAccess: Boolean,
    val vpnService: Boolean,
    val readsSms: Boolean,
    val readsContacts: Boolean,
    val readsCallLog: Boolean,
    val camera: Boolean,
    val microphone: Boolean,
    val backgroundLocation: Boolean,
    val internet: Boolean,
    val bootPersistence: Boolean,
    val sideloaded: Boolean,
    val hiddenIcon: Boolean,
    val isVerifiedSecurityTool: Boolean
) {
    val cameraOrMic: Boolean get() = camera || microphone

    companion object {
        /**
         * Build the fact set from a snapshot.
         *
         * Special access is read from [AppSnapshot.specialAccess] and counted only
         * when actually ENABLED, never when merely declared: a combination rule
         * describes what an app *can do right now*, and a rule that fired on
         * declared-only access would overstate the case in its own reason text.
         */
        fun from(snapshot: AppSnapshot, trust: TrustRegistry): CombinationFacts {
            val sa = snapshot.specialAccess
            return CombinationFacts(
                accessibility = sa.isEnabled(SpecialAccessKind.ACCESSIBILITY),
                overlay = sa.isEnabled(SpecialAccessKind.OVERLAY),
                deviceAdmin = sa.isEnabled(SpecialAccessKind.DEVICE_ADMIN),
                notificationListener = sa.isEnabled(SpecialAccessKind.NOTIFICATION_LISTENER),
                installPackages = sa.isEnabled(SpecialAccessKind.INSTALL_PACKAGES),
                usageStats = sa.isEnabled(SpecialAccessKind.USAGE_STATS),
                allFilesAccess = sa.isEnabled(SpecialAccessKind.ALL_FILES_ACCESS),
                vpnService = sa.isEnabled(SpecialAccessKind.VPN_SERVICE),
                readsSms = snapshot.hasGranted("android.permission.READ_SMS") ||
                    snapshot.hasGranted("android.permission.RECEIVE_SMS"),
                readsContacts = snapshot.hasGranted("android.permission.READ_CONTACTS"),
                readsCallLog = snapshot.hasGranted("android.permission.READ_CALL_LOG"),
                camera = snapshot.hasGranted("android.permission.CAMERA"),
                microphone = snapshot.hasGranted("android.permission.RECORD_AUDIO"),
                backgroundLocation = snapshot.hasGranted("android.permission.ACCESS_BACKGROUND_LOCATION"),
                internet = snapshot.permissions.any {
                    it.name == "android.permission.INTERNET" && it.declared
                },
                bootPersistence = snapshot.permissions.any {
                    it.name == "android.permission.RECEIVE_BOOT_COMPLETED" && it.declared
                },
                sideloaded = snapshot.app.isFromUntrustedSource,
                hiddenIcon = !snapshot.app.hasLauncherIcon,
                isVerifiedSecurityTool = trust.deviceAdminIsExpected(snapshot.app)
            )
        }
    }
}

/**
 * The predicate half of each combination rule (PS-01 should-have 07).
 *
 * Ids here must match ids in `rubric.json` exactly. `CombinationRulesTest` asserts
 * both directions, so a rule can never have a predicate without numbers, or
 * numbers without a predicate.
 *
 * Note `INTERNET` and `RECEIVE_BOOT_COMPLETED` are checked as *declared* rather
 * than granted: both are install-time `normal` permissions that are always
 * granted when declared, so "granted" is not a meaningful distinction for them.
 */
object CombinationRules {

    val predicates: Map<String, (CombinationFacts) -> Boolean> = mapOf(

        // The classic banking-trojan pair: read the screen, then cover it.
        "COMBO_ACCESSIBILITY_OVERLAY" to { f -> f.accessibility && f.overlay },

        // OTP interception and exfiltration from an unreviewed source.
        "COMBO_SMS_INTERNET_BOOT_SIDELOADED" to { f ->
            f.readsSms && f.internet && f.bootPersistence && f.sideloaded
        },

        // Uninstall blocking by something that is not a security or work tool.
        "COMBO_DEVICE_ADMIN_NOT_SECURITY" to { f -> f.deviceAdmin && !f.isVerifiedSecurityTool },

        // Both places a one-time code can appear, plus a way out.
        "COMBO_NOTIFICATION_LISTENER_INTERNET_SMS" to { f ->
            f.notificationListener && f.internet && f.readsSms
        },

        // Weaker form of the above: notifications alone still carry OTPs.
        // Suppressed when the stronger rule already fired, see RiskEngine.
        "COMBO_NOTIFICATION_LISTENER_INTERNET" to { f ->
            f.notificationListener && f.internet && !f.readsSms
        },

        // Knowing when you open your bank, plus the ability to cover the screen.
        "COMBO_OVERLAY_USAGE_STATS" to { f -> f.overlay && f.usageStats },

        // Dropper pattern.
        "COMBO_INSTALL_PACKAGES_SIDELOADED_INTERNET" to { f ->
            f.installPackages && f.sideloaded && f.internet
        },

        // Can accept its own install prompt.
        "COMBO_ACCESSIBILITY_INSTALL_PACKAGES" to { f -> f.accessibility && f.installPackages },

        // Screen plus messages.
        "COMBO_ACCESSIBILITY_SMS" to { f -> f.accessibility && f.readsSms },

        // Covert monitoring shape.
        "COMBO_CAMERA_MIC_INTERNET_BOOT_HIDDEN" to { f ->
            f.cameraOrMic && f.internet && f.bootPersistence && f.hiddenIcon
        },

        // Covert tracking shape.
        "COMBO_BACKGROUND_LOCATION_BOOT_HIDDEN" to { f ->
            f.backgroundLocation && f.bootPersistence && f.hiddenIcon
        },

        // Enough to rebuild the social graph.
        "COMBO_CONTACTS_CALLLOG_SMS_INTERNET" to { f ->
            f.readsContacts && f.readsCallLog && f.readsSms && f.internet
        },

        // Documents and photos, from an unreviewed source.
        "COMBO_ALL_FILES_INTERNET_SIDELOADED" to { f ->
            f.allFilesAccess && f.internet && f.sideloaded
        },

        // Sees all traffic, from an unreviewed source.
        "COMBO_VPN_INTERNET_SIDELOADED" to { f -> f.vpnService && f.sideloaded }
    )

    /**
     * Rules suppressed when a stronger rule covering the same ground has fired.
     * Without this the user sees two reasons saying almost the same thing, and the
     * score double-counts one behaviour.
     *
     * key = the weaker rule, value = the rule whose firing suppresses it.
     */
    val supersededBy: Map<String, String> = mapOf(
        "COMBO_NOTIFICATION_LISTENER_INTERNET" to "COMBO_NOTIFICATION_LISTENER_INTERNET_SMS"
    )

    val ids: Set<String> get() = predicates.keys
}

/** Category helper used by the engine when attributing combination evidence. */
internal val COMBINATION_EVIDENCE_CATEGORIES = setOf(
    Category.SPECIAL_ACCESS,
    Category.SMS,
    Category.CONTACTS_AND_CALL_LOGS,
    Category.CAMERA_AND_MICROPHONE,
    Category.LOCATION,
    Category.NETWORK,
    Category.BACKGROUND_AND_PERSISTENCE
)
