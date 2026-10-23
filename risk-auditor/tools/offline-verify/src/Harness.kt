package verify

import com.riskauditor.model.AccessState
import com.riskauditor.model.AppRecord
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.Category
import com.riskauditor.model.InstallerInfo
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.ManifestFindings
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.Protection
import com.riskauditor.model.SpecialAccessEntry
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.model.SpecialAccessState
import com.riskauditor.catalog.CatalogLoader

/* ------------------------------------------------------------------ *
 * Minimal zero-dependency assertion harness.
 * Stands in for JUnit + Truth, which cannot be downloaded here.
 * ------------------------------------------------------------------ */

class AssertionFailed(message: String) : AssertionError(message)

object Check {
    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()
    private var currentSuite = ""

    fun suite(name: String) {
        currentSuite = name
        println()
        println("── $name ${"─".repeat(maxOf(0, 58 - name.length))}")
    }

    fun test(name: String, body: () -> Unit) {
        try {
            body()
            passed++
            println("  PASS  $name")
        } catch (e: Throwable) {
            failed++
            val where = if (currentSuite.isEmpty()) name else "$currentSuite / $name"
            failures += "$where\n          ${e.message}"
            println("  FAIL  $name")
            println("        ${e.message}")
        }
    }

    fun isTrue(condition: Boolean, message: String) {
        if (!condition) throw AssertionFailed(message)
    }

    fun isFalse(condition: Boolean, message: String) {
        if (condition) throw AssertionFailed(message)
    }

    fun <T> eq(actual: T, expected: T, what: String) {
        if (actual != expected) throw AssertionFailed("$what: expected <$expected> but was <$actual>")
    }

    fun inRange(actual: Int, min: Int, max: Int, what: String) {
        if (actual !in min..max) throw AssertionFailed("$what: expected within $min..$max but was $actual")
    }

    fun atLeast(actual: Int, min: Int, what: String) {
        if (actual < min) throw AssertionFailed("$what: expected at least $min but was $actual")
    }

    fun atMost(actual: Int, max: Int, what: String) {
        if (actual > max) throw AssertionFailed("$what: expected at most $max but was $actual")
    }

    fun summary(): Int {
        println()
        println("=".repeat(62))
        println("  passed: $passed    failed: $failed")
        if (failures.isNotEmpty()) {
            println()
            println("  FAILURES")
            failures.forEach { println("    - $it") }
        }
        println("=".repeat(62))
        return if (failed == 0) 0 else 1
    }
}

/* ------------------------------------------------------------------ *
 * Snapshot builders.
 *
 * Deliberately hand-built rather than captured from a device: the point is to
 * exercise the engine against profiles we control, including ones no real
 * device would conveniently provide.
 * ------------------------------------------------------------------ */

private val catalog by lazy { CatalogLoader.default }

fun perm(
    name: String,
    granted: Boolean = true,
    grantable: Boolean = true
): PermissionRecord {
    val entry = catalog.find(name)
    return PermissionRecord(
        name = name,
        declared = true,
        granted = granted,
        protection = entry?.protection ?: Protection.UNKNOWN,
        category = entry?.category ?: Category.UNCATEGORIZED,
        grantable = grantable
    )
}

fun app(
    pkg: String,
    label: String,
    installer: InstallerKind = InstallerKind.GOOGLE_PLAY,
    targetSdk: Int = 34,
    hasLauncherIcon: Boolean = true,
    isSystem: Boolean = false,
    appCategory: Int = -1,
    signingCerts: List<String> = listOf("de".repeat(32))
): AppRecord = AppRecord(
    packageName = pkg,
    label = label,
    versionName = "1.0",
    versionCode = 1,
    firstInstallTime = 1_700_000_000_000L,
    lastUpdateTime = 1_700_000_000_000L,
    targetSdk = targetSdk,
    minSdk = 26,
    installer = InstallerInfo(
        kind = installer,
        installingPackage = when (installer) {
            InstallerKind.GOOGLE_PLAY -> "com.android.vending"
            InstallerKind.SIDELOADED -> "com.android.packageinstaller"
            InstallerKind.ADB_OR_NONE -> null
            else -> "com.example.store"
        }
    ),
    isSystem = isSystem,
    hasLauncherIcon = hasLauncherIcon,
    signingCertSha256 = signingCerts,
    apkPaths = listOf("/data/app/$pkg/base.apk"),
    appCategory = appCategory,
    uid = 10000
)

fun snapshot(
    app: AppRecord,
    permissions: List<PermissionRecord> = emptyList(),
    special: List<Pair<SpecialAccessKind, AccessState>> = emptyList(),
    manifest: ManifestFindings = ManifestFindings(exportedComponents = emptyList(), cleartextTrafficAllowed = false)
): AppSnapshot = AppSnapshot(
    app = app,
    permissions = permissions,
    specialAccess = SpecialAccessState(
        special.map { (kind, state) ->
            SpecialAccessEntry(kind, state, evidence = "harness")
        }
    ),
    manifest = manifest,
    scannedAt = 1_700_600_000_000L
)
