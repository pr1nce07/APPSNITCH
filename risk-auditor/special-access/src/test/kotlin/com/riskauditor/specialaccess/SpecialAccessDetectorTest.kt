package com.riskauditor.specialaccess

import com.google.common.truth.Truth.assertThat
import com.riskauditor.model.AccessState
import com.riskauditor.model.AppRecord
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.trust.DeviceRole
import org.junit.Test

/* ---- Fakes: one per subsystem, so each state can be set independently ---- */

class FakeSettings(private val values: Map<String, String?> = emptyMap()) : SettingsSource {
    override fun secureString(key: String): String? = values[key]
}

class FakeAppOps(
    private val results: Map<String, AppOpsResult> = emptyMap(),
    private val throwFor: Set<String> = emptySet()
) : AppOpsSource {
    override fun checkOp(opString: String, uid: Int, packageName: String): AppOpsResult {
        if (opString in throwFor) throw SecurityException("this OEM refuses")
        return results[opString] ?: AppOpsResult.UNREADABLE
    }
}

class FakeAdmins(private val admins: Map<String, String> = emptyMap()) : DeviceAdminSource {
    override fun activeAdmins(): Map<String, String> = admins
}

class FakeAccessibility(private val services: Map<String, String> = emptyMap()) : AccessibilitySource {
    override fun enabledServices(): Map<String, String> = services
}

class FakePower(private val exempt: Set<String> = emptySet()) : PowerSource {
    override fun isIgnoringBatteryOptimizations(packageName: String) = packageName in exempt
}

class FakeVpn(private val packages: Set<String> = emptySet()) : VpnSource {
    override fun packagesWithVpnService(): Set<String> = packages
}

class FakeRoles(
    private val sms: String? = null,
    private val dialer: String? = null,
    private val assistant: String? = null,
    private val browser: String? = null,
    private val home: String? = null
) : RoleSource {
    override fun defaultSmsPackage() = sms
    override fun defaultDialerPackage() = dialer
    override fun defaultAssistantPackage() = assistant
    override fun defaultBrowserPackage() = browser
    override fun defaultHomePackage() = home
}

class SpecialAccessDetectorTest {

    private val app = AppRecord(packageName = "com.example.app", label = "Example", uid = 10123)

    private fun detector(
        settings: SettingsSource = FakeSettings(),
        appOps: AppOpsSource = FakeAppOps(),
        admins: DeviceAdminSource = FakeAdmins(),
        accessibility: AccessibilitySource = FakeAccessibility(),
        power: PowerSource = FakePower(),
        vpn: VpnSource = FakeVpn()
    ) = SpecialAccessDetector(settings, appOps, admins, accessibility, power, vpn)

    private fun perms(vararg names: String) = names.map { PermissionRecord(it, declared = true) }

    // ------------------------------------------------------- accessibility ----

    @Test
    fun `enabled accessibility service is detected`() {
        val d = detector(
            accessibility = FakeAccessibility(
                mapOf("com.example.app" to "com.example.app/.MyService")
            )
        )

        val state = d.detect(app, perms("android.permission.BIND_ACCESSIBILITY_SERVICE"))

        assertThat(state.stateOf(SpecialAccessKind.ACCESSIBILITY)).isEqualTo(AccessState.ENABLED)
        assertThat(state.entries.first { it.kind == SpecialAccessKind.ACCESSIBILITY }.component)
            .isEqualTo("com.example.app/.MyService")
    }

    @Test
    fun `declared but not enabled accessibility is declared only`() {
        val state = detector().detect(app, perms("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertThat(state.stateOf(SpecialAccessKind.ACCESSIBILITY)).isEqualTo(AccessState.DECLARED_ONLY)
    }

    @Test
    fun `an app that neither declares nor holds accessibility gets no entry`() {
        val state = detector().detect(app, perms("android.permission.INTERNET"))
        assertThat(state.stateOf(SpecialAccessKind.ACCESSIBILITY)).isEqualTo(AccessState.NOT_PRESENT)
    }

    // ------------------------------------------------------- device admin ----

    @Test
    fun `active device admin is detected`() {
        val d = detector(
            admins = FakeAdmins(mapOf("com.example.app" to "com.example.app/.AdminReceiver"))
        )
        val state = d.detect(app, perms("android.permission.BIND_DEVICE_ADMIN"))
        assertThat(state.stateOf(SpecialAccessKind.DEVICE_ADMIN)).isEqualTo(AccessState.ENABLED)
    }

    // ------------------------------------------------ notification listener ----

    @Test
    fun `enabled notification listener is detected`() {
        val d = detector(
            settings = FakeSettings(
                mapOf(
                    SettingsSource.ENABLED_NOTIFICATION_LISTENERS to
                        "com.other/.X:com.example.app/.Listener"
                )
            )
        )
        val state = d.detect(app, perms("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertThat(state.stateOf(SpecialAccessKind.NOTIFICATION_LISTENER)).isEqualTo(AccessState.ENABLED)
    }

    @Test
    fun `component lists tolerate OEM formatting quirks`() {
        // Trailing separators, semicolons and whitespace all appear in the wild.
        val parsed = SpecialAccessDetector.parseComponentListForTest(
            " com.a/.One ; com.b/.Two: :com.c/.Three:"
        )
        assertThat(parsed.keys).containsExactly("com.a", "com.b", "com.c")
    }

    @Test
    fun `an empty component list parses to nothing`() {
        assertThat(SpecialAccessDetector.parseComponentListForTest(null)).isEmpty()
        assertThat(SpecialAccessDetector.parseComponentListForTest("")).isEmpty()
    }

    // -------------------------------------------------------------- appops ----

    @Test
    fun `overlay appops allowed is reported as enabled`() {
        val d = detector(
            appOps = FakeAppOps(mapOf(AppOpsSource.OPSTR_SYSTEM_ALERT_WINDOW to AppOpsResult.ALLOWED))
        )
        val state = d.detect(app, perms("android.permission.SYSTEM_ALERT_WINDOW"))
        assertThat(state.stateOf(SpecialAccessKind.OVERLAY)).isEqualTo(AccessState.ENABLED)
    }

    @Test
    fun `declared but not granted is declared only`() {
        val d = detector(
            appOps = FakeAppOps(mapOf(AppOpsSource.OPSTR_SYSTEM_ALERT_WINDOW to AppOpsResult.DENIED))
        )
        val state = d.detect(app, perms("android.permission.SYSTEM_ALERT_WINDOW"))
        assertThat(state.stateOf(SpecialAccessKind.OVERLAY)).isEqualTo(AccessState.DECLARED_ONLY)
    }

    @Test
    fun `unreadable appops is unknown not denied`() {
        // THE important case. Reporting "no overlay access" when the truth is
        // "this phone would not tell us" is worse than saying nothing, because
        // the user acts on it.
        val d = detector(
            appOps = FakeAppOps(throwFor = setOf(AppOpsSource.OPSTR_SYSTEM_ALERT_WINDOW))
        )
        val state = d.detect(app, perms("android.permission.SYSTEM_ALERT_WINDOW"))

        assertThat(state.stateOf(SpecialAccessKind.OVERLAY)).isEqualTo(AccessState.UNKNOWN)
        assertThat(state.stateOf(SpecialAccessKind.OVERLAY)).isNotEqualTo(AccessState.DECLARED_ONLY)
    }

    @Test
    fun `appops mode default is also unknown`() {
        val d = detector(
            appOps = FakeAppOps(mapOf(AppOpsSource.OPSTR_GET_USAGE_STATS to AppOpsResult.DEFAULT))
        )
        val state = d.detect(app, perms("android.permission.PACKAGE_USAGE_STATS"))
        assertThat(state.stateOf(SpecialAccessKind.USAGE_STATS)).isEqualTo(AccessState.UNKNOWN)
    }

    @Test
    fun `install packages and all files access are detected through appops`() {
        val d = detector(
            appOps = FakeAppOps(
                mapOf(
                    AppOpsSource.OPSTR_REQUEST_INSTALL_PACKAGES to AppOpsResult.ALLOWED,
                    AppOpsSource.OPSTR_MANAGE_EXTERNAL_STORAGE to AppOpsResult.ALLOWED
                )
            )
        )
        val state = d.detect(
            app,
            perms(
                "android.permission.REQUEST_INSTALL_PACKAGES",
                "android.permission.MANAGE_EXTERNAL_STORAGE"
            )
        )

        assertThat(state.isEnabled(SpecialAccessKind.INSTALL_PACKAGES)).isTrue()
        assertThat(state.isEnabled(SpecialAccessKind.ALL_FILES_ACCESS)).isTrue()
    }

    @Test
    fun `an appop is never reported for a permission the app does not declare`() {
        val d = detector(
            appOps = FakeAppOps(mapOf(AppOpsSource.OPSTR_SYSTEM_ALERT_WINDOW to AppOpsResult.ALLOWED))
        )
        val state = d.detect(app, perms("android.permission.INTERNET"))
        assertThat(state.stateOf(SpecialAccessKind.OVERLAY)).isEqualTo(AccessState.NOT_PRESENT)
    }

    // ----------------------------------------------------------------- vpn ----

    @Test
    fun `a declared vpn service is never reported as enabled`() {
        // There is no public API to learn which app is the ACTIVE VPN, so
        // claiming ENABLED would be a guess dressed as a fact.
        val d = detector(vpn = FakeVpn(setOf("com.example.app")))
        val state = d.detect(app, perms("android.permission.BIND_VPN_SERVICE"))

        assertThat(state.stateOf(SpecialAccessKind.VPN_SERVICE)).isEqualTo(AccessState.DECLARED_ONLY)
    }

    // ------------------------------------------------------------- battery ----

    @Test
    fun `battery optimization exemption is detected`() {
        val d = detector(power = FakePower(setOf("com.example.app")))
        val state = d.detect(app, perms("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertThat(state.stateOf(SpecialAccessKind.BATTERY_OPTIMIZATION_EXEMPT))
            .isEqualTo(AccessState.ENABLED)
    }

    @Test
    fun `device state is read once and reused`() {
        val d = detector(
            accessibility = FakeAccessibility(mapOf("com.a" to "com.a/.S")),
            admins = FakeAdmins(mapOf("com.b" to "com.b/.A"))
        )
        val state = d.readDeviceState()

        val a = d.detect(
            AppRecord("com.a", "A"), perms("android.permission.BIND_ACCESSIBILITY_SERVICE"), state
        )
        val b = d.detect(
            AppRecord("com.b", "B"), perms("android.permission.BIND_DEVICE_ADMIN"), state
        )

        assertThat(a.isEnabled(SpecialAccessKind.ACCESSIBILITY)).isTrue()
        assertThat(b.isEnabled(SpecialAccessKind.DEVICE_ADMIN)).isTrue()
    }
}

class RoleResolverTest {

    @Test
    fun `roles are collected per package`() {
        val roles = RoleResolver.resolve(
            FakeRoles(sms = "com.sms.app", dialer = "com.dialer.app", home = "com.launcher")
        )

        assertThat(roles.holds("com.sms.app", DeviceRole.DEFAULT_SMS)).isTrue()
        assertThat(roles.holds("com.dialer.app", DeviceRole.DEFAULT_DIALER)).isTrue()
        assertThat(roles.holds("com.launcher", DeviceRole.DEFAULT_HOME)).isTrue()
    }

    @Test
    fun `one package can hold several roles`() {
        val roles = RoleResolver.resolve(FakeRoles(sms = "com.both", dialer = "com.both"))
        assertThat(roles.rolesOf("com.both"))
            .containsExactly(DeviceRole.DEFAULT_SMS, DeviceRole.DEFAULT_DIALER)
    }

    @Test
    fun `missing roles produce an empty holder set`() {
        assertThat(RoleResolver.resolve(FakeRoles()).rolesByPackage).isEmpty()
    }

    @Test
    fun `blank role holders are ignored`() {
        assertThat(RoleResolver.resolve(FakeRoles(sms = "  ")).rolesByPackage).isEmpty()
    }
}
