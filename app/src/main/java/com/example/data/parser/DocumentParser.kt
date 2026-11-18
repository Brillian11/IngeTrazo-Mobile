package com.example.data.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.math.buildOrthonormalBasis
import com.example.domain.model.CadColor
import com.example.domain.model.CadDocument
import com.example.domain.model.CadEdge
import com.example.domain.model.CadFace
import com.example.domain.model.ModelFormat
import com.example.domain.model.ModelStats
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

sealed interface CadParseResult {
    data class Success(val document: CadDocument) : CadParseResult
    data class Failure(val message: String, val details: String? = null) : CadParseResult
}

interface DocumentParser {
    val supportedFormats: Set<ModelFormat>
    fun parse(
        bytes: ByteArray,
        documentKey: String,
        title: String,
        formatHint: ModelFormat? = null
    ): CadParseResult
}

object CadGeometryHelper {

    fun computePlanarUvs(
        points: List<Vec3>,
        normal: Vec3,
        scaleW: Double = 1.0,
        scaleH: Double = 1.0,
        rotDeg: Double = 0.0,
        uvwAffine: DoubleArray? = null
    ): List<Vec2> {
        if (points.isEmpty()) return emptyList()
        if (uvwAffine != null && uvwAffine.size >= 8) {
            return points.map { p ->
                val u = uvwAffine[0] * p.x + uvwAffine[1] * p.y + uvwAffine[2] * p.z + uvwAffine[3]
                val v = uvwAffine[4] * p.x + uvwAffine[5] * p.y + uvwAffine[6] * p.z + uvwAffine[7]
                Vec2(u, v)
            }
        }
        val n = normal.normalized()
        val xx = -n.y
        val xy = n.x
        val lx = kotlin.math.sqrt(xx * xx + xy * xy)
        var uAxis: Vec3
        var vAxis: Vec3
        if (lx < 1e-3) {
            if (n.z >= 0.0) {
                uAxis = Vec3.UNIT_X
                vAxis = Vec3.UNIT_Y
            } else {
                uAxis = Vec3(-1.0, 0.0, 0.0)
                vAxis = Vec3.UNIT_Y
            }
        } else {
            val ux = xx / lx
            val uy = xy / lx
            uAxis = Vec3(ux, uy, 0.0)
            vAxis = Vec3(-n.z * uy, n.z * ux, n.x * uy - n.y * ux)
        }
        if (rotDeg != 0.0) {
            val rad = Math.toRadians(rotDeg)
            val cosA = cos(rad)
            val sinA = sin(rad)
            val rotatedU = uAxis * cosA + vAxis * sinA
            val rotatedV = vAxis * cosA - uAxis * sinA
            uAxis = rotatedU
            vAxis = rotatedV
        }
        val sw = if (kotlin.math.abs(scaleW) > 1e-6) scaleW else 1.0
        val sh = if (kotlin.math.abs(scaleH) > 1e-6) scaleH else 1.0
        return points.map { p ->
            Vec2(p.dot(uAxis) / sw, p.dot(vAxis) / sh)
        }
    }

    fun computeStatsAndBounds(
        nodeCount: Int,
        faces: List<CadFace>,
        edges: List<CadEdge>,
        layerCount: Int,
        savedViewCount: Int,
        textureCount: Int
    ): Pair<BoundingBox3D, ModelStats> {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY

        val uniqueVerts = HashSet<Long>((edges.size * 2).coerceAtLeast(64))
        var triCount = 0

        fun hashPoint(p: Vec3): Long {
            val qx = (p.x * 10000.0).roundToLong()
            val qy = (p.y * 10000.0).roundToLong()
            val qz = (p.z * 10000.0).roundToLong()
            var h = qx * 73856093L
            h = h xor (qy * 19349663L)
            h = h xor (qz * 83492791L)
            return h
        }

        fun expandPrimitive(p: Vec3) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            minZ = min(minZ, p.z)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
            maxZ = max(maxZ, p.z)
        }

        for (face in faces) {
            triCount += face.triangleVertices.size / 3
            for (p in face.outerLoop) {
                expandPrimitive(p)
                uniqueVerts.add(hashPoint(p))
            }
            for (v in face.triangleVertices) {
                expandPrimitive(v)
            }
        }
        for (edge in edges) {
            expandPrimitive(edge.a)
            expandPrimitive(edge.b)
            uniqueVerts.add(hashPoint(edge.a))
            uniqueVerts.add(hashPoint(edge.b))
        }

        val bounds = if (minX <= maxX && minY <= maxY && minZ <= maxZ) {
            BoundingBox3D(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
        } else {
            BoundingBox3D(Vec3(-1.0, -1.0, 0.0), Vec3(1.0, 1.0, 1.0))
        }

        val stats = ModelStats(
            nodeCount = nodeCount,
            faceCount = faces.size,
            triangleCount = triCount,
            edgeCount = edges.size,
            uniqueVertexCount = uniqueVerts.size,
            layerCount = layerCount,
            savedViewCount = savedViewCount,
            textureCount = textureCount
        )
        return bounds to stats
    }

    fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int = 1024): Bitmap? {
        if (bytes.isEmpty()) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val w = boundsOpts.outWidth
        val h = boundsOpts.outHeight
        if (w <= 0 || h <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        var sample = 1
        while ((w / sample) > maxDim || (h / sample) > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun parseColorArray(arr: org.json.JSONArray?, defaultColor: CadColor): CadColor {
        if (arr == null || arr.length() < 3) return defaultColor
        val c0 = arr.optDouble(0, 1.0)
        val c1 = arr.optDouble(1, 1.0)
        val c2 = arr.optDouble(2, 1.0)
        val c3 = if (arr.length() >= 4) arr.optDouble(3, 1.0) else 1.0
        val scale = if (c0 > 1.0 || c1 > 1.0 || c2 > 1.0) 1.0 / 255.0 else 1.0
        return CadColor(
            (c0 * scale).toFloat().coerceIn(0f, 1f),
            (c1 * scale).toFloat().coerceIn(0f, 1f),
            (c2 * scale).toFloat().coerceIn(0f, 1f),
            (if (c3 > 1.0) c3 / 255.0 else c3).toFloat().coerceIn(0f, 1f)
        )
    }
}

class DocumentParserRegistry(
    private val parsers: List<DocumentParser>
) {
    fun parse(
        bytes: ByteArray,
        documentKey: String,
        fileName: String,
        formatHint: ModelFormat? = null
    ): CadParseResult {
        if (bytes.isEmpty()) {
            return CadParseResult.Failure("Empty file", "The selected file contains 0 bytes.")
        }
        val detected = formatHint ?: detectFormat(bytes, fileName)
        if (detected == null) {
            return CadParseResult.Failure(
                "Unsupported file format",
                "Supported formats: IngeTrazo (.igz), SketchUp (.skp), Wavefront (.obj), and glTF 2.0 (.gltf/.glb)."
            )
        }
        val parser = parsers.firstOrNull { detected in it.supportedFormats }
            ?: return CadParseResult.Failure("No parser registered for ${detected.displayName}")
        return parser.parse(bytes, documentKey, fileName, detected)
    }

    companion object {
        fun detectFormat(bytes: ByteArray, fileName: String): ModelFormat? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "igz" -> ModelFormat.IGZ
                "skp" -> ModelFormat.SKP
                "obj" -> ModelFormat.OBJ
                "gltf" -> ModelFormat.GLTF
                "glb" -> ModelFormat.GLB
                else -> sniffByHeader(bytes)
            }
        }

        private fun sniffByHeader(bytes: ByteArray): ModelFormat? {
            if (bytes.size >= 4) {
                // glTF binary magic "glTF"
                if (bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() &&
                    bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()
                ) {
                    return ModelFormat.GLB
                }
                // SketchUp VFF / legacy UTF-16LE marker
                if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
                    bytes[2] == 0xFF.toByte() && bytes[3] == 0x0E.toByte()
                ) {
                    return ModelFormat.SKP
                }
                // ZIP container (IGZ v2/v3 or unwrapped SKP VFF payload)
                if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                    bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
                ) {
                    sniffZipContainer(bytes)?.let { return it }
                }
                val headStr = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
                if (headStr.contains("\"igz_format\"")) return ModelFormat.IGZ
                if (headStr.contains("\"asset\"") && headStr.contains("\"version\"")) return ModelFormat.GLTF
                if (headStr.lineSequence().any { line ->
                        val t = line.trim()
                        t.startsWith("v ") || t.startsWith("mtllib ") || t.startsWith("vn ")
                    }
                ) {
                    return ModelFormat.OBJ
                }
            }
            return null
        }

        private fun sniffZipContainer(bytes: ByteArray): ModelFormat? {
            return try {
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            "document.json" -> return ModelFormat.IGZ
                            "model.dat" -> return ModelFormat.SKP
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
