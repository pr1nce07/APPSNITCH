package com.riskauditor.report

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.riskauditor.model.Category
import com.riskauditor.model.RiskBand
import com.riskauditor.presentation.BandCounts
import com.riskauditor.presentation.CategoryRollup
import com.riskauditor.presentation.DeviceReport
import com.riskauditor.presentation.RankedApp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class PdfReportWriterTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun report(appCount: Int): DeviceReport = DeviceReport(
        scannedAt = 1_700_000_000_000L,
        appCount = appCount,
        includedSystemApps = false,
        bandCounts = BandCounts(low = appCount),
        ranked = (1..appCount).map {
            RankedApp(
                rank = it,
                packageName = "com.app$it",
                label = "App $it",
                score = 10,
                band = RiskBand.LOW,
                topReason = "Reason $it",
                isSystem = false,
                installSource = "Google Play"
            )
        },
        categoryRollups = if (appCount == 0) emptyList() else listOf(
            CategoryRollup(Category.NETWORK, "Network", appCount, 0, appCount, emptyList(), "$appCount apps can send data online")
        ),
        specialAccessRoster = emptyList(),
        headline = "Scanned $appCount apps."
    )

    private fun outputFile(name: String): File =
        Files.createTempDirectory("pdf-report").resolve(name).toFile()

    @Test
    fun `a report with 300 apps produces pages and does not crash`() {
        val file = PdfReportWriter(context).write(report(300), emptyList(), outputFile("big.pdf"))

        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isGreaterThan(0L)
    }

    @Test
    fun `an empty scan does not crash`() {
        val file = PdfReportWriter(context).write(report(0), emptyList(), outputFile("empty.pdf"))

        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isGreaterThan(0L)
    }

    @Test
    fun `the output is a valid pdf header`() {
        val file = PdfReportWriter(context).write(report(5), emptyList(), outputFile("small.pdf"))
        val head = file.inputStream().use { String(it.readNBytes(5)) }
        assertThat(head).isEqualTo("%PDF-")
    }

    @Test
    fun `the default output path lives in the app cache and never outside it`() {
        val file = PdfReportWriter(context).defaultOutputFile()
        assertThat(file.absolutePath).contains(context.cacheDir.absolutePath)
        assertThat(file.name).endsWith(".pdf")
    }

    @Test
    fun `parent directories are created as needed`() {
        val nested = Files.createTempDirectory("pdf-nested")
            .resolve("a/b/c/report.pdf").toFile()

        val file = PdfReportWriter(context).write(report(2), emptyList(), nested)
        assertThat(file.exists()).isTrue()
    }
}
