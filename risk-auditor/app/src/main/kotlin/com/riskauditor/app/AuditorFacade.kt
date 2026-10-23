package com.riskauditor.app

import android.content.Context
import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.diff.DiffAlert
import com.riskauditor.diff.DiffEngine
import com.riskauditor.hashintel.ConsentManager
import com.riskauditor.hashintel.LocalBlocklistProvider
import com.riskauditor.hashintel.Sha256Hasher
import com.riskauditor.hashintel.ThreatIntelService
import com.riskauditor.inventory.AppInventory
import com.riskauditor.inventory.InventoryOptions
import com.riskauditor.inventory.RealPackageSource
import com.riskauditor.manifestanalysis.ManifestAnalyzer
import com.riskauditor.ml.MlScorer
import com.riskauditor.ml.ModelLoader
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.DeviceScan
import com.riskauditor.model.HashVerdict
import com.riskauditor.model.ScoredApp
import com.riskauditor.permissions.PermissionExtractor
import com.riskauditor.presentation.AppDetail
import com.riskauditor.presentation.ContractMapper
import com.riskauditor.presentation.DeviceReport
import com.riskauditor.presentation.ReportBuilder
import com.riskauditor.report.PdfReportWriter
import com.riskauditor.scoring.RiskEngine
import com.riskauditor.scoring.RubricLoader
import com.riskauditor.scoring.ScoringInput
import com.riskauditor.specialaccess.AndroidAccessibilitySource
import com.riskauditor.specialaccess.AndroidAppOpsSource
import com.riskauditor.specialaccess.AndroidDeviceAdminSource
import com.riskauditor.specialaccess.AndroidPowerSource
import com.riskauditor.specialaccess.AndroidRoleSource
import com.riskauditor.specialaccess.AndroidSettingsSource
import com.riskauditor.specialaccess.AndroidVpnSource
import com.riskauditor.specialaccess.RoleResolver
import com.riskauditor.specialaccess.SpecialAccessDetector
import com.riskauditor.store.JsonFileSnapshotStore
import com.riskauditor.trust.TrustLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Options for one scan. */
data class ScanOptions(
    val includeSystemApps: Boolean = false,
    /** Hashing every APK costs seconds; only do it when the result will be used. */
    val computeHashes: Boolean = false,
    val runManifestAnalysis: Boolean = true,
    val runMlModel: Boolean = true
)

/** Everything a scan produced. */
data class ScanOutcome(
    val scan: DeviceScan,
    val report: DeviceReport,
    val alerts: List<DiffAlert>
)

/**
 * Wires every module together. The single entry point a UI (or the headless
 * debug activity) talks to.
 *
 * Dependencies are constructed by hand rather than through a DI framework: there
 * are about fifteen of them, they are all singletons, and the wiring is one
 * readable block. A framework here would add a build-time dependency and a
 * layer of indirection to save nothing.
 *
 * ## Scan order, and why
 *
 * 1. inventory  — cheapest, and everything else keys off the package list
 * 2. permissions — needed before special access, which uses declared permissions
 * 3. special access — device state read ONCE, then applied per app
 * 4. manifest   — optional, per app
 * 5. hashing + intel — optional, gated on consent, last because it is slowest
 * 6. score      — pure, needs everything above
 * 7. diff       — compares with the stored snapshot, then updates it
 */
class AuditorFacade(private val context: Context) {

    private val packageSource = RealPackageSource(context)
    private val inventory = AppInventory(packageSource)
    private val catalog = CatalogLoader.default
    private val permissions = PermissionExtractor(packageSource, catalog)
    private val trust = TrustLoader.default

    private val specialAccess = SpecialAccessDetector(
        settings = AndroidSettingsSource(context),
        appOps = AndroidAppOpsSource(context),
        admins = AndroidDeviceAdminSource(context),
        accessibility = AndroidAccessibilitySource(context),
        power = AndroidPowerSource(context),
        vpn = AndroidVpnSource(context)
    )

    private val roleSource = AndroidRoleSource(context)
    private val manifestAnalyzer = ManifestAnalyzer(context.packageManager)
    private val engine = RiskEngine(RubricLoader.default, catalog, trust)
    private val reportBuilder = ReportBuilder(catalog)
    private val diffEngine = DiffEngine(catalog)

    private val store = JsonFileSnapshotStore(
        File(context.filesDir, "snapshots.json"),
        onError = { message, _ -> android.util.Log.w(TAG, message) }
    )

    val consent = ConsentManager(SharedPrefsConsentStore(context))

    private val threatIntel = ThreatIntelService(
        consent = consent,
        providers = listOf(LocalBlocklistProvider(loadLocalBlocklist()))
        // VirusTotalProvider / MalwareBazaarProvider are added here once the
        // user supplies their own API key. No key is shipped with the app.
    )

    private val hasher = Sha256Hasher()
    private val mlScorer: MlScorer? = ModelLoader.loadOrNull()?.let { MlScorer(it) }

    private var lastScan: DeviceScan? = null

    suspend fun scan(options: ScanOptions = ScanOptions()): ScanOutcome =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()

            val inventoryResult = inventory.scan(
                InventoryOptions(includeSystemApps = options.includeSystemApps)
            )
            val deviceState = specialAccess.readDeviceState()
            val roles = RoleResolver.resolve(roleSource)

            val skipped = LinkedHashMap(inventoryResult.skipped)

            val snapshots = inventoryResult.apps.mapNotNull { app ->
                try {
                    val perms = permissions.extract(app.packageName)

                    var snapshot = AppSnapshot(
                        app = app,
                        permissions = perms,
                        specialAccess = specialAccess.detect(app, perms, deviceState),
                        manifest = if (options.runManifestAnalysis) {
                            manifestAnalyzer.analyse(app.packageName)
                        } else {
                            com.riskauditor.model.ManifestFindings.NOT_ANALYSED
                        },
                        scannedAt = now
                    )

                    if (options.computeHashes) {
                        val hash = app.baseApkPath?.let {
                            hasher.hash(app.packageName, it, app.lastUpdateTime)
                        }
                        snapshot = snapshot.copy(
                            apkSha256 = hash?.sha256,
                            hashVerdict = hash?.let { threatIntel.lookup(it.sha256) }
                                ?: HashVerdict.NotLookedUp
                        )
                    }

                    if (options.runMlModel && mlScorer != null) {
                        snapshot = snapshot.copy(ml = mlScorer.score(snapshot))
                    }

                    snapshot
                } catch (t: Throwable) {
                    // One unreadable app must never abort a whole-device scan.
                    skipped[app.packageName] = t.javaClass.simpleName
                    null
                }
            }

            val scored = snapshots.map { snapshot ->
                ScoredApp(
                    snapshot = snapshot,
                    risk = engine.score(ScoringInput(snapshot, roles, now))
                )
            }

            val scan = DeviceScan(
                scannedAt = now,
                includedSystemApps = options.includeSystemApps,
                apps = scored,
                skipped = skipped
            )

            val alerts = diffEngine.diffAll(store, snapshots)

            lastScan = scan
            ScanOutcome(scan, reportBuilder.deviceReport(scan), alerts)
        }

    /** Re-scan one package, e.g. after an install broadcast. */
    suspend fun scanOne(packageName: String): ScoredApp? = withContext(Dispatchers.Default) {
        val app = inventory.scanOne(packageName) ?: return@withContext null
        val perms = permissions.extract(packageName)
        val snapshot = AppSnapshot(
            app = app,
            permissions = perms,
            specialAccess = specialAccess.detect(app, perms),
            manifest = manifestAnalyzer.analyse(packageName),
            scannedAt = System.currentTimeMillis()
        ).let { s -> mlScorer?.let { s.copy(ml = it.score(s)) } ?: s }

        val roles = RoleResolver.resolve(roleSource)
        ScoredApp(snapshot, engine.score(ScoringInput(snapshot, roles, System.currentTimeMillis())))
    }

    fun appDetail(packageName: String): AppDetail? {
        val scored = lastScan?.apps?.firstOrNull { it.packageName == packageName } ?: return null
        return reportBuilder.appDetail(scored)
    }

    suspend fun exportPdf(): File? = withContext(Dispatchers.IO) {
        val scan = lastScan ?: return@withContext null
        val report = reportBuilder.deviceReport(scan)
        val details = scan.apps
            .filter { it.risk.band.requiresMinimumReasons }
            .map { reportBuilder.appDetail(it) }
        PdfReportWriter(context).write(report, details)
    }

    /**
     * Full scan, returned as the AppSnitch ScanResult JSON (appsnitch/docs/DATA-CONTRACT.md).
     * This is what the AppSnitch Capacitor plugin calls for its scan() method.
     */
    suspend fun scanForAppSnitch(options: ScanOptions = ScanOptions()): String {
        val outcome = scan(options)
        return ContractMapper.toScanResult(
            scan = outcome.scan,
            reportBuilder = reportBuilder,
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE
        ).toString()
    }

    private fun loadLocalBlocklist(): Set<String> = try {
        context.assets.open("local_blocklist.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
    } catch (t: Throwable) {
        emptySet()
    }

    companion object {
        private const val TAG = "AuditorFacade"
    }
}
