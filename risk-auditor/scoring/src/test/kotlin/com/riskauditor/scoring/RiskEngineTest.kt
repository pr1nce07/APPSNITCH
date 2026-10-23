package com.riskauditor.scoring

import com.google.common.truth.Truth.assertThat
import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.model.AccessState
import com.riskauditor.model.AppRecord
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.ComponentType
import com.riskauditor.model.ExportedComponent
import com.riskauditor.model.HashVerdict
import com.riskauditor.model.InstallerInfo
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.ManifestFindings
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.RiskBand
import com.riskauditor.model.SpecialAccessEntry
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.model.SpecialAccessState
import org.junit.Test

/**
 * JUnit mirror of the offline harness suites.
 *
 * The harness in `tools/offline-verify` exercises the same engine without any
 * third-party dependency, because the authoring environment could not download
 * JUnit. This file is the version that runs under `./gradlew :scoring:test` on a
 * networked machine. Both must pass; if they ever disagree, one of them is wrong
 * and that is worth knowing.
 */
class RiskEngineTest {

    private val catalog = CatalogLoader.default
    private val rubric = RubricLoader.default
    private val engine = RiskEngine(rubric, catalog)

    private fun perm(name: String, granted: Boolean = true, grantable: Boolean = true) =
        catalog.find(name).let { entry ->
            PermissionRecord(
                name = name,
                granted = granted,
                grantable = grantable,
                protection = entry?.protection ?: com.riskauditor.model.Protection.UNKNOWN,
                category = entry?.category ?: com.riskauditor.model.Category.UNCATEGORIZED
            )
        }

    private fun snapshot(
        pkg: String = "com.example.app",
        label: String = "Example App",
        installer: InstallerKind = InstallerKind.GOOGLE_PLAY,
        hasLauncherIcon: Boolean = true,
        isSystem: Boolean = false,
        targetSdk: Int = 34,
        permissions: List<PermissionRecord> = emptyList(),
        special: List<Pair<SpecialAccessKind, AccessState>> = emptyList(),
        manifest: ManifestFindings = ManifestFindings(cleartextTrafficAllowed = false),
        hashVerdict: HashVerdict = HashVerdict.SkippedNoConsent
    ) = AppSnapshot(
        app = AppRecord(
            packageName = pkg,
            label = label,
            targetSdk = targetSdk,
            installer = InstallerInfo(installer, installingPackage = "com.android.vending"),
            isSystem = isSystem,
            hasLauncherIcon = hasLauncherIcon
        ),
        permissions = permissions,
        specialAccess = SpecialAccessState(special.map { SpecialAccessEntry(it.first, it.second) }),
        manifest = manifest,
        hashVerdict = hashVerdict
    )

    // ------------------------------------------------------- invariants ----

    @Test
    fun `score is always within range`() {
        val everything = catalog.entries.map { perm(it.permission) }
        val result = engine.score(
            snapshot(
                installer = InstallerKind.ADB_OR_NONE,
                hasLauncherIcon = false,
                targetSdk = 21,
                permissions = everything,
                special = SpecialAccessKind.entries.map { it to AccessState.ENABLED },
                manifest = ManifestFindings(debuggable = true, cleartextTrafficAllowed = true, testOnly = true),
                hashVerdict = HashVerdict.Malicious("local", 50)
            )
        )
        assertThat(result.score).isAtMost(100)
        assertThat(result.score).isAtLeast(0)
    }

    @Test
    fun `permissions alone never reach high`() {
        val everything = catalog.entries.filter { !it.special }.map { perm(it.permission) }
        val result = engine.score(snapshot(permissions = everything))

        assertThat(result.band).isAnyOf(RiskBand.LOW, RiskBand.MEDIUM)
    }

    @Test
    fun `every high or critical result has at least three reasons`() {
        val result = engine.score(
            snapshot(
                installer = InstallerKind.SIDELOADED,
                permissions = listOf(perm("android.permission.READ_SMS"), perm("android.permission.INTERNET")),
                special = listOf(
                    SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                    SpecialAccessKind.OVERLAY to AccessState.ENABLED
                )
            )
        )
        assertThat(result.band).isEqualTo(RiskBand.CRITICAL)
        assertThat(result.reasons.size).isAtLeast(3)
    }

    @Test
    fun `reasons are sorted by contribution`() {
        val result = engine.score(
            snapshot(
                installer = InstallerKind.SIDELOADED,
                permissions = listOf(perm("android.permission.READ_SMS"), perm("android.permission.INTERNET")),
                special = listOf(SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED)
            )
        )
        val points = result.reasons.map { it.points }
        assertThat(points).isEqualTo(points.sortedDescending())
    }

    // --------------------------------------------------------- profiles ----

    @Test
    fun `a clean play-installed app is low`() {
        val result = engine.score(snapshot(permissions = listOf(perm("android.permission.INTERNET"))))
        assertThat(result.band).isEqualTo(RiskBand.LOW)
    }

    @Test
    fun `accessibility plus overlay is critical`() {
        val result = engine.score(
            snapshot(
                special = listOf(
                    SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                    SpecialAccessKind.OVERLAY to AccessState.ENABLED
                )
            )
        )
        assertThat(result.band).isEqualTo(RiskBand.CRITICAL)
        assertThat(result.reasons.map { it.code }).contains("COMBO_ACCESSIBILITY_OVERLAY")
    }

    @Test
    fun `a torch app holding sms is critical`() {
        val result = engine.score(
            snapshot(
                pkg = "com.bright.torch",
                label = "Super Torch Flashlight",
                installer = InstallerKind.SIDELOADED,
                permissions = listOf(
                    perm("android.permission.READ_SMS"),
                    perm("android.permission.READ_CONTACTS"),
                    perm("android.permission.READ_CALL_LOG"),
                    perm("android.permission.INTERNET")
                )
            )
        )
        assertThat(result.band).isEqualTo(RiskBand.CRITICAL)
        assertThat(result.reasons.map { it.code }.any { it.startsWith("CONTEXT_MISMATCH") }).isTrue()
    }

    // --------------------------------------------------------- modifiers ----

    @Test
    fun `manifest findings add points`() {
        val clean = engine.score(
            snapshot(
                permissions = listOf(perm("android.permission.READ_SMS")),
                manifest = ManifestFindings(cleartextTrafficAllowed = false)
            )
        ).score

        val dirty = engine.score(
            snapshot(
                permissions = listOf(perm("android.permission.READ_SMS")),
                manifest = ManifestFindings(
                    debuggable = true,
                    cleartextTrafficAllowed = true,
                    testOnly = true,
                    exportedComponents = listOf(
                        ExportedComponent(ComponentType.PROVIDER, "com.a.Provider", permission = null)
                    )
                )
            )
        ).score

        assertThat(dirty).isGreaterThan(clean)
    }

    @Test
    fun `unanalysed manifests add nothing`() {
        val a = engine.score(
            snapshot(
                permissions = listOf(perm("android.permission.READ_SMS")),
                manifest = ManifestFindings.NOT_ANALYSED
            )
        ).score
        val b = engine.score(
            snapshot(
                permissions = listOf(perm("android.permission.READ_SMS")),
                manifest = ManifestFindings(cleartextTrafficAllowed = false)
            )
        ).score
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `a guarded exported component is not penalised`() {
        val guarded = engine.score(
            snapshot(
                manifest = ManifestFindings(
                    exportedComponents = listOf(
                        ExportedComponent(
                            ComponentType.PROVIDER, "com.a.P",
                            permission = "com.a.permission.ACCESS"
                        )
                    )
                )
            )
        ).score
        val none = engine.score(snapshot()).score
        assertThat(guarded).isEqualTo(none)
    }

    @Test
    fun `sideloading increases the score`() {
        val perms = listOf(perm("android.permission.READ_CONTACTS"))
        assertThat(engine.score(snapshot(installer = InstallerKind.SIDELOADED, permissions = perms)).score)
            .isGreaterThan(engine.score(snapshot(installer = InstallerKind.GOOGLE_PLAY, permissions = perms)).score)
    }

    @Test
    fun `an old target sdk increases the score`() {
        val perms = listOf(perm("android.permission.READ_CONTACTS"))
        assertThat(engine.score(snapshot(targetSdk = 21, permissions = perms)).score)
            .isGreaterThan(engine.score(snapshot(targetSdk = 34, permissions = perms)).score)
    }

    @Test
    fun `a malicious verdict forces critical and lifts the score to match`() {
        val result = engine.score(
            snapshot(
                permissions = listOf(perm("android.permission.INTERNET")),
                hashVerdict = HashVerdict.Malicious("local", 9)
            )
        )
        assertThat(result.band).isEqualTo(RiskBand.CRITICAL)
        // The displayed number must not contradict the band.
        assertThat(result.score).isAtLeast(rubric.bands.criticalFrom)
        assertThat(result.bandFloorRule).isEqualTo("THREAT_INTEL_MALICIOUS")
    }

    @Test
    fun `skipped consent contributes nothing`() {
        val a = engine.score(snapshot(hashVerdict = HashVerdict.SkippedNoConsent)).score
        val b = engine.score(snapshot(hashVerdict = HashVerdict.NotLookedUp)).score
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `an ungrantable signature permission adds nothing`() {
        val none = engine.score(snapshot()).score
        val ungrantable = engine.score(
            snapshot(
                permissions = listOf(
                    perm("android.permission.CAPTURE_AUDIO_OUTPUT", granted = false, grantable = false)
                )
            )
        ).score
        assertThat(ungrantable).isEqualTo(none)
    }

    @Test
    fun `special access is never double counted`() {
        val both = engine.score(
            snapshot(
                permissions = listOf(perm("android.permission.SYSTEM_ALERT_WINDOW")),
                special = listOf(SpecialAccessKind.OVERLAY to AccessState.ENABLED)
            )
        ).score
        val onlySpecial = engine.score(
            snapshot(special = listOf(SpecialAccessKind.OVERLAY to AccessState.ENABLED))
        ).score
        assertThat(both).isEqualTo(onlySpecial)
    }
}

/** Configuration integrity of rubric.json itself. */
class RubricTest {

    private val rubric = RubricLoader.default

    @Test
    fun `every combination predicate has numbers and vice versa`() {
        assertThat(rubric.combinationRules.keys).containsExactlyElementsIn(CombinationRules.ids)
    }

    @Test
    fun `band thresholds are strictly increasing`() {
        assertThat(rubric.bands.mediumFrom).isLessThan(rubric.bands.highFrom)
        assertThat(rubric.bands.highFrom).isLessThan(rubric.bands.criticalFrom)
    }

    @Test
    fun `bands cover the whole range with no gaps`() {
        val seen = (0..100).map { rubric.bandFor(it) }.toSet()
        assertThat(seen).containsExactly(
            RiskBand.LOW, RiskBand.MEDIUM, RiskBand.HIGH, RiskBand.CRITICAL
        )
    }

    @Test
    fun `network is capped at five points`() {
        assertThat(rubric.caps.networkCategory).isAtMost(5.0)
    }

    @Test
    fun `special access carries the highest base weight`() {
        val special = rubric.weightFor(com.riskauditor.model.Category.SPECIAL_ACCESS).base
        com.riskauditor.model.Category.entries
            .filter { it != com.riskauditor.model.Category.SPECIAL_ACCESS }
            .forEach { assertThat(rubric.weightFor(it).base).isAtMost(special) }
    }

    @Test
    fun `an invalid band ordering is rejected at construction`() {
        runCatching { BandThresholds(mediumFrom = 80, highFrom = 60, criticalFrom = 30) }
            .onSuccess { error("BandThresholds should reject a decreasing ordering") }
    }

    @Test
    fun `every combination rule has user facing text`() {
        rubric.combinationRules.values.forEach {
            assertThat(it.headline).isNotEmpty()
            assertThat(it.detail).isNotEmpty()
            assertThat(it.bonus).isGreaterThan(0)
        }
    }

    @Test
    fun `band lower bounds match the thresholds`() {
        assertThat(rubric.bands.lowerBoundOf(RiskBand.LOW)).isEqualTo(0)
        assertThat(rubric.bands.lowerBoundOf(RiskBand.MEDIUM)).isEqualTo(rubric.bands.mediumFrom)
        assertThat(rubric.bands.lowerBoundOf(RiskBand.HIGH)).isEqualTo(rubric.bands.highFrom)
        assertThat(rubric.bands.lowerBoundOf(RiskBand.CRITICAL)).isEqualTo(rubric.bands.criticalFrom)
    }
}
