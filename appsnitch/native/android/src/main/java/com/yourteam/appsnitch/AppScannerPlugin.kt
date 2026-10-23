package com.yourteam.appsnitch

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.riskauditor.app.AuditorFacade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Capacitor bridge between the AppSnitch web UI and the risk-auditor engine.
 * Method names and shapes follow appsnitch/docs/DATA-CONTRACT.md ("Device actions").
 * Registered in the plugin name configured in www/js/config.js (default "AppScanner").
 */
@CapacitorPlugin(name = "AppScanner")
class AppScannerPlugin : Plugin() {

    private val facade by lazy { AuditorFacade(context) }
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Resolves with a ScanResult (schemaVersion 1). */
    @PluginMethod
    fun scan(call: PluginCall) {
        scope.launch {
            try {
                val json = facade.scanForAppSnitch()
                call.resolve(JSObject(json))
            } catch (t: Throwable) {
                call.reject("Scan failed: ${t.message}")
            }
        }
    }

    /** Opens the system uninstall prompt. Resolves only if the package is gone afterwards. */
    @PluginMethod
    fun uninstall(call: PluginCall) {
        val pkg = call.getString("packageName")
        if (pkg.isNullOrBlank()) {
            call.reject("packageName is required")
            return
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        startActivityForResult(call, intent, "uninstallResult")
    }

    @ActivityCallback
    private fun uninstallResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val pkg = call.getString("packageName") ?: return
        if (!isInstalled(pkg)) call.resolve() else call.reject("Uninstall was cancelled")
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val pkg = call.getString("packageName")
        if (pkg.isNullOrBlank()) {
            call.reject("packageName is required")
            return
        }
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
        call.resolve()
    }

    @PluginMethod
    fun openSystemSetting(call: PluginCall) {
        val action = when (call.getString("setting")) {
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "overlay" -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            "notification_access" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "install_unknown_apps" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES else null
            else -> null
        }
        if (action == null) {
            call.reject("Unknown or unsupported setting")
            return
        }
        activity.startActivity(Intent(action))
        call.resolve()
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
