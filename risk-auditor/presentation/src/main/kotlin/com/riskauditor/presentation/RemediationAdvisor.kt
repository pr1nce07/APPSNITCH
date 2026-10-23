package com.riskauditor.presentation

import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.RiskBand
import com.riskauditor.model.RiskResult
import com.riskauditor.model.SpecialAccessKind

/**
 * Turns a verdict into an ordered list of things the user can actually do (Q3).
 *
 * **Order is the whole point of this class.** Android enforces real dependencies
 * between these steps, and an app that tells someone to "uninstall" an app
 * holding device administrator will send them to a dialog that simply refuses:
 *
 *  1. Device administrator must be deactivated *before* uninstall is possible.
 *  2. An accessibility service should be switched off first, because an app that
 *     can tap buttons for you can tap "Cancel" on its own uninstall prompt.
 *  3. Overlay access should go next, for the same reason: an app that can draw
 *     over the screen can cover the confirm button.
 *  4. Only then does uninstall work reliably.
 *
 * Getting this wrong is the difference between advice that works and advice that
 * leaves a non-technical person stuck in a loop blaming themselves.
 *
 * Pure Kotlin: this produces [ActionItem]s describing *what* to do. Turning one
 * into an Android `Intent` is `:actions`' job.
 */
object RemediationAdvisor {

    fun adviseFor(snapshot: AppSnapshot, risk: RiskResult): List<ActionItem> {
        val actions = mutableListOf<ActionItem>()
        var order = 0
        val enabled = snapshot.specialAccess.enabledKinds

        // --- Blocking steps, in the order Android actually requires ---

        if (SpecialAccessKind.DEVICE_ADMIN in enabled) {
            actions += ActionItem(
                id = "deactivate_device_admin",
                order = order++,
                title = "Turn off administrator access first",
                explanation = "While this app is a device administrator your phone will refuse to uninstall it. " +
                    "Turn that off here, then come back.",
                kind = ActionKind.OPEN_DEVICE_ADMIN_SETTINGS,
                blocking = true
            )
        }

        if (SpecialAccessKind.ACCESSIBILITY in enabled) {
            actions += ActionItem(
                id = "disable_accessibility",
                order = order++,
                title = "Turn off its access to your screen",
                explanation = "This app can read your screen and tap for you, which means it could cancel its own " +
                    "removal. Switch this off before anything else.",
                kind = ActionKind.OPEN_ACCESSIBILITY_SETTINGS,
                blocking = true
            )
        }

        if (SpecialAccessKind.OVERLAY in enabled) {
            actions += ActionItem(
                id = "disable_overlay",
                order = order++,
                title = "Stop it drawing over other apps",
                explanation = "An app that can cover the screen can hide the button you are trying to press.",
                kind = ActionKind.OPEN_OVERLAY_SETTINGS,
                blocking = true
            )
        }

        // --- Non-blocking special access ---

        if (SpecialAccessKind.NOTIFICATION_LISTENER in enabled) {
            actions += ActionItem(
                id = "disable_notification_access",
                order = order++,
                title = "Stop it reading your notifications",
                explanation = "This also stops it seeing one-time codes that arrive while your phone is locked.",
                kind = ActionKind.OPEN_NOTIFICATION_LISTENER_SETTINGS
            )
        }

        if (SpecialAccessKind.INSTALL_PACKAGES in enabled) {
            actions += ActionItem(
                id = "disable_install_unknown_apps",
                order = order++,
                title = "Stop it installing other apps",
                explanation = "This prevents it adding further software to your phone.",
                kind = ActionKind.OPEN_UNKNOWN_SOURCES_SETTINGS
            )
        }

        if (SpecialAccessKind.ALL_FILES_ACCESS in enabled) {
            actions += ActionItem(
                id = "revoke_all_files",
                order = order++,
                title = "Take away access to all your files",
                explanation = "It will still be able to use files you choose for it.",
                kind = ActionKind.OPEN_ALL_FILES_ACCESS_SETTINGS
            )
        }

        if (SpecialAccessKind.USAGE_STATS in enabled) {
            actions += ActionItem(
                id = "revoke_usage_access",
                order = order++,
                title = "Stop it tracking which apps you open",
                explanation = "Few apps need this. Turning it off rarely breaks anything.",
                kind = ActionKind.OPEN_USAGE_ACCESS_SETTINGS
            )
        }

        if (SpecialAccessKind.VPN_SERVICE in enabled) {
            actions += ActionItem(
                id = "review_vpn",
                order = order++,
                title = "Check your VPN settings",
                explanation = "This app can route everything your phone sends through itself.",
                kind = ActionKind.OPEN_VPN_SETTINGS
            )
        }

        // --- Always available ---

        actions += ActionItem(
            id = "review_permissions",
            order = order++,
            title = "Review its permissions",
            explanation = "You can take away individual permissions here without removing the app. " +
                "Some features may stop working, which is usually the point.",
            kind = ActionKind.OPEN_APP_SETTINGS
        )

        if (snapshot.app.isSystemOrUpdatedSystem) {
            // Offering "uninstall" on a system app sends the user to a dialog
            // that fails, which looks like a bug in this app rather than a
            // limitation of Android.
            actions += ActionItem(
                id = "disable_system_app",
                order = order++,
                title = "Disable it instead",
                explanation = "This app came with your phone and cannot be removed. Disabling stops it running.",
                kind = ActionKind.DISABLE_SYSTEM_APP
            )
        } else {
            actions += ActionItem(
                id = "uninstall",
                order = order++,
                title = if (risk.band == RiskBand.CRITICAL) "Remove this app" else "Remove it if you do not use it",
                explanation = if (risk.band == RiskBand.CRITICAL) {
                    "Given what this app can do, removing it is the safest option unless you are certain you need it."
                } else {
                    "If you do not recognise it or no longer use it, removing it is the simplest fix."
                },
                kind = ActionKind.UNINSTALL
            )
        }

        return actions
    }
}
