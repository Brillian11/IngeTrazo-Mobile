package com.example.domain.model

import android.graphics.Bitmap
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Mat4
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import java.util.Locale

enum class ModelFormat(val extension: String, val displayName: String) {
    IGZ("igz", "IngeTrazo (.igz)"),
    SKP("skp", "SketchUp (.skp)"),
    OBJ("obj", "Wavefront (.obj)"),
    GLTF("gltf", "glTF 2.0 (.gltf)"),
    GLB("glb", "Binary glTF (.glb)")
}

enum class UnitSystem(val symbol: String, val label: String, val metersPerUnit: Double) {
    METERS("m", "Meters (m)", 1.0),
    CENTIMETERS("cm", "Centimeters (cm)", 0.01),
    MILLIMETERS("mm", "Millimeters (mm)", 0.001),
    FEET("ft", "Feet (ft)", 0.3048),
    INCHES("in", "Inches (in)", 0.0254);

    fun fromMeters(meters: Double): Double = meters / metersPerUnit

    fun formatMeters(meters: Double, decimals: Int = 2): String {
        val value = fromMeters(meters)
        return String.format(Locale.US, "%.${decimals.coerceIn(0, 4)}f %s", value, symbol)
    }

    companion object {
        fun fromCode(code: String?): UnitSystem = when (code?.lowercase(Locale.US)?.trim()) {
            "cm", "centimeter", "centimeters" -> CENTIMETERS
            "mm", "millimeter", "millimeters" -> MILLIMETERS
            "ft", "foot", "feet" -> FEET
            "in", "inch", "inches" -> INCHES
            else -> METERS
        }
    }
}

enum class DisplayStyle(val label: String) {
    SHADED_EDGES("Shaded + Edges"),
    TEXTURED("Textured"),
    MONOCHROME("Monochrome"),
    XRAY("X-Ray"),
    WIREFRAME("Wireframe")
}

enum class StandardView(val label: String) {
    ISO("Iso"),
    TOP("Top"),
    BOTTOM("Bottom"),
    FRONT("Front"),
    BACK("Back"),
    LEFT("Left"),
    RIGHT("Right")
}

enum class CadTool(val label: String) {
    SELECT("Select"),
    ORBIT("Orbit"),
    PAN("Pan"),
    MEASURE_LENGTH("Tape"),
    MEASURE_ANGLE("Angle")
}

enum class SnapType(val label: String) {
    VERTEX("Endpoint"),
    MIDPOINT("Midpoint"),
    EDGE("On Edge"),
    FACE("On Face")
}

data class CadColor(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    companion object {
        val DEFAULT_FRONT = CadColor(0.95f, 0.96f, 0.98f, 1f)
        val DEFAULT_BACK = CadColor(0.68f, 0.74f, 0.82f, 1f)
        val DEFAULT_EDGE = CadColor(0.13f, 0.17f, 0.23f, 1f)
    }
}

data class CadMaterial(
    val id: String,
    val name: String,
    val color: CadColor,
    val opacity: Float = 1f,
    val textureKey: String? = null,
    val tileWidthMeters: Double = 1.0,
    val tileHeightMeters: Double = 1.0
)

data class CadLayer(
    val name: String,
    val folder: String? = null,
    val isVisible: Boolean = true,
    val color: CadColor? = null
)

data class CadFace(
    val id: String,
    val nodeId: String,
    val layer: String,
    val outerLoop: List<Vec3>,
    val holes: List<List<Vec3>> = emptyList(),
    val normal: Vec3,
    val triangleVertices: List<Vec3>,
    val triangleUvs: List<Vec2>,
    val color: CadColor,
    val backColor: CadColor = CadColor.DEFAULT_BACK,
    val materialKey: String? = null,
    val textureKey: String? = null,
    val hidden: Boolean = false
) {
    val centroid: Vec3 by lazy {
        if (outerLoop.isEmpty()) Vec3.ZERO
        else outerLoop.fold(Vec3.ZERO) { acc, p -> acc + p } / outerLoop.size.toDouble()
    }
}

data class CadEdge(
    val id: String,
    val nodeId: String,
    val layer: String,
    val a: Vec3,
    val b: Vec3,
    val soft: Boolean = false,
    val hidden: Boolean = false,
    val isProfile: Boolean = false
) {
    val midpoint: Vec3 get() = (a + b) * 0.5
    val lengthMeters: Double get() = a.distanceTo(b)
}

data class CadNode(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val layer: String = "Layer 0",
    val isComponent: Boolean = false,
    val isVisible: Boolean = true,
    val localTransform: Mat4 = Mat4.IDENTITY,
    val faceCount: Int = 0,
    val edgeCount: Int = 0,
    val childrenIds: List<String> = emptyList()
)

data class CadSavedView(
    val name: String,
    val target: Vec3,
    val distance: Double,
    val yawRad: Double,
    val pitchRad: Double,
    val parallel: Boolean = false,
    val hiddenLayers: Set<String> = emptySet(),
    val styleName: String? = null
)

data class CadDimensionAnnotation(
    val a: Vec3,
    val b: Vec3,
    val offset: Vec3,
    val layer: String
)

data class CadTextLabel(
    val anchor: Vec3,
    val offset: Vec3,
    val text: String,
    val layer: String
)

data class CadSectionPlane(
    val name: String,
    val symbol: String,
    val point: Vec3,
    val normal: Vec3,
    val layer: String
)

data class ModelStats(
    val nodeCount: Int,
    val faceCount: Int,
    val triangleCount: Int,
    val edgeCount: Int,
    val uniqueVertexCount: Int,
    val layerCount: Int,
    val savedViewCount: Int,
    val textureCount: Int
)

data class CadDocument(
    val documentKey: String,
    val title: String,
    val format: ModelFormat,
    val formatVersion: String,
    val appVersion: String? = null,
    val sourceUnits: UnitSystem = UnitSystem.METERS,
    val sourceDecimals: Int = 2,
    val nodes: List<CadNode>,
    val faces: List<CadFace>,
    val edges: List<CadEdge>,
    val layers: List<CadLayer>,
    val materials: Map<String, CadMaterial>,
    val textures: Map<String, Bitmap>,
    val savedViews: List<CadSavedView>,
    val dimensions: List<CadDimensionAnnotation> = emptyList(),
    val textLabels: List<CadTextLabel> = emptyList(),
    val sectionPlanes: List<CadSectionPlane> = emptyList(),
    val defaultFrontColor: CadColor = CadColor.DEFAULT_FRONT,
    val defaultBackColor: CadColor = CadColor.DEFAULT_BACK,
    val bounds: BoundingBox3D,
    val stats: ModelStats,
    val warnings: List<String> = emptyList()
)
