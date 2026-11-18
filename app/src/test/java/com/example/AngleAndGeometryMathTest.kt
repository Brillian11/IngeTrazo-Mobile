package com.example

import com.example.data.export.CsvExporter
import com.example.data.local.AngleMeasurementEntity
import com.example.data.local.CountedAngleEntity
import com.example.data.local.LengthMeasurementEntity
import com.example.domain.math.AngleMath
import com.example.domain.math.EarcutTriangulator
import com.example.domain.math.Mat4
import com.example.domain.math.Ray3D
import com.example.domain.math.SnapEngine
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.math.angleBetweenVectorsDeg
import com.example.domain.model.CadColor
import com.example.domain.model.CadDocument
import com.example.domain.model.CadEdge
import com.example.domain.model.CadFace
import com.example.domain.model.CadLayer
import com.example.domain.model.CadNode
import com.example.domain.model.ModelFormat
import com.example.domain.model.ModelStats
import com.example.domain.model.SnapType
import com.example.domain.model.UnitSystem
import com.example.domain.math.BoundingBox3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleAndGeometryMathTest {

    @Test
    fun `three point angle computes exact 90 and 45 and 120 degrees`() {
        val right = AngleMath.computeThreePointAngle(
            p1 = Vec3(2.0, 0.0, 0.0),
            vertex = Vec3.ZERO,
            p2 = Vec3(0.0, 3.0, 0.0)
        )
        assertEquals(90.0, right.degrees, 1e-6)
        assertTrue(right.arcPoints.size >= 10)

        val fortyFive = AngleMath.computeThreePointAngle(
            p1 = Vec3(1.0, 0.0, 0.0),
            vertex = Vec3.ZERO,
            p2 = Vec3(1.0, 1.0, 0.0)
        )
        assertEquals(45.0, fortyFive.degrees, 1e-6)

        val oneTwenty = AngleMath.computeThreePointAngle(
            p1 = Vec3(1.0, 0.0, 0.0),
            vertex = Vec3.ZERO,
            p2 = Vec3(-0.5, kotlin.math.sqrt(3.0) / 2.0, 0.0)
        )
        assertEquals(120.0, oneTwenty.degrees, 1e-6)
    }

    @Test
    fun `two edge angle resolves shared vertex automatically`() {
        val e1 = CadEdge("e1", "n1", "Layer 0", Vec3(0.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0))
        val e2 = CadEdge("e2", "n1", "Layer 0", Vec3(2.0, 0.0, 0.0), Vec3(2.0, 2.0, 0.0))
        val comp = AngleMath.computeTwoEdgeAngle(e1, e2)
        assertEquals(90.0, comp.degrees, 1e-6)
        assertEquals(0.0, comp.vertex.distanceTo(Vec3(2.0, 0.0, 0.0)), 1e-6)
    }

    @Test
    fun `dihedral angle between orthogonal faces is 90 degrees`() {
        val floor = CadFace(
            id = "f1",
            nodeId = "n1",
            layer = "Layer 0",
            outerLoop = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(0.0, 1.0, 0.0)),
            normal = Vec3(0.0, 0.0, 1.0),
            triangleVertices = emptyList(),
            triangleUvs = emptyList(),
            color = CadColor.DEFAULT_FRONT
        )
        val wall = CadFace(
            id = "f2",
            nodeId = "n1",
            layer = "Layer 0",
            outerLoop = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(1.0, 0.0, 1.0), Vec3(0.0, 0.0, 1.0)),
            normal = Vec3(0.0, -1.0, 0.0),
            triangleVertices = emptyList(),
            triangleUvs = emptyList(),
            color = CadColor.DEFAULT_FRONT
        )
        val dihedral = AngleMath.computeTwoFaceDihedralAngle(floor, wall)
        assertEquals(90.0, dihedral.degrees, 1e-6)
    }

    @Test
    fun `detectAnglesInSelection and groupAnglesByTolerance count rectangle corners`() {
        val rectLoop = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(4.0, 0.0, 0.0),
            Vec3(4.0, 2.0, 0.0),
            Vec3(0.0, 2.0, 0.0)
        )
        val rectFace = CadFace(
            id = "f_rect",
            nodeId = "n1",
            layer = "Wall",
            outerLoop = rectLoop,
            normal = Vec3.UNIT_Z,
            triangleVertices = EarcutTriangulator.triangulate(rectLoop),
            triangleUvs = emptyList(),
            color = CadColor.DEFAULT_FRONT
        )
        val detected = AngleMath.detectAnglesInSelection(listOf(rectFace), emptyList())
        assertEquals(4, detected.size)
        detected.forEach { assertEquals(90.0, it.angleDegrees, 1e-4) }

        val buckets = AngleMath.groupAnglesByTolerance(
            detected.map { it.angleDegrees } + listOf(45.1, 44.9),
            toleranceDeg = 0.5
        )
        assertEquals(2, buckets.size)
        assertEquals(4, buckets[0].count)
        assertEquals(90.0, buckets[0].nominalDegrees, 0.01)
        assertEquals(2, buckets[1].count)
        assertEquals(45.0, buckets[1].nominalDegrees, 0.01)
    }

    @Test
    fun `earcut triangulator handles concave L-shape and polygon with inner hole`() {
        val lShape = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(2.0, 0.0, 0.0),
            Vec3(2.0, 1.0, 0.0),
            Vec3(1.0, 1.0, 0.0),
            Vec3(1.0, 2.0, 0.0),
            Vec3(0.0, 2.0, 0.0)
        )
        val lTris = EarcutTriangulator.triangulate(lShape)
        assertEquals(12, lTris.size) // 4 triangles = 12 vertices

        val outerSquare = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(4.0, 0.0, 0.0),
            Vec3(4.0, 4.0, 0.0),
            Vec3(0.0, 4.0, 0.0)
        )
        val innerHole = listOf(
            Vec3(1.0, 1.0, 0.0),
            Vec3(3.0, 1.0, 0.0),
            Vec3(3.0, 3.0, 0.0),
            Vec3(1.0, 3.0, 0.0)
        )
        val holedTris = EarcutTriangulator.triangulate(outerSquare, listOf(innerHole))
        // 4 outer + 4 inner + 2 bridge = 10-vertex simple polygon => 8 triangles = 24 vertices
        assertEquals(24, holedTris.size)
    }

    @Test
    fun `snap engine prioritizes vertex then midpoint then edge`() {
        val edge = CadEdge("e1", "n1", "Layer 0", Vec3(0.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0))
        val doc = CadDocument(
            documentKey = "test",
            title = "Test",
            format = ModelFormat.IGZ,
            formatVersion = "v1",
            nodes = listOf(CadNode("n1", "Root")),
            faces = emptyList(),
            edges = listOf(edge),
            layers = listOf(CadLayer("Layer 0")),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = BoundingBox3D(Vec3.ZERO, Vec3(10.0, 1.0, 1.0)),
            stats = ModelStats(1, 0, 0, 1, 2, 1, 0, 0)
        )
        val project: (Vec3) -> Vec2? = { v -> Vec2(v.x * 100.0, 500.0 - v.y * 100.0) }
        val noRay: (Float, Float) -> Ray3D? = { _, _ -> null }

        // Cursor near vertex (0,0,0) -> (0, 500)
        val vHit = SnapEngine.findBestSnap(doc, emptySet(), emptySet(), 5f, 502f, 1200, 1000, project, noRay)
        assertNotNull(vHit)
        assertEquals(SnapType.VERTEX, vHit!!.type)
        assertEquals(0.0, vHit.point.distanceTo(Vec3.ZERO), 1e-6)

        // Cursor near midpoint (5,0,0) -> (500, 500)
        val mHit = SnapEngine.findBestSnap(doc, emptySet(), emptySet(), 503f, 498f, 1200, 1000, project, noRay)
        assertNotNull(mHit)
        assertEquals(SnapType.MIDPOINT, mHit!!.type)
        assertEquals(0.0, mHit.point.distanceTo(Vec3(5.0, 0.0, 0.0)), 1e-6)

        // Cursor near edge x=2 -> (200, 500)
        val eHit = SnapEngine.findBestSnap(doc, emptySet(), emptySet(), 200f, 505f, 1200, 1000, project, noRay)
        assertNotNull(eHit)
        assertEquals(SnapType.EDGE, eHit!!.type)
        assertEquals(2.0, eHit.point.x, 0.05)
    }

    @Test
    fun `unit conversions and csv exporter produce valid structured output`() {
        assertEquals("250.0 cm", UnitSystem.CENTIMETERS.formatMeters(2.5, 1))
        assertEquals("1000 mm", UnitSystem.MILLIMETERS.formatMeters(1.0, 0))
        assertEquals(1.0, UnitSystem.INCHES.fromMeters(0.0254), 1e-6)

        val mat = Mat4.translation(1.0, 2.0, 3.0)
        val moved = mat.transformPoint(Vec3(1.0, 1.0, 1.0))
        assertEquals(Vec3(2.0, 3.0, 4.0), moved)
        assertEquals(90.0, angleBetweenVectorsDeg(Vec3.UNIT_X, Vec3.UNIT_Y), 1e-6)

        val csv = CsvExporter.buildMeasurementAndCountCsv(
            documentTitle = "Pergola, Yanque",
            unitSystem = UnitSystem.CENTIMETERS,
            decimals = 1,
            angleToleranceDeg = 0.5,
            lengths = listOf(
                LengthMeasurementEntity(1L, "doc1", "Beam Span", 0.0, 0.0, 0.0, 2.5, 0.0, 0.0, 2.5)
            ),
            angles = listOf(
                AngleMeasurementEntity(2L, "doc1", "Rafter Cut", 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 90.0, "3-Point")
            ),
            countedAngles = listOf(
                CountedAngleEntity(3L, "doc1", 90.0, "Corner 1", "Auto-Selection", "f1", 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, "Timber"),
                CountedAngleEntity(4L, "doc1", 90.1, "Corner 2", "Auto-Selection", "f2", 2.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 1.0, 0.0, "")
            )
        )
        assertTrue(csv.contains("\"Pergola, Yanque\""))
        assertTrue(csv.contains("Total Counted Angles,2"))
        assertTrue(csv.contains("Distinct Angle Groups,1"))
        assertTrue(csv.contains("Beam Span,250.0,2.5000"))
        assertTrue(csv.contains("Rafter Cut,90.000,3-Point"))
    }

    @Test
    fun `ar scale math clamps at 1_1 and formats projected physical sizes in user units`() {
        // Never allow larger than true 1:1, clamp lower bound at 1:1000 (0.001f)
        assertEquals(1.0f, com.example.ui.ar.ArScaleMath.clampScale(2.5f), 1e-6f)
        assertEquals(com.example.ui.ar.ArScaleMath.MIN_SCALE, com.example.ui.ar.ArScaleMath.clampScale(0.0001f), 1e-6f)
        assertEquals("1:1", com.example.ui.ar.ArScaleMath.formatScaleRatio(1.0f))
        assertEquals("100%", com.example.ui.ar.ArScaleMath.formatScalePercent(1.0f))
        assertEquals("1:10", com.example.ui.ar.ArScaleMath.formatScaleRatio(0.1f))
        assertEquals("1:200", com.example.ui.ar.ArScaleMath.formatScaleRatio(0.005f))
        assertEquals("1:1000", com.example.ui.ar.ArScaleMath.formatScaleRatio(0.001f))

        // Slider logarithmic mapping roundtrip
        val midFrac = com.example.ui.ar.ArScaleMath.scaleToSliderFraction(0.1f)
        assertEquals(0.1f, com.example.ui.ar.ArScaleMath.sliderFractionToScale(midFrac), 0.02f)
        assertEquals(1.0f, com.example.ui.ar.ArScaleMath.sliderFractionToScale(1.0f), 1e-6f)

        val bounds = BoundingBox3D(Vec3.ZERO, Vec3(2.0, 1.0, 0.5))
        assertEquals(
            "2.00 × 1.00 × 0.50 m",
            com.example.ui.ar.ArScaleMath.formatProjectedDimensions(bounds, 1.0f, UnitSystem.METERS, 2)
        )
        assertEquals(
            "20.0 × 10.0 × 5.0 cm",
            com.example.ui.ar.ArScaleMath.formatProjectedDimensions(bounds, 0.1f, UnitSystem.CENTIMETERS, 1)
        )
    }

    @Test
    fun `ar yuv420 buffer converter produces square upright ARGB center crop`() {
        val w = 32
        val h = 32
        val y = ByteArray(w * h) { 200.toByte() }
        val u = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val v = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val cropped = com.example.ui.ar.ArImageCropper.convertYuvBuffersToUprightCenterCropArgb(
            width = w,
            height = h,
            yBytes = y,
            yRowStride = w,
            yPixelStride = 1,
            uBytes = u,
            uRowStride = w / 2,
            uPixelStride = 1,
            vBytes = v,
            vRowStride = w / 2,
            vPixelStride = 1,
            rotateCwDegrees = 90,
            cropFraction = 0.5f,
            outputSizePx = 16
        )
        assertEquals(16, cropped.width)
        assertEquals(16, cropped.height)
        assertEquals(256, cropped.argb.size)
        val samplePixel = cropped.argb[0]
        val r = (samplePixel shr 16) and 0xFF
        assertTrue(r in 190..210)
    }

    @Test
    fun `select orbit and pan tools are strictly mutually exclusive`() {
        val policy = com.example.ui.viewer.gl.CadToolGesturePolicy

        // SELECT: tap only, no orbit, no pan
        assertTrue(policy.allowsTap(com.example.domain.model.CadTool.SELECT))
        assertTrue(!policy.allowsOrbit(com.example.domain.model.CadTool.SELECT))
        assertTrue(!policy.allowsPan(com.example.domain.model.CadTool.SELECT))

        // ORBIT: orbit only, no tap selection, no pan
        assertTrue(!policy.allowsTap(com.example.domain.model.CadTool.ORBIT))
        assertTrue(policy.allowsOrbit(com.example.domain.model.CadTool.ORBIT))
        assertTrue(!policy.allowsPan(com.example.domain.model.CadTool.ORBIT))

        // PAN: pan only, no tap selection, no orbit
        assertTrue(!policy.allowsTap(com.example.domain.model.CadTool.PAN))
        assertTrue(!policy.allowsOrbit(com.example.domain.model.CadTool.PAN))
        assertTrue(policy.allowsPan(com.example.domain.model.CadTool.PAN))
    }
}
