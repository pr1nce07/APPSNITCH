package com.riskauditor.manifestanalysis

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.riskauditor.model.ComponentType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestAnalyzerTest {

    private val analyzer = ManifestAnalyzer(
        ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
    )

    private fun packageInfo(
        flags: Int = 0,
        activities: List<ActivityInfo> = emptyList(),
        services: List<ServiceInfo> = emptyList(),
        providers: List<ProviderInfo> = emptyList()
    ) = PackageInfo().apply {
        packageName = "com.example.app"
        applicationInfo = ApplicationInfo().apply {
            packageName = "com.example.app"
            this.flags = flags
        }
        this.activities = activities.toTypedArray()
        this.services = services.toTypedArray()
        this.providers = providers.toTypedArray()
    }

    private fun activity(name: String, exported: Boolean, permission: String? = null) =
        ActivityInfo().apply {
            this.name = name
            this.exported = exported
            this.permission = permission
        }

    private fun provider(name: String, exported: Boolean, read: String? = null) =
        ProviderInfo().apply {
            this.name = name
            this.exported = exported
            this.readPermission = read
        }

    @Test
    fun `unguarded exported providers are reported`() {
        val findings = analyzer.analyse(
            packageInfo(
                providers = listOf(
                    provider("com.example.app.OpenProvider", exported = true),
                    provider("com.example.app.GuardedProvider", exported = true, read = "com.example.PERM"),
                    provider("com.example.app.PrivateProvider", exported = false)
                )
            )
        )

        assertThat(findings.unguardedExportedProviders.map { it.className })
            .containsExactly("com.example.app.OpenProvider")
        assertThat(findings.exportedComponents.map { it.className })
            .containsExactly("com.example.app.OpenProvider", "com.example.app.GuardedProvider")
    }

    @Test
    fun `exported activities are reported by type`() {
        val findings = analyzer.analyse(
            packageInfo(
                activities = listOf(
                    activity("com.example.app.Main", exported = true),
                    activity("com.example.app.Internal", exported = false)
                )
            )
        )

        val exported = findings.exportedComponents.single()
        assertThat(exported.type).isEqualTo(ComponentType.ACTIVITY)
        assertThat(exported.isUnguarded).isTrue()
    }

    @Test
    fun `a guarded component is not counted as unguarded`() {
        val findings = analyzer.analyse(
            packageInfo(
                activities = listOf(
                    activity("com.example.app.Guarded", exported = true, permission = "com.example.PERM")
                )
            )
        )

        assertThat(findings.exportedComponents).hasSize(1)
        assertThat(findings.unguardedExported).isEmpty()
    }

    @Test
    fun `application flags are decoded`() {
        val findings = analyzer.analyse(
            packageInfo(
                flags = ApplicationInfo.FLAG_DEBUGGABLE or
                    ApplicationInfo.FLAG_TEST_ONLY or
                    ApplicationInfo.FLAG_ALLOW_BACKUP or
                    ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC
            )
        )

        assertThat(findings.debuggable).isTrue()
        assertThat(findings.testOnly).isTrue()
        assertThat(findings.allowBackup).isTrue()
        assertThat(findings.cleartextTrafficAllowed).isTrue()
        assertThat(findings.analysed).isTrue()
    }

    @Test
    fun `a clean app reports nothing`() {
        val findings = analyzer.analyse(packageInfo())

        assertThat(findings.debuggable).isFalse()
        assertThat(findings.testOnly).isFalse()
        assertThat(findings.exportedComponents).isEmpty()
        assertThat(findings.analysed).isTrue()
    }

    @Test
    fun `a package with no application info reports an analysis error rather than throwing`() {
        val info = PackageInfo().apply { packageName = "com.broken" }
        val findings = analyzer.analyse(info)

        assertThat(findings.analysed).isFalse()
        assertThat(findings.analysisError).isNotNull()
    }

    @Test
    fun `a package that cannot be read reports an analysis error`() {
        val findings = analyzer.analyse("com.does.not.exist")

        assertThat(findings.analysed).isFalse()
        assertThat(findings.analysisError).isNotNull()
    }

    @Test
    fun `an unanalysed result is distinguishable from a clean one`() {
        // A clean app and an app we could not read must never look the same:
        // one means "nothing found", the other means "we do not know".
        val clean = analyzer.analyse(packageInfo())
        val unknown = com.riskauditor.model.ManifestFindings.NOT_ANALYSED

        assertThat(clean.analysed).isTrue()
        assertThat(unknown.analysed).isFalse()
    }
}
