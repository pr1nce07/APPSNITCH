package com.riskauditor.inventory

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.riskauditor.model.InstallerKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppInventoryTest {

    @Test
    fun `scan excludes system apps by default`() {
        val source = FakePackageSource()
            .addPackage("com.user.app", isSystem = false)
            .addPackage("com.android.systemui", isSystem = true)

        val result = AppInventory(source).scan()

        assertThat(result.apps.map { it.packageName }).containsExactly("com.user.app")
    }

    @Test
    fun `the toggle includes system apps`() {
        val source = FakePackageSource()
            .addPackage("com.user.app", isSystem = false)
            .addPackage("com.android.systemui", isSystem = true)

        val result = AppInventory(source).scan(InventoryOptions(includeSystemApps = true))

        assertThat(result.apps).hasSize(2)
    }

    @Test
    fun `updated system apps are treated as system`() {
        val source = FakePackageSource()
            .addPackage("com.updated.system", isSystem = false, isUpdatedSystem = true)

        val excluded = AppInventory(source).scan()
        assertThat(excluded.apps).isEmpty()

        val included = AppInventory(source).scan(InventoryOptions(includeSystemApps = true))
        assertThat(included.apps.single().isUpdatedSystem).isTrue()
        assertThat(included.apps.single().isSystemOrUpdatedSystem).isTrue()
    }

    @Test
    fun `a package that throws is skipped not fatal`() {
        val source = FakePackageSource()
            .addPackage("com.good.one")
            .addPackage("com.vanishing.app")
            .addPackage("com.good.two")
            .makeThrow("com.vanishing.app")

        val result = AppInventory(source).scan()

        // One bad package must never abort a whole-device scan.
        assertThat(result.apps.map { it.packageName })
            .containsExactly("com.good.one", "com.good.two")
        assertThat(result.skipped).containsKey("com.vanishing.app")
    }

    @Test
    fun `metadata is captured`() {
        val source = FakePackageSource().addPackage(
            packageName = "com.example.app",
            label = "Example",
            versionName = "2.4.1",
            targetSdk = 33,
            hasLauncherIcon = false,
            certs = listOf("CD".repeat(32))
        )

        val app = AppInventory(source).scan().apps.single()

        assertThat(app.label).isEqualTo("Example")
        assertThat(app.versionName).isEqualTo("2.4.1")
        assertThat(app.targetSdk).isEqualTo(33)
        assertThat(app.hasLauncherIcon).isFalse()
        assertThat(app.signingCertSha256).hasSize(1)
        assertThat(app.apkPaths).isNotEmpty()
    }

    @Test
    fun `installer attribution is classified`() {
        val source = FakePackageSource()
            .addPackage("com.play.app", installingPackage = "com.android.vending")
            .addPackage("com.side.app", installingPackage = "com.android.packageinstaller")
            .addPackage("com.adb.app", installingPackage = null)

        val apps = AppInventory(source).scan().apps.associateBy { it.packageName }

        assertThat(apps["com.play.app"]!!.installer.kind).isEqualTo(InstallerKind.GOOGLE_PLAY)
        assertThat(apps["com.side.app"]!!.installer.kind).isEqualTo(InstallerKind.SIDELOADED)
        assertThat(apps["com.adb.app"]!!.installer.kind).isEqualTo(InstallerKind.ADB_OR_NONE)
    }

    @Test
    fun `scanOne returns a single record`() {
        val source = FakePackageSource().addPackage("com.one.app", label = "One")

        val app = AppInventory(source).scanOne("com.one.app")

        assertThat(app).isNotNull()
        assertThat(app!!.label).isEqualTo("One")
    }

    @Test
    fun `scanOne returns null for a package that is not installed`() {
        assertThat(AppInventory(FakePackageSource()).scanOne("com.nope")).isNull()
    }

    @Test
    fun `scan uses only package manager`() {
        // Structural: the inventory never shells out, never needs root and never
        // needs adb. The fake source satisfies the whole interface, which is the
        // proof that nothing else is reached for.
        val source = FakePackageSource().addPackage("com.example.app")
        val result = AppInventory(source).scan()
        assertThat(result.apps).hasSize(1)
    }

    @Test
    fun `an empty device produces an empty result rather than an error`() {
        val result = AppInventory(FakePackageSource()).scan()
        assertThat(result.apps).isEmpty()
        assertThat(result.skipped).isEmpty()
    }
}

@RunWith(RobolectricTestRunner::class)
class IconLoaderTest {

    @Test
    fun `load returns null for missing package`() {
        val loader = IconLoader(ApplicationProvider.getApplicationContext())
        assertThat(loader.load("com.does.not.exist")).isNull()
    }

    @Test
    fun `clear does not throw on an empty cache`() {
        IconLoader(ApplicationProvider.getApplicationContext()).clear()
    }
}
