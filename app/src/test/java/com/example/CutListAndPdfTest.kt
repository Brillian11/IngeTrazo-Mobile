package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.export.CutListPdfExportResult
import com.example.data.export.CutListPdfExporter
import com.example.data.local.UserPreferences
import com.example.domain.bom.CutListEntry
import com.example.domain.bom.CutListExtractor
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Mat4
import com.example.domain.math.Ray3D
import com.example.domain.math.SnapEngine
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.model.CadColor
import com.example.domain.model.CadDocument
import com.example.domain.model.CadFace
import com.example.domain.model.CadLayer
import com.example.domain.model.CadNode
import com.example.domain.model.CadTool
import com.example.domain.model.ModelFormat
import com.example.domain.model.ModelStats
import com.example.domain.model.UnitSystem
import com.example.ui.CutListUiState
import com.example.ui.ViewerUiState
import com.example.ui.theme.IngeTrazoTheme
import com.example.ui.viewer.CadViewerScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CutListAndPdfTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun rotationZMatrix(angleRad: Double, tx: Double, ty: Double, tz: Double): Mat4 {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Mat4(
            doubleArrayOf(
                c, s, 0.0, 0.0,
                -s, c, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                tx, ty, tz, 1.0
            )
        )
    }

    private fun makeBoxFaces(
        nodeId: String,
        lx: Double,
        ly: Double,
        lz: Double,
        xform: Mat4
    ): List<CadFace> {
        val hx = lx * 0.5
        val hy = ly * 0.5
        val hz = lz * 0.5
        val corners = listOf(
            Vec3(-hx, -hy, -hz), // 0
            Vec3(hx, -hy, -hz),  // 1
            Vec3(hx, hy, -hz),   // 2
            Vec3(-hx, hy, -hz),  // 3
            Vec3(-hx, -hy, hz),  // 4
            Vec3(hx, -hy, hz),   // 5
            Vec3(hx, hy, hz),    // 6
            Vec3(-hx, hy, hz)    // 7
        ).map { xform.transformPoint(it) }

        val quadSpecs = listOf(
            listOf(3, 2, 1, 0) to Vec3(0.0, 0.0, -1.0),
            listOf(4, 5, 6, 7) to Vec3(0.0, 0.0, 1.0),
            listOf(0, 1, 5, 4) to Vec3(0.0, -1.0, 0.0),
            listOf(1, 2, 6, 5) to Vec3(1.0, 0.0, 0.0),
            listOf(2, 3, 7, 6) to Vec3(0.0, 1.0, 0.0),
            listOf(3, 0, 4, 7) to Vec3(-1.0, 0.0, 0.0)
        )

        return quadSpecs.mapIndexed { idx, (indices, localN) ->
            val loop = indices.map { corners[it] }
            val tris = listOf(loop[0], loop[1], loop[2], loop[0], loop[2], loop[3])
            CadFace(
                id = "${nodeId}_f$idx",
                nodeId = nodeId,
                layer = "Framing",
                outerLoop = loop,
                normal = xform.transformDirection(localN).normalized(),
                triangleVertices = tris,
                triangleUvs = List(6) { Vec2(0.0, 0.0) },
                color = CadColor(0.76f, 0.55f, 0.32f, 1f)
            )
        }
    }

    private fun buildTestTimberDoc(): CadDocument {
        // Two identical beams (1.20m x 0.14m x 0.04m) — one axis-aligned, one rotated 45 degrees!
        val beamAForm = rotationZMatrix(0.0, -1.0, 0.0, 0.5)
        val beamBForm = rotationZMatrix(PI / 4.0, 1.0, 0.5, 0.5)
        // One shorter post (0.80m x 0.10m x 0.10m)
        val postForm = rotationZMatrix(PI / 2.0, 0.0, -1.0, 0.4)

        val nodes = listOf(
            CadNode("n_beam_a", "Side Beam #1", layer = "Framing", isComponent = true, localTransform = beamAForm, faceCount = 6),
            CadNode("n_beam_b", "Side Beam #2", layer = "Framing", isComponent = true, localTransform = beamBForm, faceCount = 6),
            CadNode("n_post", "Corner Post", layer = "Framing", isComponent = true, localTransform = postForm, faceCount = 6)
        )

        val faces = makeBoxFaces("n_beam_a", 1.20, 0.14, 0.04, beamAForm) +
            makeBoxFaces("n_beam_b", 1.20, 0.14, 0.04, beamBForm) +
            makeBoxFaces("n_post", 0.10, 0.10, 0.80, postForm)

        var bounds = BoundingBox3D.EMPTY
        for (f in faces) {
            for (v in f.triangleVertices) bounds = bounds.expand(v)
        }

        return CadDocument(
            documentKey = "cutlist_test_doc",
            title = "Timber Frame Pergola",
            format = ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = nodes,
            faces = faces,
            edges = emptyList(),
            layers = listOf(CadLayer("Framing")),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = bounds,
            stats = ModelStats(nodes.size, faces.size, faces.size * 2, 0, faces.size * 4, 1, 0, 0)
        )
    }

    @Test
    fun `cut list extractor groups identical rotated parts, converts units, and computes explode vectors`() {
        val doc = buildTestTimberDoc()
        val result = CutListExtractor.extract(doc)

        assertEquals(3, result.totalPieceCount)
        assertEquals(2, result.entries.size)

        val beamEntry = result.entries.first { it.quantity == 2 }
        assertEquals("Side Beam", beamEntry.partName)
        assertEquals(1.20, beamEntry.lengthMeters, 1e-3)
        assertEquals(0.14, beamEntry.widthMeters, 1e-3)
        assertEquals(0.04, beamEntry.heightMeters, 1e-3)
        assertEquals("1200 × 140 × 40 mm", beamEntry.formatDimensions(UnitSystem.MILLIMETERS, 0))
        assertEquals("47.24 × 5.51 × 1.57 in", beamEntry.formatDimensions(UnitSystem.INCHES, 2))
        assertEquals(setOf("n_beam_a", "n_beam_b"), beamEntry.memberNodeIds)

        val postEntry = result.entries.first { it.quantity == 1 }
        assertEquals("Corner Post", postEntry.partName)
        assertEquals(0.80, postEntry.lengthMeters, 1e-3)
        assertEquals(0.10, postEntry.widthMeters, 1e-3)
        assertEquals(0.10, postEntry.heightMeters, 1e-3)

        val zeroExplode = result.computeExplodeOffsets(doc.bounds, 0f)
        assertTrue(zeroExplode.isEmpty())

        val fullExplode = result.computeExplodeOffsets(doc.bounds, 1f)
        assertEquals(3, fullExplode.size)
        val beamAOffset = fullExplode["n_beam_a"]!!
        val beamBOffset = fullExplode["n_beam_b"]!!
        // Left beam explodes toward -X, right beam explodes toward +X
        assertTrue(beamAOffset.x < -0.1)
        assertTrue(beamBOffset.x > 0.1)
    }

    @Test
    fun `snap engine ray picking respects explode offsets`() {
        val doc = buildTestTimberDoc()
        val result = CutListExtractor.extract(doc)
        val explodeOffsets = result.computeExplodeOffsets(doc.bounds, 1f)
        val beamAOffset = explodeOffsets["n_beam_a"]!!

        // Pick center of exploded Beam A from directly above along -Z
        val explodedCenter = Vec3(-1.0, 0.0, 0.5) + beamAOffset
        val ray = Ray3D(
            origin = Vec3(explodedCenter.x, explodedCenter.y, explodedCenter.z + 2.0),
            direction = Vec3(0.0, 0.0, -1.0)
        )

        val hitWithoutExplode = SnapEngine.raycastFaces(doc, emptySet(), emptySet(), ray)
        assertNull(hitWithoutExplode)

        val hitWithExplode = SnapEngine.raycastFaces(
            doc = doc,
            hiddenLayers = emptySet(),
            hiddenNodes = emptySet(),
            ray = ray,
            explodeOffsets = explodeOffsets
        )
        assertNotNull(hitWithExplode)
        assertEquals("n_beam_a", hitWithExplode?.nodeId)
    }

    @Test
    fun `pdf exporter builds paginated valid pdf bytes with embedded 3d preview bitmap`() {
        val doc = buildTestTimberDoc()
        val result = CutListExtractor.extract(doc)
        val snapshot = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 41, 59))
        }

        // Create 38 rows to verify pagination across multiple A4 pages
        val manyEntries = (1..38).map { i ->
            val template = result.entries[i % result.entries.size]
            CutListEntry(
                groupId = "cut_$i",
                partNumber = i,
                partName = "Structural Member $i",
                lengthMeters = template.lengthMeters,
                widthMeters = template.widthMeters,
                heightMeters = template.heightMeters,
                quantity = (i % 4) + 1,
                memberNodeIds = setOf("node_$i"),
                instances = template.instances,
                combinedWorldBounds = template.combinedWorldBounds,
                totalSurfaceAreaM2 = template.totalSurfaceAreaM2,
                primaryColor = template.primaryColor
            )
        }

        val bytes = CutListPdfExporter.renderPdfBytes(
            projectTitle = doc.title,
            entries = manyEntries,
            totalPieces = manyEntries.sumOf { it.quantity },
            modelBounds = doc.bounds,
            unitSystem = UnitSystem.MILLIMETERS,
            precisionDecimals = 0,
            explodeFraction = 0.65f,
            viewportSnapshot = snapshot
        )
        snapshot.recycle()

        assertTrue(bytes.size > 2000)
        val pdfText = String(bytes, Charsets.ISO_8859_1)
        assertTrue(pdfText.startsWith("%PDF-"))
        assertTrue(pdfText.contains("/Type /Page"))
        assertTrue(pdfText.contains("/Count 2"))
        assertTrue(pdfText.contains("/Subtype /Image"))
        assertTrue(pdfText.contains("Timber Frame Pergola"))
        assertTrue(pdfText.contains("Structural Member 38"))
        assertTrue(pdfText.trimEnd().endsWith("%%EOF"))
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `cad viewer opens cut list split panel, selects row, exports pdf, and exposes share action`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val doc = buildTestTimberDoc()
        val extracted = CutListExtractor.extract(doc)

        var cutListOpened = false
        var selectedGroup: String? = null
        var explodeFraction = 0f
        var exportTriggered = false
        var cutListUiState by mutableStateOf(
            CutListUiState(
                entries = extracted.entries,
                totalPieces = extracted.totalPieceCount
            )
        )

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                CadViewerScreen(
                    document = doc,
                    viewerState = ViewerUiState(),
                    preferences = UserPreferences(unitSystem = UnitSystem.MILLIMETERS, precisionDecimals = 0),
                    lengths = emptyList(),
                    angles = emptyList(),
                    countedAngles = emptyList(),
                    angleBuckets = emptyList(),
                    onBack = {},
                    onOpenArMode = {},
                    onSetTool = {},
                    onSetAngleInputMode = {},
                    onSetDisplayStyle = {},
                    onToggleTheme = {},
                    onToggleOrthographic = {},
                    onApplyStandardView = { _, _ -> },
                    onApplySavedView = { _, _ -> },
                    onTapViewport = { _, _, _ -> },
                    onSnapHover = { _, _, _ -> },
                    onUndoPick = {},
                    onClearPicks = {},
                    onClearSelection = {},
                    onToggleLayer = {},
                    onToggleNode = {},
                    onSelectNode = {},
                    onShowAllHierarchy = {},
                    onChangeUnits = {},
                    onChangeDecimals = {},
                    onChangeTolerance = {},
                    onFocusPoint = { _, _ -> },
                    onRenameLength = { _, _ -> },
                    onDeleteLength = {},
                    onRenameAngle = { _, _ -> },
                    onDeleteAngle = {},
                    onMarkAngleIntoCounter = {},
                    onAutoCountSelection = {},
                    onUpdateCountedNote = { _, _ -> },
                    onDeleteCountedAngle = {},
                    onClearCountedAngles = {},
                    onExportCsv = {},
                    onDismissBanner = {},
                    cutListState = cutListUiState,
                    onOpenCutList = { cutListOpened = true },
                    onSelectCutListRow = { gid, _ ->
                        selectedGroup = gid
                        cutListUiState = cutListUiState.copy(selectedGroupId = gid)
                    },
                    onSetExplodeFraction = { frac ->
                        explodeFraction = frac
                        cutListUiState = cutListUiState.copy(explodeFraction = frac)
                    },
                    onExportCutListPdf = {
                        exportTriggered = true
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            java.io.File(context.cacheDir, "exports/sample.pdf").apply {
                                parentFile?.mkdirs()
                                writeText("%PDF-1.4\n")
                            }
                        )
                        cutListUiState = cutListUiState.copy(
                            lastExportedPdf = CutListPdfExportResult(
                                fileName = "Timber_Frame_CutList.pdf",
                                savedLocationLabel = "Downloads/Timber_Frame_CutList.pdf",
                                downloadsUri = uri,
                                shareUri = uri
                            )
                        )
                    }
                )
            }
        }
        composeRule.waitForIdle()

        // Tap Cut List dock tool
        composeRule.onNodeWithTag("tool_cutlist").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(cutListOpened)
        composeRule.onNodeWithTag("cutlist_panel").assertIsDisplayed()
        composeRule.onNodeWithText("1200 × 140 × 40 mm").assertIsDisplayed()

        // Tap first row to highlight and trigger camera focus callback
        val firstRowId = extracted.entries.first().groupId
        composeRule.onNodeWithTag("cutlist_row_$firstRowId").performClick()
        composeRule.waitForIdle()
        assertEquals(firstRowId, selectedGroup)
        composeRule.onNodeWithTag("cutlist_row_$firstRowId").assertIsSelected()

        // Tap Export PDF and verify confirmation + Share CTA
        composeRule.onNodeWithTag("cutlist_export_pdf_button").performClick()
        composeRule.waitForIdle()
        assertTrue(exportTriggered)
        composeRule.onNodeWithTag("cutlist_pdf_ready_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("cutlist_share_pdf_button").assertIsDisplayed()

        // Close split panel via Close button
        composeRule.onNodeWithTag("cutlist_close_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cutlist_panel").assertDoesNotExist()
    }

    @Test
    fun `cut list extractor only extracts components, excludes groups, and decomposes Banca & Pergola Yanque`() {
        // 1. Verify non-component Group nodes are excluded when mixed with Component nodes
        val baseDoc = buildTestTimberDoc()
        val groupFaces = makeBoxFaces("n_group_wrapper", 3.0, 2.0, 1.0, Mat4.IDENTITY)
        val mixedDoc = baseDoc.copy(
            nodes = baseDoc.nodes + CadNode(
                id = "n_group_wrapper",
                name = "Group 5",
                layer = "Framing",
                isComponent = false,
                faceCount = groupFaces.size
            ),
            faces = baseDoc.faces + groupFaces
        )
        val mixedResult = CutListExtractor.extract(mixedDoc)
        assertEquals(3, mixedResult.totalPieceCount)
        assertTrue(mixedResult.entries.none { it.partName.startsWith("Group") })
        assertNull(mixedResult.nodeToGroupId["n_group_wrapper"])

        // 2. Verify real Banca & Pergola Yanque sample extracts 33 individual 3D solid components (not whole bench/roof groups)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bancaBytes = context.assets.open("samples/banca-pergola-yanque.igz").use { it.readBytes() }
        val parseRes = com.example.data.parser.IgzParser().parse(
            bancaBytes,
            "asset:banca",
            "Banca & Pérgola Yanque",
            null
        ) as com.example.data.parser.CadParseResult.Success
        val bancaCutList = CutListExtractor.extract(parseRes.document)

        assertEquals(33, bancaCutList.totalPieceCount)
        assertEquals(8, bancaCutList.entries.size)
        assertTrue(bancaCutList.entries.none { it.partName.startsWith("Group", ignoreCase = true) })

        // Top 2 rows are the 10 pergola roof slats (2.40m) and 7 bench slats (1.80m)
        val roofSlats = bancaCutList.entries.first { it.quantity == 10 }
        assertEquals(2.40, roofSlats.lengthMeters, 0.02)
        assertEquals(0.10, roofSlats.widthMeters, 0.02)
        assertEquals(0.04, roofSlats.heightMeters, 0.02)

        val benchSlats = bancaCutList.entries.first { it.quantity == 7 }
        assertEquals(1.80, benchSlats.lengthMeters, 0.02)
        assertEquals(0.10, benchSlats.widthMeters, 0.02)
        assertEquals(0.04, benchSlats.heightMeters, 0.02)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h740dp-port")
    fun `top and bottom toolbars display scroll indicator arrows when screen width is insufficient`() {
        val doc = buildTestTimberDoc()
        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                CadViewerScreen(
                    document = doc,
                    viewerState = ViewerUiState(),
                    preferences = UserPreferences(),
                    lengths = emptyList(),
                    angles = emptyList(),
                    countedAngles = emptyList(),
                    angleBuckets = emptyList(),
                    onBack = {},
                    onOpenArMode = {},
                    onSetTool = {},
                    onSetAngleInputMode = {},
                    onSetDisplayStyle = {},
                    onToggleTheme = {},
                    onToggleOrthographic = {},
                    onApplyStandardView = { _, _ -> },
                    onApplySavedView = { _, _ -> },
                    onTapViewport = { _, _, _ -> },
                    onSnapHover = { _, _, _ -> },
                    onUndoPick = {},
                    onClearPicks = {},
                    onClearSelection = {},
                    onToggleLayer = {},
                    onToggleNode = {},
                    onSelectNode = {},
                    onShowAllHierarchy = {},
                    onChangeUnits = {},
                    onChangeDecimals = {},
                    onChangeTolerance = {},
                    onFocusPoint = { _, _ -> },
                    onRenameLength = { _, _ -> },
                    onDeleteLength = {},
                    onRenameAngle = { _, _ -> },
                    onDeleteAngle = {},
                    onMarkAngleIntoCounter = {},
                    onAutoCountSelection = {},
                    onUpdateCountedNote = { _, _ -> },
                    onDeleteCountedAngle = {},
                    onClearCountedAngles = {},
                    onExportCsv = {},
                    onDismissBanner = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Initially at scroll position 0, both top and bottom toolbars show right overflow arrows
        composeRule.onNodeWithTag("top_toolbar_scroll_right").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_toolbar_scroll_right").assertIsDisplayed()

        // Clicking right scroll arrows scrolls the toolbars and reveals the left scroll arrows
        composeRule.onNodeWithTag("top_toolbar_scroll_right").performClick()
        composeRule.onNodeWithTag("bottom_toolbar_scroll_right").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("top_toolbar_scroll_left").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_toolbar_scroll_left").assertIsDisplayed()
    }
}

