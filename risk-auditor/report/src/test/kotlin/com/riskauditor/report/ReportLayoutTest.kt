package com.riskauditor.report

import com.google.common.truth.Truth.assertThat
import com.riskauditor.model.Category
import com.riskauditor.model.Reason
import com.riskauditor.model.RiskBand
import com.riskauditor.model.Severity
import com.riskauditor.presentation.ActionItem
import com.riskauditor.presentation.ActionKind
import com.riskauditor.presentation.AppDetail
import com.riskauditor.presentation.AppHeader
import com.riskauditor.presentation.BandCounts
import com.riskauditor.presentation.CategoryRollup
import com.riskauditor.presentation.DeviceReport
import com.riskauditor.presentation.RankedApp
import org.junit.Test

class ReportLayoutTest {

    private val layout = ReportLayout()

    private fun report(appCount: Int, critical: Int = 0, high: Int = 0): DeviceReport =
        DeviceReport(
            scannedAt = 1L,
            appCount = appCount,
            includedSystemApps = false,
            bandCounts = BandCounts(low = appCount - critical - high, high = high, critical = critical),
            ranked = (1..appCount).map {
                RankedApp(
                    rank = it,
                    packageName = "com.app$it",
                    label = "App $it",
                    score = 100 - it,
                    band = when {
                        it <= critical -> RiskBand.CRITICAL
                        it <= critical + high -> RiskBand.HIGH
                        else -> RiskBand.LOW
                    },
                    topReason = "Reason for app $it",
                    isSystem = false,
                    installSource = "Google Play"
                )
            },
            categoryRollups = listOf(
                CategoryRollup(
                    Category.SMS, "SMS", 3, 1, 4, listOf("App 1"),
                    "3 apps can read your text messages"
                )
            ),
            specialAccessRoster = emptyList(),
            headline = "Scanned $appCount apps."
        )

    private fun detail(index: Int, band: RiskBand) = AppDetail(
        header = AppHeader(
            packageName = "com.app$index",
            label = "App $index",
            versionName = "1.0",
            firstInstallTime = 1L,
            lastUpdateTime = 1L,
            installSource = "Google Play",
            targetSdk = 34,
            isSystem = false,
            hasLauncherIcon = true
        ),
        score = 90,
        band = band,
        whyFlagged = (1..4).map {
            Reason("R$it", Severity.HIGH, "Headline $it for app $index", "A detail sentence.", points = 10)
        },
        categoryGroups = emptyList(),
        specialAccess = emptyList(),
        actions = listOf(
            ActionItem("uninstall", 0, "Remove this app", "Because it can do a lot.", ActionKind.UNINSTALL)
        )
    )

    @Test
    fun `a small report fits on one page`() {
        val pages = layout.build(report(3), emptyList(), "Pixel", "Android 14", "2026-10-16")
        assertThat(pages).hasSize(1)
    }

    @Test
    fun `blocks are split across pages without overflow`() {
        val pages = layout.build(report(300), emptyList(), "Pixel", "Android 14", "2026-10-16")

        assertThat(pages.size).isGreaterThan(1)

        val usable = 842f - 48f - 48f
        pages.forEach { page ->
            val used = page.blocks.sumOf { it.height.toDouble() }.toFloat()
            // Allow the single-oversized-block case, which gets a page to itself.
            if (page.blocks.size > 1) {
                assertThat(used).isAtMost(usable)
            }
        }
    }

    @Test
    fun `an empty scan still produces one page`() {
        val pages = layout.build(report(0), emptyList(), "Pixel", "Android 14", "2026-10-16")
        assertThat(pages).isNotEmpty()
    }

    @Test
    fun `every app appears exactly once in the ranked table`() {
        val pages = layout.build(report(120), emptyList(), "Pixel", "Android 14", "2026-10-16")

        val rows = pages.flatMap { it.blocks }
            .filterIsInstance<Block.TableRow>()
            .filter { !it.header }

        assertThat(rows).hasSize(120)
        assertThat(rows.map { it.cells[1] }).containsNoDuplicates()
    }

    @Test
    fun `flagged apps get their own sections`() {
        val details = listOf(detail(1, RiskBand.CRITICAL), detail(2, RiskBand.HIGH))
        val pages = layout.build(report(10, critical = 1, high = 1), details, "Pixel", "Android 14", "x")

        val headings = pages.flatMap { it.blocks }.filterIsInstance<Block.Heading>().map { it.text }
        assertThat(headings.any { it.contains("App 1") }).isTrue()
        assertThat(headings.any { it.contains("App 2") }).isTrue()
    }

    @Test
    fun `low band apps do not get their own sections`() {
        val details = listOf(detail(1, RiskBand.LOW))
        val pages = layout.build(report(3), details, "Pixel", "Android 14", "x")

        val headings = pages.flatMap { it.blocks }.filterIsInstance<Block.Heading>().map { it.text }
        assertThat(headings.any { it.contains("App 1  —") }).isFalse()
    }

    @Test
    fun `the methodology section is always present`() {
        val pages = layout.build(report(1), emptyList(), "Pixel", "Android 14", "x")
        val headings = pages.flatMap { it.blocks }.filterIsInstance<Block.Heading>().map { it.text }
        assertThat(headings).contains("How these scores were produced")
    }

    @Test
    fun `the report states that nothing was uploaded`() {
        val pages = layout.build(report(1), emptyList(), "Pixel", "Android 14", "x")
        val bodies = pages.flatMap { it.blocks }.filterIsInstance<Block.Body>().map { it.text }
        assertThat(bodies.any { it.contains("Nothing was uploaded") }).isTrue()
    }

    @Test
    fun `line wrapping counts multiple lines for long text`() {
        assertThat(layout.wrappedLineCount("short")).isEqualTo(1)
        assertThat(layout.wrappedLineCount("x".repeat(200))).isAtLeast(2)
        assertThat(layout.wrappedLineCount("a\nb\nc")).isEqualTo(3)
        assertThat(layout.wrappedLineCount("")).isEqualTo(1)
    }

    @Test
    fun `an oversized block gets a page of its own rather than looping forever`() {
        val huge = Block.Body("x", 1, height = 5000f)
        val pages = layout.paginate(listOf(Block.Title("t"), huge, Block.Title("u")))

        assertThat(pages.size).isAtLeast(2)
        assertThat(pages.any { it.blocks == listOf(huge) }).isTrue()
    }

    @Test
    fun `paginating nothing still yields a page`() {
        assertThat(layout.paginate(emptyList())).hasSize(1)
    }
}
