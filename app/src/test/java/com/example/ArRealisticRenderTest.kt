package com.example

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.model.CadDocument
import com.example.domain.model.CadLayer
import com.example.domain.model.ModelFormat
import com.example.domain.model.ModelStats
import com.example.domain.model.SnapType
import com.example.domain.model.UnitSystem
import com.example.ui.ar.ArAnchorOrigin
import com.example.ui.ar.ArHudOverlay
import com.example.ui.ar.ArLightEstimatorMath
import com.example.ui.ar.ArLightModeLabel
import com.example.ui.ar.ArModelTrackingDot
import com.example.ui.ar.ArTapActionMode
import com.example.ui.ar.ArTrackingMode
import com.example.ui.ar.ArTrackingStatus
import com.example.ui.ar.ObjectTrackingPhase
import com.example.ui.theme.IngeTrazoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.net.Uri
import androidx.compose.ui.test.performScrollTo
import com.example.ui.ar.ArCameraTelemetryAnalyzer
import com.example.ui.ar.ArCaptureMode
import com.example.ui.ar.ArCapturedMedia
import com.example.ui.ar.ArCoreRenderer
import com.example.ui.ar.ArEnvironmentCondition
import com.example.ui.ar.ArGlVideoRecorder
import com.example.ui.ar.ArHitCandidate
import com.example.ui.ar.ArHitKind
import com.example.ui.ar.ArHitPlacementPolicy
import com.example.ui.ar.ArLightSettings
import com.example.ui.ar.ArPlacementLockPolicy
import com.example.ui.ar.ArSceneLightAnalyzer
import com.example.ui.ar.ArVideoSizeCalculator

import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArRealisticRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildSimpleDoc(): CadDocument {
        val bounds = BoundingBox3D(Vec3(-1.0, -0.5, 0.0), Vec3(1.0, 0.5, 2.0))
        return CadDocument(
            documentKey = "ar_realistic_test_doc",
            title = "Pergola Frame",
            format = ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = listOf(CadLayer("Layer 0")),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = bounds,
            stats = ModelStats(0, 0, 0, 0, 0, 1, 0, 0)
        )
    }

    @Test
    fun `environmental hdr lighting parses direction, compresses highlights, scales spherical harmonics, and computes shadows`() {
        val rawSh = FloatArray(27).apply {
            this[0] = 1.8f; this[1] = 1.6f; this[2] = 1.4f
            this[3] = 0.4f; this[4] = 0.4f; this[5] = 0.5f
        }
        val brightSun = ArLightEstimatorMath.fromEnvironmentalHdr(
            rawDirection = floatArrayOf(0.8f, 0.6f, 0.0f),
            rawMainIntensity = floatArrayOf(6.0f, 5.5f, 5.0f),
            rawSphericalHarmonics = rawSh
        )
        assertEquals(ArLightModeLabel.ENVIRONMENTAL_HDR, brightSun.modeLabel)
        assertTrue(brightSun.isEstimateValid)

        val len = sqrt(
            brightSun.mainLightDirection[0] * brightSun.mainLightDirection[0] +
                brightSun.mainLightDirection[1] * brightSun.mainLightDirection[1] +
                brightSun.mainLightDirection[2] * brightSun.mainLightDirection[2]
        )
        assertEquals(1.0f, len, 1e-4f)
        assertTrue(brightSun.mainLightDirection[1] > 0.5f)
        assertTrue(brightSun.mainLightIntensity[0] > 1.5f)
        assertTrue(brightSun.mainLightIntensity[0] < 2.6f)
        assertEquals(1.8f * ArLightEstimatorMath.SPHERICAL_HARMONIC_FACTORS[0], brightSun.sphericalHarmonics[0], 1e-4f)
        assertTrue(brightSun.shadowOffsetX < -0.1f)

        val diffuseRoom = ArLightEstimatorMath.fromEnvironmentalHdr(
            rawDirection = floatArrayOf(0.0f, 1.0f, 0.0f),
            rawMainIntensity = floatArrayOf(0.15f, 0.15f, 0.15f),
            rawSphericalHarmonics = rawSh
        )
        assertTrue(brightSun.shadowStrength > diffuseRoom.shadowStrength)
    }

    @Test
    fun `camera scene light analyzer compares real room luminance, white balance, bright light side, and fuses with HDR`() {
        val cols = 12
        val rows = 9
        val n = cols * rows
        val dimR = FloatArray(n)
        val dimG = FloatArray(n)
        val dimB = FloatArray(n)
        val dimLuma = FloatArray(n)

        // Simulate a cool dim indoor kitchen lit from the top-left window/lamp
        for (ry in 0 until rows) {
            for (rx in 0 until cols) {
                val i = ry * cols + rx
                val isBrightSide = rx < 4 && ry < 4
                val base = if (isBrightSide) 0.78f else 0.30f
                // Slightly cooler indoor fluorescent cast (higher blue than red)
                dimR[i] = (base * 0.92f).coerceIn(0f, 1f)
                dimG[i] = (base * 0.98f).coerceIn(0f, 1f)
                dimB[i] = (base * 1.06f).coerceIn(0f, 1f)
                dimLuma[i] = 0.2126f * dimR[i] + 0.7152f * dimG[i] + 0.0722f * dimB[i]
            }
        }

        val sceneSample = ArSceneLightAnalyzer.analyzeSampledRgbGrid(
            cols = cols,
            rows = rows,
            gridR = dimR,
            gridG = dimG,
            gridB = dimB,
            gridLuma = dimLuma,
            cameraRotateVector = { v -> v }
        )

        // Dim indoor room should dial down exposure below 0.90
        assertTrue(sceneSample.calibratedExposure < 0.90f)
        // Cool white balance reflects higher blue than red
        assertTrue(sceneSample.whiteBalanceRgb[2] > sceneSample.whiteBalanceRgb[0])
        // Bright upper-left region produces a negative-X light direction hint with valid confidence
        assertNotNull(sceneSample.worldLightHintDir)
        assertTrue(sceneSample.worldLightHintDir!![0] < -0.15f)
        assertTrue(sceneSample.directionConfidence > 0.1f)

        // Fuse with HDR and verify mode upgrades to Scene + HDR and exposure adopts calibrated value
        val baseHdr = ArLightEstimatorMath.fromEnvironmentalHdr(
            rawDirection = floatArrayOf(0.0f, 1.0f, 0.0f),
            rawMainIntensity = floatArrayOf(0.45f, 0.45f, 0.45f),
            rawSphericalHarmonics = FloatArray(27).apply { this[0] = 0.8f; this[1] = 0.8f; this[2] = 0.8f }
        )
        val fused = ArLightEstimatorMath.fuseWithCameraScene(baseHdr, sceneSample)
        assertEquals(ArLightModeLabel.SCENE_AND_HDR, fused.modeLabel)
        assertEquals(sceneSample.calibratedExposure, fused.sceneExposure, 1e-4f)
        assertTrue(fused.mainLightDirection[0] < -0.05f)

        // Apply manual light override settings and confirm azimuth/elevation & label switch to Manual Light
        val manual = ArLightEstimatorMath.applyUserSettings(
            base = fused,
            settings = ArLightSettings(
                autoDetectLight = false,
                manualAzimuthDeg = 90f,
                manualElevationDeg = 45f,
                exposureMultiplier = 1.2f,
                shadowStrengthScale = 1.3f
            )
        )
        assertEquals(ArLightModeLabel.MANUAL_LIGHT, manual.modeLabel)
        assertEquals(90f, manual.lightAzimuthDeg, 1.5f)
        assertEquals(45f, manual.lightElevationDeg, 1.5f)
        assertTrue(manual.sceneExposure > fused.sceneExposure)
        assertTrue(manual.shadowStrength > fused.shadowStrength)
    }

    @Test
    fun `ambient intensity fallback, shadow map light matrix, and pbr material heuristics behave physically`() {
        val warmRoom = ArLightEstimatorMath.fromAmbientIntensity(
            pixelIntensity = 0.65f,
            colorCorrectionRgba = floatArrayOf(1.25f, 1.00f, 0.78f, 1.0f)
        )
        assertEquals(ArLightModeLabel.AMBIENT_FALLBACK, warmRoom.modeLabel)
        assertTrue(warmRoom.isEstimateValid)
        assertTrue(warmRoom.mainLightIntensity[0] > warmRoom.mainLightIntensity[2])

        val initial = ArLightEstimatorMath.defaultStudioLighting()
        var current = initial
        repeat(35) {
            current = ArLightEstimatorMath.smooth(current, warmRoom, alpha = 0.25f)
        }
        assertEquals(warmRoom.mainLightIntensity[0], current.mainLightIntensity[0], 0.02f)

        val bounds = BoundingBox3D(Vec3(-1.0, -0.5, 0.0), Vec3(1.0, 0.5, 2.0))
        val worldMat = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        val lightVp = ArLightEstimatorMath.computeLightViewProjMatrix(bounds, worldMat, floatArrayOf(0.4f, 0.8f, 0.4f))
        assertEquals(16, lightVp.size)
        assertTrue(lightVp.all { it.isFinite() })

        val quad = ArLightEstimatorMath.computeGroundShadowQuadBounds(bounds)
        assertNotNull(quad)
        assertTrue(quad!!.minX < -2.0f && quad.maxX > 2.0f)
        assertTrue(quad.groundZ > 0.0f && quad.groundZ < 0.01f)

        val glass = ArLightEstimatorMath.inferPbrMaterialParams(0.7f, 0.85f, 0.95f, alpha = 0.45f, hasTexture = false)
        val steel = ArLightEstimatorMath.inferPbrMaterialParams(0.18f, 0.19f, 0.20f, alpha = 1.0f, hasTexture = false)
        val concrete = ArLightEstimatorMath.inferPbrMaterialParams(0.92f, 0.92f, 0.90f, alpha = 1.0f, hasTexture = false)
        val wood = ArLightEstimatorMath.inferPbrMaterialParams(0.68f, 0.48f, 0.28f, alpha = 1.0f, hasTexture = true)

        assertTrue(glass.roughness < steel.roughness)
        assertTrue(steel.metallic > 0.6f)
        assertTrue(concrete.roughness > 0.75f)
        assertTrue(concrete.metallic < 0.05f)
        assertTrue(wood.roughness > steel.roughness)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `ar hud bottom dock starts minimized, opens one tabbed section at a time, syncs realistic render, and opens light tab`() {
        val doc = buildSimpleDoc()
        var realisticEnabled by mutableStateOf(false)
        var tapMode by mutableStateOf(ArTapActionMode.MOVE_IN_WORLD)
        var lightSettings by mutableStateOf(ArLightSettings())

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                ArHudOverlay(
                    document = doc,
                    unitSystem = UnitSystem.METERS,
                    precisionDecimals = 2,
                    trackingStatus = ArTrackingStatus(
                        planesCount = 2,
                        featurePointCount = 45,
                        isPlaced = true,
                        phase = ObjectTrackingPhase.PINNED_TO_OBJECT,
                        targetDescription = "Anchored to horizontal surface",
                        trackingDot = ArModelTrackingDot(Vec3.ZERO, SnapType.VERTEX, "Front-L Base"),
                        trackingDotScreenPos = Vec2(200.0, 320.0),
                        tapActionMode = tapMode,
                        lightModeLabel = if (lightSettings.autoDetectLight) {
                            ArLightModeLabel.SCENE_AND_HDR.label
                        } else {
                            ArLightModeLabel.MANUAL_LIGHT.label
                        },
                        isLightEstimateValid = true,
                        detectedLightAzimuthDeg = 52f,
                        detectedLightElevationDeg = 60f
                    ),
                    selectedScale = 1.0f,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = ArAnchorOrigin.MODEL_DOT,
                    tapActionMode = tapMode,
                    realisticRenderEnabled = realisticEnabled,
                    lightSettings = lightSettings,
                    trackingMode = ArTrackingMode.OBJECT_SURFACE,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onClearAnchor = {},
                    onSelectScale = {},
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = {},
                    onSelectTapActionMode = { tapMode = it },
                    onToggleRealisticRender = { realisticEnabled = it },
                    onUpdateLightSettings = { lightSettings = it },
                    onSelectTrackingMode = {},
                    onSelectTargetWidth = {},
                    onPinAtCenter = {},
                    onCaptureObjectTarget = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Dock starts minimized: slim tab strip visible, none of the expanded sections are obstructing the AR view
        composeRule.onNodeWithTag("ar_tab_mode").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_tab_style").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_realistic_render_chip").assertDoesNotExist()
        composeRule.onNodeWithTag("ar_scale_1_1").assertDoesNotExist()
        composeRule.onNodeWithTag("ar_light_section").assertDoesNotExist()
        // Move Dot badge stays hidden during normal viewing so it does not clutter the AR model
        composeRule.onNodeWithTag("ar_model_tracking_dot_badge").assertDoesNotExist()

        // Open Render tab -> shows Realistic chip, while Scale & Light stay hidden
        composeRule.onNodeWithTag("ar_tab_style").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_realistic_render_chip").assertIsDisplayed().assertIsNotSelected()
        composeRule.onNodeWithTag("ar_scale_1_1").assertDoesNotExist()
        composeRule.onNodeWithTag("ar_light_status_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("ar_model_tracking_dot_badge").assertDoesNotExist()

        // Open three-dot overflow menu & tap Realistic Render -> enables Realistic Render, syncs Render chip, shows Scene + HDR badge
        composeRule.onNodeWithTag("ar_more_menu_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_realistic_render_top_toggle").assertIsDisplayed().assertIsNotSelected().performClick()
        composeRule.waitForIdle()
        assertTrue(realisticEnabled)
        composeRule.onNodeWithTag("ar_realistic_render_chip").assertIsSelected()
        composeRule.onNodeWithTag("ar_light_status_badge").assertIsDisplayed()
        composeRule.onNodeWithText("Scene + HDR").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_model_tracking_dot_badge").assertDoesNotExist()

        // Tap the top Light badge -> switches dock directly to Light tab (hiding Render tab)
        composeRule.onNodeWithTag("ar_light_status_badge").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_light_section").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_realistic_render_chip").assertDoesNotExist()

        // Switch to Manual Light angle override inside Light tab
        composeRule.onNodeWithTag("ar_light_manual_chip").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(lightSettings.autoDetectLight)
        composeRule.onNodeWithText("Manual Light").assertIsDisplayed()

        // Tap active Light tab again -> minimizes dock back to slim bar
        composeRule.onNodeWithTag("ar_tab_light").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_light_section").assertDoesNotExist()

        // Re-open Render tab and turn Realistic Render OFF -> syncs overflow menu item and keeps Move Dot hidden
        composeRule.onNodeWithTag("ar_tab_style").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_realistic_render_chip").performClick()
        composeRule.waitForIdle()
        assertFalse(realisticEnabled)
        composeRule.onNodeWithTag("ar_light_status_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("ar_model_tracking_dot_badge").assertDoesNotExist()

        // Verify overflow menu reflects OFF state
        composeRule.onNodeWithTag("ar_more_menu_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_realistic_render_top_toggle").assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun `placement lock policy blocks accidental taps once placed until explicitly unlocked or center-pinned`() {
        // First placement always allowed even if lock flag was false
        assertTrue(
            ArPlacementLockPolicy.shouldAllowWorldPlacement(
                isAlreadyPlaced = false,
                isPlacementLocked = false,
                forceExplicitPlacement = false,
                tapActionMode = ArTapActionMode.MOVE_IN_WORLD
            )
        )
        // Once placed & locked, normal screen tap is blocked so user can walk close safely
        assertFalse(
            ArPlacementLockPolicy.shouldAllowWorldPlacement(
                isAlreadyPlaced = true,
                isPlacementLocked = true,
                forceExplicitPlacement = false,
                tapActionMode = ArTapActionMode.MOVE_IN_WORLD
            )
        )
        // Unlocking allows a deliberate tap to reposition
        assertTrue(
            ArPlacementLockPolicy.shouldAllowWorldPlacement(
                isAlreadyPlaced = true,
                isPlacementLocked = false,
                forceExplicitPlacement = false,
                tapActionMode = ArTapActionMode.MOVE_IN_WORLD
            )
        )
        // Explicit Stick-at-Center button still works intentionally
        assertTrue(
            ArPlacementLockPolicy.shouldAllowWorldPlacement(
                isAlreadyPlaced = true,
                isPlacementLocked = true,
                forceExplicitPlacement = true,
                tapActionMode = ArTapActionMode.MOVE_IN_WORLD
            )
        )
        // Picking a tracking dot on model never teleports the world anchor
        assertFalse(
            ArPlacementLockPolicy.shouldAllowWorldPlacement(
                isAlreadyPlaced = true,
                isPlacementLocked = false,
                forceExplicitPlacement = true,
                tapActionMode = ArTapActionMode.PICK_DOT_ON_MODEL
            )
        )
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `ar hud removes duplicate lock pill under info box and manages lock via mode tab chip`() {
        val doc = buildSimpleDoc()
        var placementLocked by mutableStateOf(true)

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                ArHudOverlay(
                    document = doc,
                    unitSystem = UnitSystem.METERS,
                    precisionDecimals = 2,
                    trackingStatus = ArTrackingStatus(
                        planesCount = 2,
                        featurePointCount = 40,
                        isPlaced = true,
                        phase = ObjectTrackingPhase.PINNED_TO_OBJECT,
                        targetDescription = "Locked · Anchored to horizontal surface",
                        isPlacementLocked = placementLocked
                    ),
                    selectedScale = 1.0f,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = ArAnchorOrigin.MODEL_DOT,
                    placementLocked = placementLocked,
                    trackingMode = ArTrackingMode.OBJECT_SURFACE,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onClearAnchor = {},
                    onSelectScale = {},
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = {},
                    onTogglePlacementLock = { placementLocked = it },
                    onSelectTrackingMode = {},
                    onSelectTargetWidth = {},
                    onPinAtCenter = {},
                    onCaptureObjectTarget = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Duplicate lock pill under info box is removed
        composeRule.onNodeWithTag("ar_placement_lock_toggle").assertDoesNotExist()

        // Open Mode tab -> Lock chip toggles unlock & re-lock cleanly
        composeRule.onNodeWithTag("ar_tab_mode").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_mode_lock_toggle").performScrollTo().assertIsSelected().performClick()
        composeRule.waitForIdle()
        assertFalse(placementLocked)
        composeRule.onNodeWithTag("ar_mode_lock_toggle").assertIsNotSelected().performClick()
        composeRule.waitForIdle()
        assertTrue(placementLocked)
        composeRule.onNodeWithTag("ar_mode_lock_toggle").assertIsSelected()
    }

    @Test
    fun `camera hardware telemetry computes EV100, Kelvin, environment condition, and fuses into realistic lighting`() {
        // 1. Bright outdoor sunlight: ISO 50, 1/2000s, f/1.8, neutral daylight AWB gains (rGain ≈ bGain)
        val brightSun = ArCameraTelemetryAnalyzer.analyze(
            iso = 50,
            exposureTimeNs = 500_000L,
            apertureFNumber = 1.8f,
            colorCorrectionGains = floatArrayOf(1.75f, 1.0f, 1.0f, 1.75f),
            sceneFlickerHz = 0
        )
        assertNotNull(brightSun)
        assertEquals(ArEnvironmentCondition.BRIGHT_SUNLIGHT, brightSun!!.condition)
        assertTrue(brightSun.ev100 > 12.5f)
        assertTrue(brightSun.colorTemperatureKelvin in 5000..6000)
        assertEquals("1/2000s", brightSun.formatShutterSpeed())

        // 2. Warm indoor room with 50Hz artificial flicker: ISO 320, 1/30s, f/1.8, high blue gain (warm light)
        val warmIndoor = ArCameraTelemetryAnalyzer.analyze(
            iso = 320,
            exposureTimeNs = 33_333_333L,
            apertureFNumber = 1.8f,
            colorCorrectionGains = floatArrayOf(1.25f, 1.0f, 1.0f, 2.50f),
            sceneFlickerHz = 50
        )
        assertNotNull(warmIndoor)
        assertEquals(ArEnvironmentCondition.INDOOR_ROOM, warmIndoor!!.condition)
        assertTrue(warmIndoor.colorTemperatureKelvin < 3600)
        // Warm indoor tint should favor red over blue
        assertTrue(warmIndoor.awbLightTintRgb[0] > warmIndoor.awbLightTintRgb[2])

        // 3. Dim high-ISO night room with 200% post-raw boost: ISO 1600 * 200% = 3200, 1/15s
        val dimNight = ArCameraTelemetryAnalyzer.analyze(
            iso = 1600,
            postRawBoostPercent = 200,
            exposureTimeNs = 66_666_666L,
            apertureFNumber = 1.8f
        )
        assertNotNull(dimNight)
        assertEquals(3200, dimNight!!.effectiveIso)
        assertEquals(ArEnvironmentCondition.NIGHT_DIM, dimNight.condition)
        assertTrue(dimNight.recommendedExposure < warmIndoor.recommendedExposure)
        assertTrue(warmIndoor.recommendedExposure < brightSun.recommendedExposure)
        assertTrue(dimNight.specularScale < brightSun.specularScale)

        // 4. Fuse bright sun vs dim night telemetry with ARCore HDR base and verify physical adaptation
        val baseHdr = ArLightEstimatorMath.fromEnvironmentalHdr(
            rawDirection = floatArrayOf(0.4f, 0.85f, 0.3f),
            rawMainIntensity = floatArrayOf(1.0f, 1.0f, 1.0f),
            rawSphericalHarmonics = FloatArray(27).apply { this[0] = 0.9f; this[1] = 0.9f; this[2] = 0.9f }
        )
        val sunFused = ArLightEstimatorMath.fuseWithCameraTelemetryAndScene(baseHdr, brightSun, null)
        val nightFused = ArLightEstimatorMath.fuseWithCameraTelemetryAndScene(baseHdr, dimNight, null)
        assertEquals(ArEnvironmentCondition.BRIGHT_SUNLIGHT, sunFused.environmentCondition)
        assertEquals(ArEnvironmentCondition.NIGHT_DIM, nightFused.environmentCondition)
        assertTrue(sunFused.sceneExposure > nightFused.sceneExposure)
        assertTrue(sunFused.shadowStrength > nightFused.shadowStrength)
        assertTrue(sunFused.specularScale > nightFused.specularScale)
    }

    @Test
    fun `arcore glsl es 300 shaders declare explicit int precision and have zero cross-stage uniform mismatches`() {
        fun extractUniformNames(shader: String): Set<String> {
            val regex = Regex("""uniform\s+(?:(?:highp|mediump|lowp)\s+)?\w+\s+(\w+)""")
            return regex.findAll(shader).map { it.groupValues[1] }.toSet()
        }

        val pairs = listOf(
            "MODEL" to (ArCoreRenderer.MODEL_VERTEX to ArCoreRenderer.MODEL_FRAGMENT),
            "SHADOW" to (ArCoreRenderer.SHADOW_VERTEX to ArCoreRenderer.SHADOW_FRAGMENT),
            "BASIC_MODEL" to (ArCoreRenderer.BASIC_MODEL_VERTEX to ArCoreRenderer.BASIC_MODEL_FRAGMENT)
        )

        for ((name, pair) in pairs) {
            val (vs, fs) = pair
            assertTrue("$name VS must declare precision highp int", vs.contains("precision highp int;"))
            assertTrue("$name FS must declare precision highp int", fs.contains("precision highp int;"))
            val sharedUniforms = extractUniformNames(vs).intersect(extractUniformNames(fs))
            assertTrue(
                "$name VS and FS must not share uniforms that risk GLSL ES 3.00 link mismatch: $sharedUniforms",
                sharedUniforms.isEmpty()
            )
        }

        assertTrue(ArCoreRenderer.MODEL_FRAGMENT.contains("uniform highp sampler2D uShadowMap;"))
        assertTrue(ArCoreRenderer.MODEL_FRAGMENT.contains("uniform highp samplerCube uEnvCubemap;"))
        assertTrue(ArCoreRenderer.SHADOW_FRAGMENT.contains("uniform highp sampler2D uShadowMap;"))
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `ar light tab displays live environment condition and camera ISO telemetry readout`() {
        val doc = buildSimpleDoc()
        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                ArHudOverlay(
                    document = doc,
                    unitSystem = UnitSystem.METERS,
                    precisionDecimals = 2,
                    trackingStatus = ArTrackingStatus(
                        planesCount = 1,
                        featurePointCount = 30,
                        isPlaced = true,
                        lightModeLabel = ArLightModeLabel.SCENE_AND_HDR.label,
                        isLightEstimateValid = true,
                        environmentConditionLabel = ArEnvironmentCondition.INDOOR_ROOM.label,
                        cameraIso = 400,
                        cameraShutterLabel = "1/30s",
                        cameraEv100 = 5.4f,
                        cameraColorTempK = 3600,
                        cameraTelemetrySummary = "ISO 400 · 1/30s · f/1.8 · EV 5.4 · 3600K"
                    ),
                    selectedScale = 1.0f,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = ArAnchorOrigin.MODEL_DOT,
                    realisticRenderEnabled = true,
                    trackingMode = ArTrackingMode.OBJECT_SURFACE,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onClearAnchor = {},
                    onSelectScale = {},
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = {},
                    onSelectTrackingMode = {},
                    onSelectTargetWidth = {},
                    onPinAtCenter = {},
                    onCaptureObjectTarget = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ar_tab_light").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_light_telemetry_readout").assertIsDisplayed()
        composeRule.onNodeWithText("Indoor Room · ISO 400 · 1/30s · f/1.8 · EV 5.4 · 3600K").assertIsDisplayed()
    }

    @Test
    fun `ar hit placement policy prioritizes metric planes over distant points and refines approximate first placements`() {
        // 1. Tapping near a newly detected floor plane prefers the metric plane extension over a distant background feature point
        val hits = listOf(
            ArHitCandidate("far_wall_pt", ArHitKind.FEATURE_POINT, distanceMeters = 6.2f),
            ArHitCandidate("back_plane", ArHitKind.PLANE_IN_POLYGON, distanceMeters = 1.0f, isFrontFacing = false),
            ArHitCandidate("subsumed_plane", ArHitKind.PLANE_IN_POLYGON, distanceMeters = 1.1f, isSubsumed = true),
            ArHitCandidate("floor_ext", ArHitKind.PLANE_EXTENDED, distanceMeters = 1.6f, planeEdgeDistanceMeters = 0.8f),
            ArHitCandidate("mid_feature_pt", ArHitKind.FEATURE_POINT, distanceMeters = 2.4f)
        )
        val best = ArHitPlacementPolicy.selectBestHit(hits)
        assertNotNull(best)
        assertEquals("floor_ext", best!!.payload)
        assertTrue(best.isExactMetricSurface)

        // 2. Plane inside polygon beats plane extents and depth point
        val polyVsDepth = listOf(
            ArHitCandidate("depth_hit", ArHitKind.DEPTH_POINT, distanceMeters = 1.4f),
            ArHitCandidate("plane_extents", ArHitKind.PLANE_IN_EXTENTS, distanceMeters = 1.5f),
            ArHitCandidate("plane_poly", ArHitKind.PLANE_IN_POLYGON, distanceMeters = 1.55f)
        )
        assertEquals("plane_poly", ArHitPlacementPolicy.selectBestHit(polyVsDepth)?.payload)

        // 3. Clearly closer 3D depth object in front of a far extended floor wins (e.g. placing on a table)
        val tableVsFarFloor = listOf(
            ArHitCandidate("far_floor_ext", ArHitKind.PLANE_EXTENDED, distanceMeters = 3.2f, planeEdgeDistanceMeters = 1.4f),
            ArHitCandidate("close_table_depth", ArHitKind.DEPTH_POINT, distanceMeters = 1.1f)
        )
        assertEquals("close_table_depth", ArHitPlacementPolicy.selectBestHit(tableVsFarFloor)?.payload)

        // 4. Front-facing plane normal check rejects underside/back-facing plane hits
        assertTrue(
            ArHitPlacementPolicy.isPlaneFrontFacing(
                planeNormalX = 0f, planeNormalY = 1f, planeNormalZ = 0f,
                hitX = 0f, hitY = 0f, hitZ = -1.5f,
                cameraX = 0f, cameraY = 1.4f, cameraZ = 0f
            )
        )
        assertFalse(
            ArHitPlacementPolicy.isPlaneFrontFacing(
                planeNormalX = 0f, planeNormalY = 1f, planeNormalZ = 0f,
                hitX = 0f, hitY = 2.0f, hitZ = -1.5f,
                cameraX = 0f, cameraY = 1.0f, cameraZ = 0f
            )
        )

        // 5. Approximate initial anchor placed too far (smaller apparent scale) triggers automatic refinement onto metric plane
        assertTrue(
            ArHitPlacementPolicy.shouldRefineApproximateAnchor(
                currentDistanceMeters = 3.4f,
                candidatePlaneDistanceMeters = 1.5f,
                heightDeltaMeters = -0.25f,
                wasInstantPlacement = false
            )
        )
        assertTrue(
            ArHitPlacementPolicy.shouldRefineApproximateAnchor(
                currentDistanceMeters = 2.0f,
                candidatePlaneDistanceMeters = 2.02f,
                heightDeltaMeters = 0.01f,
                wasInstantPlacement = true
            )
        )
        assertFalse(
            ArHitPlacementPolicy.shouldRefineApproximateAnchor(
                currentDistanceMeters = 1.50f,
                candidatePlaneDistanceMeters = 1.52f,
                heightDeltaMeters = 0.005f,
                wasInstantPlacement = false
            )
        )
    }

    @Test
    fun `ar video size calculator aligns dimensions to multiples of 16 and video blit shaders pass ES 300 checks`() {
        val portrait = ArVideoSizeCalculator.computeEncoderSize(1080, 2400)
        assertEquals(0, portrait.width % 16)
        assertEquals(0, portrait.height % 16)
        assertEquals(1280, portrait.height)
        assertTrue(portrait.width in 560..592)

        val landscape = ArVideoSizeCalculator.computeEncoderSize(2400, 1080)
        assertEquals(0, landscape.width % 16)
        assertEquals(0, landscape.height % 16)
        assertEquals(1280, landscape.width)

        assertEquals("00:00", ArVideoSizeCalculator.formatElapsedDuration(0L))
        assertEquals("01:05", ArVideoSizeCalculator.formatElapsedDuration(65_400L))
        assertEquals("Pergola_Frame", ArVideoSizeCalculator.sanitizeFileSlug("Pergola Frame!"))

        assertTrue(ArGlVideoRecorder.COPY_VERTEX.contains("precision highp int;"))
        assertTrue(ArGlVideoRecorder.COPY_FRAGMENT.contains("precision highp int;"))
        assertTrue(ArGlVideoRecorder.COPY_FRAGMENT.contains("uniform mediump sampler2D uTex;"))
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `ar record tab switches between photo and video, shows live REC pill, and exposes view and share actions`() {
        val doc = buildSimpleDoc()
        var captureMode by mutableStateOf(ArCaptureMode.PHOTO)
        var isRecording by mutableStateOf(false)
        var lastMedia by mutableStateOf<ArCapturedMedia?>(null)
        var photoClicks = 0
        var viewClicks = 0
        var shareClicks = 0

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                ArHudOverlay(
                    document = doc,
                    unitSystem = UnitSystem.METERS,
                    precisionDecimals = 2,
                    trackingStatus = ArTrackingStatus(
                        planesCount = 2,
                        featurePointCount = 40,
                        isPlaced = true
                    ),
                    selectedScale = 1.0f,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = ArAnchorOrigin.MODEL_DOT,
                    trackingMode = ArTrackingMode.OBJECT_SURFACE,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    captureMode = captureMode,
                    isRecordingVideo = isRecording,
                    recordingElapsedMs = 5_200L,
                    lastCapturedMedia = lastMedia,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onClearAnchor = {},
                    onSelectScale = {},
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = {},
                    onSelectTrackingMode = {},
                    onSelectTargetWidth = {},
                    onPinAtCenter = {},
                    onCaptureObjectTarget = {},
                    onSelectCaptureMode = { captureMode = it },
                    onTakePhoto = {
                        photoClicks++
                        lastMedia = ArCapturedMedia(
                            uri = Uri.parse("content://media/external/images/media/42"),
                            isVideo = false,
                            displayName = "IngeTrazo_Pergola.jpg"
                        )
                    },
                    onToggleVideoRecording = {
                        if (isRecording) {
                            isRecording = false
                            lastMedia = ArCapturedMedia(
                                uri = Uri.parse("content://media/external/video/media/99"),
                                isVideo = true,
                                displayName = "IngeTrazo_Pergola.mp4",
                                durationMs = 5_200L
                            )
                        } else {
                            isRecording = true
                        }
                    },
                    onViewCapturedMedia = { viewClicks++ },
                    onShareCapturedMedia = { shareClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        // Open Record tab in AR toolbar menu
        composeRule.onNodeWithTag("ar_tab_record").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_record_section").assertIsDisplayed()

        // Take Photo in default Photo mode
        composeRule.onNodeWithTag("ar_record_mode_photo").assertIsSelected()
        composeRule.onNodeWithTag("ar_record_action_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, photoClicks)
        composeRule.onNodeWithTag("ar_record_last_capture_row").assertIsDisplayed()

        // Switch to Video mode and start recording
        composeRule.onNodeWithTag("ar_record_mode_video").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_record_mode_video").assertIsSelected()
        composeRule.onNodeWithTag("ar_record_action_button").performClick()
        composeRule.waitForIdle()
        assertTrue(isRecording)
        composeRule.onNodeWithTag("ar_recording_live_pill").assertIsDisplayed()
        composeRule.onNodeWithText("REC · 00:05").assertIsDisplayed()

        // Stop recording via live top REC pill -> saves video and shows View & Share actions
        composeRule.onNodeWithTag("ar_recording_live_stop_button").performClick()
        composeRule.waitForIdle()
        assertFalse(isRecording)
        composeRule.onNodeWithTag("ar_recording_live_pill").assertDoesNotExist()
        composeRule.onNodeWithTag("ar_record_view_button").performClick()
        composeRule.onNodeWithTag("ar_record_share_button").performClick()
        assertEquals(1, viewClicks)
        assertEquals(1, shareClicks)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `dark mode theme provides light content color at root and inside translucent HUD surfaces`() {
        var rootContentColor = Color.Unspecified
        var translucentSurfaceContentColor = Color.Unspecified
        var darkOnSurface = Color.Unspecified
        var darkOnSurfaceVariant = Color.Unspecified
        var darkMenuContainer = Color.Unspecified

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = true) {
                rootContentColor = LocalContentColor.current
                darkOnSurface = MaterialTheme.colorScheme.onSurface
                darkOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                darkMenuContainer = MaterialTheme.colorScheme.surfaceContainerHigh

                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                    translucentSurfaceContentColor = LocalContentColor.current
                }
            }
        }
        composeRule.waitForIdle()

        // Root LocalContentColor & translucent HUD Surface must be light (not default Color.Black)
        assertTrue(
            "Expected light root LocalContentColor in dark mode, got $rootContentColor",
            rootContentColor.luminance() > 0.7f
        )
        assertTrue(
            "Expected light contentColor inside translucent HUD Surface in dark mode, got $translucentSurfaceContentColor",
            translucentSurfaceContentColor.luminance() > 0.7f
        )
        assertTrue(
            "Expected light onSurface in dark mode, got $darkOnSurface",
            darkOnSurface.luminance() > 0.7f
        )
        assertTrue(
            "Expected clearly legible light onSurfaceVariant in dark mode, got $darkOnSurfaceVariant",
            darkOnSurfaceVariant.luminance() > 0.5f
        )
        assertTrue(
            "Expected dark surfaceContainerHigh for dropdown menus in dark mode, got $darkMenuContainer",
            darkMenuContainer.luminance() < 0.1f
        )
    }
}
