package com.example.data.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.domain.bom.CutListEntry
import com.example.domain.math.BoundingBox3D
import com.example.domain.model.UnitSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

data class CutListPdfExportResult(
    val fileName: String,
    val savedLocationLabel: String,
    val downloadsUri: Uri?,
    val shareUri: Uri
)

object CutListPdfExporter {

    private const val PAGE_WIDTH = 595   // A4 portrait points (72 dpi)
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    fun exportToDownloadsAndShareCache(
        context: Context,
        projectTitle: String,
        entries: List<CutListEntry>,
        totalPieces: Int,
        modelBounds: BoundingBox3D,
        unitSystem: UnitSystem,
        precisionDecimals: Int,
        explodeFraction: Float,
        viewportSnapshot: Bitmap?,
        nowMillis: Long = System.currentTimeMillis(),
        selectedPartsOnly: Boolean = true
    ): CutListPdfExportResult {
        val safeBase = projectTitle.substringBeforeLast('.')
            .replace(Regex("""[^A-Za-z0-9_-]+"""), "_")
            .trim('_')
            .ifEmpty { "IngeTrazo_Model" }
        val fileDate = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis))
        val fileName = "${safeBase}_CutList_$fileDate.pdf"

        val pdfBytes = renderPdfBytes(
            projectTitle = projectTitle,
            entries = entries,
            totalPieces = totalPieces,
            modelBounds = modelBounds,
            unitSystem = unitSystem,
            precisionDecimals = precisionDecimals,
            explodeFraction = explodeFraction,
            viewportSnapshot = viewportSnapshot,
            nowMillis = nowMillis,
            selectedPartsOnly = selectedPartsOnly
        )

        // 1. Write shareable file inside FileProvider-exposed cache directory
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Keep cache clean of stale PDFs
        exportDir.listFiles()?.forEach { old ->
            if (old.name.endsWith(".pdf") && old.lastModified() < nowMillis - 3600_000L) {
                old.delete()
            }
        }
        val cacheFile = File(exportDir, fileName)
        cacheFile.writeBytes(pdfBytes)
        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )

        // 2. Save to device Downloads without broad legacy storage permissions
        val (downloadsUri, savedLabel) = saveToDownloads(context, fileName, pdfBytes)

        return CutListPdfExportResult(
            fileName = fileName,
            savedLocationLabel = savedLabel,
            downloadsUri = downloadsUri ?: shareUri,
            shareUri = shareUri
        )
    }

    internal fun renderPdfBytes(
        projectTitle: String,
        entries: List<CutListEntry>,
        totalPieces: Int,
        modelBounds: BoundingBox3D,
        unitSystem: UnitSystem,
        precisionDecimals: Int,
        explodeFraction: Float,
        viewportSnapshot: Bitmap?,
        nowMillis: Long = System.currentTimeMillis(),
        selectedPartsOnly: Boolean = true
    ): ByteArray {
        var pdf: PdfDocument? = null
        return try {
            pdf = buildPdfDocument(
                projectTitle = projectTitle,
                entries = entries,
                totalPieces = totalPieces,
                modelBounds = modelBounds,
                unitSystem = unitSystem,
                precisionDecimals = precisionDecimals,
                explodeFraction = explodeFraction,
                viewportSnapshot = viewportSnapshot,
                nowMillis = nowMillis,
                selectedPartsOnly = selectedPartsOnly
            )
            ByteArrayOutputStream().use { baos ->
                pdf.writeTo(baos)
                baos.toByteArray()
            }
        } catch (_: IllegalStateException) {
            renderPurePdf14Bytes(
                projectTitle = projectTitle,
                entries = entries,
                totalPieces = totalPieces,
                modelBounds = modelBounds,
                unitSystem = unitSystem,
                precisionDecimals = precisionDecimals,
                explodeFraction = explodeFraction,
                viewportSnapshot = viewportSnapshot,
                nowMillis = nowMillis,
                selectedPartsOnly = selectedPartsOnly
            )
        } catch (_: UnsatisfiedLinkError) {
            renderPurePdf14Bytes(
                projectTitle = projectTitle,
                entries = entries,
                totalPieces = totalPieces,
                modelBounds = modelBounds,
                unitSystem = unitSystem,
                precisionDecimals = precisionDecimals,
                explodeFraction = explodeFraction,
                viewportSnapshot = viewportSnapshot,
                nowMillis = nowMillis,
                selectedPartsOnly = selectedPartsOnly
            )
        } finally {
            runCatching { pdf?.close() }
        }
    }

    internal fun buildPdfDocument(
        projectTitle: String,
        entries: List<CutListEntry>,
        totalPieces: Int,
        modelBounds: BoundingBox3D,
        unitSystem: UnitSystem,
        precisionDecimals: Int,
        explodeFraction: Float,
        viewportSnapshot: Bitmap?,
        nowMillis: Long = System.currentTimeMillis(),
        selectedPartsOnly: Boolean = true
    ): PdfDocument {
        val pdf = PdfDocument()
        val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(nowMillis))
        val explodePct = (explodeFraction.coerceIn(0f, 1f) * 100f).roundToInt()

        val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F172A") }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F97316") }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 9.5f
        }
        val metaRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f
            textAlign = Paint.Align.RIGHT
        }
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val summaryCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F1F5F9") }
        val summaryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 8.5f
        }
        val summaryValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val tableHeaderBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E293B") }
        val tableHeaderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val zebraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }
        val rowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 9.5f
        }
        val rowMonoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val rowQtyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C2410C")
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 8.5f
        }

        val colNoX = MARGIN + 8f
        val colNameX = MARGIN + 36f
        val colDimX = MARGIN + 245f
        val colQtyRightX = PAGE_WIDTH - MARGIN - 10f
        val rowHeight = 23f
        val bottomLimit = PAGE_HEIGHT - 44f

        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas

        fun drawHeaderBar(targetCanvas: Canvas, continued: Boolean) {
            targetCanvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 64f, headerBgPaint)
            targetCanvas.drawRect(0f, 61f, PAGE_WIDTH.toFloat(), 64f, accentPaint)
            val truncatedTitle = if (projectTitle.length > 36) projectTitle.take(33) + "..." else projectTitle
            targetCanvas.drawText(truncatedTitle, MARGIN, 28f, titlePaint)
            val sub = if (continued) {
                "3D Cut List / Bill of Materials (Continued)"
            } else {
                "Automatic 3D Cut List (Bill of Materials)"
            }
            targetCanvas.drawText(sub, MARGIN, 46f, subtitlePaint)
            targetCanvas.drawText("Generated: $dateText", PAGE_WIDTH - MARGIN, 27f, metaRightPaint)
            val viewTag = if (explodePct > 0) {
                "Units: ${unitSystem.symbol} · Explode: $explodePct%"
            } else {
                "Units: ${unitSystem.symbol} · Standard View"
            }
            targetCanvas.drawText(viewTag, PAGE_WIDTH - MARGIN, 45f, metaRightPaint)
        }

        fun drawTableHeader(targetCanvas: Canvas, topY: Float): Float {
            targetCanvas.drawRect(MARGIN, topY, PAGE_WIDTH - MARGIN, topY + 22f, tableHeaderBgPaint)
            targetCanvas.drawText("#", colNoX, topY + 14.5f, tableHeaderTextPaint)
            targetCanvas.drawText("Part Name / ID", colNameX, topY + 14.5f, tableHeaderTextPaint)
            targetCanvas.drawText("Dimensions (L × W × H)", colDimX, topY + 14.5f, tableHeaderTextPaint)
            val qtyHeaderPaint = Paint(tableHeaderTextPaint).apply { textAlign = Paint.Align.RIGHT }
            targetCanvas.drawText("Qty", colQtyRightX, topY + 14.5f, qtyHeaderPaint)
            return topY + 22f
        }

        drawHeaderBar(canvas, continued = false)
        var y = 78f

        // 2D Rendered Screenshot of selected parts (or exploded view)
        if (viewportSnapshot != null && viewportSnapshot.width > 0 && viewportSnapshot.height > 0) {
            val basePreviewTitle = if (selectedPartsOnly) "3D Selected Parts Preview" else "3D Viewport Preview"
            val viewTitle = if (explodePct > 0) {
                "$basePreviewTitle (Exploded View · $explodePct%)"
            } else {
                basePreviewTitle
            }
            canvas.drawText(viewTitle, MARGIN, y + 10f, sectionTitlePaint)
            y += 16f

            val maxImgW = CONTENT_WIDTH - 8f
            val maxImgH = 192f
            val scale = min(maxImgW / viewportSnapshot.width.toFloat(), maxImgH / viewportSnapshot.height.toFloat())
            val drawW = viewportSnapshot.width * scale
            val drawH = viewportSnapshot.height * scale
            val imgLeft = MARGIN + (CONTENT_WIDTH - drawW) * 0.5f

            val frameRect = RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + drawH + 8f)
            canvas.drawRoundRect(frameRect, 6f, 6f, summaryCardPaint)
            canvas.drawRoundRect(frameRect, 6f, 6f, borderPaint)
            canvas.drawBitmap(
                viewportSnapshot,
                null,
                RectF(imgLeft, y + 4f, imgLeft + drawW, y + 4f + drawH),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            y += drawH + 18f
        }

        // Summary KPI strip
        val kpiRect = RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + 42f)
        canvas.drawRoundRect(kpiRect, 6f, 6f, summaryCardPaint)
        canvas.drawRoundRect(kpiRect, 6f, 6f, borderPaint)
        val colStep = CONTENT_WIDTH / 3f
        canvas.drawText("Unique Part Types", MARGIN + 12f, y + 15f, summaryLabelPaint)
        canvas.drawText("${entries.size}", MARGIN + 12f, y + 31f, summaryValuePaint)

        canvas.drawText("Total Pieces", MARGIN + colStep + 12f, y + 15f, summaryLabelPaint)
        canvas.drawText("$totalPieces", MARGIN + colStep + 12f, y + 31f, summaryValuePaint)

        val overallText = if (modelBounds.isValid) {
            val sz = modelBounds.size
            val d = precisionDecimals.coerceIn(0, 3)
            String.format(
                Locale.US,
                "%.${d}f × %.${d}f × %.${d}f %s",
                unitSystem.fromMeters(sz.x),
                unitSystem.fromMeters(sz.y),
                unitSystem.fromMeters(sz.z),
                unitSystem.symbol
            )
        } else "—"
        val extentsLabel = if (selectedPartsOnly) "Selected Parts Extents" else "Overall Assembly Extents"
        canvas.drawText(extentsLabel, MARGIN + colStep * 2f + 12f, y + 15f, summaryLabelPaint)
        canvas.drawText(overallText, MARGIN + colStep * 2f + 12f, y + 31f, summaryValuePaint)
        y += 56f

        canvas.drawText("Cut List Breakdown", MARGIN, y + 10f, sectionTitlePaint)
        y += 16f
        y = drawTableHeader(canvas, y)

        for ((idx, entry) in entries.withIndex()) {
            if (y + rowHeight > bottomLimit) {
                canvas.drawText("IngeTrazo Mobile · Page $pageNumber", MARGIN, PAGE_HEIGHT - 20f, footerPaint)
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                drawHeaderBar(canvas, continued = true)
                y = drawTableHeader(canvas, 80f)
            }

            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + rowHeight, zebraPaint)
            }
            canvas.drawLine(MARGIN, y + rowHeight, PAGE_WIDTH - MARGIN, y + rowHeight, borderPaint)

            val baseline = y + 15f
            canvas.drawText("${entry.partNumber}", colNoX, baseline, rowMonoPaint)
            val safeName = if (entry.partName.length > 30) entry.partName.take(27) + "..." else entry.partName
            canvas.drawText(safeName, colNameX, baseline, rowTextPaint)
            canvas.drawText(entry.formatDimensions(unitSystem, precisionDecimals), colDimX, baseline, rowMonoPaint)
            canvas.drawText("×${entry.quantity}", colQtyRightX, baseline, rowQtyPaint)
            y += rowHeight
        }

        if (entries.isEmpty()) {
            canvas.drawText("No solid CAD parts extracted for this model.", colNoX, y + 18f, rowTextPaint)
            y += 28f
        }

        // Outer table outline on final page segment & total line
        canvas.drawText("Total Pieces: $totalPieces", MARGIN, (y + 18f).coerceAtMost(bottomLimit), sectionTitlePaint)
        canvas.drawText("IngeTrazo Mobile · Page $pageNumber", MARGIN, PAGE_HEIGHT - 20f, footerPaint)
        pdf.finishPage(page)

        return pdf
    }

    private fun saveToDownloads(
        context: Context,
        fileName: String,
        pdfBytes: ByteArray
    ): Pair<Uri?, String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(pdfBytes)
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    uri to "Downloads/$fileName"
                } else {
                    null to "App cache (Share ready)"
                }
            } else {
                val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outFile = File(downloadsDir, fileName)
                outFile.writeBytes(pdfBytes)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outFile
                )
                uri to "Downloads/$fileName"
            }
        } catch (_: Throwable) {
            null to "App cache (Share ready)"
        }
    }

    private fun renderPurePdf14Bytes(
        projectTitle: String,
        entries: List<CutListEntry>,
        totalPieces: Int,
        modelBounds: BoundingBox3D,
        unitSystem: UnitSystem,
        precisionDecimals: Int,
        explodeFraction: Float,
        viewportSnapshot: Bitmap?,
        nowMillis: Long,
        selectedPartsOnly: Boolean = true
    ): ByteArray {
        val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(nowMillis))
        val explodePct = (explodeFraction.coerceIn(0f, 1f) * 100f).roundToInt()

        val jpegBytes: ByteArray? = if (viewportSnapshot != null && viewportSnapshot.width > 0 && viewportSnapshot.height > 0) {
            ByteArrayOutputStream().use { baos ->
                if (viewportSnapshot.compress(Bitmap.CompressFormat.JPEG, 88, baos)) {
                    baos.toByteArray().takeIf { it.isNotEmpty() }
                } else null
            }
        } else null

        fun pdfEsc(raw: String): String = buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '(' -> append("\\(")
                    ')' -> append("\\)")
                    '×' -> append('x')
                    '·' -> append('-')
                    '—' -> append('-')
                    else -> if (ch.code in 32..126) append(ch) else append(' ')
                }
            }
        }

        // Convert top-down Y to PDF bottom-up Y
        fun py(topY: Float): Float = PAGE_HEIGHT - topY

        val pageStreams = mutableListOf<String>()
        var pageNum = 1
        val sb = StringBuilder()

        fun drawHeader(continued: Boolean) {
            sb.append("0.059 0.090 0.165 rg 0 ${py(64f)} $PAGE_WIDTH 64 re f\n")
            sb.append("0.976 0.451 0.086 rg 0 ${py(64f)} $PAGE_WIDTH 3 re f\n")
            val title = if (projectTitle.length > 36) projectTitle.take(33) + "..." else projectTitle
            sb.append("1 1 1 rg BT /F2 15 Tf $MARGIN ${py(28f)} Td (${pdfEsc(title)}) Tj ET\n")
            val sub = if (continued) "3D Cut List / Bill of Materials (Continued)" else "Automatic 3D Cut List (Bill of Materials)"
            sb.append("0.80 0.84 0.88 rg BT /F1 9.5 Tf $MARGIN ${py(46f)} Td (${pdfEsc(sub)}) Tj ET\n")
            val viewTag = if (explodePct > 0) "Explode: $explodePct% | $dateText" else "Standard View | $dateText"
            sb.append("1 1 1 rg BT /F1 9 Tf ${PAGE_WIDTH - MARGIN - 165f} ${py(28f)} Td (${pdfEsc(viewTag)}) Tj ET\n")
        }

        fun drawTableHeader(topY: Float): Float {
            sb.append("0.118 0.161 0.231 rg $MARGIN ${py(topY + 22f)} $CONTENT_WIDTH 22 re f\n")
            val textY = py(topY + 15f)
            sb.append("1 1 1 rg BT /F2 9.5 Tf ${MARGIN + 8f} $textY Td (#) Tj ET\n")
            sb.append("BT /F2 9.5 Tf ${MARGIN + 36f} $textY Td (Part Name / ID) Tj ET\n")
            sb.append("BT /F2 9.5 Tf ${MARGIN + 245f} $textY Td (Dimensions L x W x H) Tj ET\n")
            sb.append("BT /F2 9.5 Tf ${PAGE_WIDTH - MARGIN - 28f} $textY Td (Qty) Tj ET\n")
            return topY + 22f
        }

        drawHeader(continued = false)
        var y = 78f

        if (jpegBytes != null && viewportSnapshot != null) {
            val baseLabel = if (selectedPartsOnly) "3D Selected Parts Preview" else "3D Viewport Preview"
            val previewLabel = if (explodePct > 0) "$baseLabel (Exploded $explodePct%)" else baseLabel
            sb.append("0.059 0.090 0.165 rg BT /F2 11 Tf $MARGIN ${py(y + 10f)} Td (${pdfEsc(previewLabel)}) Tj ET\n")
            y += 16f
            val maxImgW = CONTENT_WIDTH - 8f
            val maxImgH = 192f
            val scale = min(maxImgW / viewportSnapshot.width.toFloat(), maxImgH / viewportSnapshot.height.toFloat())
            val drawW = viewportSnapshot.width * scale
            val drawH = viewportSnapshot.height * scale
            val imgLeft = MARGIN + (CONTENT_WIDTH - drawW) * 0.5f
            sb.append("0.945 0.961 0.976 rg $MARGIN ${py(y + drawH + 8f)} $CONTENT_WIDTH ${drawH + 8f} re f\n")
            sb.append("q $drawW 0 0 $drawH $imgLeft ${py(y + 4f + drawH)} cm /Im1 Do Q\n")
            y += drawH + 18f
        }

        // KPI Summary Bar
        sb.append("0.945 0.961 0.976 rg $MARGIN ${py(y + 42f)} $CONTENT_WIDTH 42 re f\n")
        val colStep = CONTENT_WIDTH / 3f
        sb.append("0.278 0.333 0.412 rg BT /F1 8.5 Tf ${MARGIN + 12f} ${py(y + 15f)} Td (Unique Part Types) Tj ET\n")
        sb.append("0.059 0.090 0.165 rg BT /F3 11 Tf ${MARGIN + 12f} ${py(y + 31f)} Td (${entries.size}) Tj ET\n")
        sb.append("0.278 0.333 0.412 rg BT /F1 8.5 Tf ${MARGIN + colStep + 12f} ${py(y + 15f)} Td (Total Pieces) Tj ET\n")
        sb.append("0.059 0.090 0.165 rg BT /F3 11 Tf ${MARGIN + colStep + 12f} ${py(y + 31f)} Td ($totalPieces) Tj ET\n")
        val extentsText = if (modelBounds.isValid) {
            val sz = modelBounds.size
            val d = precisionDecimals.coerceIn(0, 3)
            String.format(
                Locale.US,
                "%.${d}f x %.${d}f x %.${d}f %s",
                unitSystem.fromMeters(sz.x),
                unitSystem.fromMeters(sz.y),
                unitSystem.fromMeters(sz.z),
                unitSystem.symbol
            )
        } else "-"
        val extentsLabel = if (selectedPartsOnly) "Selected Parts Extents" else "Assembly Extents"
        sb.append("0.278 0.333 0.412 rg BT /F1 8.5 Tf ${MARGIN + colStep * 2f + 12f} ${py(y + 15f)} Td (${pdfEsc(extentsLabel)}) Tj ET\n")
        sb.append("0.059 0.090 0.165 rg BT /F3 10 Tf ${MARGIN + colStep * 2f + 12f} ${py(y + 31f)} Td (${pdfEsc(extentsText)}) Tj ET\n")
        y += 56f

        sb.append("0.059 0.090 0.165 rg BT /F2 11.5 Tf $MARGIN ${py(y + 10f)} Td (Cut List Breakdown) Tj ET\n")
        y += 16f
        y = drawTableHeader(y)

        val rowHeight = 23f
        val bottomLimit = PAGE_HEIGHT - 44f
        for ((idx, entry) in entries.withIndex()) {
            if (y + rowHeight > bottomLimit) {
                sb.append("0.392 0.455 0.545 rg BT /F1 8.5 Tf $MARGIN 20 Td (IngeTrazo Mobile - Page $pageNum) Tj ET\n")
                pageStreams.add(sb.toString())
                sb.clear()
                pageNum++
                drawHeader(continued = true)
                y = drawTableHeader(80f)
            }
            if (idx % 2 == 1) {
                sb.append("0.973 0.980 0.988 rg $MARGIN ${py(y + rowHeight)} $CONTENT_WIDTH $rowHeight re f\n")
            }
            val baseY = py(y + 15f)
            val safeName = if (entry.partName.length > 30) entry.partName.take(27) + "..." else entry.partName
            sb.append("0.059 0.090 0.165 rg BT /F3 9 Tf ${MARGIN + 8f} $baseY Td (${entry.partNumber}) Tj ET\n")
            sb.append("BT /F1 9.5 Tf ${MARGIN + 36f} $baseY Td (${pdfEsc(safeName)}) Tj ET\n")
            sb.append("BT /F3 9 Tf ${MARGIN + 245f} $baseY Td (${pdfEsc(entry.formatDimensions(unitSystem, precisionDecimals))}) Tj ET\n")
            sb.append("0.761 0.255 0.047 rg BT /F3 9.5 Tf ${PAGE_WIDTH - MARGIN - 30f} $baseY Td (x${entry.quantity}) Tj ET\n")
            y += rowHeight
        }

        sb.append("0.059 0.090 0.165 rg BT /F2 11 Tf $MARGIN ${py((y + 18f).coerceAtMost(bottomLimit))} Td (Total Pieces: $totalPieces) Tj ET\n")
        sb.append("0.392 0.455 0.545 rg BT /F1 8.5 Tf $MARGIN 20 Td (IngeTrazo Mobile - Page $pageNum) Tj ET\n")
        pageStreams.add(sb.toString())

        // Assemble multi-object PDF 1.4 binary
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        fun writeAscii(s: String) {
            out.write(s.toByteArray(Charsets.US_ASCII))
        }

        fun beginObj(id: Int) {
            offsets.add(out.size())
            writeAscii("$id 0 obj\n")
        }

        writeAscii("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
        val hasImage = jpegBytes != null && viewportSnapshot != null
        val catalogId = 1
        val pagesId = 2
        val f1Id = 3
        val f2Id = 4
        val f3Id = 5
        val imgId = if (hasImage) 6 else -1
        val firstPageObjId = if (hasImage) 7 else 6

        val pageIds = pageStreams.indices.map { firstPageObjId + it * 2 }

        beginObj(catalogId)
        writeAscii("<< /Type /Catalog /Pages $pagesId 0 R >>\nendobj\n")

        beginObj(pagesId)
        writeAscii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")

        beginObj(f1Id)
        writeAscii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        beginObj(f2Id)
        writeAscii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>\nendobj\n")

        beginObj(f3Id)
        writeAscii("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>\nendobj\n")

        if (hasImage) {
            beginObj(imgId)
            writeAscii("<< /Type /XObject /Subtype /Image /Width ${viewportSnapshot!!.width} /Height ${viewportSnapshot.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpegBytes!!.size} >>\nstream\n")
            out.write(jpegBytes)
            writeAscii("\nendstream\nendobj\n")
        }

        pageStreams.forEachIndexed { i, streamText ->
            val pageObjId = firstPageObjId + i * 2
            val contentObjId = pageObjId + 1
            val xObjEntry = if (i == 0 && hasImage) " /XObject << /Im1 $imgId 0 R >>" else ""
            beginObj(pageObjId)
            writeAscii("<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 $PAGE_WIDTH $PAGE_HEIGHT] /Resources << /Font << /F1 $f1Id 0 R /F2 $f2Id 0 R /F3 $f3Id 0 R >>$xObjEntry >> /Contents $contentObjId 0 R >>\nendobj\n")

            val streamBytes = streamText.toByteArray(Charsets.US_ASCII)
            beginObj(contentObjId)
            writeAscii("<< /Length ${streamBytes.size} >>\nstream\n")
            out.write(streamBytes)
            writeAscii("endstream\nendobj\n")
        }

        val xrefOffset = out.size()
        val totalObjs = offsets.size + 1
        writeAscii("xref\n0 $totalObjs\n0000000000 65535 f \n")
        for (off in offsets) {
            writeAscii(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        writeAscii("trailer\n<< /Size $totalObjs /Root $catalogId 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")

        return out.toByteArray()
    }
}
