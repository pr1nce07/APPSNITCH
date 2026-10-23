package com.riskauditor.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.core.content.FileProvider
import com.riskauditor.model.RiskBand
import com.riskauditor.presentation.AppDetail
import com.riskauditor.presentation.DeviceReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the paginated layout to a PDF (PS-01 stretch goal 14).
 *
 * This class draws; it does not decide. Everything about what goes where is in
 * [ReportLayout], which is pure Kotlin and unit-tested. That split is why the
 * pagination can be tested at all.
 *
 * The file is written to the app's own `cache/reports` directory and shared via
 * `FileProvider`. It is never uploaded: PS-01's offline-first and privacy
 * constraints apply to the report exactly as they do to the scan.
 */
class PdfReportWriter(
    private val context: Context,
    private val layout: ReportLayout = ReportLayout()
) {

    private val titlePaint = Paint().apply {
        isAntiAlias = true
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val headingPaint = Paint().apply {
        isAntiAlias = true
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val bodyPaint = Paint().apply {
        isAntiAlias = true
        textSize = 10f
        color = Color.DKGRAY
    }
    private val keyPaint = Paint().apply {
        isAntiAlias = true
        textSize = 10f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val rulePaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
    }

    /**
     * Band colours double up as the only colour in the document. They are chosen
     * to stay distinguishable when printed in greyscale, because a printed
     * report where every band looks the same is useless.
     */
    private fun bandColor(band: RiskBand?): Int = when (band) {
        RiskBand.CRITICAL -> Color.rgb(176, 0, 32)
        RiskBand.HIGH -> Color.rgb(198, 94, 0)
        RiskBand.MEDIUM -> Color.rgb(140, 120, 0)
        RiskBand.LOW -> Color.rgb(27, 94, 32)
        null -> Color.BLACK
    }

    fun write(
        report: DeviceReport,
        details: List<AppDetail>,
        outputFile: File = defaultOutputFile()
    ): File {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(Date(report.scannedAt.takeIf { it > 0 } ?: System.currentTimeMillis()))

        val pages = layout.build(
            report = report,
            details = details,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            generatedAt = generatedAt
        )

        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(
                    layout.width.toInt(), layout.height.toInt(), index + 1
                ).create()

                val pdfPage = document.startPage(info)
                drawPage(pdfPage.canvas, page, index + 1, pages.size)
                document.finishPage(pdfPage)
            }

            outputFile.parentFile?.mkdirs()
            outputFile.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }

        return outputFile
    }

    private fun drawPage(canvas: Canvas, page: Page, pageNumber: Int, pageCount: Int) {
        var y = layout.top
        val left = 48f
        val right = layout.width - 48f

        page.blocks.forEach { block ->
            when (block) {
                is Block.Title -> {
                    y += block.height * 0.75f
                    canvas.drawText(block.text, left, y, titlePaint)
                    y += block.height * 0.25f
                }

                is Block.Heading -> {
                    y += block.height * 0.75f
                    canvas.drawText(block.text, left, y, headingPaint)
                    y += block.height * 0.25f
                }

                is Block.Body -> {
                    val wrapped = wrap(block.text, 92)
                    wrapped.forEach { line ->
                        y += 13f
                        canvas.drawText(line, left, y, bodyPaint)
                    }
                }

                is Block.KeyValue -> {
                    y += block.height
                    canvas.drawText(block.key, left, y, keyPaint)
                    canvas.drawText(block.value, left + 150f, y, bodyPaint)
                }

                is Block.TableRow -> {
                    y += block.height
                    val paint = if (block.header) keyPaint else bodyPaint
                    val columns = floatArrayOf(left, left + 30f, left + 330f, left + 390f)
                    block.cells.forEachIndexed { i, cell ->
                        val p = if (!block.header && i == 3) {
                            Paint(paint).apply { color = bandColor(block.band) }
                        } else {
                            paint
                        }
                        canvas.drawText(cell, columns.getOrElse(i) { left }, y, p)
                    }
                }

                is Block.Bar -> {
                    y += block.height
                    canvas.drawText(block.label, left, y, bodyPaint)
                    val barLeft = left + 180f
                    val barMax = right - barLeft - 30f
                    val fraction = if (block.max == 0) 0f else block.value.toFloat() / block.max
                    val fill = Paint().apply { color = Color.rgb(90, 90, 90) }
                    canvas.drawRect(barLeft, y - 9f, barLeft + barMax * fraction, y - 1f, fill)
                    canvas.drawText(block.value.toString(), right - 24f, y, bodyPaint)
                }

                is Block.Rule -> {
                    y += block.height / 2
                    canvas.drawLine(left, y, right, y, rulePaint)
                    y += block.height / 2
                }

                is Block.Spacer -> y += block.height
            }
        }

        canvas.drawText(
            "Page $pageNumber of $pageCount  ·  generated on this device, nothing uploaded",
            left,
            layout.height - 24f,
            bodyPaint
        )
    }

    private fun wrap(text: String, charsPerLine: Int): List<String> {
        if (text.length <= charsPerLine) return listOf(text)
        val out = mutableListOf<String>()
        var line = StringBuilder()
        text.split(" ").forEach { word ->
            if (line.length + word.length + 1 > charsPerLine) {
                out += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    fun defaultOutputFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return File(File(context.cacheDir, "reports"), "app-risk-report-$stamp.pdf")
    }

    /**
     * A content URI for sharing. FileProvider only — a `file://` URI throws
     * `FileUriExposedException` from API 24 onwards.
     */
    fun shareUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.reports",
        file
    )
}
