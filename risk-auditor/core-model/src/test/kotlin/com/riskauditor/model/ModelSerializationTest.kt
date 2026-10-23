package com.riskauditor.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class ModelSerializationTest {

    private fun sampleSnapshot(): AppSnapshot = AppSnapshot(
        app = AppRecord(
            packageName = "com.example.torch",
            label = "Super Torch",
            versionName = "1.4.2",
            versionCode = 1_4002L,
            firstInstallTime = 1_700_000_000_000L,
            lastUpdateTime = 1_700_500_000_000L,
            targetSdk = 33,
            minSdk = 21,
            installer = InstallerInfo(
                kind = InstallerKind.SIDELOADED,
                installingPackage = "com.android.packageinstaller"
            ),
            isSystem = false,
            hasLauncherIcon = true,
            signingCertSha256 = listOf("aa".repeat(32)),
            apkPaths = listOf("/data/app/com.example.torch/base.apk"),
            appCategory = -1,
            uid = 10234
        ),
        permissions = listOf(
            PermissionRecord("android.permission.READ_SMS", granted = true, protection = Protection.DANGEROUS, category = Category.SMS),
            PermissionRecord("android.permission.INTERNET", granted = true, protection = Protection.NORMAL, category = Category.NETWORK),
            PermissionRecord("com.example.custom.PERMISSION", granted = false, protection = Protection.UNKNOWN, category = Category.UNCATEGORIZED)
        ),
        specialAccess = SpecialAccessState(
            listOf(
                SpecialAccessEntry(SpecialAccessKind.OVERLAY, AccessState.DECLARED_ONLY, evidence = "manifest:SYSTEM_ALERT_WINDOW")
            )
        ),
        manifest = ManifestFindings(debuggable = true, cleartextTrafficAllowed = true),
        apkSha256 = "bb".repeat(32),
        hashVerdict = HashVerdict.SkippedNoConsent,
        ml = MlResult(0.81, MlLabel.FLAGGED, listOf(MlContributor("android.permission.READ_SMS", 1.4)), "test-1"),
        scannedAt = 1_700_600_000_000L
    )

    @Test
    fun `snapshot survives a json round trip unchanged`() {
        val original = sampleSnapshot()
        val encoded = ModelJson.strict.encodeToString(original)
        val decoded = ModelJson.strict.decodeFromString<AppSnapshot>(encoded)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `lenient parser tolerates unknown fields from a newer build`() {
        val encoded = ModelJson.strict.encodeToString(sampleSnapshot())
        // Simulate a field added by a future version of the app.
        val withExtra = encoded.replaceFirst("{", """{"a_field_from_the_future":123,""")

        val decoded = ModelJson.lenient.decodeFromString<AppSnapshot>(withExtra)
        assertThat(decoded.packageName).isEqualTo("com.example.torch")
    }

    @Test
    fun `strict parser rejects unknown fields so schema drift is caught in tests`() {
        val encoded = ModelJson.strict.encodeToString(sampleSnapshot())
        val withExtra = encoded.replaceFirst("{", """{"a_field_from_the_future":123,""")

        runCatching { ModelJson.strict.decodeFromString<AppSnapshot>(withExtra) }
            .onSuccess { error("strict parser should have rejected the unknown field") }
    }

    @Test
    fun `schema version is stamped on every snapshot`() {
        assertThat(sampleSnapshot().schemaVersion).isEqualTo(SCHEMA_VERSION)
        assertThat(ModelJson.isCompatible(SCHEMA_VERSION)).isTrue()
        assertThat(ModelJson.isCompatible(SCHEMA_VERSION + 1)).isFalse()
    }

    @Test
    fun `hash verdict polymorphism round trips for every variant`() {
        val variants: List<HashVerdict> = listOf(
            HashVerdict.SkippedNoConsent,
            HashVerdict.NotLookedUp,
            HashVerdict.Unknown("local"),
            HashVerdict.Clean("local"),
            HashVerdict.Suspicious("vt", 3),
            HashVerdict.Malicious("vt", 41),
            HashVerdict.Error("vt", "rate limited")
        )
        variants.forEach { v ->
            val json = ModelJson.strict.encodeToString(v)
            assertThat(ModelJson.strict.decodeFromString<HashVerdict>(json)).isEqualTo(v)
        }
    }

    @Test
    fun `risk result rejects an out of range score`() {
        runCatching { RiskResult(score = 101, band = RiskBand.CRITICAL) }
            .onSuccess { error("RiskResult must reject a score above 100") }
        runCatching { RiskResult(score = -1, band = RiskBand.LOW) }
            .onSuccess { error("RiskResult must reject a negative score") }
    }

    @Test
    fun `all nine mandated categories are present`() {
        assertThat(Category.MANDATED).hasSize(9)
        assertThat(Category.MANDATED).containsAtLeast(
            Category.SPECIAL_ACCESS,
            Category.SMS,
            Category.CONTACTS_AND_CALL_LOGS,
            Category.CAMERA_AND_MICROPHONE,
            Category.LOCATION,
            Category.PHONE_AND_IDENTITY,
            Category.STORAGE_AND_MEDIA,
            Category.BACKGROUND_AND_PERSISTENCE,
            Category.NETWORK
        )
    }

    @Test
    fun `band floor never lowers a band`() {
        assertThat(RiskBand.CRITICAL.atLeast(RiskBand.HIGH)).isEqualTo(RiskBand.CRITICAL)
        assertThat(RiskBand.LOW.atLeast(RiskBand.HIGH)).isEqualTo(RiskBand.HIGH)
    }

    @Test
    fun `golden file decodes with the lenient parser`() {
        val golden = javaClass.getResourceAsStream("/golden/app_snapshot_v1.json")
            ?.bufferedReader()?.readText()
            ?: error("golden/app_snapshot_v1.json missing from test resources")

        val decoded = ModelJson.lenient.decodeFromString<AppSnapshot>(golden)
        assertThat(decoded.packageName).isEqualTo("com.example.torch")
        assertThat(decoded.permissions.map { it.name })
            .contains("android.permission.READ_SMS")
        assertThat(decoded.specialAccess.stateOf(SpecialAccessKind.OVERLAY))
            .isEqualTo(AccessState.DECLARED_ONLY)
    }

    @Test
    fun `access state helpers distinguish enabled from declared only`() {
        val state = SpecialAccessState(
            listOf(
                SpecialAccessEntry(SpecialAccessKind.ACCESSIBILITY, AccessState.ENABLED),
                SpecialAccessEntry(SpecialAccessKind.OVERLAY, AccessState.DECLARED_ONLY),
                SpecialAccessEntry(SpecialAccessKind.USAGE_STATS, AccessState.UNKNOWN)
            )
        )
        assertThat(state.enabledKinds).containsExactly(SpecialAccessKind.ACCESSIBILITY)
        assertThat(state.declaredOnlyKinds).containsExactly(SpecialAccessKind.OVERLAY)
        assertThat(state.stateOf(SpecialAccessKind.DEVICE_ADMIN)).isEqualTo(AccessState.NOT_PRESENT)
        assertThat(AccessState.UNKNOWN.isPotentiallyActive).isTrue()
        assertThat(AccessState.UNKNOWN.isActive).isFalse()
    }

    @Test
    fun `installer kinds that carry no trust signal are flagged as untrusted`() {
        assertThat(InstallerKind.GOOGLE_PLAY.isUntrustedSource).isFalse()
        assertThat(InstallerKind.OEM_STORE.isUntrustedSource).isFalse()
        assertThat(InstallerKind.SIDELOADED.isUntrustedSource).isTrue()
        assertThat(InstallerKind.ADB_OR_NONE.isUntrustedSource).isTrue()
        assertThat(InstallerKind.UNKNOWN.isUntrustedSource).isTrue()
    }

    @Test
    fun `apps targeting below api 23 are marked as predating runtime permissions`() {
        val old = AppRecord("a", "A", targetSdk = 22)
        val modern = AppRecord("b", "B", targetSdk = 34)
        assertThat(old.predatesRuntimePermissions).isTrue()
        assertThat(modern.predatesRuntimePermissions).isFalse()
    }
}
