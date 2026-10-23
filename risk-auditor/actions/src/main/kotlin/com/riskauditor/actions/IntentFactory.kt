package com.riskauditor.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.riskauditor.presentation.ActionItem
import com.riskauditor.presentation.ActionKind

/** An intent plus whether this device can actually handle it. */
data class ResolvedAction(
    val action: ActionItem,
    val intent: Intent,
    /** False when no activity on this device will accept the intent. */
    val resolvable: Boolean,
    /** Set when the primary intent was unresolvable and a fallback was substituted. */
    val fallbackReason: String? = null
)

/**
 * Builds the Android intents behind each remediation step (PS-01 must-have 06).
 *
 * ## Why every intent is resolved before being offered
 *
 * OEMs remove and rename Settings screens. `ACTION_MANAGE_OVERLAY_PERMISSION`
 * is missing on some builds; `ACTION_NOTIFICATION_LISTENER_SETTINGS` has moved;
 * `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` only exists from API 30. Firing an
 * unresolvable intent throws `ActivityNotFoundException`, which to a
 * non-technical user looks like this app is broken.
 *
 * So every action is resolved first, and anything unresolvable falls back to the
 * app's own settings page — a screen that exists on every Android device ever
 * shipped — with the reason recorded so the UI can say "open this manually".
 */
class IntentFactory(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    fun resolve(action: ActionItem, packageName: String): ResolvedAction {
        val primary = buildIntent(action.kind, packageName)

        if (primary != null && isResolvable(primary)) {
            return ResolvedAction(action, primary, resolvable = true)
        }

        val fallback = appDetailsIntent(packageName)
        return ResolvedAction(
            action = action,
            intent = fallback,
            resolvable = isResolvable(fallback),
            fallbackReason = if (primary == null) {
                "this version of Android has no dedicated screen for that"
            } else {
                "your phone does not have that settings screen"
            }
        )
    }

    fun resolveAll(actions: List<ActionItem>, packageName: String): List<ResolvedAction> =
        actions.sortedBy { it.order }.map { resolve(it, packageName) }

    private fun buildIntent(kind: ActionKind, packageName: String): Intent? = when (kind) {

        ActionKind.OPEN_APP_SETTINGS -> appDetailsIntent(packageName)

        ActionKind.UNINSTALL ->
            // ACTION_DELETE shows the standard uninstall confirmation. The app
            // also declares REQUEST_DELETE_PACKAGES so this is not silently
            // dropped on newer platforms.
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))

        ActionKind.DISABLE_SYSTEM_APP ->
            // There is no public intent to disable an app. The app-info screen
            // is where the Disable button lives, so that is where we send them.
            appDetailsIntent(packageName)

        ActionKind.OPEN_ACCESSIBILITY_SETTINGS ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        ActionKind.OPEN_OVERLAY_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", packageName, null)
                )
            } else {
                null
            }

        ActionKind.OPEN_NOTIFICATION_LISTENER_SETTINGS ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        ActionKind.OPEN_USAGE_ACCESS_SETTINGS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        ActionKind.OPEN_UNKNOWN_SOURCES_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", packageName, null)
                )
            } else {
                null
            }

        ActionKind.OPEN_ALL_FILES_ACCESS_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", packageName, null)
                )
            } else {
                null
            }

        ActionKind.OPEN_DEVICE_ADMIN_SETTINGS ->
            // No public constant; this is the documented Settings action string.
            Intent("android.settings.DEVICE_ADMIN_SETTINGS")

        ActionKind.OPEN_VPN_SETTINGS ->
            Intent(Settings.ACTION_VPN_SETTINGS)
    }

    /**
     * The app-info screen. Present on every Android device, which is why it is
     * the universal fallback.
     */
    fun appDetailsIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )

    @Suppress("DEPRECATION")
    private fun isResolvable(intent: Intent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
        } else {
            pm.resolveActivity(intent, 0) != null
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * Add [Intent.FLAG_ACTIVITY_NEW_TASK] when launching from a non-activity
     * context, such as a notification tap handler.
     */
    fun forLaunchOutsideActivity(intent: Intent): Intent =
        Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
