package com.riskauditor.report

import com.riskauditor.model.RiskBand
import com.riskauditor.presentation.AppDetail
import com.riskauditor.presentation.DeviceReport

/**
 * One drawable element on a page. Pure data: no Canvas, no Paint, no Android.
 */
sealed interface Block {
    val height: Float

    data class Title(val text: String, override val height: Float = 34f) : Block
    data class Heading(val text: String, override val height: Float = 24f) : Block
    data class Body(val text: String, val lines: Int, override val height: Float) : Block
    data class KeyValue(val key: String, val value: String, override val height: Float = 16f) : Block
    data class TableRow(
        val cells: List<String>,
        val band: RiskBand?,
        val header: Boolean = false,
        override val height: Float = 18f
    ) : Block
    data class Bar(
        val label: String,
        val value: Int,
        val max: Int,
        override val height: Float = 20f
    ) : Block
    data class Spacer(override val height: Float) : Block
    data class Rule(override val height: Float = 10f) : Block
}

data class Page(val blocks: List<Block>)

/**
 * Paginates a device report into fixed-size pages (PS-01 stretch goal 14).
 *
 * Kept entirely free of Android so pagination — the part with the off-by-one
 * errors — can be unit-tested without a `PdfDocument`. [PdfReportWriter] does
 * nothing but walk these blocks and draw them.
 *
 * A4 at 72dpi is 595x842pt, which is what `PdfDocument.PageInfo` expects.
 */
class ReportLayout(
    private val pageWidth: Float = 595f,
    private val pageHeight: Float = 842f,
    private val marginTop: Float = 48f,
    private val marginBottom: Float = 48f,
    /** Characters that fit on one body line at the chosen font size. */
    private val charsPerLine: Int = 92
) {

    private val usableHeight: Float get() = pageHeight - marginTop - marginBottom

    fun build(
        report: DeviceReport,
        details: List<AppDetail>,
        deviceModel: String,
        osVersion: String,
        generatedAt: String
    ): List<Page> {
        val blocks = mutableListOf<Block>()

        // ---- Cover ----
        blocks += Block.Title("App Risk Report")
        blocks += Block.Spacer(8f)
        blocks += Block.KeyValue("Device", deviceModel)
        blocks += Block.KeyValue("Android", osVersion)
        blocks += Block.KeyValue("Scanned", generatedAt)
        blocks += Block.KeyValue("Apps scanned", report.appCount.toString())
        blocks += Block.KeyValue("System apps included", if (report.includedSystemApps) "yes" else "no")
        blocks += Block.Spacer(12f)
        blocks += Block.Heading(report.headline)
        blocks += Block.Rule()

        val maxBand = maxOf(
            report.bandCounts.critical, report.bandCounts.high,
            report.bandCounts.medium, report.bandCounts.low, 1
        )
        blocks += Block.Bar("Critical", report.bandCounts.critical, maxBand)
        blocks += Block.Bar("High", report.bandCounts.high, maxBand)
        blocks += Block.Bar("Medium", report.bandCounts.medium, maxBand)
        blocks += Block.Bar("Low", report.bandCounts.low, maxBand)
        blocks += Block.Rule()

        // ---- Ranked table ----
        blocks += Block.Heading("All apps, most risky first")
        blocks += Block.TableRow(listOf("#", "App", "Score", "Band"), band = null, header = true)
        report.ranked.forEach { app ->
            blocks += Block.TableRow(
                cells = listOf(
                    app.rank.toString(),
                    app.label.take(48),
                    app.score.toString(),
                    app.band.name
                ),
                band = app.band
            )
        }
        blocks += Block.Rule()

        // ---- Category summary ----
        if (report.categoryRollups.isNotEmpty()) {
            blocks += Block.Heading("What your apps can reach")
            val maxApps = report.categoryRollups.maxOf { it.appsWithGranted }.coerceAtLeast(1)
            report.categoryRollups.forEach { rollup ->
                blocks += Block.Bar(rollup.category.displayName, rollup.appsWithGranted, maxApps)
            }
            blocks += Block.Rule()
        }

        // ---- One section per High/Critical app ----
        val flagged = details.filter { it.band.requiresMinimumReasons }
        if (flagged.isNotEmpty()) {
            blocks += Block.Heading("Apps that need your attention")
        }
        flagged.forEach { detail ->
            blocks += Block.Heading("${detail.header.label}  —  ${detail.score}/100  ${detail.band.name}")
            blocks += Block.KeyValue("Package", detail.header.packageName)
            blocks += Block.KeyValue("Installed from", detail.header.installSource)
            detail.header.versionName?.let { blocks += Block.KeyValue("Version", it) }

            detail.whyFlagged.take(6).forEach { reason ->
                blocks += body("• ${reason.headline}")
                blocks += body("   ${reason.detail}")
            }

            detail.ml?.let { ml ->
                val pct = (ml.probability * 100).toInt()
                blocks += Block.KeyValue(
                    "Model score (for comparison)",
                    "$pct% — ${ml.label.name.lowercase()}" +
                        if (detail.mlDisagreesWithRules) "  (disagrees with the rules)" else ""
                )
            }

            if (detail.actions.isNotEmpty()) {
                blocks += body("What you can do:")
                detail.actions.sortedBy { it.order }.take(5).forEach { action ->
                    blocks += body("   ${action.order + 1}. ${action.title} — ${action.explanation}")
                }
            }
            blocks += Block.Rule()
        }

        // ---- Methodology and limitations ----
        blocks += Block.Heading("How these scores were produced")
        blocks += body(
            "Every app is scored from 0 to 100 using a published set of weights and combination rules. " +
                "Permissions alone are capped so that no app reaches High on permission count by itself; " +
                "reaching High or Critical requires special access, a known risky combination, an " +
                "unverified install source, or access that does not match what the app claims to be."
        )
        blocks += body(
            "Limitations: this tool reads what Android reports. It does not run or decompile apps, so it " +
                "cannot tell what an app actually does with the access it holds. A high score means an app " +
                "CAN do a lot of harm, not that it IS doing harm. Some settings cannot be read on every " +
                "phone; where that happened the report says so rather than guessing."
        )
        blocks += body(
            "All scanning ran on this device. Nothing was uploaded, except fingerprint lookups if you " +
                "turned them on."
        )

        if (report.skipped.isNotEmpty()) {
            blocks += body("${report.skipped.size} app(s) could not be read and were skipped.")
        }

        return paginate(blocks)
    }

    /** Split a body string into a [Block.Body] whose height reflects its wrapped line count. */
    private fun body(text: String, lineHeight: Float = 13f): Block.Body {
        val lines = wrappedLineCount(text)
        return Block.Body(text, lines, lines * lineHeight)
    }

    internal fun wrappedLineCount(text: String): Int {
        if (text.isEmpty()) return 1
        // +charsPerLine-1 gives a ceiling division without floating point.
        return text.split("\n").sumOf { line ->
            maxOf(1, (line.length + charsPerLine - 1) / charsPerLine)
        }
    }

    /**
     * Greedy pagination.
     *
     * A block taller than a whole page would otherwise loop forever producing
     * empty pages, so an oversized block is placed on a page of its own and the
     * renderer clips it. That is a rendering compromise, never an infinite loop.
     */
    internal fun paginate(blocks: List<Block>): List<Page> {
        val pages = mutableListOf<Page>()
        var current = mutableListOf<Block>()
        var used = 0f

        blocks.forEach { block ->
            if (block.height > usableHeight) {
                if (current.isNotEmpty()) {
                    pages += Page(current)
                    current = mutableListOf()
                    used = 0f
                }
                pages += Page(listOf(block))
                return@forEach
            }

            if (used + block.height > usableHeight) {
                pages += Page(current)
                current = mutableListOf()
                used = 0f
            }

            current += block
            used += block.height
        }

        if (current.isNotEmpty()) pages += Page(current)
        // An empty scan still produces one page rather than a zero-page PDF,
        // which some viewers refuse to open.
        if (pages.isEmpty()) pages += Page(listOf(Block.Title("App Risk Report")))
        return pages
    }

    val width: Float get() = pageWidth
    val height: Float get() = pageHeight
    val top: Float get() = marginTop
}
