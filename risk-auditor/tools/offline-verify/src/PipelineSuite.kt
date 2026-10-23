package verify

import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.diff.DiffEngine
import com.riskauditor.model.AccessState
import com.riskauditor.model.Category
import com.riskauditor.model.DeviceScan
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.MlLabel
import com.riskauditor.model.MlResult
import com.riskauditor.model.RiskBand
import com.riskauditor.model.ScoredApp
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.presentation.ActionKind
import com.riskauditor.presentation.RemediationAdvisor
import com.riskauditor.presentation.ReportBuilder
import com.riskauditor.report.Block
import com.riskauditor.report.ReportLayout
import com.riskauditor.scoring.RiskEngine
import com.riskauditor.scoring.RubricLoader
import com.riskauditor.store.InMemorySnapshotStore
import com.riskauditor.trust.TrustLoader

private val catalog = CatalogLoader.default
private val pipelineEngine = RiskEngine(RubricLoader.default, catalog, TrustLoader.default)
private val builder = ReportBuilder(catalog)
private val diffEngine = DiffEngine(catalog)

/**
 * End-to-end over the pure half of the pipeline: snapshot -> score -> report ->
 * diff -> PDF layout.
 *
 * These modules are pure Kotlin, so they can be executed here even though the
 * Android half of the project cannot be. This covers the output a UI would bind
 * to, which is otherwise entirely unverified.
 */
fun pipelineSuite() {
    Check.suite("Store, diff, presentation and report layout (M12, M13, M16)")

    // ---------------------------------------------------------- store ----

    Check.test("a new store is empty and retainOnly removes uninstalled packages") {
        val store = InMemorySnapshotStore()
        Check.isTrue(store.isEmpty(), "a new store should be empty")

        store.putAll(
            listOf(
                snapshot(app("com.a", "A")),
                snapshot(app("com.b", "B")),
                snapshot(app("com.c", "C"))
            )
        )
        val removed = store.retainOnly(setOf("com.a", "com.c"))

        Check.eq(removed, setOf("com.b"), "removed packages")
        Check.eq(store.all().keys, setOf("com.a", "com.c"), "remaining packages")
    }

    // ----------------------------------------------------------- diff ----

    Check.test("the first run baselines and raises no alerts") {
        val store = InMemorySnapshotStore()
        val alerts = diffEngine.diffAll(
            store,
            listOf(
                snapshot(app("com.a", "A"), listOf(perm("android.permission.READ_SMS"))),
                snapshot(app("com.b", "B"), emptyList(), listOf(SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED))
            )
        )
        Check.isTrue(alerts.isEmpty(), "a first run must not flood the user with alerts")
        Check.eq(store.all().size, 2, "baseline stored")
    }

    Check.test("a later run that adds SMS does alert") {
        val store = InMemorySnapshotStore()
        diffEngine.diffAll(store, listOf(snapshot(app("com.a", "A"))))

        val alerts = diffEngine.diffAll(
            store,
            listOf(snapshot(app("com.a", "A"), listOf(perm("android.permission.READ_SMS"))))
        )
        Check.isTrue(alerts.isNotEmpty(), "newly granted SMS should raise an alert")
        Check.isTrue(
            alerts.any { it.detail.contains("text messages") },
            "the alert should describe the access in plain words: ${alerts.map { it.detail }}"
        )
    }

    Check.test("no change raises no alert") {
        val store = InMemorySnapshotStore()
        val apps = listOf(snapshot(app("com.a", "A"), listOf(perm("android.permission.INTERNET"))))
        diffEngine.diffAll(store, apps)
        Check.isTrue(diffEngine.diffAll(store, apps).isEmpty(), "an unchanged device must stay quiet")
    }

    Check.test("newly enabled special access raises a critical alert") {
        val store = InMemorySnapshotStore()
        diffEngine.diffAll(
            store,
            listOf(snapshot(app("com.a", "A"), emptyList(), listOf(SpecialAccessKind.OVERLAY to AccessState.DECLARED_ONLY)))
        )
        val alerts = diffEngine.diffAll(
            store,
            listOf(snapshot(app("com.a", "A"), emptyList(), listOf(SpecialAccessKind.OVERLAY to AccessState.ENABLED)))
        )
        Check.isTrue(alerts.isNotEmpty(), "enabling overlay should alert")
        Check.eq(alerts.first().severity, Severity.CRITICAL, "severity")
    }

    Check.test("a trivial new permission produces no alert") {
        val store = InMemorySnapshotStore()
        diffEngine.diffAll(store, listOf(snapshot(app("com.a", "A"), listOf(perm("android.permission.INTERNET")))))

        val alerts = diffEngine.diffAll(
            store,
            listOf(
                snapshot(
                    app("com.a", "A"),
                    listOf(perm("android.permission.INTERNET"), perm("android.permission.ACCESS_NETWORK_STATE"))
                )
            )
        )
        Check.isTrue(alerts.isEmpty(), "low-value changes must not interrupt the user")
    }

    // --------------------------------------------------- presentation ----

    fun scoredApp(pkg: String, label: String, perms: List<String> = emptyList(),
                  special: List<Pair<SpecialAccessKind, AccessState>> = emptyList(),
                  installer: InstallerKind = InstallerKind.GOOGLE_PLAY,
                  isSystem: Boolean = false): ScoredApp {
        val s = snapshot(app(pkg, label, installer = installer, isSystem = isSystem),
            perms.map { perm(it) }, special)
        return ScoredApp(s, pipelineEngine.score(s))
    }

    Check.test("Q1: the device report ranks apps worst first") {
        val scan = DeviceScan(
            scannedAt = 1L,
            apps = listOf(
                scoredApp("com.clean", "Clean", listOf("android.permission.INTERNET")),
                scoredApp(
                    "com.bad", "Bad",
                    listOf("android.permission.READ_SMS", "android.permission.INTERNET"),
                    listOf(
                        SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                        SpecialAccessKind.OVERLAY to AccessState.ENABLED
                    ),
                    installer = InstallerKind.SIDELOADED
                ),
                scoredApp("com.middle", "Middle", listOf("android.permission.READ_CONTACTS", "android.permission.CAMERA"))
            )
        )

        val report = builder.deviceReport(scan)

        Check.eq(report.ranked.first().packageName, "com.bad", "worst app first")
        Check.eq(report.ranked.first().rank, 1, "rank numbering starts at 1")
        val scores = report.ranked.map { it.score }
        Check.isTrue(scores == scores.sortedDescending(), "ranked order: $scores")
        Check.isTrue(report.ranked.first().topReason != null, "the top row needs a reason to show")
    }

    Check.test("Q1: ranking is stable between scans") {
        val a = scoredApp("com.a", "Alpha", listOf("android.permission.READ_CONTACTS"))
        val b = scoredApp("com.b", "Beta", listOf("android.permission.READ_CONTACTS"))

        val first = builder.deviceReport(DeviceScan(scannedAt = 1L, apps = listOf(a, b)))
        val second = builder.deviceReport(DeviceScan(scannedAt = 2L, apps = listOf(b, a)))

        Check.eq(
            first.ranked.map { it.packageName },
            second.ranked.map { it.packageName },
            "a list that reshuffles on rescan looks broken"
        )
    }

    Check.test("Q1: category rollups read as plain sentences") {
        val scan = DeviceScan(
            scannedAt = 1L,
            apps = listOf(
                scoredApp("com.a", "A", listOf("android.permission.READ_SMS")),
                scoredApp("com.b", "B", listOf("android.permission.READ_SMS"))
            )
        )
        val rollup = builder.deviceReport(scan).categoryRollups.first { it.category == Category.SMS }
        Check.eq(rollup.sentence, "2 apps can read your text messages", "rollup sentence")
        Check.eq(rollup.appsWithGranted, 2, "apps with granted SMS")
    }

    Check.test("Q1: the headline counts apps needing attention") {
        val scan = DeviceScan(
            scannedAt = 1L,
            apps = listOf(
                scoredApp("com.clean", "Clean", listOf("android.permission.INTERNET")),
                scoredApp(
                    "com.bad", "Bad", listOf("android.permission.READ_SMS"),
                    listOf(SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED),
                    installer = InstallerKind.SIDELOADED
                )
            )
        )
        val report = builder.deviceReport(scan)
        Check.eq(report.bandCounts.needingAttention, 1, "one app needs attention")
        Check.isTrue(
            report.headline.contains("1 needs your attention"),
            "headline was: ${report.headline}"
        )
    }

    Check.test("Q2: every HIGH or CRITICAL detail carries at least 3 reasons and 1 action") {
        val bad = scoredApp(
            "com.bad", "Bad",
            listOf("android.permission.READ_SMS", "android.permission.INTERNET"),
            listOf(
                SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                SpecialAccessKind.OVERLAY to AccessState.ENABLED
            ),
            installer = InstallerKind.SIDELOADED
        )
        Check.isTrue(bad.risk.band.requiresMinimumReasons, "fixture should be HIGH or CRITICAL")

        val detail = builder.appDetail(bad)
        Check.atLeast(detail.whyFlagged.size, 3, "reason count")
        Check.atLeast(detail.actions.size, 1, "action count")
        Check.eq(detail.score, bad.risk.score, "score carried through unchanged")
    }

    Check.test("Q2: category groups are ordered by contribution") {
        val detail = builder.appDetail(
            scoredApp("com.a", "A", listOf("android.permission.READ_SMS", "android.permission.INTERNET"))
        )
        val points = detail.categoryGroups.map { it.points }
        Check.isTrue(points == points.sortedDescending(), "category order: $points")
    }

    Check.test("Q2: unknown special access says so rather than claiming absence") {
        val detail = builder.appDetail(
            scoredApp("com.a", "A", emptyList(), listOf(SpecialAccessKind.OVERLAY to AccessState.UNKNOWN))
        )
        val row = detail.specialAccess.first { it.kind == SpecialAccessKind.OVERLAY }
        Check.isTrue(
            row.sentence.contains("could not check"),
            "expected an honest 'could not check' sentence, got: ${row.sentence}"
        )
    }

    Check.test("Q2: ML disagreement is flagged, not silently resolved") {
        val clean = scoredApp("com.clean", "Clean", listOf("android.permission.INTERNET"))
        val withMl = clean.copy(
            snapshot = clean.snapshot.copy(
                ml = MlResult(0.97, MlLabel.FLAGGED, modelVersion = "harness")
            )
        )
        val detail = builder.appDetail(withMl)

        Check.isTrue(detail.mlDisagreesWithRules, "rules say LOW, model says FLAGGED")
        Check.eq(detail.band, clean.risk.band, "the model must not change the band")
        Check.eq(detail.score, clean.risk.score, "the model must not change the score")
    }

    // ------------------------------------------------------- actions ----

    Check.test("Q3: device admin is removed before uninstall is offered") {
        val admin = scoredApp(
            "com.admin", "Admin", listOf("android.permission.INTERNET"),
            listOf(SpecialAccessKind.DEVICE_ADMIN to AccessState.ENABLED),
            installer = InstallerKind.SIDELOADED
        )
        val actions = RemediationAdvisor.adviseFor(admin.snapshot, admin.risk)

        val adminStep = actions.first { it.id == "deactivate_device_admin" }
        val uninstall = actions.first { it.id == "uninstall" }

        Check.isTrue(
            adminStep.order < uninstall.order,
            "Android refuses to uninstall an active device admin, so that step must come first"
        )
        Check.isTrue(adminStep.blocking, "the admin step is blocking")
    }

    Check.test("Q3: accessibility and overlay are disabled before uninstall") {
        val s = scoredApp(
            "com.bad", "Bad", emptyList(),
            listOf(
                SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                SpecialAccessKind.OVERLAY to AccessState.ENABLED
            )
        )
        val actions = RemediationAdvisor.adviseFor(s.snapshot, s.risk)
        val uninstall = actions.first { it.id == "uninstall" }.order

        Check.isTrue(actions.first { it.id == "disable_accessibility" }.order < uninstall, "accessibility first")
        Check.isTrue(actions.first { it.id == "disable_overlay" }.order < uninstall, "overlay first")
    }

    Check.test("Q3: system apps are offered disable, never a failing uninstall") {
        val sys = scoredApp("com.sys", "Sys", listOf("android.permission.READ_SMS"), isSystem = true)
        val actions = RemediationAdvisor.adviseFor(sys.snapshot, sys.risk)

        Check.isTrue(actions.any { it.kind == ActionKind.DISABLE_SYSTEM_APP }, "disable offered")
        Check.isFalse(actions.any { it.kind == ActionKind.UNINSTALL }, "uninstall must not be offered")
    }

    Check.test("Q3: every app gets at least one action, even a clean one") {
        val clean = scoredApp("com.clean", "Clean", listOf("android.permission.INTERNET"))
        val actions = RemediationAdvisor.adviseFor(clean.snapshot, clean.risk)
        Check.atLeast(actions.size, 1, "action count")
        Check.eq(actions.map { it.order }, actions.indices.toList(), "orders are contiguous")
    }

    // -------------------------------------------------- report layout ----

    Check.test("the PDF layout paginates a 300-app report without overflow") {
        val apps = (1..300).map { scoredApp("com.app$it", "App $it", listOf("android.permission.INTERNET")) }
        val report = builder.deviceReport(DeviceScan(scannedAt = 1L, apps = apps))

        val layout = ReportLayout()
        val pages = layout.build(report, emptyList(), "Test Device", "Android 15", "2026-10-16")

        Check.atLeast(pages.size, 2, "300 apps should span several pages")

        val usable = 842f - 48f - 48f
        pages.forEach { page ->
            if (page.blocks.size > 1) {
                val used = page.blocks.sumOf { it.height.toDouble() }
                Check.isTrue(used <= usable.toDouble(), "page overflow: $used > $usable")
            }
        }

        val rows = pages.flatMap { it.blocks }
            .filterIsInstance<Block.TableRow>()
            .filter { !it.header }
        Check.eq(rows.size, 300, "every app appears exactly once in the ranked table")
    }

    Check.test("an empty scan still produces one page") {
        val report = builder.deviceReport(DeviceScan(scannedAt = 1L, apps = emptyList()))
        val pages = ReportLayout().build(report, emptyList(), "Test", "Android 15", "x")
        Check.atLeast(pages.size, 1, "page count")
    }

    Check.test("the report always states its own limitations") {
        val report = builder.deviceReport(DeviceScan(scannedAt = 1L, apps = emptyList()))
        val pages = ReportLayout().build(report, emptyList(), "Test", "Android 15", "x")

        val text = pages.flatMap { it.blocks }.filterIsInstance<Block.Body>().joinToString(" ") { it.text }
        Check.isTrue(text.contains("does not run or decompile"), "methodology must state what it cannot do")
        Check.isTrue(text.contains("Nothing was uploaded"), "privacy statement must be present")
    }

    Check.test("an oversized block gets its own page instead of looping forever") {
        val huge = Block.Body("x", 1, height = 5000f)
        val pages = ReportLayout().paginate(listOf(Block.Title("t"), huge, Block.Title("u")))
        Check.atLeast(pages.size, 2, "page count")
        Check.isTrue(pages.any { it.blocks == listOf<Block>(huge) }, "oversized block isolated")
    }

    // ------------------------------------------------ full pipeline ----

    Check.test("END TO END: snapshot -> score -> report -> detail -> actions") {
        val apps = listOf(
            scoredApp("com.clean", "Clean App", listOf("android.permission.INTERNET")),
            scoredApp(
                "com.bright.torch", "Super Torch Flashlight",
                listOf(
                    "android.permission.READ_SMS", "android.permission.READ_CONTACTS",
                    "android.permission.READ_CALL_LOG", "android.permission.INTERNET"
                ),
                installer = InstallerKind.SIDELOADED
            ),
            scoredApp(
                "com.chat.messenger", "Chat Messenger",
                listOf(
                    "android.permission.READ_SMS", "android.permission.READ_CONTACTS",
                    "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
                    "android.permission.INTERNET"
                )
            )
        )

        val report = builder.deviceReport(DeviceScan(scannedAt = 1L, apps = apps))

        // Q1: the torch is the worst thing on the device.
        Check.eq(report.ranked.first().packageName, "com.bright.torch", "Q1 ranking")
        Check.eq(report.ranked.first().band, RiskBand.CRITICAL, "Q1 band")

        // The messenger, despite broad access, is not in the top slot.
        val messenger = report.ranked.first { it.packageName == "com.chat.messenger" }
        Check.isFalse(
            messenger.band.requiresMinimumReasons,
            "a legitimate messenger must not be flagged (was ${messenger.band})"
        )

        // Q2: the detail explains itself in plain words.
        val detail = builder.appDetail(apps.first { it.packageName == "com.bright.torch" })
        Check.atLeast(detail.whyFlagged.size, 3, "Q2 reason count")
        detail.whyFlagged.forEach { reason ->
            BANNED_JARGON.forEach { banned ->
                Check.isFalse(
                    reason.headline.contains(banned),
                    "jargon '$banned' in: ${reason.headline}"
                )
            }
        }

        // Q3: there is something to do about it.
        Check.atLeast(detail.actions.size, 1, "Q3 action count")
        Check.isTrue(
            detail.actions.any { it.kind == ActionKind.UNINSTALL },
            "a sideloaded critical app should offer removal"
        )
    }
}
