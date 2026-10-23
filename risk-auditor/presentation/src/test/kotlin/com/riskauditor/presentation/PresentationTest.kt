package com.riskauditor.presentation

import com.google.common.truth.Truth.assertThat
import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.model.AccessState
import com.riskauditor.model.AppRecord
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.Category
import com.riskauditor.model.DeviceScan
import com.riskauditor.model.InstallerInfo
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.MlLabel
import com.riskauditor.model.MlResult
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.Reason
import com.riskauditor.model.RiskBand
import com.riskauditor.model.RiskResult
import com.riskauditor.model.ScoredApp
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessEntry
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.model.SpecialAccessState
import com.riskauditor.scoring.RiskEngine
import org.junit.Test

class ReportBuilderTest {

    private val catalog = CatalogLoader.default
    private val builder = ReportBuilder(catalog)
    private val engine = RiskEngine()

    private fun scored(
        pkg: String,
        label: String,
        score: Int,
        band: RiskBand,
        permissions: List<String> = emptyList(),
        special: List<Pair<SpecialAccessKind, AccessState>> = emptyList(),
        isSystem: Boolean = false,
        ml: MlResult? = null,
        reasons: List<Reason> = listOf(
            Reason("R1", Severity.HIGH, "Top reason", "detail", points = 20),
            Reason("R2", Severity.MEDIUM, "Second reason", "detail", points = 10),
            Reason("R3", Severity.LOW, "Third reason", "detail", points = 5)
        )
    ) = ScoredApp(
        snapshot = AppSnapshot(
            app = AppRecord(
                packageName = pkg,
                label = label,
                isSystem = isSystem,
                installer = InstallerInfo(InstallerKind.GOOGLE_PLAY, "com.android.vending")
            ),
            permissions = permissions.map {
                PermissionRecord(it, granted = true, category = catalog.categoryOf(it))
            },
            specialAccess = SpecialAccessState(
                special.map { SpecialAccessEntry(it.first, it.second) }
            ),
            ml = ml
        ),
        risk = RiskResult(score = score, band = band, reasons = reasons)
    )

    private fun scan(vararg apps: ScoredApp) =
        DeviceScan(scannedAt = 1L, apps = apps.toList())

    // -------------------------------------------------------------- Q1 ----

    @Test
    fun `ranked list is ordered worst first`() {
        val report = builder.deviceReport(
            scan(
                scored("com.low", "Low", 10, RiskBand.LOW),
                scored("com.crit", "Critical", 90, RiskBand.CRITICAL),
                scored("com.med", "Medium", 40, RiskBand.MEDIUM)
            )
        )

        assertThat(report.ranked.map { it.packageName })
            .containsExactly("com.crit", "com.med", "com.low").inOrder()
        assertThat(report.ranked.first().rank).isEqualTo(1)
    }

    @Test
    fun `ties break deterministically so the list does not reshuffle between scans`() {
        val first = builder.deviceReport(
            scan(scored("com.b", "Beta", 50, RiskBand.MEDIUM), scored("com.a", "Alpha", 50, RiskBand.MEDIUM))
        )
        val second = builder.deviceReport(
            scan(scored("com.a", "Alpha", 50, RiskBand.MEDIUM), scored("com.b", "Beta", 50, RiskBand.MEDIUM))
        )

        assertThat(first.ranked.map { it.packageName })
            .isEqualTo(second.ranked.map { it.packageName })
    }

    @Test
    fun `a user app outranks a system app at the same score`() {
        val report = builder.deviceReport(
            scan(
                scored("com.system", "System", 50, RiskBand.MEDIUM, isSystem = true),
                scored("com.user", "User", 50, RiskBand.MEDIUM)
            )
        )
        assertThat(report.ranked.first().packageName).isEqualTo("com.user")
    }

    @Test
    fun `band counts and headline reflect the scan`() {
        val report = builder.deviceReport(
            scan(
                scored("com.a", "A", 90, RiskBand.CRITICAL),
                scored("com.b", "B", 65, RiskBand.HIGH),
                scored("com.c", "C", 10, RiskBand.LOW)
            )
        )

        assertThat(report.bandCounts.critical).isEqualTo(1)
        assertThat(report.bandCounts.high).isEqualTo(1)
        assertThat(report.bandCounts.low).isEqualTo(1)
        assertThat(report.bandCounts.needingAttention).isEqualTo(2)
        assertThat(report.headline).contains("2 need your attention")
    }

    @Test
    fun `headline is singular for one flagged app`() {
        val report = builder.deviceReport(scan(scored("com.a", "A", 90, RiskBand.CRITICAL)))
        assertThat(report.headline).contains("1 needs your attention")
    }

    @Test
    fun `headline is reassuring when nothing is flagged`() {
        val report = builder.deviceReport(scan(scored("com.a", "A", 5, RiskBand.LOW)))
        assertThat(report.headline).contains("Nothing needs your attention")
    }

    @Test
    fun `category rollup produces a readable sentence`() {
        val report = builder.deviceReport(
            scan(
                scored("com.a", "A", 50, RiskBand.MEDIUM, listOf("android.permission.READ_SMS")),
                scored("com.b", "B", 50, RiskBand.MEDIUM, listOf("android.permission.READ_SMS"))
            )
        )

        val sms = report.categoryRollups.first { it.category == Category.SMS }
        assertThat(sms.sentence).isEqualTo("2 apps can read your text messages")
        assertThat(sms.appsWithGranted).isEqualTo(2)
    }

    @Test
    fun `special access roster lists holders with their state`() {
        val report = builder.deviceReport(
            scan(
                scored(
                    "com.a", "A", 90, RiskBand.CRITICAL,
                    special = listOf(SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED)
                ),
                scored(
                    "com.b", "B", 40, RiskBand.MEDIUM,
                    special = listOf(SpecialAccessKind.ACCESSIBILITY to AccessState.DECLARED_ONLY)
                )
            )
        )

        assertThat(report.specialAccessRoster).hasSize(2)
        // Enabled holders sort before declared-only ones.
        assertThat(report.specialAccessRoster.first().state).isEqualTo(AccessState.ENABLED)
    }

    @Test
    fun `skipped packages are surfaced rather than hidden`() {
        val report = builder.deviceReport(
            DeviceScan(scannedAt = 1L, apps = emptyList(), skipped = mapOf("com.gone" to "NameNotFound"))
        )
        assertThat(report.skipped).containsKey("com.gone")
    }

    // ----------------------------------------------------------- Q2/Q3 ----

    @Test
    fun `app detail carries why flagged reasons`() {
        val detail = builder.appDetail(scored("com.a", "A", 90, RiskBand.CRITICAL))
        assertThat(detail.whyFlagged).hasSize(3)
        assertThat(detail.whyFlagged.first().headline).isEqualTo("Top reason")
    }

    @Test
    fun `every high or critical detail carries at least three reasons and one action`() {
        listOf(RiskBand.HIGH, RiskBand.CRITICAL).forEach { band ->
            val detail = builder.appDetail(scored("com.a", "A", 70, band))
            assertThat(detail.whyFlagged.size).isAtLeast(3)
            assertThat(detail.actions).isNotEmpty()
        }
    }

    @Test
    fun `category groups are ordered by points`() {
        val snapshot = AppSnapshot(
            app = AppRecord("com.a", "A"),
            permissions = listOf(
                PermissionRecord("android.permission.READ_SMS", granted = true, category = Category.SMS),
                PermissionRecord("android.permission.INTERNET", granted = true, category = Category.NETWORK)
            )
        )
        val risk = engine.score(snapshot)
        val detail = builder.appDetail(ScoredApp(snapshot, risk))

        val points = detail.categoryGroups.map { it.points }
        assertThat(points).isEqualTo(points.sortedDescending())
    }

    @Test
    fun `permissions within a group put granted ones first`() {
        val snapshot = AppSnapshot(
            app = AppRecord("com.a", "A"),
            permissions = listOf(
                PermissionRecord("android.permission.SEND_SMS", granted = false, category = Category.SMS),
                PermissionRecord("android.permission.READ_SMS", granted = true, category = Category.SMS)
            )
        )
        val detail = builder.appDetail(ScoredApp(snapshot, engine.score(snapshot)))
        val sms = detail.categoryGroups.first { it.category == Category.SMS }

        assertThat(sms.permissions.first().granted).isTrue()
        assertThat(sms.permissions.last().latent).isTrue()
    }

    @Test
    fun `ml disagreement is flagged not resolved`() {
        // Rules say LOW, model says FLAGGED: the UI must say they disagree
        // rather than silently preferring one.
        val detail = builder.appDetail(
            scored(
                "com.a", "A", 10, RiskBand.LOW,
                ml = MlResult(0.95, MlLabel.FLAGGED, modelVersion = "t")
            )
        )
        assertThat(detail.mlDisagreesWithRules).isTrue()
        assertThat(detail.score).isEqualTo(10)
        assertThat(detail.band).isEqualTo(RiskBand.LOW)
    }

    @Test
    fun `ml agreement is not flagged as disagreement`() {
        val detail = builder.appDetail(
            scored(
                "com.a", "A", 90, RiskBand.CRITICAL,
                ml = MlResult(0.95, MlLabel.FLAGGED, modelVersion = "t")
            )
        )
        assertThat(detail.mlDisagreesWithRules).isFalse()
    }

    @Test
    fun `unknown special access says so instead of claiming it is absent`() {
        val detail = builder.appDetail(
            scored(
                "com.a", "A", 50, RiskBand.MEDIUM,
                special = listOf(SpecialAccessKind.OVERLAY to AccessState.UNKNOWN)
            )
        )
        val row = detail.specialAccess.first { it.kind == SpecialAccessKind.OVERLAY }
        assertThat(row.sentence).contains("could not check")
    }
}

class RemediationAdvisorTest {

    private fun snapshot(
        isSystem: Boolean = false,
        special: List<SpecialAccessKind> = emptyList()
    ) = AppSnapshot(
        app = AppRecord("com.a", "A", isSystem = isSystem),
        specialAccess = SpecialAccessState(
            special.map { SpecialAccessEntry(it, AccessState.ENABLED) }
        )
    )

    private fun risk(band: RiskBand) = RiskResult(
        score = when (band) {
            RiskBand.CRITICAL -> 90
            RiskBand.HIGH -> 65
            RiskBand.MEDIUM -> 40
            RiskBand.LOW -> 10
        },
        band = band
    )

    @Test
    fun `device admin must be deactivated before uninstall`() {
        val actions = RemediationAdvisor.adviseFor(
            snapshot(special = listOf(SpecialAccessKind.DEVICE_ADMIN)),
            risk(RiskBand.CRITICAL)
        )

        val adminOrder = actions.first { it.id == "deactivate_device_admin" }.order
        val uninstallOrder = actions.first { it.id == "uninstall" }.order

        // Android refuses to uninstall an active device admin, so advice that
        // leads with "uninstall" sends the user into a dialog that just fails.
        assertThat(adminOrder).isLessThan(uninstallOrder)
        assertThat(actions.first { it.id == "deactivate_device_admin" }.blocking).isTrue()
    }

    @Test
    fun `accessibility is disabled before uninstall`() {
        val actions = RemediationAdvisor.adviseFor(
            snapshot(special = listOf(SpecialAccessKind.ACCESSIBILITY)),
            risk(RiskBand.CRITICAL)
        )

        assertThat(actions.first { it.id == "disable_accessibility" }.order)
            .isLessThan(actions.first { it.id == "uninstall" }.order)
    }

    @Test
    fun `overlay is disabled before uninstall`() {
        val actions = RemediationAdvisor.adviseFor(
            snapshot(special = listOf(SpecialAccessKind.OVERLAY)),
            risk(RiskBand.HIGH)
        )

        assertThat(actions.first { it.id == "disable_overlay" }.order)
            .isLessThan(actions.first { it.id == "uninstall" }.order)
    }

    @Test
    fun `all blocking steps come before every non-blocking one`() {
        val actions = RemediationAdvisor.adviseFor(
            snapshot(
                special = listOf(
                    SpecialAccessKind.DEVICE_ADMIN,
                    SpecialAccessKind.ACCESSIBILITY,
                    SpecialAccessKind.OVERLAY,
                    SpecialAccessKind.USAGE_STATS
                )
            ),
            risk(RiskBand.CRITICAL)
        )

        val lastBlocking = actions.filter { it.blocking }.maxOf { it.order }
        val firstNonBlocking = actions.filter { !it.blocking }.minOf { it.order }
        assertThat(lastBlocking).isLessThan(firstNonBlocking)
    }

    @Test
    fun `system apps are offered disable not uninstall`() {
        val actions = RemediationAdvisor.adviseFor(snapshot(isSystem = true), risk(RiskBand.HIGH))

        assertThat(actions.map { it.id }).contains("disable_system_app")
        assertThat(actions.map { it.id }).doesNotContain("uninstall")
    }

    @Test
    fun `a clean app still gets at least one action`() {
        val actions = RemediationAdvisor.adviseFor(snapshot(), risk(RiskBand.LOW))
        assertThat(actions).isNotEmpty()
        assertThat(actions.map { it.id }).contains("review_permissions")
    }

    @Test
    fun `critical apps are told to remove, lower bands are told to consider it`() {
        val critical = RemediationAdvisor.adviseFor(snapshot(), risk(RiskBand.CRITICAL))
        val medium = RemediationAdvisor.adviseFor(snapshot(), risk(RiskBand.MEDIUM))

        assertThat(critical.first { it.id == "uninstall" }.title).isEqualTo("Remove this app")
        assertThat(medium.first { it.id == "uninstall" }.title).contains("if you do not use it")
    }

    @Test
    fun `orders are unique and contiguous`() {
        val actions = RemediationAdvisor.adviseFor(
            snapshot(special = SpecialAccessKind.entries.toList()),
            risk(RiskBand.CRITICAL)
        )
        val orders = actions.map { it.order }
        assertThat(orders).containsNoDuplicates()
        assertThat(orders).isEqualTo((0 until actions.size).toList())
    }
}
