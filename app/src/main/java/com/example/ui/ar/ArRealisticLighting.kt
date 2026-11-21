package com.example.ui.ar

import android.media.Image
import android.opengl.Matrix
import com.example.domain.math.BoundingBox3D
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ArLightModeLabel(val label: String) {
    SCENE_AND_HDR("Scene + HDR"),
    ENVIRONMENTAL_HDR("HDR Light"),
    AMBIENT_FALLBACK("Scene Light"),
    MANUAL_LIGHT("Manual Light"),
    DEFAULT_STUDIO("Auto Light")
}

/**
 * Physical environment lighting class inferred from real camera hardware exposure telemetry
 * (ISO, shutter speed, aperture -> EV100), AC lighting flicker, and optional ambient lux sensor.
 */
enum class ArEnvironmentCondition(val label: String) {
    BRIGHT_SUNLIGHT("Bright Sun"),
    OUTDOOR_OVERCAST("Outdoor Shade"),
    BRIGHT_INDOOR("Bright Indoor"),
    INDOOR_ROOM("Indoor Room"),
    LOW_LIGHT("Low Light"),
    NIGHT_DIM("Dim / Night")
}

/**
 * Real-time camera hardware exposure & white-balance telemetry read from ARCore `ImageMetadata`
 * (Camera2 capture result tags) plus optional Android `Sensor.TYPE_LIGHT` illuminance.
 */
data class ArCameraTelemetry(
    val iso: Int?,
    val postRawBoostPercent: Int?,
    val effectiveIso: Int?,
    val exposureTimeNs: Long?,
    val apertureFNumber: Float?,
    val colorCorrectionGains: FloatArray?,
    val neutralColorPoint: FloatArray?,
    val sceneFlickerHz: Int?,
    val ambientLux: Float?,
    val ev100: Float,
    val estimatedLux: Float,
    val colorTemperatureKelvin: Int,
    val awbLightTintRgb: FloatArray,
    val condition: ArEnvironmentCondition,
    val recommendedExposure: Float,
    val shadowContrastScale: Float,
    val specularScale: Float,
    val ambientFillScale: Float,
    val hasHardwareCameraData: Boolean
) {
    fun formatShutterSpeed(): String? {
        val ns = exposureTimeNs ?: return null
        if (ns <= 0L) return null
        val sec = ns / 1_000_000_000.0
        return if (sec < 0.25) {
            val denom = (1.0 / sec).roundToInt().coerceAtLeast(1)
            "1/${denom}s"
        } else {
            String.format(Locale.US, "%.2fs", sec)
        }
    }

    fun formatCompactSummary(): String {
        val parts = ArrayList<String>(5)
        effectiveIso?.let { parts.add("ISO $it") }
        formatShutterSpeed()?.let { parts.add(it) }
        apertureFNumber?.let { if (it in 0.7f..22f) parts.add(String.format(Locale.US, "f/%.1f", it)) }
        parts.add(String.format(Locale.US, "EV %.1f", ev100))
        parts.add("${colorTemperatureKelvin}K")
        ambientLux?.let { if (it.isFinite() && it >= 0f) parts.add("${it.roundToInt()} lx") }
        return parts.joinToString(" · ")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArCameraTelemetry) return false
        return iso == other.iso &&
            postRawBoostPercent == other.postRawBoostPercent &&
            effectiveIso == other.effectiveIso &&
            exposureTimeNs == other.exposureTimeNs &&
            apertureFNumber == other.apertureFNumber &&
            (colorCorrectionGains?.contentEquals(other.colorCorrectionGains) ?: (other.colorCorrectionGains == null)) &&
            (neutralColorPoint?.contentEquals(other.neutralColorPoint) ?: (other.neutralColorPoint == null)) &&
            sceneFlickerHz == other.sceneFlickerHz &&
            ambientLux == other.ambientLux &&
            ev100 == other.ev100 &&
            estimatedLux == other.estimatedLux &&
            colorTemperatureKelvin == other.colorTemperatureKelvin &&
            awbLightTintRgb.contentEquals(other.awbLightTintRgb) &&
            condition == other.condition &&
            recommendedExposure == other.recommendedExposure &&
            shadowContrastScale == other.shadowContrastScale &&
            specularScale == other.specularScale &&
            ambientFillScale == other.ambientFillScale &&
            hasHardwareCameraData == other.hasHardwareCameraData
    }

    override fun hashCode(): Int {
        var result = iso ?: 0
        result = 31 * result + (postRawBoostPercent ?: 0)
        result = 31 * result + (effectiveIso ?: 0)
        result = 31 * result + (exposureTimeNs?.hashCode() ?: 0)
        result = 31 * result + (apertureFNumber?.hashCode() ?: 0)
        result = 31 * result + (colorCorrectionGains?.contentHashCode() ?: 0)
        result = 31 * result + (neutralColorPoint?.contentHashCode() ?: 0)
        result = 31 * result + (sceneFlickerHz ?: 0)
        result = 31 * result + (ambientLux?.hashCode() ?: 0)
        result = 31 * result + ev100.hashCode()
        result = 31 * result + estimatedLux.hashCode()
        result = 31 * result + colorTemperatureKelvin
        result = 31 * result + awbLightTintRgb.contentHashCode()
        result = 31 * result + condition.hashCode()
        result = 31 * result + recommendedExposure.hashCode()
        result = 31 * result + shadowContrastScale.hashCode()
        result = 31 * result + specularScale.hashCode()
        result = 31 * result + ambientFillScale.hashCode()
        result = 31 * result + hasHardwareCameraData.hashCode()
        return result
    }
}

/**
 * Pure physics & photographic metering analyzer that turns raw camera hardware metadata
 * (ISO, exposure time, lens aperture, ISP AWB gains, neutral color point, flicker, ambient lux)
 * into EV100, correlated color temperature, environment condition, and PBR rendering parameters.
 */
object ArCameraTelemetryAnalyzer {
    private const val LN2 = 0.6931471805599453
    private const val DEFAULT_MOBILE_APERTURE = 1.8f

    fun analyze(
        iso: Int? = null,
        postRawBoostPercent: Int? = null,
        exposureTimeNs: Long? = null,
        apertureFNumber: Float? = null,
        colorCorrectionGains: FloatArray? = null,
        neutralColorPoint: FloatArray? = null,
        sceneFlickerHz: Int? = null,
        ambientLux: Float? = null,
        fallbackApertureFNumber: Float = DEFAULT_MOBILE_APERTURE
    ): ArCameraTelemetry? {
        val validIso = iso?.takeIf { it in 10..102_400 }
        val boost = postRawBoostPercent?.takeIf { it in 50..1600 } ?: 100
        val effectiveIso = validIso?.let { ((it.toLong() * boost.toLong()) / 100L).toInt().coerceIn(10, 204_800) }
        val validExpNs = exposureTimeNs?.takeIf { it in 10_000L..2_000_000_000L }
        val resolvedAperture = apertureFNumber?.takeIf { it.isFinite() && it in 0.7f..22f }
            ?: fallbackApertureFNumber.takeIf { it.isFinite() && it in 0.7f..22f }
            ?: DEFAULT_MOBILE_APERTURE
        val validLux = ambientLux?.takeIf { it.isFinite() && it >= 0f }

        val hasCameraExposure = effectiveIso != null && validExpNs != null
        val hasAwb = (colorCorrectionGains != null && colorCorrectionGains.size >= 4) ||
            (neutralColorPoint != null && neutralColorPoint.size >= 3)

        if (!hasCameraExposure && !hasAwb && validLux == null) {
            return null
        }

        val cameraEv100 = if (hasCameraExposure) {
            computeEv100(effectiveIso!!, validExpNs!!, resolvedAperture)
        } else {
            null
        }
        val luxEv100 = validLux?.let { ev100FromLux(it) }

        val ev100 = when {
            cameraEv100 != null && luxEv100 != null -> {
                // Trust camera AE metering strongly (78%) while gently grounding with the hardware lux sensor (22%)
                (cameraEv100 * 0.78f + luxEv100 * 0.22f).coerceIn(-2.0f, 17.0f)
            }
            cameraEv100 != null -> cameraEv100
            luxEv100 != null -> luxEv100
            else -> 6.8f
        }

        val estimatedLux = validLux ?: luxFromEv100(ev100)
        val condition = classifyCondition(ev100, estimatedLux, sceneFlickerHz)
        val (kelvin, awbTint) = estimateKelvinAndLightTint(colorCorrectionGains, neutralColorPoint)
        val recommendedExposure = computeExposureForEv100(ev100, condition)
        val (shadowContrast, specularScale, ambientFill) = computeRenderParamsForCondition(ev100, condition)

        return ArCameraTelemetry(
            iso = validIso,
            postRawBoostPercent = postRawBoostPercent?.takeIf { it in 50..1600 },
            effectiveIso = effectiveIso,
            exposureTimeNs = validExpNs,
            apertureFNumber = resolvedAperture,
            colorCorrectionGains = colorCorrectionGains?.copyOf(),
            neutralColorPoint = neutralColorPoint?.copyOf(),
            sceneFlickerHz = sceneFlickerHz,
            ambientLux = validLux,
            ev100 = ev100,
            estimatedLux = estimatedLux,
            colorTemperatureKelvin = kelvin,
            awbLightTintRgb = awbTint,
            condition = condition,
            recommendedExposure = recommendedExposure,
            shadowContrastScale = shadowContrast,
            specularScale = specularScale,
            ambientFillScale = ambientFill,
            hasHardwareCameraData = hasCameraExposure || hasAwb
        )
    }

    /**
     * Exact photographic EV100 = log2((100 * N^2) / (ISO * t_sec)).
     */
    fun computeEv100(effectiveIso: Int, exposureTimeNs: Long, apertureFNumber: Float): Float {
        val n = apertureFNumber.coerceIn(0.7f, 22f).toDouble()
        val tSec = (exposureTimeNs.coerceAtLeast(10_000L)) * 1e-9
        val s = effectiveIso.coerceAtLeast(10).toDouble()
        val ratio = (100.0 * n * n) / (s * tSec)
        return (ln(ratio.coerceAtLeast(1e-4)) / LN2).toFloat().coerceIn(-2.0f, 17.0f)
    }

    fun ev100FromLux(lux: Float): Float {
        val safeLux = lux.coerceIn(0.25f, 200_000f).toDouble()
        // Standard incident light meter constant C = 2.5 -> lux = 2.5 * 2^EV100
        return (ln(safeLux / 2.5) / LN2).toFloat().coerceIn(-2.0f, 17.0f)
    }

    fun luxFromEv100(ev100: Float): Float {
        return (2.5 * 2.0.pow(ev100.coerceIn(-2.0f, 17.0f).toDouble())).toFloat()
    }

    fun classifyCondition(
        ev100: Float,
        estimatedLux: Float,
        sceneFlickerHz: Int?
    ): ArEnvironmentCondition {
        val hasIndoorAcFlicker = sceneFlickerHz == 50 || sceneFlickerHz == 60
        return when {
            !hasIndoorAcFlicker && (ev100 >= 12.0f || estimatedLux >= 10_000f) -> ArEnvironmentCondition.BRIGHT_SUNLIGHT
            !hasIndoorAcFlicker && (ev100 >= 9.5f || estimatedLux >= 1_800f) -> ArEnvironmentCondition.OUTDOOR_OVERCAST
            ev100 >= 7.0f -> ArEnvironmentCondition.BRIGHT_INDOOR
            ev100 >= 4.2f -> ArEnvironmentCondition.INDOOR_ROOM
            ev100 >= 2.2f -> ArEnvironmentCondition.LOW_LIGHT
            else -> ArEnvironmentCondition.NIGHT_DIM
        }
    }

    /**
     * Estimates Correlated Color Temperature (Kelvin) and a subtle linear light chromaticity tint
     * from Camera2 ISP `COLOR_CORRECTION_GAINS` `[R, Ge, Go, B]` (or `SENSOR_NEUTRAL_COLOR_POINT`).
     * Uses the red-to-blue gain ratio (`bGain / rGain`) so Bayer green sensitivity never causes a green cast.
     */
    fun estimateKelvinAndLightTint(
        colorCorrectionGains: FloatArray?,
        neutralColorPoint: FloatArray?
    ): Pair<Int, FloatArray> {
        var warmthRatio: Float? = null
        if (colorCorrectionGains != null && colorCorrectionGains.size >= 4) {
            val rGain = colorCorrectionGains[0]
            val bGain = colorCorrectionGains[3]
            if (rGain.isFinite() && bGain.isFinite() && rGain > 0.15f && bGain > 0.15f) {
                warmthRatio = (bGain / rGain).coerceIn(0.45f, 3.2f)
            }
        }
        if (warmthRatio == null && neutralColorPoint != null && neutralColorPoint.size >= 3) {
            val nr = neutralColorPoint[0]
            val nb = neutralColorPoint[2]
            if (nr.isFinite() && nb.isFinite() && nr > 0.05f && nb > 0.05f) {
                warmthRatio = (nr / nb).coerceIn(0.45f, 3.2f)
            }
        }
        if (warmthRatio == null) {
            return 5200 to floatArrayOf(1.0f, 1.0f, 1.0f)
        }

        // Mireds scale is physically linear with ln(B_gain / R_gain), centered around 5500K daylight (ratio = 1.0)
        val mireds = (1_000_000.0 / 5500.0) + 175.0 * ln(warmthRatio.toDouble())
        val kelvin = (1_000_000.0 / mireds.coerceIn(120.0, 420.0)).roundToInt().coerceIn(2500, 8000)
        return kelvin to kelvinToSubtleLightTint(kelvin)
    }

    fun kelvinToSubtleLightTint(kelvin: Int): FloatArray {
        // Map Kelvin deviation in mireds relative to neutral 5500K into gentle, natural RGB tint multipliers
        val miredDelta = ((1_000_000f / kelvin.coerceIn(2500, 8000).toFloat()) - (1_000_000f / 5500f)) / 145f
        val clamped = miredDelta.coerceIn(-0.65f, 1.15f)
        val r = (1.0f + clamped * 0.15f).coerceIn(0.86f, 1.18f)
        val g = (1.0f - abs(clamped) * 0.015f).coerceIn(0.96f, 1.01f)
        val b = (1.0f - clamped * 0.18f).coerceIn(0.78f, 1.16f)
        return floatArrayOf(r, g, b)
    }

    private fun computeExposureForEv100(ev100: Float, condition: ArEnvironmentCondition): Float {
        // Smooth monotonic curve mapping EV100 (~1..15) to photographic PBR scene exposure (~0.48..1.20)
        val norm = ((ev100 - 1.5f) / 12.5f).coerceIn(0f, 1f)
        val smooth = 0.48f + 0.70f * norm.toDouble().pow(0.82).toFloat()
        val condBias = when (condition) {
            ArEnvironmentCondition.BRIGHT_SUNLIGHT -> 1.04f
            ArEnvironmentCondition.OUTDOOR_OVERCAST -> 1.00f
            ArEnvironmentCondition.BRIGHT_INDOOR -> 0.96f
            ArEnvironmentCondition.INDOOR_ROOM -> 0.94f
            ArEnvironmentCondition.LOW_LIGHT -> 0.88f
            ArEnvironmentCondition.NIGHT_DIM -> 0.82f
        }
        return (smooth * condBias).coerceIn(0.44f, 1.24f)
    }

    private fun computeRenderParamsForCondition(
        ev100: Float,
        condition: ArEnvironmentCondition
    ): Triple<Float, Float, Float> {
        val norm = ((ev100 - 2.0f) / 12.0f).coerceIn(0f, 1f)
        return when (condition) {
            ArEnvironmentCondition.BRIGHT_SUNLIGHT -> Triple(1.22f, 1.18f, 0.92f)
            ArEnvironmentCondition.OUTDOOR_OVERCAST -> Triple(0.88f, 0.95f, 1.08f)
            ArEnvironmentCondition.BRIGHT_INDOOR -> Triple(0.86f, 0.86f, 1.02f)
            ArEnvironmentCondition.INDOOR_ROOM -> Triple(0.72f + 0.10f * norm, 0.72f + 0.10f * norm, 0.96f)
            ArEnvironmentCondition.LOW_LIGHT -> Triple(0.54f, 0.54f, 0.84f)
            ArEnvironmentCondition.NIGHT_DIM -> Triple(0.38f, 0.40f, 0.72f)
        }
    }
}

data class PbrMaterialHint(
    val roughness: Float,
    val metallic: Float,
    val reflectanceF0: Float
)

data class GroundShadowQuadBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val halfFootprintX: Float,
    val halfFootprintY: Float,
    val centerX: Float,
    val centerY: Float,
    val groundZ: Float
)

/**
 * User-adjustable lighting override settings exposed in the AR Light tab.
 */
data class ArLightSettings(
    val autoDetectLight: Boolean = true,
    val manualAzimuthDeg: Float = 45f,
    val manualElevationDeg: Float = 56f,
    val exposureMultiplier: Float = 1.0f,
    val shadowStrengthScale: Float = 1.0f
)

/**
 * Real-world scene lighting cues extracted directly from the live camera feed by comparing
 * real surfaces, highlights, shadow gradients, and floor colors in view.
 */
data class ArSceneLightSample(
    val medianLuminance: Float,
    val highlightLuminance: Float,
    val calibratedExposure: Float,
    val whiteBalanceRgb: FloatArray,
    val floorBounceRgb: FloatArray,
    val worldLightHintDir: FloatArray?,
    val directionConfidence: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArSceneLightSample) return false
        return medianLuminance == other.medianLuminance &&
            highlightLuminance == other.highlightLuminance &&
            calibratedExposure == other.calibratedExposure &&
            whiteBalanceRgb.contentEquals(other.whiteBalanceRgb) &&
            floorBounceRgb.contentEquals(other.floorBounceRgb) &&
            (worldLightHintDir?.contentEquals(other.worldLightHintDir) ?: (other.worldLightHintDir == null)) &&
            directionConfidence == other.directionConfidence
    }

    override fun hashCode(): Int {
        var result = medianLuminance.hashCode()
        result = 31 * result + highlightLuminance.hashCode()
        result = 31 * result + calibratedExposure.hashCode()
        result = 31 * result + whiteBalanceRgb.contentHashCode()
        result = 31 * result + floorBounceRgb.contentHashCode()
        result = 31 * result + (worldLightHintDir?.contentHashCode() ?: 0)
        result = 31 * result + directionConfidence.hashCode()
        return result
    }
}

/**
 * Snapshot of world-space lighting extracted from ARCore + real camera scene analysis,
 * ready to upload directly to OpenGL ES 3.0 PBR and real-time shadow-mapping shaders.
 */
data class ArEnvironmentLighting(
    val mainLightDirection: FloatArray,
    val mainLightIntensity: FloatArray,
    val sphericalHarmonics: FloatArray,
    val ambientColorCorrection: FloatArray,
    val floorBounceColor: FloatArray,
    val sceneExposure: Float,
    val ambientIntensity: Float,
    val shadowOffsetX: Float,
    val shadowOffsetZ: Float,
    val shadowStrength: Float,
    val lightAzimuthDeg: Float,
    val lightElevationDeg: Float,
    val modeLabel: ArLightModeLabel,
    val isEstimateValid: Boolean,
    val specularScale: Float = 1.0f,
    val ambientFillScale: Float = 1.0f,
    val environmentCondition: ArEnvironmentCondition? = null,
    val cameraTelemetry: ArCameraTelemetry? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArEnvironmentLighting) return false
        return mainLightDirection.contentEquals(other.mainLightDirection) &&
            mainLightIntensity.contentEquals(other.mainLightIntensity) &&
            sphericalHarmonics.contentEquals(other.sphericalHarmonics) &&
            ambientColorCorrection.contentEquals(other.ambientColorCorrection) &&
            floorBounceColor.contentEquals(other.floorBounceColor) &&
            sceneExposure == other.sceneExposure &&
            ambientIntensity == other.ambientIntensity &&
            shadowOffsetX == other.shadowOffsetX &&
            shadowOffsetZ == other.shadowOffsetZ &&
            shadowStrength == other.shadowStrength &&
            lightAzimuthDeg == other.lightAzimuthDeg &&
            lightElevationDeg == other.lightElevationDeg &&
            modeLabel == other.modeLabel &&
            isEstimateValid == other.isEstimateValid &&
            specularScale == other.specularScale &&
            ambientFillScale == other.ambientFillScale &&
            environmentCondition == other.environmentCondition &&
            cameraTelemetry == other.cameraTelemetry
    }

    override fun hashCode(): Int {
        var result = mainLightDirection.contentHashCode()
        result = 31 * result + mainLightIntensity.contentHashCode()
        result = 31 * result + sphericalHarmonics.contentHashCode()
        result = 31 * result + ambientColorCorrection.contentHashCode()
        result = 31 * result + floorBounceColor.contentHashCode()
        result = 31 * result + sceneExposure.hashCode()
        result = 31 * result + ambientIntensity.hashCode()
        result = 31 * result + shadowOffsetX.hashCode()
        result = 31 * result + shadowOffsetZ.hashCode()
        result = 31 * result + shadowStrength.hashCode()
        result = 31 * result + lightAzimuthDeg.hashCode()
        result = 31 * result + lightElevationDeg.hashCode()
        result = 31 * result + modeLabel.hashCode()
        result = 31 * result + isEstimateValid.hashCode()
        result = 31 * result + specularScale.hashCode()
        result = 31 * result + ambientFillScale.hashCode()
        result = 31 * result + (environmentCondition?.hashCode() ?: 0)
        result = 31 * result + (cameraTelemetry?.hashCode() ?: 0)
        return result
    }
}

/**
 * Lightweight camera-frame scene light analyzer that reads brightness, color temperature,
 * floor bounce tint, and dominant light-side gradient directly from real objects in the camera view.
 */
object ArSceneLightAnalyzer {

    /**
     * Samples a compact 24x18 grid from an ARCore YUV_420_888 camera image and maps any
     * detected directional bright-side cue into AR world space via [cameraRotateVector].
     */
    fun analyzeCameraImage(
        image: Image,
        displayRotationDegreesCw: Int,
        cameraRotateVector: (FloatArray) -> FloatArray
    ): ArSceneLightSample? {
        val w = image.width
        val h = image.height
        if (w < 16 || h < 16 || image.planes.size < 3) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        val cols = 20
        val rows = 15
        val gridLuma = FloatArray(cols * rows)
        val gridR = FloatArray(cols * rows)
        val gridG = FloatArray(cols * rows)
        val gridB = FloatArray(cols * rows)

        var idx = 0
        for (ry in 0 until rows) {
            val ny = (ry + 0.5f) / rows
            for (rx in 0 until cols) {
                val nx = (rx + 0.5f) / cols
                // Map upright screen normalized (nx, ny) back to sensor coordinates
                val (sxNorm, syNorm) = uprightToSensorUv(nx, ny, displayRotationDegreesCw)
                val px = (sxNorm * (w - 1)).toInt().coerceIn(0, w - 1)
                val py = (syNorm * (h - 1)).toInt().coerceIn(0, h - 1)

                val yPos = py * yRowStride + px * yPixStride
                val uvX = px / 2
                val uvY = py / 2
                val uPos = uvY * uRowStride + uvX * uPixStride
                val vPos = uvY * vRowStride + uvX * vPixStride

                val yVal = if (yPos in 0 until yBuf.limit()) (yBuf.get(yPos).toInt() and 0xFF) else 128
                val uVal = if (uPos in 0 until uBuf.limit()) (uBuf.get(uPos).toInt() and 0xFF) - 128 else 0
                val vVal = if (vPos in 0 until vBuf.limit()) (vBuf.get(vPos).toInt() and 0xFF) - 128 else 0

                val r = ((yVal + 1.370705f * vVal) / 255f).coerceIn(0f, 1f)
                val g = ((yVal - 0.337633f * uVal - 0.698001f * vVal) / 255f).coerceIn(0f, 1f)
                val b = ((yVal + 1.732446f * uVal) / 255f).coerceIn(0f, 1f)
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

                gridLuma[idx] = luma
                gridR[idx] = r
                gridG[idx] = g
                gridB[idx] = b
                idx++
            }
        }

        return analyzeSampledRgbGrid(
            cols = cols,
            rows = rows,
            gridR = gridR,
            gridG = gridG,
            gridB = gridB,
            gridLuma = gridLuma,
            cameraRotateVector = cameraRotateVector
        )
    }

    /**
     * Pure grid analyzer (unit-testable without android.media.Image) that derives:
     * - Real-scene exposure calibration from median/midtone luminance
     * - Real-scene white-balance chromaticity from midtone real objects
     * - Floor bounce tint from the lower quarter of the frame
     * - Directional light hint comparing bright vs shadowed sides across the frame
     */
    fun analyzeSampledRgbGrid(
        cols: Int,
        rows: Int,
        gridR: FloatArray,
        gridG: FloatArray,
        gridB: FloatArray,
        gridLuma: FloatArray,
        cameraRotateVector: (FloatArray) -> FloatArray
    ): ArSceneLightSample {
        val count = min(cols * rows, gridLuma.size)
        if (count <= 0) {
            return ArSceneLightSample(
                medianLuminance = 0.45f,
                highlightLuminance = 0.75f,
                calibratedExposure = 0.85f,
                whiteBalanceRgb = floatArrayOf(1f, 1f, 1f),
                floorBounceRgb = floatArrayOf(0.32f, 0.33f, 0.35f),
                worldLightHintDir = null,
                directionConfidence = 0f
            )
        }

        val sortedLuma = gridLuma.copyOf(count).apply { sort() }
        val medianLuma = sortedLuma[count / 2].coerceIn(0.05f, 0.98f)
        val p88Luma = sortedLuma[(count * 88 / 100).coerceIn(0, count - 1)].coerceIn(0.08f, 1f)
        val p15Luma = sortedLuma[(count * 15 / 100).coerceIn(0, count - 1)].coerceIn(0.01f, 0.95f)

        // Compare real-scene midtone luminance against a neutral photographic target (~0.48 sRGB)
        // Indoor rooms (~0.32-0.42 sRGB) automatically dial down model brightness so wood/white
        // surfaces sit naturally beside real furniture instead of glowing.
        val linearMedian = medianLuma.toDouble().pow(2.2).toFloat()
        val linearTarget = 0.48.pow(2.2).toFloat() // ~0.198 linear
        val rawExposure = sqrt((linearMedian / linearTarget).coerceIn(0.18f, 2.0f))
        val calibratedExposure = (0.34f + 0.58f * rawExposure).coerceIn(0.46f, 1.28f)

        // Grey-world white-balance estimation from midtone real surfaces (exclude clipped highlights & deep blacks)
        var wbR = 0f
        var wbG = 0f
        var wbB = 0f
        var wbCount = 0

        // Floor bounce tint from bottom 28% of the camera view
        var flR = 0f
        var flG = 0f
        var flB = 0f
        var flCount = 0

        // Bright-region & gradient direction cue (screen X right, screen Y up)
        var weightedX = 0f
        var weightedY = 0f
        var weightSum = 0f
        val brightThreshold = max(medianLuma + (p88Luma - medianLuma) * 0.45f, 0.40f)

        for (ry in 0 until rows) {
            val screenYUp = 1f - 2f * ((ry + 0.5f) / rows) // +1 top, -1 bottom
            for (rx in 0 until cols) {
                val i = ry * cols + rx
                if (i >= count) break
                val luma = gridLuma[i]
                val r = gridR[i]
                val g = gridG[i]
                val b = gridB[i]

                if (luma in 0.14f..0.86f) {
                    val maxC = max(r, max(g, b))
                    val minC = min(r, min(g, b))
                    val sat = if (maxC > 1e-4f) (maxC - minC) / maxC else 0f
                    // Favor neutral-to-moderate saturation surfaces (walls, tiles, counters) for accurate white balance
                    val wNeutral = (1.15f - sat).coerceIn(0.15f, 1.0f)
                    wbR += r * wNeutral
                    wbG += g * wNeutral
                    wbB += b * wNeutral
                    wbCount++
                }

                if (ry >= (rows * 72 / 100)) {
                    flR += r
                    flG += g
                    flB += b
                    flCount++
                }

                if (luma >= brightThreshold) {
                    val excess = (luma - medianLuma).coerceAtLeast(0.01f)
                    // Prefer upper-frame light sources/highlights over bright floor patches
                    val upperBias = if (screenYUp > -0.15f) 1.35f else 0.45f
                    val w = excess * excess * upperBias
                    val screenXRight = 2f * ((rx + 0.5f) / cols) - 1f
                    weightedX += screenXRight * w
                    weightedY += screenYUp * w
                    weightSum += w
                }
            }
        }

        val whiteBalance = if (wbCount > 0) {
            val avgR = wbR / wbCount
            val avgG = wbG / wbCount
            val avgB = wbB / wbCount
            val avgAll = ((avgR + avgG + avgB) / 3f).coerceAtLeast(0.01f)
            // Keep chromaticity subtle so real room color cast blends naturally without over-tinting
            floatArrayOf(
                (1f + (avgR / avgAll - 1f) * 0.65f).coerceIn(0.78f, 1.24f),
                (1f + (avgG / avgAll - 1f) * 0.65f).coerceIn(0.82f, 1.18f),
                (1f + (avgB / avgAll - 1f) * 0.65f).coerceIn(0.76f, 1.26f)
            )
        } else {
            floatArrayOf(1f, 1f, 1f)
        }

        val floorBounce = if (flCount > 0) {
            floatArrayOf(
                (flR / flCount).toDouble().pow(2.2).toFloat().coerceIn(0.04f, 0.55f),
                (flG / flCount).toDouble().pow(2.2).toFloat().coerceIn(0.04f, 0.55f),
                (flB / flCount).toDouble().pow(2.2).toFloat().coerceIn(0.04f, 0.55f)
            )
        } else {
            floatArrayOf(0.20f, 0.21f, 0.22f)
        }

        val contrastSpan = (p88Luma - p15Luma).coerceIn(0f, 1f)
        var worldHintDir: FloatArray? = null
        var confidence = 0f
        if (weightSum > 1e-4f && contrastSpan > 0.14f) {
            val cx = (weightedX / weightSum).coerceIn(-1f, 1f)
            val cy = (weightedY / weightSum).coerceIn(-0.4f, 1f)
            val mag2d = sqrt(cx * cx + cy * cy)
            if (mag2d > 0.12f) {
                // In ARCore camera space: +X right, +Y up, -Z forward into the scene
                val camDir = floatArrayOf(cx * 0.85f, max(cy * 0.85f + 0.55f, 0.35f), -0.55f)
                val rotated = cameraRotateVector(camDir)
                if (rotated.size >= 3) {
                    worldHintDir = ArLightEstimatorMath.normalizeLightDirection(
                        rotated[0],
                        rotated[1],
                        rotated[2]
                    )
                    confidence = ((mag2d * 0.65f) * (contrastSpan * 1.4f)).coerceIn(0.12f, 0.55f)
                }
            }
        }

        return ArSceneLightSample(
            medianLuminance = medianLuma,
            highlightLuminance = p88Luma,
            calibratedExposure = calibratedExposure,
            whiteBalanceRgb = whiteBalance,
            floorBounceRgb = floorBounce,
            worldLightHintDir = worldHintDir,
            directionConfidence = confidence
        )
    }

    private fun uprightToSensorUv(nx: Float, ny: Float, rotationDegCw: Int): Pair<Float, Float> {
        return when (((rotationDegCw % 360) + 360) % 360) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }
    }
}

/**
 * Pure math utilities for ARCore Environmental HDR lighting, camera-scene calibration,
 * temporal EMA smoothing, real-time shadow-map matrices, and PBR surface finish heuristics.
 */
object ArLightEstimatorMath {

    /**
     * Cosine-lobe irradiance pre-multipliers matching Google ARCore Environmental HDR
     * spherical harmonics layout (9 RGB bands = 27 floats).
     */
    val SPHERICAL_HARMONIC_FACTORS = floatArrayOf(
        0.282095f,
        -0.325735f, 0.325735f, -0.325735f,
        0.273137f, -0.273137f, 0.078848f, -0.273137f, 0.136569f
    )

    fun defaultStudioLighting(): ArEnvironmentLighting {
        val dir = normalizeLightDirection(0.38f, 0.84f, 0.38f)
        val (az, el) = directionToAzimuthElevationDeg(dir)
        val mainIntensity = floatArrayOf(0.95f, 0.93f, 0.90f)
        val sh = FloatArray(27).apply {
            this[0] = 0.32f; this[1] = 0.33f; this[2] = 0.36f
            this[3] = -0.07f; this[4] = -0.08f; this[5] = -0.10f
        }
        val (sx, sz) = computeShadowOffset(dir)
        return ArEnvironmentLighting(
            mainLightDirection = dir,
            mainLightIntensity = mainIntensity,
            sphericalHarmonics = sh,
            ambientColorCorrection = floatArrayOf(1.0f, 1.0f, 1.0f),
            floorBounceColor = floatArrayOf(0.18f, 0.19f, 0.21f),
            sceneExposure = 0.86f,
            ambientIntensity = 0.78f,
            shadowOffsetX = sx,
            shadowOffsetZ = sz,
            shadowStrength = 0.46f,
            lightAzimuthDeg = az,
            lightElevationDeg = el,
            modeLabel = ArLightModeLabel.DEFAULT_STUDIO,
            isEstimateValid = false
        )
    }

    /**
     * Converts raw ARCore Environmental HDR outputs into shader-ready linear lighting + shadow parameters.
     */
    fun fromEnvironmentalHdr(
        rawDirection: FloatArray,
        rawMainIntensity: FloatArray,
        rawSphericalHarmonics: FloatArray
    ): ArEnvironmentLighting {
        val dir = if (rawDirection.size >= 3) {
            normalizeLightDirection(rawDirection[0], rawDirection[1], rawDirection[2])
        } else {
            normalizeLightDirection(0.35f, 0.85f, 0.40f)
        }

        val mainInt = FloatArray(3)
        for (c in 0..2) {
            val v = if (rawMainIntensity.size > c && rawMainIntensity[c].isFinite()) {
                rawMainIntensity[c].coerceAtLeast(0f)
            } else {
                0.75f
            }
            mainInt[c] = compressHdrPeak(v, softCap = 2.4f)
        }

        val shOut = FloatArray(27)
        if (rawSphericalHarmonics.size >= 27) {
            for (band in 0 until 9) {
                val factor = SPHERICAL_HARMONIC_FACTORS[band]
                val baseIdx = band * 3
                for (c in 0..2) {
                    val raw = rawSphericalHarmonics[baseIdx + c]
                    val safe = if (raw.isFinite()) raw.coerceIn(-4.2f, 4.2f) else 0f
                    shOut[baseIdx + c] = safe * factor
                }
            }
        } else {
            shOut[0] = 0.34f; shOut[1] = 0.34f; shOut[2] = 0.34f
        }

        shOut[0] = shOut[0].coerceAtLeast(0.06f)
        shOut[1] = shOut[1].coerceAtLeast(0.06f)
        shOut[2] = shOut[2].coerceAtLeast(0.06f)

        val mainLuma = luminance(mainInt[0], mainInt[1], mainInt[2])
        val ambientLuma = luminance(shOut[0], shOut[1], shOut[2]).coerceAtLeast(0.05f)
        val directRatio = mainLuma / (mainLuma + ambientLuma * 1.4f + 0.001f)
        val shadowStrength = (0.30f + 0.38f * directRatio).coerceIn(0.28f, 0.68f)
        val (sx, sz) = computeShadowOffset(dir)
        val (az, el) = directionToAzimuthElevationDeg(dir)

        val ambientNorm = (ambientLuma * 2.0f).coerceIn(0.22f, 1.45f)
        val tintMax = max(shOut[0], max(shOut[1], shOut[2])).coerceAtLeast(0.001f)
        val ambientColor = floatArrayOf(
            (shOut[0] / tintMax).coerceIn(0.68f, 1.15f),
            (shOut[1] / tintMax).coerceIn(0.68f, 1.15f),
            (shOut[2] / tintMax).coerceIn(0.68f, 1.15f)
        )

        return ArEnvironmentLighting(
            mainLightDirection = dir,
            mainLightIntensity = mainInt,
            sphericalHarmonics = shOut,
            ambientColorCorrection = ambientColor,
            floorBounceColor = floatArrayOf(
                (shOut[0] * 0.55f).coerceIn(0.05f, 0.45f),
                (shOut[1] * 0.55f).coerceIn(0.05f, 0.45f),
                (shOut[2] * 0.55f).coerceIn(0.05f, 0.45f)
            ),
            sceneExposure = 0.88f,
            ambientIntensity = ambientNorm,
            shadowOffsetX = sx,
            shadowOffsetZ = sz,
            shadowStrength = shadowStrength,
            lightAzimuthDeg = az,
            lightElevationDeg = el,
            modeLabel = ArLightModeLabel.ENVIRONMENTAL_HDR,
            isEstimateValid = true
        )
    }

    /**
     * Converts ARCore Ambient Intensity fallback readings (`pixelIntensity` + `colorCorrection` RGBA)
     * into the unified [ArEnvironmentLighting] representation.
     */
    fun fromAmbientIntensity(
        pixelIntensity: Float,
        colorCorrectionRgba: FloatArray
    ): ArEnvironmentLighting {
        val safePixel = if (pixelIntensity.isFinite()) pixelIntensity.coerceIn(0.08f, 1.8f) else 0.65f
        val rTint = if (colorCorrectionRgba.isNotEmpty() && colorCorrectionRgba[0].isFinite()) {
            colorCorrectionRgba[0].coerceIn(0.35f, 1.6f)
        } else 1f
        val gTint = if (colorCorrectionRgba.size > 1 && colorCorrectionRgba[1].isFinite()) {
            colorCorrectionRgba[1].coerceIn(0.35f, 1.6f)
        } else 1f
        val bTint = if (colorCorrectionRgba.size > 2 && colorCorrectionRgba[2].isFinite()) {
            colorCorrectionRgba[2].coerceIn(0.35f, 1.6f)
        } else 1f

        val exposure = (safePixel / 0.466f).coerceIn(0.28f, 1.65f)
        val dir = normalizeLightDirection(0.32f, 0.88f, 0.34f)
        val (az, el) = directionToAzimuthElevationDeg(dir)
        val mainInt = floatArrayOf(
            0.85f * exposure * rTint,
            0.85f * exposure * gTint,
            0.85f * exposure * bTint
        )
        val sh = FloatArray(27).apply {
            this[0] = 0.32f * exposure * rTint
            this[1] = 0.32f * exposure * gTint
            this[2] = 0.32f * exposure * bTint
            this[3] = -0.06f * exposure * rTint
            this[4] = -0.06f * exposure * gTint
            this[5] = -0.07f * exposure * bTint
        }
        val (sx, sz) = computeShadowOffset(dir)
        val shadowStrength = (0.30f + 0.18f * min(exposure, 1.2f)).coerceIn(0.26f, 0.56f)

        return ArEnvironmentLighting(
            mainLightDirection = dir,
            mainLightIntensity = mainInt,
            sphericalHarmonics = sh,
            ambientColorCorrection = floatArrayOf(rTint, gTint, bTint),
            floorBounceColor = floatArrayOf(
                (0.18f * exposure * rTint).coerceIn(0.05f, 0.45f),
                (0.18f * exposure * gTint).coerceIn(0.05f, 0.45f),
                (0.18f * exposure * bTint).coerceIn(0.05f, 0.45f)
            ),
            sceneExposure = exposure.coerceIn(0.50f, 1.25f),
            ambientIntensity = exposure,
            shadowOffsetX = sx,
            shadowOffsetZ = sz,
            shadowStrength = shadowStrength,
            lightAzimuthDeg = az,
            lightElevationDeg = el,
            modeLabel = ArLightModeLabel.AMBIENT_FALLBACK,
            isEstimateValid = true
        )
    }

    /**
     * Fuses ARCore's light estimation with real-world camera scene comparison (exposure,
     * white-balance chromaticity, floor color bounce, and bright-side directional cue).
     */
    fun fuseWithCameraScene(
        base: ArEnvironmentLighting,
        scene: ArSceneLightSample?
    ): ArEnvironmentLighting {
        if (scene == null) return base

        // Blend white balance gently so model matches the room's cool/warm light cast
        val blendedColor = FloatArray(3) { c ->
            (base.ambientColorCorrection[c] * 0.45f + scene.whiteBalanceRgb[c] * 0.55f)
                .coerceIn(0.72f, 1.25f)
        }

        // Fuse camera directional cue when ARCore HDR is weak/near-vertical or in ambient fallback
        val fusedDir = if (scene.worldLightHintDir != null && scene.directionConfidence > 0.08f) {
            val baseMainLuma = luminance(
                base.mainLightIntensity[0],
                base.mainLightIntensity[1],
                base.mainLightIntensity[2]
            )
            // Trust ARCore HDR more when main sun is strong; lean on camera comparison indoors
            val hintWeight = when {
                base.modeLabel != ArLightModeLabel.ENVIRONMENTAL_HDR -> scene.directionConfidence.coerceIn(0.25f, 0.65f)
                baseMainLuma < 0.75f -> (scene.directionConfidence * 0.70f).coerceIn(0.12f, 0.45f)
                else -> (scene.directionConfidence * 0.25f).coerceIn(0.05f, 0.20f)
            }
            val inv = 1f - hintWeight
            normalizeLightDirection(
                base.mainLightDirection[0] * inv + scene.worldLightHintDir[0] * hintWeight,
                base.mainLightDirection[1] * inv + scene.worldLightHintDir[1] * hintWeight,
                base.mainLightDirection[2] * inv + scene.worldLightHintDir[2] * hintWeight
            )
        } else {
            base.mainLightDirection
        }

        // Tint main light & SH subtly by the measured scene white balance so it harmonizes with real objects
        val wbAdjMain = FloatArray(3) { c ->
            base.mainLightIntensity[c] * (0.65f + 0.35f * scene.whiteBalanceRgb[c])
        }
        val wbAdjSh = FloatArray(27) { i ->
            val c = i % 3
            base.sphericalHarmonics[i] * (0.65f + 0.35f * scene.whiteBalanceRgb[c])
        }

        val (sx, sz) = computeShadowOffset(fusedDir)
        val (az, el) = directionToAzimuthElevationDeg(fusedDir)
        val nextLabel = if (base.modeLabel == ArLightModeLabel.ENVIRONMENTAL_HDR) {
            ArLightModeLabel.SCENE_AND_HDR
        } else {
            ArLightModeLabel.AMBIENT_FALLBACK
        }

        return base.copy(
            mainLightDirection = fusedDir,
            mainLightIntensity = wbAdjMain,
            sphericalHarmonics = wbAdjSh,
            ambientColorCorrection = blendedColor,
            floorBounceColor = scene.floorBounceRgb.copyOf(),
            sceneExposure = scene.calibratedExposure,
            shadowOffsetX = sx,
            shadowOffsetZ = sz,
            lightAzimuthDeg = az,
            lightElevationDeg = el,
            modeLabel = nextLabel,
            isEstimateValid = true
        )
    }

    /**
     * Fuses ARCore Environmental HDR / Ambient Intensity with real camera hardware telemetry
     * (ISO, shutter speed, aperture -> EV100, ISP AWB color gains -> Kelvin, AC flicker, Lux sensor)
     * and optional camera-frame scene grid cues so the rendered model adapts accurately across
     * bright sunlight, overcast shade, indoor rooms, and high-ISO low-light environments.
     */
    fun fuseWithCameraTelemetryAndScene(
        base: ArEnvironmentLighting,
        telemetry: ArCameraTelemetry?,
        scene: ArSceneLightSample?
    ): ArEnvironmentLighting {
        val sceneFused = if (scene != null) fuseWithCameraScene(base, scene) else base
        if (telemetry == null) return sceneFused

        val hasScene = scene != null
        val hwExpWeight = if (hasScene) 0.62f else 0.80f
        val calibratedExposure = (
            telemetry.recommendedExposure * hwExpWeight +
                sceneFused.sceneExposure * (1f - hwExpWeight)
            ).coerceIn(0.42f, 1.32f)

        // Blend ISP hardware AWB tint with ARCore/scene chromaticity for accurate warm/cool room matching
        val awbWeight = if (telemetry.hasHardwareCameraData) 0.55f else 0.25f
        val blendedColor = FloatArray(3) { c ->
            (sceneFused.ambientColorCorrection[c] * (1f - awbWeight) + telemetry.awbLightTintRgb[c] * awbWeight)
                .coerceIn(0.74f, 1.24f)
        }

        // Scale direct sun vs diffuse balance according to physical environment condition
        val directConditionGain = when (telemetry.condition) {
            ArEnvironmentCondition.BRIGHT_SUNLIGHT -> 1.16f
            ArEnvironmentCondition.OUTDOOR_OVERCAST -> 0.88f
            ArEnvironmentCondition.BRIGHT_INDOOR -> 0.94f
            ArEnvironmentCondition.INDOOR_ROOM -> 0.84f
            ArEnvironmentCondition.LOW_LIGHT -> 0.68f
            ArEnvironmentCondition.NIGHT_DIM -> 0.55f
        }
        val adjMain = FloatArray(3) { c ->
            val tintFactor = 0.72f + 0.28f * telemetry.awbLightTintRgb[c]
            (sceneFused.mainLightIntensity[c] * directConditionGain * tintFactor).coerceIn(0.08f, 2.55f)
        }
        val adjSh = FloatArray(27) { i ->
            val c = i % 3
            val tintFactor = 0.72f + 0.28f * telemetry.awbLightTintRgb[c]
            sceneFused.sphericalHarmonics[i] * telemetry.ambientFillScale * tintFactor
        }

        val adjustedShadowStrength = (sceneFused.shadowStrength * telemetry.shadowContrastScale)
            .coerceIn(0.16f, 0.74f)

        val nextLabel = when {
            base.modeLabel == ArLightModeLabel.ENVIRONMENTAL_HDR -> ArLightModeLabel.SCENE_AND_HDR
            sceneFused.modeLabel == ArLightModeLabel.SCENE_AND_HDR -> ArLightModeLabel.SCENE_AND_HDR
            else -> ArLightModeLabel.AMBIENT_FALLBACK
        }

        return sceneFused.copy(
            mainLightIntensity = adjMain,
            sphericalHarmonics = adjSh,
            ambientColorCorrection = blendedColor,
            sceneExposure = calibratedExposure,
            shadowStrength = adjustedShadowStrength,
            specularScale = telemetry.specularScale,
            ambientFillScale = telemetry.ambientFillScale,
            environmentCondition = telemetry.condition,
            cameraTelemetry = telemetry,
            modeLabel = nextLabel,
            isEstimateValid = true
        )
    }

    /**
     * Applies user light overrides (Manual azimuth/elevation or exposure/shadow tuning from the Light tab).
     */
    fun applyUserSettings(
        base: ArEnvironmentLighting,
        settings: ArLightSettings
    ): ArEnvironmentLighting {
        val dir = if (settings.autoDetectLight) {
            base.mainLightDirection
        } else {
            azimuthElevationDegToDirection(settings.manualAzimuthDeg, settings.manualElevationDeg)
        }
        val (sx, sz) = computeShadowOffset(dir)
        val (az, el) = directionToAzimuthElevationDeg(dir)
        val exp = (base.sceneExposure * settings.exposureMultiplier).coerceIn(0.32f, 1.85f)
        val shadowStr = (base.shadowStrength * settings.shadowStrengthScale).coerceIn(0.0f, 0.82f)

        return base.copy(
            mainLightDirection = dir,
            sceneExposure = exp,
            shadowOffsetX = sx,
            shadowOffsetZ = sz,
            shadowStrength = shadowStr,
            lightAzimuthDeg = az,
            lightElevationDeg = el,
            modeLabel = if (settings.autoDetectLight) base.modeLabel else ArLightModeLabel.MANUAL_LIGHT
        )
    }

    /**
     * Exponential Moving Average (EMA) smoothing across consecutive AR frames so lighting follows
     * real-world room and sunlight shifts gracefully without harsh flicker.
     */
    fun smooth(
        previous: ArEnvironmentLighting,
        target: ArEnvironmentLighting,
        alpha: Float = 0.16f
    ): ArEnvironmentLighting {
        val t = alpha.coerceIn(0.02f, 1.0f)
        val inv = 1f - t

        val dir = normalizeLightDirection(
            previous.mainLightDirection[0] * inv + target.mainLightDirection[0] * t,
            previous.mainLightDirection[1] * inv + target.mainLightDirection[1] * t,
            previous.mainLightDirection[2] * inv + target.mainLightDirection[2] * t
        )
        val mainInt = FloatArray(3) { i ->
            previous.mainLightIntensity[i] * inv + target.mainLightIntensity[i] * t
        }
        val sh = FloatArray(27) { i ->
            previous.sphericalHarmonics[i] * inv + target.sphericalHarmonics[i] * t
        }
        val ambColor = FloatArray(3) { i ->
            previous.ambientColorCorrection[i] * inv + target.ambientColorCorrection[i] * t
        }
        val floorColor = FloatArray(3) { i ->
            previous.floorBounceColor[i] * inv + target.floorBounceColor[i] * t
        }
        val sceneExp = previous.sceneExposure * inv + target.sceneExposure * t
        val ambInt = previous.ambientIntensity * inv + target.ambientIntensity * t
        val sx = previous.shadowOffsetX * inv + target.shadowOffsetX * t
        val sz = previous.shadowOffsetZ * inv + target.shadowOffsetZ * t
        val shStrength = previous.shadowStrength * inv + target.shadowStrength * t
        val specScale = previous.specularScale * inv + target.specularScale * t
        val ambFillScale = previous.ambientFillScale * inv + target.ambientFillScale * t
        val (az, el) = directionToAzimuthElevationDeg(dir)

        return ArEnvironmentLighting(
            mainLightDirection = dir,
            mainLightIntensity = mainInt,
            sphericalHarmonics = sh,
            ambientColorCorrection = ambColor,
            floorBounceColor = floorColor,
            sceneExposure = sceneExp,
            ambientIntensity = ambInt,
            shadowOffsetX = sx,
            shadowOffsetZ = sz,
            shadowStrength = shStrength,
            lightAzimuthDeg = az,
            lightElevationDeg = el,
            modeLabel = target.modeLabel,
            isEstimateValid = target.isEstimateValid,
            specularScale = specScale,
            ambientFillScale = ambFillScale,
            environmentCondition = target.environmentCondition ?: previous.environmentCondition,
            cameraTelemetry = target.cameraTelemetry ?: previous.cameraTelemetry
        )
    }

    /**
     * Converts azimuth (0..360 deg around world Y) and elevation (15..88 deg above horizon)
     * into a normalized world-space unit direction vector pointing toward the light source.
     */
    fun azimuthElevationDegToDirection(azimuthDeg: Float, elevationDeg: Float): FloatArray {
        val elRad = Math.toRadians(elevationDeg.coerceIn(15f, 88f).toDouble())
        val azRad = Math.toRadians(azimuthDeg.toDouble())
        val cosEl = cos(elRad).toFloat()
        val y = sin(elRad).toFloat()
        val x = cosEl * sin(azRad).toFloat()
        val z = cosEl * cos(azRad).toFloat()
        return normalizeLightDirection(x, y, z)
    }

    fun directionToAzimuthElevationDeg(dir: FloatArray): Pair<Float, Float> {
        val x = dir.getOrElse(0) { 0f }
        val y = dir.getOrElse(1) { 1f }.coerceIn(-1f, 1f)
        val z = dir.getOrElse(2) { 0f }
        val el = Math.toDegrees(asin(y.toDouble())).toFloat().coerceIn(0f, 90f)
        val rawAz = Math.toDegrees(atan2(x.toDouble(), z.toDouble())).toFloat()
        val az = ((rawAz % 360f) + 360f) % 360f
        return az to el
    }

    /**
     * Computes an orthographic light-space View-Projection matrix tightly fitted around the
     * world-space bounding sphere of the placed model for crisp real-time depth shadow mapping.
     */
    fun computeLightViewProjMatrix(
        bounds: BoundingBox3D,
        worldMat: FloatArray,
        lightDir: FloatArray
    ): FloatArray {
        val cx = bounds.center.x.toFloat()
        val cy = bounds.center.y.toFloat()
        val cz = bounds.center.z.toFloat()

        // Transform model center to AR world space
        val worldCenter = FloatArray(4)
        Matrix.multiplyMV(worldCenter, 0, worldMat, 0, floatArrayOf(cx, cy, cz, 1f), 0)

        // Extract uniform scale from worldMat first column length
        val scale = sqrt(
            worldMat[0] * worldMat[0] + worldMat[1] * worldMat[1] + worldMat[2] * worldMat[2]
        ).coerceAtLeast(0.001f)
        val radius = max((bounds.diagonal * 0.5).toFloat() * scale, 0.08f)

        val lDir = normalizeLightDirection(
            lightDir.getOrElse(0) { 0.35f },
            lightDir.getOrElse(1) { 0.85f },
            lightDir.getOrElse(2) { 0.40f }
        )

        val eyeDist = radius * 2.8f
        val eyeX = worldCenter[0] + lDir[0] * eyeDist
        val eyeY = worldCenter[1] + lDir[1] * eyeDist
        val eyeZ = worldCenter[2] + lDir[2] * eyeDist

        // Pick an up vector never collinear with lightDir
        val useAltUp = abs(lDir[1]) > 0.96f
        val upX = 0f
        val upY = if (useAltUp) 0f else 1f
        val upZ = if (useAltUp) 1f else 0f

        val lightView = FloatArray(16)
        Matrix.setLookAtM(
            lightView, 0,
            eyeX, eyeY, eyeZ,
            worldCenter[0], worldCenter[1], worldCenter[2],
            upX, upY, upZ
        )

        val orthoExtent = radius * 1.55f
        val lightProj = FloatArray(16)
        Matrix.orthoM(
            lightProj, 0,
            -orthoExtent, orthoExtent,
            -orthoExtent, orthoExtent,
            0.05f, radius * 6.2f
        )

        val lightVp = FloatArray(16)
        Matrix.multiplyMM(lightVp, 0, lightProj, 0, lightView, 0)
        return lightVp
    }

    /**
     * Normalizes an ARCore light direction vector while enforcing a minimum upward elevation
     * so directional shading and ground shadows remain physically plausible.
     */
    fun normalizeLightDirection(x: Float, y: Float, z: Float): FloatArray {
        val sx = if (x.isFinite()) x else 0.35f
        val sy = if (y.isFinite()) y.coerceAtLeast(0.22f) else 0.85f
        val sz = if (z.isFinite()) z else 0.40f
        val len = sqrt(sx * sx + sy * sy + sz * sz)
        return if (len < 1e-4f) {
            floatArrayOf(0.35f, 0.85f, 0.40f)
        } else {
            floatArrayOf(sx / len, sy / len, sz / len)
        }
    }

    /**
     * Projects the normalized world-space main light vector into a subtle horizontal ground-shadow
     * penumbra offset (clamped so very low sun angles don't clip outside the shadow quad).
     */
    fun computeShadowOffset(lightDir: FloatArray): Pair<Float, Float> {
        val lx = lightDir.getOrElse(0) { 0.35f }
        val ly = lightDir.getOrElse(1) { 0.85f }.coerceAtLeast(0.42f)
        val lz = lightDir.getOrElse(2) { 0.40f }
        val dx = (-lx / ly * 0.36f).coerceIn(-0.48f, 0.48f)
        val dz = (-lz / ly * 0.36f).coerceIn(-0.48f, 0.48f)
        return dx to dz
    }

    /**
     * Converts an AR world-space horizontal shadow offset (X, Z) into CAD model-local (X, Y)
     * footprint coordinates accounting for the user's AR yaw rotation and CAD Z-up -> AR Y-up transform.
     */
    fun worldShadowOffsetToModelLocal(
        worldOffsetX: Float,
        worldOffsetZ: Float,
        modelYawDeg: Float
    ): Pair<Float, Float> {
        val rad = Math.toRadians((-modelYawDeg).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val unrotatedArX = worldOffsetX * c + worldOffsetZ * s
        val unrotatedArZ = -worldOffsetX * s + worldOffsetZ * c
        return unrotatedArX to -unrotatedArZ
    }

    /**
     * Infers physically plausible PBR surface roughness, metallic factor, and base Fresnel F0
     * from CAD material color/alpha/texture attributes when explicit PBR maps are absent.
     */
    fun inferPbrMaterialParams(
        baseR: Float,
        baseG: Float,
        baseB: Float,
        alpha: Float,
        hasTexture: Boolean
    ): PbrMaterialHint {
        val r = baseR.coerceIn(0f, 1f)
        val g = baseG.coerceIn(0f, 1f)
        val b = baseB.coerceIn(0f, 1f)
        val a = alpha.coerceIn(0f, 1f)

        // Translucent glass / glazing
        if (a < 0.86f) {
            return PbrMaterialHint(roughness = 0.12f, metallic = 0.05f, reflectanceF0 = 0.08f)
        }

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val sat = if (maxC > 1e-4f) (maxC - minC) / maxC else 0f
        val luma = luminance(r, g, b)

        // Dark desaturated untextured structural frames (steel / anodized iron / dark aluminum)
        if (!hasTexture && luma < 0.32f && sat < 0.18f) {
            return PbrMaterialHint(roughness = 0.28f, metallic = 0.68f, reflectanceF0 = 0.06f)
        }

        // Bright neutral matte stone / concrete / plaster baseplates
        if (!hasTexture && luma > 0.72f && sat < 0.15f) {
            return PbrMaterialHint(roughness = 0.82f, metallic = 0.02f, reflectanceF0 = 0.03f)
        }

        // Textured natural surfaces (wood, masonry, concrete, fabric)
        if (hasTexture) {
            return PbrMaterialHint(roughness = 0.54f, metallic = 0.04f, reflectanceF0 = 0.04f)
        }

        // Warm matte/satin solids (wood color without texture, painted surfaces)
        return PbrMaterialHint(roughness = 0.50f, metallic = 0.06f, reflectanceF0 = 0.04f)
    }

    /**
     * Computes a wide ground-plane shadow-catcher quad beneath the model's CAD Z-up footprint
     * large enough to catch real projected shadows from tall pergolas/posts at low light angles.
     */
    fun computeGroundShadowQuadBounds(bounds: BoundingBox3D): GroundShadowQuadBounds? {
        if (!bounds.isValid) return null
        val halfX = max((bounds.size.x * 0.5).toFloat(), 0.04f)
        val halfY = max((bounds.size.y * 0.5).toFloat(), 0.04f)
        val height = max(bounds.size.z.toFloat(), 0.05f)
        // Expand enough for angled cast shadows (up to ~1.4x model height)
        val pad = max(max(halfX, halfY) * 1.15f, height * 1.35f) + 0.06f
        val cx = bounds.center.x.toFloat()
        val cy = bounds.center.y.toFloat()
        return GroundShadowQuadBounds(
            minX = bounds.min.x.toFloat() - pad,
            maxX = bounds.max.x.toFloat() + pad,
            minY = bounds.min.y.toFloat() - pad,
            maxY = bounds.max.y.toFloat() + pad,
            halfFootprintX = halfX,
            halfFootprintY = halfY,
            centerX = cx,
            centerY = cy,
            groundZ = bounds.min.z.toFloat() + 0.0015f
        )
    }

    /**
     * Checks if a raw ARCore Environmental HDR intensity + SH reading contains real non-zero data.
     */
    fun hasNonZeroHdrData(mainIntensity: FloatArray, sphericalHarmonics: FloatArray): Boolean {
        val mainSum = mainIntensity.take(3).sumOf { if (it.isFinite()) abs(it).toDouble() else 0.0 }
        val shSum = sphericalHarmonics.take(27).sumOf { if (it.isFinite()) abs(it).toDouble() else 0.0 }
        return (mainSum + shSum) > 1e-4
    }

    private fun compressHdrPeak(value: Float, softCap: Float): Float {
        if (value <= 1.1f) return value
        val excess = value - 1.1f
        val range = (softCap - 1.1f).coerceAtLeast(0.1f)
        return 1.1f + range * (excess / (excess + range))
    }

    private fun luminance(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b
}
