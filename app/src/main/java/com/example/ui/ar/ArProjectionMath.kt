package com.example.ui.ar

import android.graphics.Bitmap
import android.media.Image
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.model.CadDocument
import com.example.domain.model.UnitSystem
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ArTrackingMode(val label: String, val subtitle: String) {
    OBJECT_SURFACE("Pin to Object / Surface", "Tap any 3D object, wall, or floor to anchor"),
    VISUAL_OBJECT_TRACKER("Object Tracker", "Capture an object target so the model sticks & follows")
}

enum class ArAnchorOrigin(val label: String) {
    AXIS("Axis"),
    BASE("Base"),
    CENTER("Center")
}

enum class ObjectTrackingPhase {
    SCANNING,
    CAPTURING_TARGET,
    TARGET_SEARCHING,
    STICKING_TO_OBJECT,
    PINNED_TO_OBJECT,
    QUALITY_ERROR
}

data class ArScalePreset(val factor: Float, val label: String)

object ArScaleMath {
    const val MIN_SCALE: Float = 0.001f // 1:1000 for huge site/mm models
    const val MAX_SCALE: Float = 1.0f   // True 1:1 real-world size

    val PRESETS: List<ArScalePreset> = listOf(
        ArScalePreset(1.0f, "1:1"),
        ArScalePreset(0.5f, "1:2"),
        ArScalePreset(0.2f, "1:5"),
        ArScalePreset(0.1f, "1:10"),
        ArScalePreset(0.05f, "1:20"),
        ArScalePreset(0.02f, "1:50"),
        ArScalePreset(0.01f, "1:100"),
        ArScalePreset(0.005f, "1:200"),
        ArScalePreset(0.002f, "1:500"),
        ArScalePreset(0.001f, "1:1000")
    )

    val TARGET_WIDTH_PRESETS_METERS: List<Float> = listOf(0.10f, 0.20f, 0.30f, 0.50f, 1.00f)

    fun clampScale(scale: Float): Float {
        if (!scale.isFinite()) return 0.1f
        return scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /**
     * Picks a clean architectural scale preset (<= 1:1) so any model—from a 0.25m part to a
     * 30m building or 1000-unit OBJ—starts at a predictable ~0.5m..1.2m preview size in AR.
     */
    fun recommendedInitialScale(
        bounds: BoundingBox3D,
        targetSpanMeters: Double = 0.85
    ): Float {
        if (!bounds.isValid) return 0.1f
        val sx = abs(bounds.size.x)
        val sy = abs(bounds.size.y)
        val sz = abs(bounds.size.z)
        val maxDim = max(sx, max(sy, sz))
        if (!maxDim.isFinite() || maxDim < 1e-4) return 1.0f
        if (maxDim <= targetSpanMeters * 1.45) return 1.0f

        val ideal = targetSpanMeters / maxDim
        // Pick the closest clean preset on log scale without exceeding ~1.6x targetSpan
        var best = PRESETS.last().factor
        var bestErr = Double.POSITIVE_INFINITY
        for (preset in PRESETS) {
            val projected = maxDim * preset.factor
            if (projected > targetSpanMeters * 1.75 && preset.factor > MIN_SCALE) continue
            val err = abs(ln(preset.factor.toDouble() / ideal))
            if (err < bestErr) {
                bestErr = err
                best = preset.factor
            }
        }
        return clampScale(best)
    }

    fun scaleToDenominator(scale: Float): Float = 1f / clampScale(scale)

    fun denominatorToScale(denominator: Float): Float {
        if (!denominator.isFinite()) return 0.1f
        val d = denominator.coerceIn(1f, 1000f)
        return clampScale(1f / d)
    }

    /**
     * Maps [MIN_SCALE, MAX_SCALE] logarithmically to [0f, 1f] for smooth slider control
     * up to true 1:1 on the right edge.
     */
    fun scaleToSliderFraction(scale: Float): Float {
        val clamped = clampScale(scale)
        val logRange = ln(MAX_SCALE / MIN_SCALE)
        return (ln(clamped / MIN_SCALE) / logRange).coerceIn(0f, 1f)
    }

    fun sliderFractionToScale(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        if (f >= 0.992f) return MAX_SCALE
        if (f <= 0.008f) return MIN_SCALE
        val raw = MIN_SCALE * exp(f * ln(MAX_SCALE / MIN_SCALE))
        // Snap gently toward common architectural denominators when very close
        val denom = 1f / raw
        val nearestInt = denom.roundToInt().toFloat()
        val snapped = if (abs(denom - nearestInt) < 0.08f && nearestInt >= 1f) 1f / nearestInt else raw
        return clampScale(snapped)
    }

    fun formatScaleRatio(scale: Float): String {
        val clamped = clampScale(scale)
        if (clamped >= 0.995f) return "1:1"
        val denom = 1f / clamped
        val rounded = denom.roundToInt()
        return if (abs(denom - rounded) < 0.05f) {
            "1:$rounded"
        } else {
            String.format(Locale.US, "1:%.1f", denom)
        }
    }

    fun formatScalePercent(scale: Float): String {
        val pct = clampScale(scale) * 100f
        return when {
            pct >= 10f || abs(pct - pct.roundToInt()) < 0.05f -> "${pct.roundToInt()}%"
            pct >= 1f -> String.format(Locale.US, "%.1f%%", pct)
            else -> String.format(Locale.US, "%.2f%%", pct)
        }
    }

    fun projectedDimensionsMeters(bounds: BoundingBox3D, scale: Float): Vec3 {
        if (!bounds.isValid) return Vec3.ZERO
        val s = clampScale(scale).toDouble()
        return Vec3(
            abs(bounds.size.x) * s,
            abs(bounds.size.y) * s,
            abs(bounds.size.z) * s
        )
    }

    fun formatProjectedDimensions(
        bounds: BoundingBox3D,
        scale: Float,
        unit: UnitSystem,
        decimals: Int
    ): String {
        val dims = projectedDimensionsMeters(bounds, scale)
        val dec = decimals.coerceIn(0, 4)
        val w = String.format(Locale.US, "%.${dec}f", unit.fromMeters(dims.x))
        val d = String.format(Locale.US, "%.${dec}f", unit.fromMeters(dims.y))
        val h = String.format(Locale.US, "%.${dec}f", unit.fromMeters(dims.z))
        return "$w × $d × $h ${unit.symbol}"
    }
}

/**
 * ARCore world-space pose stabilization math.
 * ARCore's world +Y axis is always aligned to Earth gravity UP.
 */
object ArTransformMath {

    /**
     * When [alwaysLevel] is true, strips surface pitch and roll from a 4x4 column-major
     * ARCore pose matrix while preserving exact world position and horizontal yaw so objects
     * (lamps, arches, columns, fountains) stand plumb even on inclined slopes or walls.
     */
    fun buildPoseMatrix(rawPoseMatrix: FloatArray, alwaysLevel: Boolean): FloatArray {
        require(rawPoseMatrix.size >= 16) { "Pose matrix requires 16 floats" }
        val out = rawPoseMatrix.copyOf(16)
        if (!alwaysLevel) return out

        // Column 0 = local +X, Column 2 = local +Z, Column 3 = translation
        val xx = rawPoseMatrix[0]
        val xz = rawPoseMatrix[2]
        val zx = rawPoseMatrix[8]
        val zz = rawPoseMatrix[10]

        val lenZ = hypot(zx.toDouble(), zz.toDouble()).toFloat()
        val rx: Float
        val rz: Float
        val fx: Float
        val fz: Float

        if (lenZ >= 1e-3f) {
            fx = zx / lenZ
            fz = zz / lenZ
            // right = up(0,1,0) x fwd(fx,0,fz) = (fz, 0, -fx)
            rx = fz
            rz = -fx
        } else {
            val lenX = hypot(xx.toDouble(), xz.toDouble()).toFloat()
            if (lenX >= 1e-3f) {
                rx = xx / lenX
                rz = xz / lenX
                // fwd = right(rx,0,rz) x up(0,1,0) = (-rz, 0, rx)
                fx = -rz
                fz = rx
            } else {
                rx = 1f; rz = 0f
                fx = 0f; fz = 1f
            }
        }

        // Column 0: right
        out[0] = rx; out[1] = 0f; out[2] = rz; out[3] = 0f
        // Column 1: world gravity +Y
        out[4] = 0f; out[5] = 1f; out[6] = 0f; out[7] = 0f
        // Column 2: forward
        out[8] = fx; out[9] = 0f; out[10] = fz; out[11] = 0f
        // Column 3: translation preserved
        out[12] = rawPoseMatrix[12]
        out[13] = rawPoseMatrix[13]
        out[14] = rawPoseMatrix[14]
        out[15] = 1f
        return out
    }

    const val DEFAULT_IPD_METERS: Float = 0.064f
    val IPD_PRESETS_MM: List<Int> = listOf(58, 62, 64, 68, 72)

    /**
     * Shifts a mono ARCore view matrix horizontally in camera/head space for the Left (-1)
     * or Right (+1) eye using the user's inter-pupillary distance ([ipdMeters]).
     */
    fun buildVrEyeViewMatrix(
        monoViewMat: FloatArray,
        eyeSign: Float,
        ipdMeters: Float = DEFAULT_IPD_METERS
    ): FloatArray {
        require(monoViewMat.size >= 16) { "View matrix requires 16 floats" }
        val out = monoViewMat.copyOf(16)
        val halfIpd = ipdMeters.coerceIn(0.04f, 0.09f) * 0.5f
        val dx = -eyeSign * halfIpd
        // Pre-multiply T(dx, 0, 0) * viewMat
        out[0] += dx * out[3]
        out[4] += dx * out[7]
        out[8] += dx * out[11]
        out[12] += dx * out[15]
        return out
    }

    /**
     * Adapts ARCore's full-screen projection matrix to a side-by-side eye viewport
     * of width ratio [eyeWidthRatio] (e.g. ~0.5f for half-screen) while keeping vertical FOV exact.
     */
    fun buildVrEyeProjMatrix(
        monoProjMat: FloatArray,
        eyeWidthRatio: Float,
        zoomFactor: Float = 1.0f
    ): FloatArray {
        require(monoProjMat.size >= 16) { "Projection matrix requires 16 floats" }
        val out = monoProjMat.copyOf(16)
        val ratio = eyeWidthRatio.coerceIn(0.25f, 1.0f)
        val zoom = zoomFactor.coerceIn(0.6f, 1.15f)
        out[0] = (monoProjMat[0] / ratio) * zoom
        out[5] = monoProjMat[5] * zoom
        out[8] = monoProjMat[8] / ratio
        return out
    }

    /**
     * Center-crops the 4 corner UVs returned by ARCore's transformCoordinates2d so a half-width
     * side-by-side VR viewport renders camera passthrough 1:1 without horizontal squishing.
     */
    fun computeVrEyeBackgroundTexCoords(
        fullTexCoords: FloatArray,
        eyeWidthRatio: Float,
        eyeSign: Float = 0f,
        stereoBgShiftFraction: Float = 0f
    ): FloatArray {
        require(fullTexCoords.size >= 8) { "Tex coords require 8 floats (4 corners)" }
        val ratio = eyeWidthRatio.coerceIn(0.25f, 1.0f)
        val halfSpan = ratio * 0.5f
        val center = (0.5f + eyeSign * stereoBgShiftFraction).coerceIn(halfSpan, 1f - halfSpan)
        val leftT = (center - halfSpan).coerceIn(0f, 1f)
        val rightT = (center + halfSpan).coerceIn(0f, 1f)

        // Triangle strip order: 0=BL, 1=BR, 2=TL, 3=TR
        val blU = fullTexCoords[0]; val blV = fullTexCoords[1]
        val brU = fullTexCoords[2]; val brV = fullTexCoords[3]
        val tlU = fullTexCoords[4]; val tlV = fullTexCoords[5]
        val trU = fullTexCoords[6]; val trV = fullTexCoords[7]

        return floatArrayOf(
            blU + (brU - blU) * leftT, blV + (brV - blV) * leftT,
            blU + (brU - blU) * rightT, blV + (brV - blV) * rightT,
            tlU + (trU - tlU) * leftT, tlV + (trV - tlV) * leftT,
            tlU + (trU - tlU) * rightT, tlV + (trV - tlV) * rightT
        )
    }
}

/**
 * Clips CAD geometry against the horizontal ground datum plane (Z >= 0) so subterranean
 * footings/foundations built under the CAD axis line can be hidden ("buried") or shown cleanly.
 */
object ArGeometryClipper {

    private const val GROUND_EPSILON = -1e-4

    data class VertexUv(val pos: Vec3, val uv: Vec2)
    data class ClippedTriangle(val v0: VertexUv, val v1: VertexUv, val v2: VertexUv)

    fun hasFoundationBelowAxis(bounds: BoundingBox3D): Boolean =
        bounds.isValid && bounds.min.z < -0.005

    fun clipTriangleAboveGround(
        p0: Vec3, p1: Vec3, p2: Vec3,
        uv0: Vec2, uv1: Vec2, uv2: Vec2,
        planeZ: Double = 0.0
    ): List<ClippedTriangle> {
        val threshold = planeZ + GROUND_EPSILON
        val minZ = min(p0.z, min(p1.z, p2.z))
        val maxZ = max(p0.z, max(p1.z, p2.z))

        if (minZ >= threshold) {
            return listOf(ClippedTriangle(VertexUv(p0, uv0), VertexUv(p1, uv1), VertexUv(p2, uv2)))
        }
        if (maxZ < threshold) {
            return emptyList()
        }

        val input = arrayOf(VertexUv(p0, uv0), VertexUv(p1, uv1), VertexUv(p2, uv2))
        val poly = ArrayList<VertexUv>(4)
        for (i in 0 until 3) {
            val a = input[i]
            val b = input[(i + 1) % 3]
            val aIn = a.pos.z >= threshold
            val bIn = b.pos.z >= threshold
            when {
                aIn && bIn -> poly.add(b)
                aIn && !bIn -> poly.add(intersectZ(a, b, planeZ))
                !aIn && bIn -> {
                    poly.add(intersectZ(a, b, planeZ))
                    poly.add(b)
                }
            }
        }
        if (poly.size < 3) return emptyList()

        val out = ArrayList<ClippedTriangle>(poly.size - 2)
        for (i in 1 until poly.size - 1) {
            val a = poly[0]
            val b = poly[i]
            val c = poly[i + 1]
            val area2 = (b.pos - a.pos).cross(c.pos - a.pos).lengthSquared()
            if (area2 > 1e-12) {
                out.add(ClippedTriangle(a, b, c))
            }
        }
        return out
    }

    fun clipEdgeAboveGround(
        a: Vec3,
        b: Vec3,
        planeZ: Double = 0.0
    ): Pair<Vec3, Vec3>? {
        val threshold = planeZ + GROUND_EPSILON
        val aIn = a.z >= threshold
        val bIn = b.z >= threshold
        if (aIn && bIn) return a to b
        if (!aIn && !bIn) return null
        val dz = b.z - a.z
        val t = if (abs(dz) > 1e-12) ((planeZ - a.z) / dz).coerceIn(0.0, 1.0) else 0.5
        val hit = a + (b - a) * t
        val clipped = if (aIn) a to hit else hit to b
        return if (clipped.first.distanceTo(clipped.second) > 1e-5) clipped else null
    }

    fun computeEffectiveBounds(document: CadDocument, showFoundation: Boolean): BoundingBox3D {
        if (showFoundation || !hasFoundationBelowAxis(document.bounds)) {
            return document.bounds
        }
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY

        fun expand(p: Vec3) {
            minX = min(minX, p.x); minY = min(minY, p.y); minZ = min(minZ, p.z)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y); maxZ = max(maxZ, p.z)
        }

        for (face in document.faces) {
            if (face.hidden) continue
            val tris = face.triangleVertices
            val uvs = face.triangleUvs
            var i = 0
            while (i + 2 < tris.size) {
                val clipped = clipTriangleAboveGround(
                    tris[i], tris[i + 1], tris[i + 2],
                    uvs.getOrElse(i) { Vec2(0.0, 0.0) },
                    uvs.getOrElse(i + 1) { Vec2(0.0, 0.0) },
                    uvs.getOrElse(i + 2) { Vec2(0.0, 0.0) }
                )
                for (tri in clipped) {
                    expand(tri.v0.pos)
                    expand(tri.v1.pos)
                    expand(tri.v2.pos)
                }
                i += 3
            }
        }
        for (edge in document.edges) {
            if (edge.hidden || edge.soft) continue
            clipEdgeAboveGround(edge.a, edge.b)?.let { (ca, cb) ->
                expand(ca)
                expand(cb)
            }
        }
        return if (minX <= maxX && minY <= maxY && minZ <= maxZ) {
            BoundingBox3D(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
        } else {
            document.bounds
        }
    }

    private fun intersectZ(a: VertexUv, b: VertexUv, planeZ: Double): VertexUv {
        val dz = b.pos.z - a.pos.z
        val t = if (abs(dz) > 1e-12) ((planeZ - a.pos.z) / dz).coerceIn(0.0, 1.0) else 0.5
        val pos = Vec3(
            a.pos.x + (b.pos.x - a.pos.x) * t,
            a.pos.y + (b.pos.y - a.pos.y) * t,
            planeZ
        )
        val uv = Vec2(
            a.uv.x + (b.uv.x - a.uv.x) * t,
            a.uv.y + (b.uv.y - a.uv.y) * t
        )
        return VertexUv(pos, uv)
    }
}

/**
 * Pure Kotlin YUV_420_888 -> upright ARGB_8888 center crop pixel array conversion.
 * Rotates pixels according to Android Surface rotation (0, 90, 180, 270) so ARCore's
 * AugmentedImageDatabase receives an upright target matching what the user sees on screen.
 */
object ArImageCropper {

    fun yuv420ImageToUprightCenterCrop(
        image: Image,
        displayRotationDegreesCw: Int,
        cropFraction: Float = 0.45f,
        maxOutputSizePx: Int = 360
    ): Bitmap? {
        if (image.planes.size < 3) return null
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val yBuf = duplicateAtZero(yPlane.buffer)
        val uBuf = duplicateAtZero(uPlane.buffer)
        val vBuf = duplicateAtZero(vPlane.buffer)

        val pixels = convertYuvBuffersToUprightCenterCropArgb(
            width = width,
            height = height,
            yBytes = yBuf,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uBytes = uBuf,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vBytes = vBuf,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
            rotateCwDegrees = displayRotationDegreesCw,
            cropFraction = cropFraction,
            outputSizePx = maxOutputSizePx
        )
        return Bitmap.createBitmap(
            pixels.argb,
            pixels.width,
            pixels.height,
            Bitmap.Config.ARGB_8888
        )
    }

    data class CroppedArgbResult(val width: Int, val height: Int, val argb: IntArray)

    fun convertYuvBuffersToUprightCenterCropArgb(
        width: Int,
        height: Int,
        yBytes: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        uBytes: ByteArray,
        uRowStride: Int,
        uPixelStride: Int,
        vBytes: ByteArray,
        vRowStride: Int,
        vPixelStride: Int,
        rotateCwDegrees: Int,
        cropFraction: Float = 0.45f,
        outputSizePx: Int = 320
    ): CroppedArgbResult {
        val shortSide = min(width, height).coerceAtLeast(1)
        val cropSide = (shortSide * cropFraction.coerceIn(0.2f, 0.9f)).roundToInt().coerceAtLeast(16)
        val startX = ((width - cropSide) / 2).coerceIn(0, max(0, width - cropSide))
        val startY = ((height - cropSide) / 2).coerceIn(0, max(0, height - cropSide))

        val outSize = min(cropSide, outputSizePx).coerceAtLeast(16)
        val argb = IntArray(outSize * outSize)
        val normRot = ((rotateCwDegrees % 360) + 360) % 360

        for (oy in 0 until outSize) {
            val cropY = (oy * cropSide) / outSize
            for (ox in 0 until outSize) {
                val cropX = (ox * cropSide) / outSize
                // Map output (ox, oy) back to source sensor coordinates
                val (sxCrop, syCrop) = when (normRot) {
                    90 -> cropY to (cropSide - 1 - cropX)
                    180 -> (cropSide - 1 - cropX) to (cropSide - 1 - cropY)
                    270 -> (cropSide - 1 - cropY) to cropX
                    else -> cropX to cropY
                }
                val srcX = (startX + sxCrop).coerceIn(0, width - 1)
                val srcY = (startY + syCrop).coerceIn(0, height - 1)

                val yIndex = srcY * yRowStride + srcX * yPixelStride
                val uvX = srcX / 2
                val uvY = srcY / 2
                val uIndex = uvY * uRowStride + uvX * uPixelStride
                val vIndex = uvY * vRowStride + uvX * vPixelStride

                val yVal = if (yIndex in yBytes.indices) (yBytes[yIndex].toInt() and 0xFF) else 128
                val uVal = if (uIndex in uBytes.indices) (uBytes[uIndex].toInt() and 0xFF) - 128 else 0
                val vVal = if (vIndex in vBytes.indices) (vBytes[vIndex].toInt() and 0xFF) - 128 else 0

                val r = (yVal + (1.370705f * vVal)).roundToInt().coerceIn(0, 255)
                val g = (yVal - (0.337633f * uVal) - (0.698001f * vVal)).roundToInt().coerceIn(0, 255)
                val b = (yVal + (1.732446f * uVal)).roundToInt().coerceIn(0, 255)

                argb[oy * outSize + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return CroppedArgbResult(outSize, outSize, argb)
    }

    private fun duplicateAtZero(buffer: ByteBuffer): ByteArray {
        val dup = buffer.duplicate()
        dup.rewind()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return bytes
    }
}
