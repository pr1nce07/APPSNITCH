package com.riskauditor.fixtures

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent

/*
 * INERT FIXTURE COMPONENTS.
 *
 * Every class below is an empty no-op. They exist so that a fixture APK can
 * DECLARE a component type (an accessibility service, a device admin, a
 * notification listener) and therefore be detected by the scanner, without
 * doing anything at all at runtime.
 *
 * Do not add behaviour to any of these. If a fixture ever needs to actually do
 * something, it stops being an inert fixture and PS-01's "no live malware"
 * constraint is no longer obviously satisfied.
 */

/** Empty launcher activity. Shows nothing, does nothing. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

/** Declared so the app is detectable as an accessibility service holder. Handles nothing. */
class FixtureAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

/** Declared so the app can appear as a device admin. Enforces no policy. */
class FixtureDeviceAdminReceiver : DeviceAdminReceiver()

/** Declared so the app is detectable as a notification listener. Reads nothing. */
class FixtureNotificationListener : NotificationListenerService()

/** Declared so the app is detectable as boot-persistent. Starts nothing. */
class FixtureBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = Unit
}

/** An intentionally exported, unguarded provider. Backs no data whatsoever. */
class FixtureExportedProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
