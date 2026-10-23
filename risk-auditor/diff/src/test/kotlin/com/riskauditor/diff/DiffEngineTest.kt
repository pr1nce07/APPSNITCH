package com.riskauditor.diff

import com.google.common.truth.Truth.assertThat
import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.model.AccessState
import com.riskauditor.model.AppRecord
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.InstallerInfo
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessEntry
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.model.SpecialAccessState
import com.riskauditor.store.InMemorySnapshotStore
import org.junit.Test

class DiffEngineTest {

    private val catalog = CatalogLoader.default
    private val engine = DiffEngine(catalog)

    private fun snapshot(
        pkg: String = "com.example.app",
        label: String = "Example",
        version: String = "1.0",
        installer: String? = "com.android.vending",
        granted: List<String> = emptyList(),
        declaredOnly: List<String> = emptyList(),
        special: List<Pair<SpecialAccessKind, AccessState>> = emptyList()
    ) = AppSnapshot(
        app = AppRecord(
            packageName = pkg,
            label = label,
            versionName = version,
            installer = InstallerInfo(InstallerKind.GOOGLE_PLAY, installingPackage = installer)
        ),
        permissions = granted.map {
            PermissionRecord(it, granted = true, category = catalog.categoryOf(it))
        } + declaredOnly.map {
            PermissionRecord(it, granted = false, category = catalog.categoryOf(it))
        },
        specialAccess = SpecialAccessState(special.map { SpecialAccessEntry(it.first, it.second) }),
        scannedAt = 1L
    )

    // ------------------------------------------------------------- diffs ----

    @Test
    fun `no change raises no alert`() {
        val before = snapshot(granted = listOf("android.permission.INTERNET"))
        val after = snapshot(granted = listOf("android.permission.INTERNET"))

        assertThat(engine.diff(before, after)).isNull()
    }

    @Test
    fun `an update that adds SMS raises an alert`() {
        val before = snapshot(granted = listOf("android.permission.INTERNET"))
        val after = snapshot(
            version = "1.1",
            granted = listOf("android.permission.INTERNET", "android.permission.READ_SMS")
        )

        val diff = engine.diff(before, after)!!
        assertThat(diff.newlyDeclared).contains("android.permission.READ_SMS")

        val alerts = engine.alertsFor(diff)
        assertThat(alerts).isNotEmpty()
        assertThat(alerts.first().detail).contains("text messages")
    }

    @Test
    fun `a granted-only change is detected without a new declaration`() {
        val before = snapshot(declaredOnly = listOf("android.permission.READ_CONTACTS"))
        val after = snapshot(granted = listOf("android.permission.READ_CONTACTS"))

        val diff = engine.diff(before, after)!!
        assertThat(diff.newlyGranted).containsExactly("android.permission.READ_CONTACTS")
        assertThat(diff.newlyDeclared).isEmpty()

        assertThat(engine.alertsFor(diff).map { it.severity }).contains(Severity.HIGH)
    }

    @Test
    fun `newly enabled special access raises a critical alert`() {
        val before = snapshot(special = listOf(SpecialAccessKind.OVERLAY to AccessState.DECLARED_ONLY))
        val after = snapshot(special = listOf(SpecialAccessKind.OVERLAY to AccessState.ENABLED))

        val diff = engine.diff(before, after)!!
        assertThat(diff.newlyEnabledSpecialAccess).containsExactly(SpecialAccessKind.OVERLAY)
        assertThat(engine.alertsFor(diff).first().severity).isEqualTo(Severity.CRITICAL)
    }

    @Test
    fun `adding a trivial permission produces a diff but no alert`() {
        // WAKE_LOCK is real but low sensitivity: a diff, not something to
        // interrupt someone about. Alerting on everything trains people to
        // dismiss the notification.
        val before = snapshot(granted = listOf("android.permission.INTERNET"))
        val after = snapshot(
            granted = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
        )

        val diff = engine.diff(before, after)
        assertThat(diff).isNotNull()
        assertThat(engine.alertsFor(diff!!)).isEmpty()
    }

    @Test
    fun `a version change alone is recorded but not alerted`() {
        val before = snapshot(version = "1.0")
        val after = snapshot(version = "2.0")

        val diff = engine.diff(before, after)!!
        assertThat(diff.versionChanged).isEqualTo("1.0" to "2.0")
        assertThat(engine.alertsFor(diff)).isEmpty()
    }

    @Test
    fun `an installer change is alerted`() {
        val before = snapshot(installer = "com.android.vending")
        val after = snapshot(installer = "com.android.packageinstaller")

        val diff = engine.diff(before, after)!!
        assertThat(diff.installerChanged).isNotNull()
        assertThat(engine.alertsFor(diff).map { it.headline })
            .containsExactly("Example was reinstalled from somewhere else")
    }

    @Test
    fun `removed permissions are recorded`() {
        val before = snapshot(granted = listOf("android.permission.READ_SMS"))
        val after = snapshot(granted = emptyList())

        val diff = engine.diff(before, after)!!
        assertThat(diff.removed).containsExactly("android.permission.READ_SMS")
    }

    @Test
    fun `diffing against no previous snapshot returns null`() {
        assertThat(engine.diff(null, snapshot())).isNull()
    }

    // --------------------------------------------------------- whole scan ----

    @Test
    fun `the first run baselines and raises nothing`() {
        val store = InMemorySnapshotStore()

        val alerts = engine.diffAll(
            store,
            listOf(
                snapshot("com.a", granted = listOf("android.permission.READ_SMS")),
                snapshot("com.b", special = listOf(SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED))
            )
        )

        // On a fresh install every app looks like it "just gained" everything.
        assertThat(alerts).isEmpty()
        assertThat(store.all()).hasSize(2)
    }

    @Test
    fun `the second run does alert`() {
        val store = InMemorySnapshotStore()
        engine.diffAll(store, listOf(snapshot("com.a")))

        val alerts = engine.diffAll(
            store,
            listOf(snapshot("com.a", granted = listOf("android.permission.READ_SMS")))
        )

        assertThat(alerts).isNotEmpty()
    }

    @Test
    fun `uninstalled packages are cleaned out of the store`() {
        val store = InMemorySnapshotStore()
        engine.diffAll(store, listOf(snapshot("com.a"), snapshot("com.b")))

        engine.diffAll(store, listOf(snapshot("com.a")))

        assertThat(store.all().keys).containsExactly("com.a")
    }

    @Test
    fun `a reinstalled package is treated as new rather than diffed against stale data`() {
        val store = InMemorySnapshotStore()
        engine.diffAll(store, listOf(snapshot("com.a", granted = listOf("android.permission.READ_SMS"))))

        // Uninstalled...
        engine.diffAll(store, emptyList())
        assertThat(store.all()).isEmpty()

        // ...then back, with fewer permissions. No alert, because there is no
        // previous record to compare against.
        val alerts = engine.diffAll(store, listOf(snapshot("com.a")))
        assertThat(alerts).isEmpty()
    }
}
