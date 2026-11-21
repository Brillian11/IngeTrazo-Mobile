package com.example.ui.ar

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Vec2
import com.example.domain.model.CadDocument
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

import com.example.domain.math.Vec3

import com.google.ar.core.ImageMetadata
import com.google.ar.core.LightEstimate

data class ArTrackingStatus(
    val planesCount: Int = 0,
    val featurePointCount: Int = 0,
    val isPlaced: Boolean = false,
    val phase: ObjectTrackingPhase = ObjectTrackingPhase.SCANNING,
    val targetDescription: String = "Point camera at a surface or 3D object",
    val anchorDistanceMeters: Float? = null,
    val trackingDot: ArModelTrackingDot? = null,
    val trackingDotScreenPos: Vec2? = null,
    val tapActionMode: ArTapActionMode = ArTapActionMode.MOVE_IN_WORLD,
    val lightModeLabel: String? = null,
    val isLightEstimateValid: Boolean = false,
    val detectedLightAzimuthDeg: Float = 45f,
    val detectedLightElevationDeg: Float = 56f,
    val sceneExposure: Float = 0.86f,
    val isPlacementLocked: Boolean = false,
    val environmentConditionLabel: String? = null,
    val cameraIso: Int? = null,
    val cameraShutterLabel: String? = null,
    val cameraEv100: Float? = null,
    val cameraColorTempK: Int? = null,
    val ambientLux: Float? = null,
    val cameraTelemetrySummary: String? = null,
    val isMetricScaleCalibrated: Boolean = true
)

/**
 * Pure placement-lock policy preventing accidental screen taps from teleporting an already-placed
 * AR model when the user moves closer to inspect it.
 */
object ArPlacementLockPolicy {
    fun shouldAllowWorldPlacement(
        isAlreadyPlaced: Boolean,
        isPlacementLocked: Boolean,
        forceExplicitPlacement: Boolean,
        tapActionMode: ArTapActionMode
    ): Boolean {
        if (tapActionMode == ArTapActionMode.PICK_DOT_ON_MODEL) return false
        if (!isAlreadyPlaced) return true
        if (forceExplicitPlacement) return true
        return !isPlacementLocked
    }
}

/**
 * Experimental ARCore OpenGL ES 3.0 renderer:
 * - Passthrough OES camera background
 * - Surface Plane / Depth / Feature-point 6-DoF hit testing
 * - Gravity-leveled vertical lock ("Always Level" vs surface slope)
 * - Model-axis alignment (X,Y,Z=0) with Z<0 foundation show/bury clipping
 * - Full embedded material colors, opacity, and GL_TEXTURE_2D textures
 * - Live AugmentedImageDatabase target capture & continuous 6-DoF object tracking ("Stick to Object")
 */
class ArCoreRenderer(
    private val document: CadDocument,
    initialScale: Float = ArScaleMath.recommendedInitialScale(document.bounds),
    initialShowFoundation: Boolean = document.bounds.max.z <= 0.005,
    initialTrackingDot: ArModelTrackingDot = ArTrackingDotMath.defaultTrackingDot(document),
    private val onTrackingUpdated: (ArTrackingStatus) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    @Volatile
    var displayRotation: Int = Surface.ROTATION_0
        set(value) {
            if (field != value) {
                field = value
                displayGeometryDirty = true
            }
        }

    @Volatile
    var cameraSensorOrientationDeg: Int = 90

    @Volatile
    var fallbackApertureFNumber: Float = 1.8f

    @Volatile
    var ambientLux: Float? = null

    @Volatile
    var viewportWidth: Int = 1

    @Volatile
    var viewportHeight: Int = 1

    @Volatile
    private var displayGeometryDirty: Boolean = true

    private var hasTransformedTexCoords: Boolean = false
    private var cameraTextureBoundSession: Session? = null

    @Volatile
    var modelScale: Float = ArScaleMath.clampScale(initialScale)
        set(value) {
            field = ArScaleMath.clampScale(value)
        }

    @Volatile
    var rotationDeg: Float = 0f

    @Volatile
    var verticalOffsetMeters: Float = 0f

    @Volatile
    var anchorOrigin: ArAnchorOrigin = ArAnchorOrigin.MODEL_DOT

    @Volatile
    var trackingDot: ArModelTrackingDot = initialTrackingDot

    @Volatile
    var tapActionMode: ArTapActionMode = ArTapActionMode.MOVE_IN_WORLD

    @Volatile
    var showTrackingDotGizmo: Boolean = false

    @Volatile
    var onModelDotPickedInAr: ((ArModelTrackingDot, ArAnchorOrigin, ArTapActionMode) -> Unit)? = null

    @Volatile
    private var worldAnchorShift = FloatArray(3)

    @Volatile
    private var lastMonoMvpMatrix: FloatArray? = null

    @Volatile
    private var lastWorldMatrix: FloatArray? = null

    @Volatile
    var alwaysLevel: Boolean = true

    @Volatile
    var showFoundation: Boolean = initialShowFoundation

    @Volatile
    var showTextures: Boolean = true

    @Volatile
    var realisticRenderEnabled: Boolean = false

    @Volatile
    var lightSettings: ArLightSettings = ArLightSettings()

    @Volatile
    var trackingMode: ArTrackingMode = ArTrackingMode.OBJECT_SURFACE

    @Volatile
    var vrModeEnabled: Boolean = false

    @Volatile
    var vrIpdMeters: Float = ArTransformMath.DEFAULT_IPD_METERS
        set(value) {
            field = value.coerceIn(0.04f, 0.09f)
        }

    @Volatile
    var vrLensZoom: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.72f, 1.0f)
        }

    @Volatile
    var anchor: Anchor? = null

    @Volatile
    private var liveTrackedPose: Pose? = null

    @Volatile
    var placementLocked: Boolean = false

    @Volatile
    var onPlacementLockChanged: ((Boolean) -> Unit)? = null

    val isModelPlaced: Boolean
        get() = anchor != null || liveTrackedPose != null

    @Volatile
    private var currentPhase: ObjectTrackingPhase = ObjectTrackingPhase.SCANNING

    @Volatile
    private var currentTargetLabel: String = "Point camera at an object or surface"

    @Volatile
    private var anchorNeedsPlaneRefinement: Boolean = false

    @Volatile
    private var anchorPlacedFromInstantPlacement: Boolean = false

    @Volatile
    private var anchorPlacedElapsedMs: Long = 0L

    @Volatile
    private var lastRefinementAttemptMs: Long = 0L

    private val videoRecorder = ArGlVideoRecorder()

    val isRecordingVideo: Boolean
        get() = videoRecorder.isRecording

    private var cameraTexId = 0
    private var bgProgram = 0
    private var uBgTex = 0

    private var modelProgram = 0
    private var uMvpModel = 0
    private var uModelMat = 0
    private var uLightDir = 0
    private var uUseTexture = 0
    private var uTextureSampler = 0
    private var uRealisticMode = 0
    private var uCameraWorldPos = 0
    private var uMainLightDir = 0
    private var uMainLightIntensity = 0
    private var uSphericalHarmonics = 0
    private var uAmbientColor = 0
    private var uHasEnvCubemap = 0
    private var uEnvCubemap = 0
    private var uBoundsMinZ = 0
    private var uBoundsHeight = 0
    private var uModelLightVp = 0
    private var uModelHasShadowMap = 0
    private var uModelShadowMap = 0
    private var uSceneExposure = 0
    private var uFloorBounceColor = 0
    private var uModelShadowStrength = 0
    private var uSpecularScale = 0
    private var uAmbientFillScale = 0

    private var basicModelProgram = 0
    private var uBasicMvp = 0
    private var uBasicModelMat = 0
    private var uBasicLightDir = 0
    private var uBasicUseTexture = 0
    private var uBasicTex = 0
    private var uBasicAmbientColor = 0
    private var uBasicExposure = 0

    private var shadowDepthProgram = 0
    private var uShadowLightMvp = 0
    private var shadowFboId = 0
    private var shadowDepthTexId = 0
    private var hasShadowMapFbo = false
    private var lastLightVpMatrix: FloatArray? = null
    private val identityMat4 = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var shadowProgram = 0
    private var uMvpShadow = 0
    private var uShadowModelMat = 0
    private var uShadowLightVp = 0
    private var uShadowHasMap = 0
    private var uShadowDepthTex = 0
    private var uShadowOffset = 0
    private var uShadowStrength = 0
    private var uShadowTint = 0

    private var lineProgram = 0
    private var uMvpLine = 0
    private var uLineColor = 0

    private var colorLineProgram = 0
    private var uMvpColorLine = 0

    private var pointProgram = 0
    private var uMvpPoint = 0

    private val textureIds = HashMap<String, Int>()
    private var dummyWhiteTexId = 0
    private var dummyDepthTexId = 0
    private var envCubemapTexId = 0
    private var hasValidEnvCubemap = false
    private var lastEnvCubemapTimestamp = Long.MIN_VALUE
    private var lastSceneSampleTimeMs = 0L
    private var lastTelemetrySampleTimeMs = 0L
    private var lastAnalyzedFrameTimestamp = Long.MIN_VALUE
    private var lastSceneLightSample: ArSceneLightSample? = null
    private var lastCameraTelemetry: ArCameraTelemetry? = null
    private var smoothedLighting: ArEnvironmentLighting = ArLightEstimatorMath.defaultStudioLighting()
    private val colorCorrectionScratch = FloatArray(4)

    private data class MeshBatch(
        val textureKey: String?,
        val buffer: FloatBuffer,
        val vertexCount: Int
    )

    private data class GeometrySet(
        val opaqueBatches: List<MeshBatch>,
        val translucentBatches: List<MeshBatch>,
        val edgeBuffer: FloatBuffer?,
        val edgeCount: Int,
        val boundsBoxBuffer: FloatBuffer?,
        val boundsBoxVertexCount: Int,
        val groundShadowBuffer: FloatBuffer?,
        val groundShadowVertexCount: Int,
        val bounds: BoundingBox3D
    )

    private var fullGeometry: GeometrySet? = null
    private var aboveGroundGeometry: GeometrySet? = null
    private var axisGizmoBuffer: FloatBuffer? = null
    private var axisGizmoVertexCount: Int = 0
    private var pointCloudBuffer: FloatBuffer? = null

    private val quadCoords = floatBufferOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    )
    private val quadTexCoords = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quadTexTransformed = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quadTexTransformedArray = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val vrBgTexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        quadTexCoords.put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
        quadTexCoords.position(0)
    }

    @Volatile
    private var pendingTapX: Float? = null

    @Volatile
    private var pendingTapY: Float? = null

    @Volatile
    private var pendingForcePlace: Boolean = false

    @Volatile
    private var pendingCaptureWidthMeters: Float? = null

    private var captureCallback: ((Boolean, String) -> Unit)? = null

    fun requestPlaceAt(x: Float, y: Float, forcePlace: Boolean = false) {
        pendingForcePlace = forcePlace
        pendingTapX = x
        pendingTapY = y
    }

    fun requestPlaceAtCenter() {
        pendingForcePlace = true
        pendingTapX = viewportWidth * 0.5f
        pendingTapY = viewportHeight * 0.5f
    }

    fun requestCaptureObjectTarget(
        physicalWidthMeters: Float,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        captureCallback = onResult
        pendingCaptureWidthMeters = physicalWidthMeters.coerceIn(0.04f, 5.0f)
        currentPhase = ObjectTrackingPhase.CAPTURING_TARGET
    }

    fun resetWorldAnchorShift() {
        worldAnchorShift = FloatArray(3)
    }

    fun startVideoRecording(
        outputFile: java.io.File,
        onError: (String) -> Unit
    ) {
        videoRecorder.requestStartRecording(outputFile, onError)
    }

    fun stopVideoRecording(
        onFinished: (file: java.io.File?, durationMs: Long, error: String?) -> Unit
    ) {
        videoRecorder.requestStopRecording(onFinished)
    }

    fun releaseRecorder() {
        videoRecorder.releaseGlResources()
    }

    fun clearAnchor() {
        anchor?.detach()
        anchor = null
        liveTrackedPose = null
        anchorNeedsPlaneRefinement = false
        anchorPlacedFromInstantPlacement = false
        anchorPlacedElapsedMs = 0L
        placementLocked = false
        onPlacementLockChanged?.invoke(false)
        worldAnchorShift = FloatArray(3)
        lastMonoMvpMatrix = null
        lastWorldMatrix = null
        currentPhase = if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
            ObjectTrackingPhase.TARGET_SEARCHING
        } else {
            ObjectTrackingPhase.SCANNING
        }
        currentTargetLabel = if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
            "Aim center frame at object & tap Capture Target"
        } else {
            "Tap any object or surface to pin model by tracking dot"
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        videoRecorder.onGlContextRecreated()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        cameraTexId = tex[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        cameraTextureBoundSession = null
        hasTransformedTexCoords = false
        displayGeometryDirty = true

        bgProgram = createProgram(BG_VERTEX, BG_FRAGMENT)
        if (bgProgram != 0) {
            uBgTex = GLES30.glGetUniformLocation(bgProgram, "sTex")
            GLES30.glUseProgram(bgProgram)
            GLES30.glUniform1i(uBgTex, 0)
        }

        modelProgram = createProgram(MODEL_VERTEX, MODEL_FRAGMENT)
        if (modelProgram != 0) {
            uMvpModel = GLES30.glGetUniformLocation(modelProgram, "uMvp")
            uModelMat = GLES30.glGetUniformLocation(modelProgram, "uModelMat")
            uLightDir = GLES30.glGetUniformLocation(modelProgram, "uLightDir")
            uUseTexture = GLES30.glGetUniformLocation(modelProgram, "uUseTexture")
            uTextureSampler = GLES30.glGetUniformLocation(modelProgram, "uTex")
            uRealisticMode = GLES30.glGetUniformLocation(modelProgram, "uRealisticMode")
            uCameraWorldPos = GLES30.glGetUniformLocation(modelProgram, "uCameraWorldPos")
            uMainLightDir = GLES30.glGetUniformLocation(modelProgram, "uMainLightDir")
            uMainLightIntensity = GLES30.glGetUniformLocation(modelProgram, "uMainLightIntensity")
            uSphericalHarmonics = GLES30.glGetUniformLocation(modelProgram, "uSphericalHarmonics")
            uAmbientColor = GLES30.glGetUniformLocation(modelProgram, "uAmbientColor")
            uHasEnvCubemap = GLES30.glGetUniformLocation(modelProgram, "uHasEnvCubemap")
            uEnvCubemap = GLES30.glGetUniformLocation(modelProgram, "uEnvCubemap")
            uBoundsMinZ = GLES30.glGetUniformLocation(modelProgram, "uBoundsMinZ")
            uBoundsHeight = GLES30.glGetUniformLocation(modelProgram, "uBoundsHeight")
            uModelLightVp = GLES30.glGetUniformLocation(modelProgram, "uLightVp")
            uModelHasShadowMap = GLES30.glGetUniformLocation(modelProgram, "uHasShadowMap")
            uModelShadowMap = GLES30.glGetUniformLocation(modelProgram, "uShadowMap")
            uSceneExposure = GLES30.glGetUniformLocation(modelProgram, "uSceneExposure")
            uFloorBounceColor = GLES30.glGetUniformLocation(modelProgram, "uFloorBounceColor")
            uModelShadowStrength = GLES30.glGetUniformLocation(modelProgram, "uShadowStrength")
            uSpecularScale = GLES30.glGetUniformLocation(modelProgram, "uSpecularScale")
            uAmbientFillScale = GLES30.glGetUniformLocation(modelProgram, "uAmbientFillScale")
            // Permanently assign distinct, non-overlapping texture units so sampler2D and samplerCube
            // never collide on unit 0 (ES 3.0 Spec §11.1.3.5 GL_INVALID_OPERATION).
            GLES30.glUseProgram(modelProgram)
            GLES30.glUniform1i(uTextureSampler, 1)
            GLES30.glUniform1i(uEnvCubemap, 2)
            GLES30.glUniform1i(uModelShadowMap, 3)
        }

        basicModelProgram = createProgram(BASIC_MODEL_VERTEX, BASIC_MODEL_FRAGMENT)
        if (basicModelProgram != 0) {
            uBasicMvp = GLES30.glGetUniformLocation(basicModelProgram, "uMvp")
            uBasicModelMat = GLES30.glGetUniformLocation(basicModelProgram, "uModelMat")
            uBasicLightDir = GLES30.glGetUniformLocation(basicModelProgram, "uLightDir")
            uBasicUseTexture = GLES30.glGetUniformLocation(basicModelProgram, "uUseTexture")
            uBasicTex = GLES30.glGetUniformLocation(basicModelProgram, "uTex")
            uBasicAmbientColor = GLES30.glGetUniformLocation(basicModelProgram, "uAmbientColor")
            uBasicExposure = GLES30.glGetUniformLocation(basicModelProgram, "uSceneExposure")
            GLES30.glUseProgram(basicModelProgram)
            GLES30.glUniform1i(uBasicTex, 1)
        }

        shadowDepthProgram = createProgram(SHADOW_DEPTH_VERTEX, SHADOW_DEPTH_FRAGMENT)
        if (shadowDepthProgram != 0) {
            uShadowLightMvp = GLES30.glGetUniformLocation(shadowDepthProgram, "uLightMvp")
        }

        shadowProgram = createProgram(SHADOW_VERTEX, SHADOW_FRAGMENT)
        if (shadowProgram != 0) {
            uMvpShadow = GLES30.glGetUniformLocation(shadowProgram, "uMvp")
            uShadowModelMat = GLES30.glGetUniformLocation(shadowProgram, "uModelMat")
            uShadowLightVp = GLES30.glGetUniformLocation(shadowProgram, "uLightVp")
            uShadowHasMap = GLES30.glGetUniformLocation(shadowProgram, "uHasShadowMap")
            uShadowDepthTex = GLES30.glGetUniformLocation(shadowProgram, "uShadowMap")
            uShadowOffset = GLES30.glGetUniformLocation(shadowProgram, "uShadowOffset")
            uShadowStrength = GLES30.glGetUniformLocation(shadowProgram, "uShadowStrength")
            uShadowTint = GLES30.glGetUniformLocation(shadowProgram, "uShadowTint")
            GLES30.glUseProgram(shadowProgram)
            GLES30.glUniform1i(uShadowDepthTex, 3)
        }
        GLES30.glUseProgram(0)

        initFallbackTextures()
        initShadowMapFbo()

        lineProgram = createProgram(LINE_VERTEX, LINE_FRAGMENT)
        if (lineProgram != 0) {
            uMvpLine = GLES30.glGetUniformLocation(lineProgram, "uMvp")
            uLineColor = GLES30.glGetUniformLocation(lineProgram, "uColor")
        }

        colorLineProgram = createProgram(COLOR_LINE_VERTEX, COLOR_LINE_FRAGMENT)
        if (colorLineProgram != 0) {
            uMvpColorLine = GLES30.glGetUniformLocation(colorLineProgram, "uMvp")
        }

        pointProgram = createProgram(POINT_VERTEX, POINT_FRAGMENT)
        if (pointProgram != 0) {
            uMvpPoint = GLES30.glGetUniformLocation(pointProgram, "uMvp")
        }

        uploadTextures(document.textures)
        buildModelBuffers()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        displayGeometryDirty = true
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        session?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0.02f, 0.03f, 0.05f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val arSession = session ?: return

        try {
            // Ensure GL_TEXTURE0 is active before ARCore updates the external OES camera texture.
            // Only bind camera texture once per Session and only notify display geometry when changed.
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            if (cameraTextureBoundSession !== arSession) {
                arSession.setCameraTextureName(cameraTexId)
                cameraTextureBoundSession = arSession
                displayGeometryDirty = true
            }
            if (displayGeometryDirty) {
                arSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            }
            val frame: Frame = arSession.update()

            ensureTransformedBackgroundTexCoords(frame)
            val isVr = vrModeEnabled

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                val recBound = videoRecorder.beginFrame(viewportWidth, viewportHeight)
                try {
                    if (isVr) {
                        renderVrBackgroundOnly()
                    } else {
                        drawCameraBackground(null)
                    }
                } finally {
                    if (recBound) {
                        videoRecorder.endFrameAndEncode(viewportWidth, viewportHeight, frame.timestamp)
                    }
                }
                onTrackingUpdated(
                    ArTrackingStatus(
                        planesCount = 0,
                        featurePointCount = 0,
                        isPlaced = anchor != null || liveTrackedPose != null,
                        phase = ObjectTrackingPhase.SCANNING,
                        targetDescription = "Initializing camera tracking — move slowly",
                        isMetricScaleCalibrated = !anchorNeedsPlaneRefinement
                    )
                )
                return
            }

            // Handle live object target capture into AugmentedImageDatabase
            val captureWidth = pendingCaptureWidthMeters
            if (captureWidth != null) {
                pendingCaptureWidthMeters = null
                captureAndRegisterObjectTarget(arSession, frame, captureWidth)
            }

            // Handle tap / center reticle object placement OR picking a Move Tool dot directly on the 3D model
            val tx = pendingTapX
            val ty = pendingTapY
            val forcePlace = pendingForcePlace
            if (tx != null && ty != null) {
                pendingTapX = null
                pendingTapY = null
                pendingForcePlace = false
                val curMvp = lastMonoMvpMatrix
                val curWorld = lastWorldMatrix
                val curGeom = if (showFoundation) fullGeometry else (aboveGroundGeometry ?: fullGeometry)
                if (tapActionMode == ArTapActionMode.PICK_DOT_ON_MODEL && curMvp != null && curWorld != null && curGeom != null) {
                    val pickedDot = ArTrackingDotMath.pickDotOnRenderedArModel(
                        document = document,
                        mvpMatrix = curMvp,
                        screenX = tx,
                        screenY = ty,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        showFoundation = showFoundation
                    )
                    if (pickedDot != null) {
                        val oldAlign = ArTrackingDotMath.resolveAlignmentPoint(anchorOrigin, curGeom.bounds, trackingDot)
                        worldAnchorShift = ArTrackingDotMath.computeStationaryWorldShiftForNewDot(
                            oldAlign = oldAlign,
                            newAlign = pickedDot.point,
                            currentWorldMatrix = curWorld,
                            existingShift = worldAnchorShift
                        )
                        trackingDot = pickedDot
                        anchorOrigin = ArAnchorOrigin.MODEL_DOT
                        tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                        anchorNeedsPlaneRefinement = false
                        // Unlock for the single deliberate Move-in-World step (auto-locks right after placing)
                        placementLocked = false
                        onPlacementLockChanged?.invoke(false)
                        currentTargetLabel = "Move dot set on ${pickedDot.label} · Tap real world to move there"
                        onModelDotPickedInAr?.invoke(pickedDot, ArAnchorOrigin.MODEL_DOT, ArTapActionMode.MOVE_IN_WORLD)
                    } else {
                        currentTargetLabel = "Tap directly on a model corner, edge, or face to place dot"
                    }
                } else if (ArPlacementLockPolicy.shouldAllowWorldPlacement(
                        isAlreadyPlaced = anchor != null || liveTrackedPose != null,
                        isPlacementLocked = placementLocked,
                        forceExplicitPlacement = forcePlace,
                        tapActionMode = tapActionMode
                    )
                ) {
                    val chosenHit = selectBestObjectOrSurfaceHit(frame, camera.pose, tx, ty)
                    if (chosenHit != null) {
                        anchor?.detach()
                        anchor = chosenHit.payload.createAnchor()
                        liveTrackedPose = null
                        worldAnchorShift = FloatArray(3)
                        anchorNeedsPlaneRefinement = !chosenHit.isExactMetricSurface
                        anchorPlacedFromInstantPlacement = chosenHit.kind == ArHitKind.INSTANT_PLACEMENT
                        anchorPlacedElapsedMs = android.os.SystemClock.elapsedRealtime()
                        lastRefinementAttemptMs = anchorPlacedElapsedMs
                        placementLocked = true
                        onPlacementLockChanged?.invoke(true)
                        currentPhase = ObjectTrackingPhase.PINNED_TO_OBJECT
                        val dotInfo = if (anchorOrigin == ArAnchorOrigin.MODEL_DOT) {
                            " · Dot: ${trackingDot.label}"
                        } else {
                            " · Origin: ${anchorOrigin.label}"
                        }
                        val calibrationHint = if (chosenHit.isExactMetricSurface) {
                            chosenHit.label
                        } else {
                            "${chosenHit.label} (calibrating scale…)"
                        }
                        currentTargetLabel = "Locked · $calibrationHint$dotInfo"
                    }
                }
            }

            // Update continuous 6-DoF object tracker if an AugmentedImage is active
            if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
                updateVisualObjectTracking(frame)
            }

            val trackingPlanes = arSession.getAllTrackables(Plane::class.java)
                .count { it.trackingState == TrackingState.TRACKING }

            val viewMat = FloatArray(16)
            val projMat = FloatArray(16)
            camera.getViewMatrix(viewMat, 0)
            camera.getProjectionMatrix(projMat, 0, 0.05f, 350f)

            // If initial placement landed on an approximate non-plane hit before ARCore finished
            // detecting the real metric floor/surface plane, silently upgrade it once the plane appears
            // so the model renders at true real-world scale without needing a manual move.
            if (trackingMode == ArTrackingMode.OBJECT_SURFACE &&
                anchorNeedsPlaneRefinement &&
                trackingPlanes > 0
            ) {
                tryRefineApproximateAnchorToMetricPlane(frame, camera.pose, viewMat, projMat)
            }

            val pointCount = uploadFeaturePointCloud(frame)

            val activePose: Pose? = when {
                trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER && liveTrackedPose != null -> liveTrackedPose
                anchor?.trackingState == TrackingState.TRACKING -> anchor?.pose
                else -> null
            }

            val anchorDist = activePose?.let { poseEuclideanDistance(camera.pose, it) }

            val geom = if (activePose != null) {
                if (showFoundation) fullGeometry else (aboveGroundGeometry ?: fullGeometry)
            } else {
                null
            }
            val worldMat = if (activePose != null && geom != null) {
                computeModelWorldMatrix(activePose, geom)
            } else {
                null
            }

            var projectedDotScreenPos: Vec2? = null
            val activeBoundsForDot = geom?.bounds ?: document.bounds
            val resolvedAlignPt = ArTrackingDotMath.resolveAlignmentPoint(
                anchorOrigin = anchorOrigin,
                bounds = activeBoundsForDot,
                trackingDot = trackingDot
            )
            val resolvedActiveDot = if (anchorOrigin == ArAnchorOrigin.MODEL_DOT) {
                trackingDot
            } else {
                ArModelTrackingDot(
                    point = resolvedAlignPt,
                    snapType = com.example.domain.model.SnapType.VERTEX,
                    label = anchorOrigin.label
                )
            }

            if (worldMat != null) {
                val mvMat = FloatArray(16)
                Matrix.multiplyMM(mvMat, 0, viewMat, 0, worldMat, 0)
                val monoMvp = FloatArray(16)
                Matrix.multiplyMM(monoMvp, 0, projMat, 0, mvMat, 0)
                lastMonoMvpMatrix = monoMvp
                lastWorldMatrix = worldMat
                projectedDotScreenPos = ArTrackingDotMath.projectModelPointToScreen(
                    point = resolvedAlignPt,
                    mvpMatrix = monoMvp,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight
                )
            } else {
                lastMonoMvpMatrix = null
                lastWorldMatrix = null
            }

            updateEnvironmentLighting(frame, camera)
            val currentLighting = ArLightEstimatorMath.applyUserSettings(smoothedLighting, lightSettings)
            val cameraPose = camera.pose
            val cameraWorldPos = floatArrayOf(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

            if (realisticRenderEnabled && hasShadowMapFbo && geom != null && worldMat != null) {
                renderShadowDepthPass(geom, worldMat, currentLighting)
            } else {
                lastLightVpMatrix = null
            }

            val telemetrySnapshot = currentLighting.cameraTelemetry ?: lastCameraTelemetry
            onTrackingUpdated(
                ArTrackingStatus(
                    planesCount = trackingPlanes,
                    featurePointCount = pointCount,
                    isPlaced = activePose != null,
                    phase = currentPhase,
                    targetDescription = currentTargetLabel,
                    anchorDistanceMeters = anchorDist,
                    trackingDot = resolvedActiveDot,
                    trackingDotScreenPos = projectedDotScreenPos,
                    tapActionMode = tapActionMode,
                    lightModeLabel = currentLighting.modeLabel.label,
                    isLightEstimateValid = currentLighting.isEstimateValid,
                    detectedLightAzimuthDeg = currentLighting.lightAzimuthDeg,
                    detectedLightElevationDeg = currentLighting.lightElevationDeg,
                    sceneExposure = currentLighting.sceneExposure,
                    isPlacementLocked = placementLocked,
                    environmentConditionLabel = (currentLighting.environmentCondition ?: telemetrySnapshot?.condition)?.label,
                    cameraIso = telemetrySnapshot?.effectiveIso,
                    cameraShutterLabel = telemetrySnapshot?.formatShutterSpeed(),
                    cameraEv100 = telemetrySnapshot?.ev100,
                    cameraColorTempK = telemetrySnapshot?.colorTemperatureKelvin,
                    ambientLux = telemetrySnapshot?.ambientLux,
                    cameraTelemetrySummary = telemetrySnapshot?.formatCompactSummary(),
                    isMetricScaleCalibrated = !anchorNeedsPlaneRefinement
                )
            )

            val recBound = videoRecorder.beginFrame(viewportWidth, viewportHeight)
            try {
                if (!isVr) {
                    drawCameraBackground(null)
                    renderEyeOverlay(
                        eyeViewMat = viewMat,
                        eyeProjMat = projMat,
                        cameraWorldPos = cameraWorldPos,
                        lighting = currentLighting,
                        pointCount = pointCount,
                        geom = geom,
                        worldMat = worldMat,
                        alignPoint = resolvedAlignPt
                    )
                } else {
                    val halfW = (viewportWidth / 2).coerceAtLeast(1)
                    val zoom = vrLensZoom.coerceIn(0.72f, 1.0f)
                    val ipd = vrIpdMeters

                    GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                    try {
                        for (eyeIdx in 0..1) {
                            val eyeSign = if (eyeIdx == 0) -1f else 1f
                            val slotX = if (eyeIdx == 0) 0 else halfW
                            val slotW = if (eyeIdx == 0) halfW else (viewportWidth - halfW).coerceAtLeast(1)
                            val eyeW = (slotW * zoom).toInt().coerceAtLeast(1)
                            val eyeH = (viewportHeight * zoom).toInt().coerceAtLeast(1)
                            val eyeX = slotX + (slotW - eyeW) / 2
                            val eyeY = (viewportHeight - eyeH) / 2
                            val eyeWidthRatio = slotW.toFloat() / viewportWidth.coerceAtLeast(1).toFloat()

                            GLES30.glScissor(eyeX, eyeY, eyeW, eyeH)
                            GLES30.glViewport(eyeX, eyeY, eyeW, eyeH)
                            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)

                            val eyeTexCoords = ArTransformMath.computeVrEyeBackgroundTexCoords(
                                fullTexCoords = quadTexTransformedArray,
                                eyeWidthRatio = eyeWidthRatio,
                                eyeSign = eyeSign,
                                stereoBgShiftFraction = 0.01f
                            )
                            drawCameraBackground(eyeTexCoords)

                            val eyeViewMat = ArTransformMath.buildVrEyeViewMatrix(viewMat, eyeSign, ipd)
                            val eyeProjMat = ArTransformMath.buildVrEyeProjMatrix(projMat, eyeWidthRatio, 1.0f)
                            renderEyeOverlay(
                                eyeViewMat = eyeViewMat,
                                eyeProjMat = eyeProjMat,
                                cameraWorldPos = cameraWorldPos,
                                lighting = currentLighting,
                                pointCount = pointCount,
                                geom = geom,
                                worldMat = worldMat,
                                alignPoint = resolvedAlignPt
                            )
                        }
                    } finally {
                        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
                    }
                }
            } finally {
                if (recBound) {
                    videoRecorder.endFrameAndEncode(viewportWidth, viewportHeight, frame.timestamp)
                }
            }
        } catch (_: Throwable) {
            // Gracefully absorb transient ARCore frame exceptions
        }
    }

    private fun initFallbackTextures() {
        val ids = IntArray(3)
        GLES30.glGenTextures(3, ids, 0)
        dummyWhiteTexId = ids[0]
        envCubemapTexId = ids[1]
        dummyDepthTexId = ids[2]

        val whitePixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            put(byteArrayOf(-1, -1, -1, -1))
            position(0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyWhiteTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, whitePixel
        )

        uploadFallbackCubemapFaces()

        whitePixel.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyDepthTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, whitePixel
        )

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun uploadFallbackCubemapFaces() {
        if (envCubemapTexId == 0) return
        val neutralPixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            put(byteArrayOf(0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte(), 0xFF.toByte()))
            position(0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, envCubemapTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        for (faceIdx in 0 until 6) {
            neutralPixel.position(0)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + faceIdx,
                0,
                GLES30.GL_RGBA8,
                1,
                1,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                neutralPixel
            )
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_CUBE_MAP)
        hasValidEnvCubemap = false
        lastEnvCubemapTimestamp = Long.MIN_VALUE
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun initShadowMapFbo() {
        try {
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                // Drain prior errors before probing shadow FBO
            }
            val fbos = IntArray(1)
            val depthTex = IntArray(1)
            GLES30.glGenFramebuffers(1, fbos, 0)
            GLES30.glGenTextures(1, depthTex, 0)
            shadowFboId = fbos[0]
            shadowDepthTexId = depthTex[0]

            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowDepthTexId)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_DEPTH_COMPONENT24,
                SHADOW_MAP_SIZE,
                SHADOW_MAP_SIZE,
                0,
                GLES30.GL_DEPTH_COMPONENT,
                GLES30.GL_UNSIGNED_INT,
                null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_NONE)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFboId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_TEXTURE_2D,
                shadowDepthTexId,
                0
            )
            GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
            GLES30.glReadBuffer(GLES30.GL_NONE)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            val err = GLES30.glGetError()
            hasShadowMapFbo = (status == GLES30.GL_FRAMEBUFFER_COMPLETE && err == GLES30.GL_NO_ERROR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyDepthTexId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        } catch (_: Throwable) {
            hasShadowMapFbo = false
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
    }

    private fun renderShadowDepthPass(
        geom: GeometrySet,
        worldMat: FloatArray,
        lighting: ArEnvironmentLighting
    ) {
        if (!hasShadowMapFbo || shadowFboId == 0 || shadowDepthProgram == 0 || geom.opaqueBatches.isEmpty()) {
            lastLightVpMatrix = null
            return
        }
        val lightVp = ArLightEstimatorMath.computeLightViewProjMatrix(
            bounds = geom.bounds,
            worldMat = worldMat,
            lightDir = lighting.mainLightDirection
        )
        val lightMvp = FloatArray(16)
        Matrix.multiplyMM(lightMvp, 0, lightVp, 0, worldMat, 0)

        try {
            // Unbind shadow depth texture from GL_TEXTURE3 before attaching/rendering into shadowFboId
            // to avoid GPU read/write feedback-loop hazards on tile-based mobile GPUs.
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyDepthTexId)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFboId)
            GLES30.glViewport(0, 0, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glColorMask(false, false, false, false)
            GLES30.glDepthMask(true)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(2.5f, 4.5f)

            GLES30.glUseProgram(shadowDepthProgram)
            GLES30.glUniformMatrix4fv(uShadowLightMvp, 1, false, lightMvp, 0)
            val stride = 12 * 4
            GLES30.glEnableVertexAttribArray(0)
            for (batch in geom.opaqueBatches) {
                batch.buffer.position(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, batch.buffer)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, batch.vertexCount)
            }
            GLES30.glDisableVertexAttribArray(0)
            lastLightVpMatrix = lightVp
        } finally {
            GLES30.glUseProgram(0)
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glColorMask(true, true, true, true)
            GLES30.glDepthMask(true)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
    }

    private fun extractCameraTelemetry(frame: Frame): ArCameraTelemetry? {
        var iso: Int? = null
        var postRawBoost: Int? = null
        var exposureTimeNs: Long? = null
        var aperture: Float? = null
        var awbGains: FloatArray? = null
        var neutralPoint: FloatArray? = null
        var flickerHz: Int? = null

        try {
            val meta = frame.imageMetadata
            iso = runCatching { meta.getInt(ImageMetadata.SENSOR_SENSITIVITY) }.getOrNull()
            postRawBoost = runCatching { meta.getInt(ImageMetadata.CONTROL_POST_RAW_SENSITIVITY_BOOST) }.getOrNull()
            exposureTimeNs = runCatching { meta.getLong(ImageMetadata.SENSOR_EXPOSURE_TIME) }.getOrNull()
            aperture = runCatching { meta.getFloat(ImageMetadata.LENS_APERTURE) }.getOrNull()
            awbGains = runCatching { meta.getFloatArray(ImageMetadata.COLOR_CORRECTION_GAINS) }.getOrNull()
            neutralPoint = runCatching {
                val rationals = meta.getRationalArray(ImageMetadata.SENSOR_NEUTRAL_COLOR_POINT)
                if (rationals != null && rationals.size >= 3) {
                    floatArrayOf(
                        rationals[0].toFloat(),
                        rationals[1].toFloat(),
                        rationals[2].toFloat()
                    )
                } else {
                    null
                }
            }.getOrNull()
            flickerHz = runCatching {
                when (meta.getInt(ImageMetadata.STATISTICS_SCENE_FLICKER)) {
                    1 -> 50
                    2 -> 60
                    else -> 0
                }
            }.getOrNull()
        } catch (_: Throwable) {
            // Device/frame does not expose ImageMetadata on this tick; still allow lux sensor fallback
        }

        return ArCameraTelemetryAnalyzer.analyze(
            iso = iso,
            postRawBoostPercent = postRawBoost,
            exposureTimeNs = exposureTimeNs,
            apertureFNumber = aperture,
            colorCorrectionGains = awbGains,
            neutralColorPoint = neutralPoint,
            sceneFlickerHz = flickerHz,
            ambientLux = ambientLux,
            fallbackApertureFNumber = fallbackApertureFNumber
        )
    }

    private fun updateEnvironmentLighting(frame: Frame, camera: com.google.ar.core.Camera) {
        val nowMs = android.os.SystemClock.elapsedRealtime()

        // 1. Zero-copy real camera hardware telemetry (ISO, shutter speed, aperture, AWB gains, flicker, lux)
        // Runs at ~6 Hz with zero ImageReader buffer locks so the camera stream stays 100% glitch-free.
        if (nowMs - lastTelemetrySampleTimeMs >= 160L) {
            lastTelemetrySampleTimeMs = nowMs
            extractCameraTelemetry(frame)?.let { telemetry ->
                lastCameraTelemetry = telemetry
            }
        }

        // 2. Gently throttled live camera scene grid analysis (~2.2 Hz, unique timestamps only)
        // for real floor bounce color and bright-side light direction cues in Realistic mode.
        if (realisticRenderEnabled &&
            nowMs - lastSceneSampleTimeMs >= 450L &&
            frame.timestamp != lastAnalyzedFrameTimestamp
        ) {
            lastSceneSampleTimeMs = nowMs
            lastAnalyzedFrameTimestamp = frame.timestamp
            try {
                frame.acquireCameraImage().use { image ->
                    val displayDeg = when (displayRotation) {
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                    val cwRot = ((cameraSensorOrientationDeg - displayDeg) + 360) % 360
                    val camPose = camera.pose
                    val sample = ArSceneLightAnalyzer.analyzeCameraImage(
                        image = image,
                        displayRotationDegreesCw = cwRot
                    ) { camVec ->
                        FloatArray(3).also { out -> camPose.rotateVector(camVec, 0, out, 0) }
                    }
                    if (sample != null) {
                        lastSceneLightSample = sample
                    }
                }
            } catch (_: Throwable) {
                // Transient camera image unavailable; retain previous scene sample
            }
        }

        val estimate = try {
            frame.lightEstimate
        } catch (_: Throwable) {
            null
        }
        val rawBase = if (estimate != null && estimate.state == LightEstimate.State.VALID) {
            try {
                val mainDir = estimate.environmentalHdrMainLightDirection
                val mainInt = estimate.environmentalHdrMainLightIntensity
                val sh = estimate.environmentalHdrAmbientSphericalHarmonics
                if (mainDir != null && mainInt != null && sh != null &&
                    ArLightEstimatorMath.hasNonZeroHdrData(mainInt, sh)
                ) {
                    if (realisticRenderEnabled && estimate.timestamp != lastEnvCubemapTimestamp) {
                        uploadEnvironmentalHdrCubemap(estimate)
                    }
                    ArLightEstimatorMath.fromEnvironmentalHdr(mainDir, mainInt, sh)
                } else {
                    estimate.getColorCorrection(colorCorrectionScratch, 0)
                    ArLightEstimatorMath.fromAmbientIntensity(estimate.pixelIntensity, colorCorrectionScratch)
                }
            } catch (_: Throwable) {
                try {
                    estimate.getColorCorrection(colorCorrectionScratch, 0)
                    ArLightEstimatorMath.fromAmbientIntensity(estimate.pixelIntensity, colorCorrectionScratch)
                } catch (_: Throwable) {
                    null
                }
            }
        } else {
            null
        }

        val telemetry = lastCameraTelemetry
        val sceneSample = lastSceneLightSample
        val fusedTarget = when {
            rawBase != null -> ArLightEstimatorMath.fuseWithCameraTelemetryAndScene(rawBase, telemetry, sceneSample)
            telemetry != null || sceneSample != null -> ArLightEstimatorMath.fuseWithCameraTelemetryAndScene(
                ArLightEstimatorMath.defaultStudioLighting(),
                telemetry,
                sceneSample
            )
            else -> return
        }

        smoothedLighting = if (!smoothedLighting.isEstimateValid) {
            fusedTarget
        } else {
            ArLightEstimatorMath.smooth(smoothedLighting, fusedTarget, alpha = 0.14f)
        }
    }

    private fun uploadEnvironmentalHdrCubemap(estimate: LightEstimate) {
        val cubemapImages = try {
            estimate.acquireEnvironmentalHdrCubeMap()
        } catch (_: Throwable) {
            return
        } ?: return

        try {
            if (cubemapImages.size < 6 || envCubemapTexId == 0) return
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                // Drain prior errors
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, envCubemapTexId)
            var uploadedAll = true
            for (faceIdx in 0 until 6) {
                val img = cubemapImages[faceIdx]
                val w = img.width
                val h = img.height
                val plane = img.planes.firstOrNull()
                val buf = plane?.buffer
                if (w <= 0 || h <= 0 || buf == null || buf.remaining() < w * h * 8) {
                    uploadedAll = false
                    break
                }
                buf.rewind()
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + faceIdx,
                    0,
                    GLES30.GL_RGBA16F,
                    w,
                    h,
                    0,
                    GLES30.GL_RGBA,
                    GLES30.GL_HALF_FLOAT,
                    buf
                )
            }
            if (uploadedAll && GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_CUBE_MAP)
                if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                    GLES30.glTexParameteri(
                        GLES30.GL_TEXTURE_CUBE_MAP,
                        GLES30.GL_TEXTURE_MIN_FILTER,
                        GLES30.GL_LINEAR_MIPMAP_LINEAR
                    )
                    hasValidEnvCubemap = true
                } else {
                    // Some GPUs disallow mipmap generation on RGBA16F; fall back to base-level GL_LINEAR
                    GLES30.glTexParameteri(
                        GLES30.GL_TEXTURE_CUBE_MAP,
                        GLES30.GL_TEXTURE_MIN_FILTER,
                        GLES30.GL_LINEAR
                    )
                    hasValidEnvCubemap = true
                }
                lastEnvCubemapTimestamp = estimate.timestamp
            } else {
                uploadFallbackCubemapFaces()
            }
        } catch (_: Throwable) {
            uploadFallbackCubemapFaces()
        } finally {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            for (img in cubemapImages) {
                try {
                    img.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun renderVrBackgroundOnly() {
        val halfW = (viewportWidth / 2).coerceAtLeast(1)
        val zoom = vrLensZoom.coerceIn(0.72f, 1.0f)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        try {
            for (eyeIdx in 0..1) {
                val eyeSign = if (eyeIdx == 0) -1f else 1f
                val slotX = if (eyeIdx == 0) 0 else halfW
                val slotW = if (eyeIdx == 0) halfW else (viewportWidth - halfW).coerceAtLeast(1)
                val eyeW = (slotW * zoom).toInt().coerceAtLeast(1)
                val eyeH = (viewportHeight * zoom).toInt().coerceAtLeast(1)
                val eyeX = slotX + (slotW - eyeW) / 2
                val eyeY = (viewportHeight - eyeH) / 2
                val eyeWidthRatio = slotW.toFloat() / viewportWidth.coerceAtLeast(1).toFloat()
                GLES30.glScissor(eyeX, eyeY, eyeW, eyeH)
                GLES30.glViewport(eyeX, eyeY, eyeW, eyeH)
                drawCameraBackground(
                    ArTransformMath.computeVrEyeBackgroundTexCoords(
                        fullTexCoords = quadTexTransformedArray,
                        eyeWidthRatio = eyeWidthRatio,
                        eyeSign = eyeSign,
                        stereoBgShiftFraction = 0.01f
                    )
                )
            }
        } finally {
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        }
    }

    private fun computeModelWorldMatrix(activePose: Pose, geom: GeometrySet): FloatArray {
        val rawAnchorMat = FloatArray(16)
        activePose.toMatrix(rawAnchorMat, 0)
        val anchorMat = ArTransformMath.buildPoseMatrix(rawAnchorMat, alwaysLevel)
        val shift = worldAnchorShift
        anchorMat[12] += shift[0]
        anchorMat[13] += shift[1]
        anchorMat[14] += shift[2]

        val activeBounds = geom.bounds
        val alignPt = ArTrackingDotMath.resolveAlignmentPoint(
            anchorOrigin = anchorOrigin,
            bounds = activeBounds,
            trackingDot = trackingDot
        )
        val alignX = alignPt.x.toFloat()
        val alignY = alignPt.y.toFloat()
        val alignZ = alignPt.z.toFloat()

        val clampedScale = ArScaleMath.clampScale(modelScale)
        val modelMat = FloatArray(16)
        Matrix.setIdentityM(modelMat, 0)
        Matrix.translateM(modelMat, 0, 0f, verticalOffsetMeters, 0f)
        Matrix.rotateM(modelMat, 0, rotationDeg, 0f, 1f, 0f)
        Matrix.scaleM(modelMat, 0, clampedScale, clampedScale, clampedScale)
        // Z-up CAD -> Y-up ARCore (-90 deg around X)
        Matrix.rotateM(modelMat, 0, -90f, 1f, 0f, 0f)
        Matrix.translateM(modelMat, 0, -alignX, -alignY, -alignZ)

        val worldMat = FloatArray(16)
        Matrix.multiplyMM(worldMat, 0, anchorMat, 0, modelMat, 0)
        return worldMat
    }

    private fun renderEyeOverlay(
        eyeViewMat: FloatArray,
        eyeProjMat: FloatArray,
        cameraWorldPos: FloatArray,
        lighting: ArEnvironmentLighting,
        pointCount: Int,
        geom: GeometrySet?,
        worldMat: FloatArray?,
        alignPoint: Vec3
    ) {
        if (pointCount > 0 && anchor == null && liveTrackedPose == null) {
            val vpMat = FloatArray(16)
            Matrix.multiplyMM(vpMat, 0, eyeProjMat, 0, eyeViewMat, 0)
            drawUploadedPointCloud(pointCount, vpMat)
        }
        if (geom == null || worldMat == null) return
        val mvMat = FloatArray(16)
        Matrix.multiplyMM(mvMat, 0, eyeViewMat, 0, worldMat, 0)
        val mvpMat = FloatArray(16)
        Matrix.multiplyMM(mvpMat, 0, eyeProjMat, 0, mvMat, 0)

        val isRealistic = realisticRenderEnabled
        if (isRealistic) {
            drawGroundContactShadow(geom, worldMat, mvpMat, lighting)
        }
        drawGeometryBatches(geom, worldMat, mvpMat, cameraWorldPos, lighting, isRealistic)
        if (!isRealistic) {
            drawEdgesAndBounds(geom, mvpMat)
            drawAxisGizmo(mvpMat)
        }
        if (showTrackingDotGizmo || tapActionMode == ArTapActionMode.PICK_DOT_ON_MODEL) {
            drawTrackingDotGizmo(mvpMat, alignPoint, geom.bounds)
        }
    }

    private fun drawGroundContactShadow(
        geom: GeometrySet,
        worldMat: FloatArray,
        mvpMat: FloatArray,
        lighting: ArEnvironmentLighting
    ) {
        if (shadowProgram == 0) return
        val buf = geom.groundShadowBuffer ?: return
        if (geom.groundShadowVertexCount <= 0) return

        val (localDx, localDy) = ArLightEstimatorMath.worldShadowOffsetToModelLocal(
            worldOffsetX = lighting.shadowOffsetX,
            worldOffsetZ = lighting.shadowOffsetZ,
            modelYawDeg = rotationDeg
        )

        GLES30.glUseProgram(shadowProgram)
        GLES30.glUniformMatrix4fv(uMvpShadow, 1, false, mvpMat, 0)
        GLES30.glUniformMatrix4fv(uShadowModelMat, 1, false, worldMat, 0)
        val lightVp = lastLightVpMatrix
        val useShadowMap = hasShadowMapFbo && lightVp != null && shadowDepthTexId != 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (useShadowMap) shadowDepthTexId else dummyDepthTexId)
        GLES30.glUniform1i(uShadowDepthTex, 3)
        GLES30.glUniformMatrix4fv(uShadowLightVp, 1, false, lightVp ?: identityMat4, 0)
        GLES30.glUniform1i(uShadowHasMap, if (useShadowMap) 1 else 0)
        GLES30.glUniform2f(uShadowOffset, localDx, localDy)
        GLES30.glUniform1f(uShadowStrength, lighting.shadowStrength)
        GLES30.glUniform3f(
            uShadowTint,
            (0.05f * lighting.ambientColorCorrection[0]).coerceIn(0.02f, 0.11f),
            (0.06f * lighting.ambientColorCorrection[1]).coerceIn(0.02f, 0.11f),
            (0.08f * lighting.ambientColorCorrection[2]).coerceIn(0.03f, 0.14f)
        )

        GLES30.glDepthMask(false)
        val stride = 5 * 4
        buf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, buf)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, geom.groundShadowVertexCount)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDepthMask(true)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyDepthTexId)
        GLES30.glUseProgram(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun drawGeometryBatches(
        geom: GeometrySet,
        worldMat: FloatArray,
        mvpMat: FloatArray,
        cameraWorldPos: FloatArray,
        lighting: ArEnvironmentLighting,
        realisticMode: Boolean
    ) {
        if (geom.opaqueBatches.isEmpty() && geom.translucentBatches.isEmpty()) return

        if (modelProgram == 0) {
            drawGeometryBatchesBasicFallback(geom, worldMat, mvpMat, lighting, realisticMode)
            return
        }

        GLES30.glUseProgram(modelProgram)
        // Always bind distinct sampler units (1=albedo 2D, 2=env cubemap, 3=shadow map) with valid
        // textures so OpenGL ES 3.0 never sees mismatched sampler types sharing unit 0.
        GLES30.glUniform1i(uTextureSampler, 1)
        GLES30.glUniform1i(uEnvCubemap, 2)
        GLES30.glUniform1i(uModelShadowMap, 3)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, envCubemapTexId)

        val lightVp = lastLightVpMatrix
        val useShadowMap = realisticMode && hasShadowMapFbo && lightVp != null && shadowDepthTexId != 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (useShadowMap) shadowDepthTexId else dummyDepthTexId)

        GLES30.glUniformMatrix4fv(uMvpModel, 1, false, mvpMat, 0)
        GLES30.glUniformMatrix4fv(uModelMat, 1, false, worldMat, 0)
        GLES30.glUniformMatrix4fv(uModelLightVp, 1, false, lightVp ?: identityMat4, 0)
        // World-space Earth-gravity upper sun for CAD drafting mode
        GLES30.glUniform3f(uLightDir, 0.35f, 0.85f, 0.40f)
        GLES30.glUniform1i(uRealisticMode, if (realisticMode) 1 else 0)
        if (realisticMode) {
            GLES30.glUniform3f(uCameraWorldPos, cameraWorldPos[0], cameraWorldPos[1], cameraWorldPos[2])
            GLES30.glUniform3f(
                uMainLightDir,
                lighting.mainLightDirection[0],
                lighting.mainLightDirection[1],
                lighting.mainLightDirection[2]
            )
            GLES30.glUniform3f(
                uMainLightIntensity,
                lighting.mainLightIntensity[0],
                lighting.mainLightIntensity[1],
                lighting.mainLightIntensity[2]
            )
            GLES30.glUniform3fv(uSphericalHarmonics, 9, lighting.sphericalHarmonics, 0)
            GLES30.glUniform3f(
                uAmbientColor,
                lighting.ambientColorCorrection[0],
                lighting.ambientColorCorrection[1],
                lighting.ambientColorCorrection[2]
            )
            GLES30.glUniform1f(uSceneExposure, lighting.sceneExposure)
            GLES30.glUniform3f(
                uFloorBounceColor,
                lighting.floorBounceColor[0],
                lighting.floorBounceColor[1],
                lighting.floorBounceColor[2]
            )
            GLES30.glUniform1f(uModelShadowStrength, lighting.shadowStrength)
            GLES30.glUniform1f(uSpecularScale, lighting.specularScale)
            GLES30.glUniform1f(uAmbientFillScale, lighting.ambientFillScale)
            GLES30.glUniform1f(uBoundsMinZ, geom.bounds.min.z.toFloat())
            GLES30.glUniform1f(uBoundsHeight, max(geom.bounds.size.z.toFloat(), 0.05f))
            GLES30.glUniform1i(uModelHasShadowMap, if (useShadowMap) 1 else 0)
            GLES30.glUniform1i(uHasEnvCubemap, if (hasValidEnvCubemap && envCubemapTexId != 0) 1 else 0)
        } else {
            GLES30.glUniform1i(uModelHasShadowMap, 0)
            GLES30.glUniform1i(uHasEnvCubemap, 0)
            GLES30.glUniform1f(uSpecularScale, 1.0f)
            GLES30.glUniform1f(uAmbientFillScale, 1.0f)
        }
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.0f, 1.0f)

        fun renderBatchList(batches: List<MeshBatch>) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            for (batch in batches) {
                val texId = if (showTextures && batch.textureKey != null) textureIds[batch.textureKey] else null
                if (texId != null) {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                    GLES30.glUniform1i(uUseTexture, 1)
                } else {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyWhiteTexId)
                    GLES30.glUniform1i(uUseTexture, 0)
                }
                bindMeshBuffer(batch.buffer)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, batch.vertexCount)
            }
        }

        if (geom.opaqueBatches.isNotEmpty()) {
            GLES30.glDepthMask(true)
            renderBatchList(geom.opaqueBatches)
        }
        if (geom.translucentBatches.isNotEmpty()) {
            GLES30.glDepthMask(false)
            renderBatchList(geom.translucentBatches)
            GLES30.glDepthMask(true)
        }

        disableMeshAttribs()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyDepthTexId)
        GLES30.glUseProgram(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun drawGeometryBatchesBasicFallback(
        geom: GeometrySet,
        worldMat: FloatArray,
        mvpMat: FloatArray,
        lighting: ArEnvironmentLighting,
        realisticMode: Boolean
    ) {
        if (basicModelProgram == 0) return
        GLES30.glUseProgram(basicModelProgram)
        GLES30.glUniform1i(uBasicTex, 1)
        GLES30.glUniformMatrix4fv(uBasicMvp, 1, false, mvpMat, 0)
        GLES30.glUniformMatrix4fv(uBasicModelMat, 1, false, worldMat, 0)
        if (realisticMode) {
            GLES30.glUniform3f(
                uBasicLightDir,
                lighting.mainLightDirection[0],
                lighting.mainLightDirection[1],
                lighting.mainLightDirection[2]
            )
            GLES30.glUniform3f(
                uBasicAmbientColor,
                lighting.ambientColorCorrection[0],
                lighting.ambientColorCorrection[1],
                lighting.ambientColorCorrection[2]
            )
            GLES30.glUniform1f(uBasicExposure, lighting.sceneExposure)
        } else {
            GLES30.glUniform3f(uBasicLightDir, 0.35f, 0.85f, 0.40f)
            GLES30.glUniform3f(uBasicAmbientColor, 1f, 1f, 1f)
            GLES30.glUniform1f(uBasicExposure, 1.0f)
        }
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.0f, 1.0f)

        fun renderBatches(batches: List<MeshBatch>) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            for (batch in batches) {
                val texId = if (showTextures && batch.textureKey != null) textureIds[batch.textureKey] else null
                if (texId != null) {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                    GLES30.glUniform1i(uBasicUseTexture, 1)
                } else {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyWhiteTexId)
                    GLES30.glUniform1i(uBasicUseTexture, 0)
                }
                bindMeshBuffer(batch.buffer)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, batch.vertexCount)
            }
        }

        if (geom.opaqueBatches.isNotEmpty()) {
            GLES30.glDepthMask(true)
            renderBatches(geom.opaqueBatches)
        }
        if (geom.translucentBatches.isNotEmpty()) {
            GLES30.glDepthMask(false)
            renderBatches(geom.translucentBatches)
            GLES30.glDepthMask(true)
        }
        disableMeshAttribs()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glUseProgram(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun drawEdgesAndBounds(geom: GeometrySet, mvpMat: FloatArray) {
        if (lineProgram == 0) return
        geom.edgeBuffer?.let { buf ->
            if (geom.edgeCount > 0) {
                GLES30.glUseProgram(lineProgram)
                GLES30.glUniformMatrix4fv(uMvpLine, 1, false, mvpMat, 0)
                GLES30.glUniform4f(uLineColor, 0.08f, 0.12f, 0.18f, 0.88f)
                GLES30.glLineWidth(2f)
                buf.position(0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, buf)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, geom.edgeCount)
                GLES30.glDisableVertexAttribArray(0)
            }
        }

        geom.boundsBoxBuffer?.let { buf ->
            if (geom.boundsBoxVertexCount > 0) {
                GLES30.glUseProgram(lineProgram)
                GLES30.glUniformMatrix4fv(uMvpLine, 1, false, mvpMat, 0)
                if (currentPhase == ObjectTrackingPhase.STICKING_TO_OBJECT) {
                    GLES30.glUniform4f(uLineColor, 0.12f, 0.85f, 0.65f, 0.85f)
                } else {
                    GLES30.glUniform4f(uLineColor, 0.22f, 0.68f, 0.98f, 0.72f)
                }
                GLES30.glLineWidth(2.6f)
                buf.position(0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, buf)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, geom.boundsBoxVertexCount)
                GLES30.glDisableVertexAttribArray(0)
            }
        }
        GLES30.glUseProgram(0)
    }

    private fun drawAxisGizmo(mvpMat: FloatArray) {
        if (colorLineProgram == 0) return
        val buf = axisGizmoBuffer ?: return
        if (axisGizmoVertexCount <= 0) return
        GLES30.glUseProgram(colorLineProgram)
        GLES30.glUniformMatrix4fv(uMvpColorLine, 1, false, mvpMat, 0)
        GLES30.glLineWidth(3.5f)
        val stride = 7 * 4
        buf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, buf)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, axisGizmoVertexCount)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glUseProgram(0)
    }

    /**
     * Renders the 3D IngeTrazo Move Tool Tracking Dot & local XYZ move crosshair directly on the model
     * at [alignPoint] so the user can clearly see the exact vertex/edge/face locked to the real-world point.
     */
    private fun drawTrackingDotGizmo(
        mvpMat: FloatArray,
        alignPoint: Vec3,
        bounds: BoundingBox3D
    ) {
        val px = alignPoint.x.toFloat()
        val py = alignPoint.y.toFloat()
        val pz = alignPoint.z.toFloat()

        val diag = if (bounds.isValid) max(bounds.diagonal, 0.25).toFloat() else 1.0f
        val arm = diag * 0.085f
        val diamond = diag * 0.032f

        val builder = FloatArrayBuilder(256)
        // 3D Octahedron / Diamond Wireframe around the Tracking Dot (Vibrant Move Tool Orange-Red)
        val dr = 1.0f; val dg = 0.34f; val db = 0.13f; val da = 0.98f
        // Equatorial square in XY plane
        builder.addColorLine(px - diamond, py, pz, px, py - diamond, pz, dr, dg, db, da)
        builder.addColorLine(px, py - diamond, pz, px + diamond, py, pz, dr, dg, db, da)
        builder.addColorLine(px + diamond, py, pz, px, py + diamond, pz, dr, dg, db, da)
        builder.addColorLine(px, py + diamond, pz, px - diamond, py, pz, dr, dg, db, da)
        // Upper pyramid to +Z
        builder.addColorLine(px - diamond, py, pz, px, py, pz + diamond, dr, dg, db, da)
        builder.addColorLine(px + diamond, py, pz, px, py, pz + diamond, dr, dg, db, da)
        builder.addColorLine(px, py - diamond, pz, px, py, pz + diamond, dr, dg, db, da)
        builder.addColorLine(px, py + diamond, pz, px, py, pz + diamond, dr, dg, db, da)
        // Lower pyramid to -Z
        builder.addColorLine(px - diamond, py, pz, px, py, pz - diamond, dr, dg, db, da)
        builder.addColorLine(px + diamond, py, pz, px, py, pz - diamond, dr, dg, db, da)
        builder.addColorLine(px, py - diamond, pz, px, py, pz - diamond, dr, dg, db, da)
        builder.addColorLine(px, py + diamond, pz, px, py, pz - diamond, dr, dg, db, da)

        // Local Move Tool XYZ handle rays at the Tracking Dot
        builder.addColorLine(px - arm * 0.5f, py, pz, px + arm, py, pz, 0.96f, 0.22f, 0.22f, 0.95f)
        builder.addColorLine(px, py - arm * 0.5f, pz, px, py + arm, pz, 0.16f, 0.85f, 0.38f, 0.95f)
        builder.addColorLine(px, py, pz - arm * 0.5f, px, py, pz + arm, 0.22f, 0.58f, 1.0f, 0.95f)

        val buf = builder.toFloatBuffer()
        val vertCount = builder.size / 7

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        try {
            if (colorLineProgram != 0) {
                GLES30.glUseProgram(colorLineProgram)
                GLES30.glUniformMatrix4fv(uMvpColorLine, 1, false, mvpMat, 0)
                GLES30.glLineWidth(4.0f)
                val stride = 7 * 4
                buf.position(0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, buf)
                buf.position(3)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, buf)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, vertCount)
                GLES30.glDisableVertexAttribArray(0)
                GLES30.glDisableVertexAttribArray(1)
            }

            if (pointProgram != 0) {
                // Draw bright center point dot at alignPoint
                val dotBuf = floatBufferOf(px, py, pz)
                GLES30.glUseProgram(pointProgram)
                GLES30.glUniformMatrix4fv(uMvpPoint, 1, false, mvpMat, 0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, dotBuf)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, 1)
                GLES30.glDisableVertexAttribArray(0)
            }
        } finally {
            GLES30.glUseProgram(0)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
    }

    private fun captureAndRegisterObjectTarget(
        arSession: Session,
        frame: Frame,
        physicalWidthMeters: Float
    ) {
        val cb = captureCallback
        captureCallback = null
        try {
            val bitmap = frame.acquireCameraImage().use { image ->
                val displayDeg = when (displayRotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val cwRot = ((cameraSensorOrientationDeg - displayDeg) + 360) % 360
                ArImageCropper.yuv420ImageToUprightCenterCrop(
                    image = image,
                    displayRotationDegreesCw = cwRot,
                    cropFraction = 0.45f,
                    maxOutputSizePx = 320
                )
            }
            if (bitmap == null) {
                currentPhase = ObjectTrackingPhase.QUALITY_ERROR
                currentTargetLabel = "Could not read camera frame"
                cb?.invoke(false, "Could not capture camera image.")
                return
            }

            try {
                val db = AugmentedImageDatabase(arSession)
                db.addImage("object_target", bitmap, physicalWidthMeters)
                val cfg = arSession.config
                cfg.augmentedImageDatabase = db
                arSession.configure(cfg)
                anchor?.detach()
                anchor = null
                liveTrackedPose = null
                currentPhase = ObjectTrackingPhase.TARGET_SEARCHING
                currentTargetLabel = "Object target locked — point camera at object"
                cb?.invoke(true, "Target captured! Point at the object to stick model.")
            } finally {
                bitmap.recycle()
            }
        } catch (_: ImageInsufficientQualityException) {
            currentPhase = ObjectTrackingPhase.QUALITY_ERROR
            currentTargetLabel = "Target needs stronger visual texture or edges"
            cb?.invoke(false, "Low detail on target. Aim at an object with visible texture/edges.")
        } catch (t: Throwable) {
            currentPhase = ObjectTrackingPhase.QUALITY_ERROR
            currentTargetLabel = t.localizedMessage ?: "Failed to register object target"
            cb?.invoke(false, currentTargetLabel)
        }
    }

    private fun updateVisualObjectTracking(frame: Frame) {
        val updated = frame.getUpdatedTrackables(AugmentedImage::class.java)
        for (img in updated) {
            if (img.name != "object_target") continue
            when (img.trackingState) {
                TrackingState.TRACKING -> {
                    val pose = img.centerPose
                    if (img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                        liveTrackedPose = pose
                        val cur = anchor
                        val shouldRefreshAnchor = cur == null ||
                            cur.trackingState != TrackingState.TRACKING ||
                            poseDistanceL1(cur.pose, pose) > 0.015f
                        if (shouldRefreshAnchor) {
                            cur?.detach()
                            anchor = img.createAnchor(pose)
                        }
                        currentPhase = ObjectTrackingPhase.STICKING_TO_OBJECT
                        currentTargetLabel = "Sticking to tracked object (6-DoF live)"
                    } else {
                        liveTrackedPose = anchor?.pose ?: pose
                        currentPhase = ObjectTrackingPhase.PINNED_TO_OBJECT
                        currentTargetLabel = "Pinned to last known object position"
                    }
                }
                TrackingState.PAUSED -> {
                    if (liveTrackedPose != null || anchor != null) {
                        currentPhase = ObjectTrackingPhase.PINNED_TO_OBJECT
                        currentTargetLabel = "Target paused — holding object anchor"
                    }
                }
                TrackingState.STOPPED -> Unit
            }
        }
    }

    private fun selectBestObjectOrSurfaceHit(
        frame: Frame,
        cameraPose: Pose,
        screenX: Float,
        screenY: Float,
        planesOnly: Boolean = false
    ): ArSelectedHit<HitResult>? {
        val rawHits = try {
            frame.hitTest(screenX, screenY)
        } catch (_: Throwable) {
            emptyList()
        }

        val candidates = ArrayList<ArHitCandidate<HitResult>>(rawHits.size + 2)
        val planeNormal = FloatArray(3)
        for (hit in rawHits) {
            val t = hit.trackable
            if (t.trackingState != TrackingState.TRACKING) continue
            val hp = hit.hitPose
            when (t) {
                is Plane -> {
                    val isSubsumed = t.subsumedBy != null
                    t.centerPose.getTransformedAxis(1, 1f, planeNormal, 0)
                    val frontFacing = ArHitPlacementPolicy.isPlaneFrontFacing(
                        planeNormalX = planeNormal[0],
                        planeNormalY = planeNormal[1],
                        planeNormalZ = planeNormal[2],
                        hitX = hp.tx(),
                        hitY = hp.ty(),
                        hitZ = hp.tz(),
                        cameraX = cameraPose.tx(),
                        cameraY = cameraPose.ty(),
                        cameraZ = cameraPose.tz()
                    )
                    val surfaceName = if (t.type == Plane.Type.VERTICAL) {
                        "Anchored to vertical surface"
                    } else {
                        "Anchored to horizontal surface"
                    }
                    val kind: ArHitKind
                    val edgeOffset: Float
                    when {
                        t.isPoseInPolygon(hp) -> {
                            kind = ArHitKind.PLANE_IN_POLYGON
                            edgeOffset = 0f
                        }
                        t.isPoseInExtents(hp) -> {
                            kind = ArHitKind.PLANE_IN_EXTENTS
                            edgeOffset = 0f
                        }
                        else -> {
                            kind = ArHitKind.PLANE_EXTENDED
                            val local = t.centerPose.inverse().transformPoint(
                                floatArrayOf(hp.tx(), hp.ty(), hp.tz())
                            )
                            val dx = max(0f, abs(local[0]) - t.extentX * 0.5f)
                            val dz = max(0f, abs(local[2]) - t.extentZ * 0.5f)
                            edgeOffset = sqrt(dx * dx + dz * dz)
                        }
                    }
                    candidates.add(
                        ArHitCandidate(
                            payload = hit,
                            kind = kind,
                            distanceMeters = hit.distance,
                            isFrontFacing = frontFacing,
                            isSubsumed = isSubsumed,
                            planeEdgeDistanceMeters = edgeOffset,
                            label = surfaceName
                        )
                    )
                }
                is DepthPoint -> if (!planesOnly) {
                    candidates.add(
                        ArHitCandidate(
                            payload = hit,
                            kind = ArHitKind.DEPTH_POINT,
                            distanceMeters = hit.distance,
                            label = "Anchored to 3D Depth object"
                        )
                    )
                }
                is Point -> if (!planesOnly) {
                    val isOriented = t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    candidates.add(
                        ArHitCandidate(
                            payload = hit,
                            kind = if (isOriented) ArHitKind.ORIENTED_POINT else ArHitKind.FEATURE_POINT,
                            distanceMeters = hit.distance,
                            label = if (isOriented) "Anchored to 3D object feature" else "Pinned to 3D feature point"
                        )
                    )
                }
                is InstantPlacementPoint -> if (!planesOnly) {
                    candidates.add(
                        ArHitCandidate(
                            payload = hit,
                            kind = ArHitKind.INSTANT_PLACEMENT,
                            distanceMeters = hit.distance,
                            label = "Pinned via instant placement"
                        )
                    )
                }
            }
        }

        val selected = ArHitPlacementPolicy.selectBestHit(candidates)
        if (selected != null || planesOnly) return selected

        // Last-resort instant placement fallback when user taps before any plane/depth/feature point exists
        val instantHits = try {
            frame.hitTestInstantPlacement(screenX, screenY, 2.0f)
        } catch (_: Throwable) {
            emptyList()
        }
        val instantCandidates = instantHits.mapNotNull { hit ->
            val t = hit.trackable
            if (t.trackingState != TrackingState.TRACKING) return@mapNotNull null
            ArHitCandidate(
                payload = hit,
                kind = ArHitKind.INSTANT_PLACEMENT,
                distanceMeters = hit.distance,
                label = "Pinned via instant placement"
            )
        }
        return ArHitPlacementPolicy.selectBestHit(instantCandidates)
    }

    private fun tryRefineApproximateAnchorToMetricPlane(
        frame: Frame,
        cameraPose: Pose,
        viewMat: FloatArray,
        projMat: FloatArray
    ) {
        val currentAnchor = anchor ?: run {
            anchorNeedsPlaneRefinement = false
            return
        }
        if (currentAnchor.trackingState != TrackingState.TRACKING) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - anchorPlacedElapsedMs > 10_000L) {
            anchorNeedsPlaneRefinement = false
            return
        }
        if (nowMs - lastRefinementAttemptMs < 180L) return
        lastRefinementAttemptMs = nowMs

        // Only refine automatically if the user has not introduced a manual dot-switch shift
        val shift = worldAnchorShift
        if (abs(shift[0]) > 1e-4f || abs(shift[1]) > 1e-4f || abs(shift[2]) > 1e-4f) {
            anchorNeedsPlaneRefinement = false
            return
        }

        val ap = currentAnchor.pose
        val vpMat = FloatArray(16)
        Matrix.multiplyMM(vpMat, 0, projMat, 0, viewMat, 0)
        val screenPt = ArTrackingDotMath.projectModelPointToScreen(
            point = Vec3(ap.tx().toDouble(), ap.ty().toDouble(), ap.tz().toDouble()),
            mvpMatrix = vpMat,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        ) ?: return

        val sx = screenPt.x.toFloat()
        val sy = screenPt.y.toFloat()
        if (sx < viewportWidth * 0.05f || sx > viewportWidth * 0.95f ||
            sy < viewportHeight * 0.05f || sy > viewportHeight * 0.95f
        ) {
            return
        }

        val planeHit = selectBestObjectOrSurfaceHit(
            frame = frame,
            cameraPose = cameraPose,
            screenX = sx,
            screenY = sy,
            planesOnly = true
        ) ?: return

        val curDist = poseEuclideanDistance(cameraPose, ap)
        val candidatePose = planeHit.payload.hitPose
        val heightDelta = candidatePose.ty() - ap.ty()
        if (ArHitPlacementPolicy.shouldRefineApproximateAnchor(
                currentDistanceMeters = curDist,
                candidatePlaneDistanceMeters = planeHit.distanceMeters,
                heightDeltaMeters = heightDelta,
                wasInstantPlacement = anchorPlacedFromInstantPlacement
            )
        ) {
            currentAnchor.detach()
            anchor = planeHit.payload.createAnchor()
            anchorNeedsPlaneRefinement = false
            anchorPlacedFromInstantPlacement = false
            val dotInfo = if (anchorOrigin == ArAnchorOrigin.MODEL_DOT) {
                " · Dot: ${trackingDot.label}"
            } else {
                " · Origin: ${anchorOrigin.label}"
            }
            currentTargetLabel = "Locked · ${planeHit.label}$dotInfo"
        } else {
            // Distance & elevation already match the metric plane closely enough
            anchorNeedsPlaneRefinement = false
            anchorPlacedFromInstantPlacement = false
        }
    }

    private fun poseDistanceL1(a: Pose, b: Pose): Float {
        return abs(a.tx() - b.tx()) + abs(a.ty() - b.ty()) + abs(a.tz() - b.tz())
    }

    private fun poseEuclideanDistance(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx()
        val dy = a.ty() - b.ty()
        val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun uploadFeaturePointCloud(frame: Frame): Int {
        frame.acquirePointCloud().use { cloud ->
            val points = cloud.points
            val totalFloats = points.remaining()
            if (totalFloats < 4) return 0
            val maxPts = (totalFloats / 4).coerceAtMost(360)
            val capBytes = maxPts * 3 * 4
            var buf = pointCloudBuffer
            if (buf == null || buf.capacity() < maxPts * 3) {
                buf = ByteBuffer.allocateDirect(capBytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
                pointCloudBuffer = buf
            }
            buf.clear()
            val dup = points.duplicate()
            var added = 0
            while (dup.remaining() >= 4 && added < maxPts) {
                val x = dup.get()
                val y = dup.get()
                val z = dup.get()
                val conf = dup.get()
                if (conf >= 0.15f) {
                    buf.put(x); buf.put(y); buf.put(z)
                    added++
                }
            }
            buf.flip()
            return added
        }
    }

    private fun drawUploadedPointCloud(added: Int, vpMat: FloatArray) {
        if (added <= 0 || pointProgram == 0) return
        val buf = pointCloudBuffer ?: return
        buf.position(0)
        GLES30.glUseProgram(pointProgram)
        GLES30.glUniformMatrix4fv(uMvpPoint, 1, false, vpMat, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, buf)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, added)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glUseProgram(0)
    }

    private fun ensureTransformedBackgroundTexCoords(frame: Frame) {
        if (displayGeometryDirty || frame.hasDisplayGeometryChanged() || !hasTransformedTexCoords) {
            quadCoords.position(0)
            quadTexTransformed.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexTransformed
            )
            quadTexTransformed.position(0)
            quadTexTransformed.get(quadTexTransformedArray)
            quadTexTransformed.position(0)
            displayGeometryDirty = false
            hasTransformedTexCoords = true
        }
    }

    private fun drawCameraBackground(customTexCoords: FloatArray?) {
        if (bgProgram == 0) return
        val texBuf = if (customTexCoords != null && customTexCoords.size >= 8) {
            vrBgTexBuffer.clear()
            vrBgTexBuffer.put(customTexCoords, 0, 8)
            vrBgTexBuffer.flip()
            vrBgTexBuffer
        } else if (hasTransformedTexCoords) {
            quadTexTransformed
        } else {
            quadTexCoords
        }

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(bgProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES30.glUniform1i(uBgTex, 0)

        quadCoords.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadCoords)
        texBuf.position(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texBuf)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glUseProgram(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun uploadTextures(textures: Map<String, Bitmap>) {
        if (textureIds.isNotEmpty()) {
            val arr = textureIds.values.toIntArray()
            GLES30.glDeleteTextures(arr.size, arr, 0)
            textureIds.clear()
        }
        if (textures.isEmpty()) return
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        val supportsAnisotropy = extensions.contains("GL_EXT_texture_filter_anisotropic")
        var maxAniso = 1f
        if (supportsAnisotropy) {
            val maxArr = FloatArray(1)
            GLES30.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, maxArr, 0)
            maxAniso = maxArr[0].coerceIn(1f, 8f)
        }
        val handles = IntArray(textures.size)
        GLES30.glGenTextures(textures.size, handles, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        try {
            var i = 0
            for ((key, bmp) in textures) {
                if (bmp.isRecycled) continue
                val id = handles[i++]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
                if (supportsAnisotropy && maxAniso > 1f) {
                    GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAniso)
                }
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                textureIds[key] = id
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyWhiteTexId)
        } finally {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
    }

    private class FloatArrayBuilder(initialCapacity: Int = 512) {
        var data = FloatArray(initialCapacity)
            private set
        var size = 0
            private set

        private fun ensureCapacity(extra: Int) {
            val needed = size + extra
            if (needed > data.size) {
                var newCap = data.size * 2
                while (newCap < needed) newCap *= 2
                data = data.copyOf(newCap)
            }
        }

        fun addMeshVertex(
            px: Float, py: Float, pz: Float,
            nx: Float, ny: Float, nz: Float,
            u: Float, v: Float,
            r: Float, g: Float, b: Float, a: Float
        ) {
            ensureCapacity(12)
            var idx = size
            data[idx++] = px; data[idx++] = py; data[idx++] = pz
            data[idx++] = nx; data[idx++] = ny; data[idx++] = nz
            data[idx++] = u; data[idx++] = v
            data[idx++] = r; data[idx++] = g; data[idx++] = b; data[idx++] = a
            size = idx
        }

        fun addVec3(x: Float, y: Float, z: Float) {
            ensureCapacity(3)
            var idx = size
            data[idx++] = x; data[idx++] = y; data[idx++] = z
            size = idx
        }

        fun addColorLine(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            r: Float, g: Float, b: Float, a: Float
        ) {
            ensureCapacity(14)
            var idx = size
            data[idx++] = ax; data[idx++] = ay; data[idx++] = az
            data[idx++] = r; data[idx++] = g; data[idx++] = b; data[idx++] = a
            data[idx++] = bx; data[idx++] = by; data[idx++] = bz
            data[idx++] = r; data[idx++] = g; data[idx++] = b; data[idx++] = a
            size = idx
        }

        fun isNotEmpty(): Boolean = size > 0

        fun toFloatBuffer(): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(data, 0, size)
            fb.position(0)
            return fb
        }
    }

    private fun buildModelBuffers() {
        val full = buildGeometrySet(clipBelowZZero = false, bounds = document.bounds)
        fullGeometry = full

        aboveGroundGeometry = if (ArGeometryClipper.hasFoundationBelowAxis(document.bounds)) {
            val clippedBounds = ArGeometryClipper.computeEffectiveBounds(document, showFoundation = false)
            val clipped = buildGeometrySet(clipBelowZZero = true, bounds = clippedBounds)
            if (clipped.opaqueBatches.isEmpty() && clipped.translucentBatches.isEmpty() && clipped.edgeCount == 0) {
                full
            } else {
                clipped
            }
        } else {
            full
        }

        // Build subtle CAD origin triad at (0, 0, 0) scaled proportionally to the model
        val axisSpan = max(full.bounds.diagonal * 0.22, 0.25).toFloat()
        val axisNeg = axisSpan * 0.25f
        val axisVerts = FloatArrayBuilder(128)
        // +X Red / -X faint red
        axisVerts.addColorLine(0f, 0f, 0.002f, axisSpan, 0f, 0.002f, 0.90f, 0.20f, 0.20f, 0.95f)
        axisVerts.addColorLine(-axisNeg, 0f, 0.002f, 0f, 0f, 0.002f, 0.90f, 0.20f, 0.20f, 0.40f)
        // +Y Green / -Y faint green
        axisVerts.addColorLine(0f, 0f, 0.002f, 0f, axisSpan, 0.002f, 0.14f, 0.78f, 0.34f, 0.95f)
        axisVerts.addColorLine(0f, -axisNeg, 0.002f, 0f, 0f, 0.002f, 0.14f, 0.78f, 0.34f, 0.40f)
        // +Z Blue
        axisVerts.addColorLine(0f, 0f, 0f, 0f, 0f, axisSpan, 0.18f, 0.48f, 0.96f, 0.95f)
        axisGizmoBuffer = axisVerts.toFloatBuffer()
        axisGizmoVertexCount = axisVerts.size / 7
    }

    private fun buildGeometrySet(
        clipBelowZZero: Boolean,
        bounds: BoundingBox3D
    ): GeometrySet {
        val visibleFaces = document.faces.filterNot { it.hidden }
        val opaqueGroups = LinkedHashMap<String?, FloatArrayBuilder>()
        val translucentGroups = LinkedHashMap<String?, FloatArrayBuilder>()

        for (face in visibleFaces) {
            val nx = face.normal.x.toFloat()
            val ny = face.normal.y.toFloat()
            val nz = face.normal.z.toFloat()
            val r = face.color.r
            val g = face.color.g
            val b = face.color.b
            val a = face.color.a
            val targetGroups = if (a < 0.985f) translucentGroups else opaqueGroups
            val builder = targetGroups.getOrPut(face.textureKey) { FloatArrayBuilder(1024) }

            val tris = face.triangleVertices
            val uvs = face.triangleUvs
            if (!clipBelowZZero) {
                for (k in tris.indices) {
                    val p = tris[k]
                    val uv = uvs.getOrNull(k)
                    builder.addMeshVertex(
                        p.x.toFloat(), p.y.toFloat(), p.z.toFloat(),
                        nx, ny, nz,
                        uv?.x?.toFloat() ?: 0f, uv?.y?.toFloat() ?: 0f,
                        r, g, b, a
                    )
                }
            } else {
                var k = 0
                while (k + 2 < tris.size) {
                    val clippedTris = ArGeometryClipper.clipTriangleAboveGround(
                        tris[k], tris[k + 1], tris[k + 2],
                        uvs.getOrElse(k) { Vec2(0.0, 0.0) },
                        uvs.getOrElse(k + 1) { Vec2(0.0, 0.0) },
                        uvs.getOrElse(k + 2) { Vec2(0.0, 0.0) }
                    )
                    for (tri in clippedTris) {
                        val v0 = tri.v0; val v1 = tri.v1; val v2 = tri.v2
                        builder.addMeshVertex(
                            v0.pos.x.toFloat(), v0.pos.y.toFloat(), v0.pos.z.toFloat(),
                            nx, ny, nz,
                            v0.uv.x.toFloat(), v0.uv.y.toFloat(),
                            r, g, b, a
                        )
                        builder.addMeshVertex(
                            v1.pos.x.toFloat(), v1.pos.y.toFloat(), v1.pos.z.toFloat(),
                            nx, ny, nz,
                            v1.uv.x.toFloat(), v1.uv.y.toFloat(),
                            r, g, b, a
                        )
                        builder.addMeshVertex(
                            v2.pos.x.toFloat(), v2.pos.y.toFloat(), v2.pos.z.toFloat(),
                            nx, ny, nz,
                            v2.uv.x.toFloat(), v2.uv.y.toFloat(),
                            r, g, b, a
                        )
                    }
                    k += 3
                }
            }
        }

        val opaqueBatches = opaqueGroups.mapNotNull { (texKey, builder) ->
            if (builder.isNotEmpty()) MeshBatch(texKey, builder.toFloatBuffer(), builder.size / 12) else null
        }
        val translucentBatches = translucentGroups.mapNotNull { (texKey, builder) ->
            if (builder.isNotEmpty()) MeshBatch(texKey, builder.toFloatBuffer(), builder.size / 12) else null
        }

        val visibleEdges = document.edges.filter { !it.hidden && !it.soft }
        val edgeBuilder = FloatArrayBuilder((visibleEdges.size * 6).coerceAtLeast(64))
        for (edge in visibleEdges) {
            if (!clipBelowZZero) {
                edgeBuilder.addVec3(edge.a.x.toFloat(), edge.a.y.toFloat(), edge.a.z.toFloat())
                edgeBuilder.addVec3(edge.b.x.toFloat(), edge.b.y.toFloat(), edge.b.z.toFloat())
            } else {
                ArGeometryClipper.clipEdgeAboveGround(edge.a, edge.b)?.let { (ca, cb) ->
                    edgeBuilder.addVec3(ca.x.toFloat(), ca.y.toFloat(), ca.z.toFloat())
                    edgeBuilder.addVec3(cb.x.toFloat(), cb.y.toFloat(), cb.z.toFloat())
                }
            }
        }
        val edgeBuffer = if (edgeBuilder.isNotEmpty()) edgeBuilder.toFloatBuffer() else null
        val edgeCount = edgeBuilder.size / 3

        var boundsBoxBuf: FloatBuffer? = null
        var boundsBoxCount = 0
        if (bounds.isValid) {
            val mn = bounds.min
            val mx = bounds.max
            val x0 = mn.x.toFloat(); val y0 = mn.y.toFloat(); val z0 = mn.z.toFloat()
            val x1 = mx.x.toFloat(); val y1 = mx.y.toFloat(); val z1 = mx.z.toFloat()
            val box = floatArrayOf(
                x0, y0, z0, x1, y0, z0,
                x1, y0, z0, x1, y1, z0,
                x1, y1, z0, x0, y1, z0,
                x0, y1, z0, x0, y0, z0,
                x0, y0, z1, x1, y0, z1,
                x1, y0, z1, x1, y1, z1,
                x1, y1, z1, x0, y1, z1,
                x0, y1, z1, x0, y0, z1,
                x0, y0, z0, x0, y0, z1,
                x1, y0, z0, x1, y0, z1,
                x1, y1, z0, x1, y1, z1,
                x0, y1, z0, x0, y1, z1
            )
            boundsBoxBuf = floatBufferOf(*box)
            boundsBoxCount = box.size / 3
        }

        var groundShadowBuf: FloatBuffer? = null
        var groundShadowVertCount = 0
        ArLightEstimatorMath.computeGroundShadowQuadBounds(bounds)?.let { sb ->
            val uMin = (sb.minX - sb.centerX) / sb.halfFootprintX
            val uMax = (sb.maxX - sb.centerX) / sb.halfFootprintX
            val vMin = (sb.minY - sb.centerY) / sb.halfFootprintY
            val vMax = (sb.maxY - sb.centerY) / sb.halfFootprintY
            val gz = sb.groundZ
            val quad = floatArrayOf(
                sb.minX, sb.minY, gz, uMin, vMin,
                sb.maxX, sb.minY, gz, uMax, vMin,
                sb.maxX, sb.maxY, gz, uMax, vMax,
                sb.minX, sb.minY, gz, uMin, vMin,
                sb.maxX, sb.maxY, gz, uMax, vMax,
                sb.minX, sb.maxY, gz, uMin, vMax
            )
            groundShadowBuf = floatBufferOf(*quad)
            groundShadowVertCount = 6
        }

        return GeometrySet(
            opaqueBatches = opaqueBatches,
            translucentBatches = translucentBatches,
            edgeBuffer = edgeBuffer,
            edgeCount = edgeCount,
            boundsBoxBuffer = boundsBoxBuf,
            boundsBoxVertexCount = boundsBoxCount,
            groundShadowBuffer = groundShadowBuf,
            groundShadowVertexCount = groundShadowVertCount,
            bounds = bounds
        )
    }

    private fun bindMeshBuffer(buf: FloatBuffer) {
        val stride = 12 * 4
        buf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(6)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(8)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, buf)
    }

    private fun disableMeshAttribs() {
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
    }

    private fun floatBufferOf(vararg vals: Float): FloatBuffer =
        ByteBuffer.allocateDirect(vals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vals)
            position(0)
        }

    private fun createProgram(vs: String, fs: String): Int {
        val status = IntArray(1)
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
        GLES30.glShaderSource(v, vs)
        GLES30.glCompileShader(v)
        GLES30.glGetShaderiv(v, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("ArCoreRenderer", "Vertex shader compile error: ${GLES30.glGetShaderInfoLog(v)}")
            GLES30.glDeleteShader(v)
            return 0
        }

        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
        GLES30.glShaderSource(f, fs)
        GLES30.glCompileShader(f)
        GLES30.glGetShaderiv(f, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("ArCoreRenderer", "Fragment shader compile error: ${GLES30.glGetShaderInfoLog(f)}")
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            return 0
        }

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, v)
        GLES30.glAttachShader(program, f)
        GLES30.glLinkProgram(program)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        if (status[0] == 0) {
            Log.e("ArCoreRenderer", "Program link error: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    companion object {
        internal const val BG_VERTEX = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTex;
out vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = aTex;
}"""

        internal const val BG_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vTex;
uniform samplerExternalOES sTex;
out vec4 fragColor;
void main() {
    fragColor = texture(sTex, vTex);
}"""

        private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        private const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF

        private const val SHADOW_MAP_SIZE = 1024

        internal const val SHADOW_DEPTH_VERTEX = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
uniform mat4 uLightMvp;
void main() {
    gl_Position = uLightMvp * vec4(aPos, 1.0);
}"""

        internal const val SHADOW_DEPTH_FRAGMENT = """#version 300 es
precision highp float;
void main() {
    // Depth-only pass writes gl_FragDepth automatically
}"""

        internal const val BASIC_MODEL_VERTEX = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;
uniform mat4 uMvp;
uniform mat4 uModelMat;
out vec3 vWorldNormal;
out vec2 vUv;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vec3 wn = mat3(uModelMat) * aNormal;
    float wnLen = length(wn);
    vWorldNormal = (wnLen > 1e-6) ? (wn / wnLen) : vec3(0.0, 1.0, 0.0);
    vUv = aUv;
    vColor = aColor;
}"""

        internal const val BASIC_MODEL_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
in vec3 vWorldNormal;
in vec2 vUv;
in vec4 vColor;
uniform vec3 uLightDir;
uniform int uUseTexture;
uniform mediump sampler2D uTex;
uniform vec3 uAmbientColor;
uniform float uSceneExposure;
out vec4 fragColor;
void main() {
    float nLen = length(vWorldNormal);
    vec3 n = (nLen > 1e-5) ? (vWorldNormal / nLen) : vec3(0.0, 1.0, 0.0);
    if (!gl_FrontFacing) n = -n;
    vec4 base = vColor;
    if (uUseTexture == 1) {
        vec4 tex = texture(uTex, vec2(vUv.x, 1.0 - vUv.y));
        if (tex.a < 0.08) discard;
        base = vec4(tex.rgb * base.rgb, tex.a * base.a);
    }
    float ndotl = max(dot(n, normalize(uLightDir)), 0.0);
    float fill = abs(dot(n, normalize(vec3(-0.6, 0.35, -0.5))));
    float hemi = 0.56 + 0.32 * ndotl + 0.14 * fill;
    vec3 lit = base.rgb * hemi * mix(vec3(1.0), uAmbientColor, 0.35) * clamp(uSceneExposure, 0.55, 1.35);
    fragColor = vec4(clamp(lit, 0.0, 1.0), base.a);
}"""

        internal const val MODEL_VERTEX = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;
uniform mat4 uMvp;
uniform mat4 uModelMat;
uniform mat4 uLightVp;
out vec3 vWorldPos;
out vec3 vWorldNormal;
out vec4 vLightSpacePos;
out float vModelZ;
out vec2 vUv;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vec4 wp = uModelMat * vec4(aPos, 1.0);
    vWorldPos = wp.xyz;
    vec3 wn = mat3(uModelMat) * aNormal;
    float wnLen = length(wn);
    vWorldNormal = (wnLen > 1e-6) ? (wn / wnLen) : vec3(0.0, 1.0, 0.0);
    vLightSpacePos = uLightVp * wp;
    vModelZ = aPos.z;
    vUv = aUv;
    vColor = aColor;
}"""

        internal const val MODEL_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
in vec3 vWorldPos;
in vec3 vWorldNormal;
in vec4 vLightSpacePos;
in float vModelZ;
in vec2 vUv;
in vec4 vColor;
uniform vec3 uLightDir;
uniform int uUseTexture;
uniform mediump sampler2D uTex;
uniform int uRealisticMode;
uniform vec3 uCameraWorldPos;
uniform vec3 uMainLightDir;
uniform vec3 uMainLightIntensity;
uniform vec3 uSphericalHarmonics[9];
uniform vec3 uAmbientColor;
uniform vec3 uFloorBounceColor;
uniform float uSceneExposure;
uniform float uShadowStrength;
uniform float uSpecularScale;
uniform float uAmbientFillScale;
uniform int uHasShadowMap;
uniform highp sampler2D uShadowMap;
uniform int uHasEnvCubemap;
uniform highp samplerCube uEnvCubemap;
uniform float uBoundsMinZ;
uniform float uBoundsHeight;
out vec4 fragColor;

vec3 evalSphericalHarmonics(vec3 n) {
    vec3 irr = uSphericalHarmonics[0]
        + uSphericalHarmonics[1] * n.y
        + uSphericalHarmonics[2] * n.z
        + uSphericalHarmonics[3] * n.x
        + uSphericalHarmonics[4] * (n.y * n.x)
        + uSphericalHarmonics[5] * (n.y * n.z)
        + uSphericalHarmonics[6] * (3.0 * n.z * n.z - 1.0)
        + uSphericalHarmonics[7] * (n.z * n.x)
        + uSphericalHarmonics[8] * (n.x * n.x - n.y * n.y);
    return max(irr, vec3(0.04));
}

float sampleShadowVisibility(vec4 lightSpacePos, vec3 n, vec3 l) {
    if (uHasShadowMap == 0 || lightSpacePos.w <= 0.0001) return 1.0;
    vec3 proj = lightSpacePos.xyz / lightSpacePos.w;
    proj = proj * 0.5 + 0.5;
    if (proj.x < 0.01 || proj.x > 0.99 || proj.y < 0.01 || proj.y > 0.99 || proj.z > 1.0) {
        return 1.0;
    }
    float ndotl = max(dot(n, l), 0.0);
    float bias = max(0.0018 * (1.0 - ndotl), 0.00075);
    vec2 texel = vec2(1.0 / 1024.0);
    float lit = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float mapDepth = texture(uShadowMap, proj.xy + vec2(float(x), float(y)) * texel * 1.35).r;
            lit += (proj.z - bias <= mapDepth) ? 1.0 : 0.0;
        }
    }
    return lit / 9.0;
}

vec3 acesToneMap(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    float nLen = length(vWorldNormal);
    vec3 n = (nLen > 1e-5) ? (vWorldNormal / nLen) : vec3(0.0, 1.0, 0.0);
    if (!gl_FrontFacing) n = -n;

    vec4 base = vColor;
    float texLuma = 0.5;
    if (uUseTexture == 1) {
        vec4 tex = texture(uTex, vec2(vUv.x, 1.0 - vUv.y));
        if (tex.a < 0.08) discard;
        texLuma = dot(tex.rgb, vec3(0.2126, 0.7152, 0.0722));
        if (uRealisticMode == 1) {
            // Soften overly harsh CAD texture contrast & moderate artificial saturation
            vec3 neutral = vec3(texLuma);
            vec3 balanced = mix(neutral, tex.rgb, 0.88);
            vec3 refinedTex = mix(balanced, balanced * 0.92 + vec3(0.025), 0.30);
            base = vec4(refinedTex * base.rgb, tex.a * base.a);
        } else {
            base = vec4(tex.rgb * base.rgb, tex.a * base.a);
        }
    }

    if (uRealisticMode == 0) {
        float ndotl = max(dot(n, normalize(uLightDir)), 0.0);
        float fill = abs(dot(n, normalize(vec3(-0.6, 0.35, -0.5))));
        float hemi = 0.56 + 0.32 * ndotl + 0.14 * fill;
        vec3 lit = base.rgb * hemi;
        if (!gl_FrontFacing && uUseTexture == 0) {
            lit = mix(lit, vec3(0.65, 0.72, 0.80), 0.30);
        }
        fragColor = vec4(lit, base.a);
        return;
    }

    // Subtle micro-surface normal detail derived from texture luminance gradients
    if (uUseTexture == 1) {
        vec3 dPdx = dFdx(vWorldPos);
        vec3 dPdy = dFdy(vWorldPos);
        float dLdx = dFdx(texLuma);
        float dLdy = dFdy(texLuma);
        float lenX = length(dPdx);
        float lenY = length(dPdy);
        if (lenX > 1e-4 && lenY > 1e-4) {
            vec3 tDir = dPdx / lenX;
            vec3 bDir = dPdy / lenY;
            vec3 perturb = (tDir * clamp(dLdx, -0.22, 0.22) + bDir * clamp(dLdy, -0.22, 0.22)) * 0.38;
            n = normalize(n - perturb);
        }
    }

    // Decode sRGB albedo into linear light space
    vec3 albedo = pow(max(base.rgb, vec3(0.002)), vec3(2.2));

    // Infer physically plausible PBR finish (glass, brushed steel, matte concrete/stone, wood)
    float maxC = max(base.r, max(base.g, base.b));
    float minC = min(base.r, min(base.g, base.b));
    float sat = (maxC > 0.001) ? (maxC - minC) / maxC : 0.0;
    float luma = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));

    float roughness = 0.50;
    float metallic = 0.06;
    float baseF0Scalar = 0.04;
    if (base.a < 0.86) {
        roughness = 0.12;
        metallic = 0.05;
        baseF0Scalar = 0.08;
    } else if (uUseTexture == 0 && luma < 0.32 && sat < 0.18) {
        // Structural dark steel / anodized metal
        roughness = 0.28;
        metallic = 0.68;
        baseF0Scalar = 0.06;
    } else if (uUseTexture == 0 && luma > 0.72 && sat < 0.15) {
        // Matte stone / concrete / white mineral baseplate (prevent artificial white glow)
        roughness = 0.82;
        metallic = 0.02;
        baseF0Scalar = 0.03;
        albedo = min(albedo, vec3(0.54));
    } else if (uUseTexture == 1) {
        roughness = 0.54;
        metallic = 0.04;
        baseF0Scalar = 0.04;
    }

    vec3 F0 = mix(vec3(baseF0Scalar), albedo, metallic);
    vec3 V = normalize(uCameraWorldPos - vWorldPos);
    vec3 L = normalize(uMainLightDir);
    vec3 H = normalize(V + L);

    float NdotV = max(dot(n, V), 0.001);
    float NdotL = max(dot(n, L), 0.0);
    float NdotH = max(dot(n, H), 0.0);
    float HdotV = max(dot(H, V), 0.0);

    // Cook-Torrance GGX Microfacet Specular + Soft-Wrapped Lambert Diffuse
    float a = roughness * roughness;
    float a2 = a * a;
    float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
    float D = a2 / (3.14159265 * denom * denom + 0.0001);

    float k = (roughness + 1.0);
    k = (k * k) / 8.0;
    float gV = NdotV / (NdotV * (1.0 - k) + k);
    float gL = NdotL / (NdotL * (1.0 - k) + k);
    float G = gV * gL;

    vec3 F = F0 + (vec3(1.0) - F0) * pow(clamp(1.0 - HdotV, 0.0, 1.0), 5.0);
    vec3 specDirect = (D * G * F) / max(4.0 * NdotV * NdotL, 0.001);
    vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);

    // Real-time 9-tap PCF self-shadowing (e.g. pergola slats casting shadows on bench/posts below)
    float shadowVis = sampleShadowVisibility(vLightSpacePos, n, L);
    float directShadowFactor = mix(1.0, shadowVis, clamp(uShadowStrength * 1.25 + 0.20, 0.20, 0.92));

    float wrappedDiffuse = mix(NdotL, max(dot(n, L) * 0.5 + 0.5, 0.0), 0.12);
    vec3 directLight = ((kD * albedo / 3.14159265) * uMainLightIntensity * wrappedDiffuse * 1.18
        + specDirect * uMainLightIntensity * NdotL * 1.25 * clamp(uSpecularScale, 0.30, 1.45)) * directShadowFactor;

    // Live ARCore 9-Band Spherical Harmonics diffuse irradiance + camera floor bounce + Environment reflections
    vec3 shIrradiance = evalSphericalHarmonics(n);
    vec3 R = reflect(-V, n);
    vec3 envReflection;
    if (uHasEnvCubemap == 1) {
        envReflection = textureLod(uEnvCubemap, R, roughness * 4.0).rgb;
    } else {
        float skyFactor = clamp(R.y * 0.5 + 0.5, 0.0, 1.0);
        vec3 groundBounce = mix(shIrradiance * 0.45, uFloorBounceColor * 1.35, 0.55);
        vec3 skyTint = shIrradiance * 1.25 + uMainLightIntensity * 0.18;
        envReflection = mix(groundBounce, skyTint, skyFactor);
    }

    vec3 F_env = F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(clamp(1.0 - NdotV, 0.0, 1.0), 5.0);
    vec3 kD_env = (vec3(1.0) - F_env) * (1.0 - metallic);

    // Subtle ground-contact height AO + hemisphere downward AO + self-shadow indirect occlusion
    float hFrac = clamp((vModelZ - uBoundsMinZ) / max(uBoundsHeight, 0.05), 0.0, 1.0);
    float contactAo = mix(0.68, 1.0, smoothstep(0.0, 0.22, hFrac));
    float hemiAo = mix(0.76, 1.0, clamp(n.y * 0.5 + 0.5, 0.0, 1.0));
    float shadowAmbientAo = mix(0.74, 1.0, shadowVis);
    float ao = contactAo * hemiAo * shadowAmbientAo;

    // Real-floor upward bounce tint on undersides
    vec3 floorBounceLight = kD_env * albedo * uFloorBounceColor * max(-n.y, 0.0) * 0.65;

    float envSpecWeight = (0.36 + 0.64 * (1.0 - roughness)) * clamp(uSpecularScale, 0.35, 1.35);
    vec3 ambientLight = (kD_env * albedo * shIrradiance * 0.90 * clamp(uAmbientFillScale, 0.65, 1.25)
        + floorBounceLight
        + F_env * envReflection * envSpecWeight) * ao;

    // Match real-scene camera exposure & room white-balance chromaticity
    vec3 roomTint = mix(vec3(1.0), uAmbientColor, 0.38);
    vec3 linearColor = (directLight + ambientLight) * uSceneExposure * roomTint;
    vec3 toneMapped = acesToneMap(linearColor);
    vec3 srgb = pow(max(toneMapped, vec3(0.0)), vec3(1.0 / 2.2));
    fragColor = vec4(srgb, base.a);
}"""

        internal const val SHADOW_VERTEX = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aFootprintUv;
uniform mat4 uMvp;
uniform mat4 uModelMat;
uniform mat4 uLightVp;
out vec2 vFootprintUv;
out vec4 vLightSpacePos;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vFootprintUv = aFootprintUv;
    vec4 wp = uModelMat * vec4(aPos, 1.0);
    vLightSpacePos = uLightVp * wp;
}"""

        internal const val SHADOW_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
in vec2 vFootprintUv;
in vec4 vLightSpacePos;
uniform int uHasShadowMap;
uniform highp sampler2D uShadowMap;
uniform vec2 uShadowOffset;
uniform float uShadowStrength;
uniform vec3 uShadowTint;
out vec4 fragColor;

float roundedBoxSdf(vec2 p, vec2 halfExtents, float radius) {
    vec2 q = abs(p) - halfExtents + vec2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float sampleGroundCastShadow(vec4 lightSpacePos) {
    if (lightSpacePos.w <= 0.0001) return 0.0;
    vec3 proj = lightSpacePos.xyz / lightSpacePos.w;
    proj = proj * 0.5 + 0.5;
    if (proj.x < 0.015 || proj.x > 0.985 || proj.y < 0.015 || proj.y > 0.985 || proj.z > 1.0) {
        return 0.0;
    }
    vec2 texel = vec2(1.0 / 1024.0);
    float bias = 0.0014;
    float occluded = 0.0;
    // Soft 9-tap PCF penumbra projected directly from real CAD geometry
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float mapDepth = texture(uShadowMap, proj.xy + vec2(float(x), float(y)) * texel * 1.9).r;
            occluded += (proj.z - bias > mapDepth) ? 1.0 : 0.0;
        }
    }
    float shadow = occluded / 9.0;
    // Soft fade near the frustum edges of the shadow map
    float border = min(min(proj.x, 1.0 - proj.x), min(proj.y, 1.0 - proj.y));
    float frustumFade = smoothstep(0.015, 0.09, border);
    return shadow * frustumFade;
}

void main() {
    float combined = 0.0;
    if (uHasShadowMap == 1) {
        float castShadow = sampleGroundCastShadow(vLightSpacePos);
        // Subtle tight ambient contact occlusion right under model footprint
        float dContact = roundedBoxSdf(vFootprintUv, vec2(0.94, 0.94), 0.24);
        float contactAo = (1.0 - smoothstep(-0.08, 0.34, dContact)) * 0.24;
        combined = clamp(max(castShadow * 0.92, contactAo) * uShadowStrength, 0.0, 0.72);
    } else {
        // Fallback soft contact + directional penumbra when shadow FBO is unavailable
        float dContact = roundedBoxSdf(vFootprintUv, vec2(0.96, 0.96), 0.22);
        float contactAlpha = 1.0 - smoothstep(-0.08, 0.42, dContact);
        float dCast = roundedBoxSdf(vFootprintUv - uShadowOffset, vec2(1.00, 1.00), 0.34);
        float castAlpha = 1.0 - smoothstep(-0.12, 0.85, dCast);
        float edgeFade = 1.0 - smoothstep(1.25, 1.78, max(abs(vFootprintUv.x), abs(vFootprintUv.y)));
        combined = max(contactAlpha * 0.72, castAlpha * 0.56) * uShadowStrength * edgeFade;
    }
    if (combined < 0.005) discard;
    fragColor = vec4(uShadowTint, clamp(combined, 0.0, 0.72));
}"""

        private const val LINE_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
}"""

        private const val LINE_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}"""

        private const val COLOR_LINE_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
uniform mat4 uMvp;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vColor = aColor;
}"""

        private const val COLOR_LINE_FRAGMENT = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() {
    fragColor = vColor;
}"""

        private const val POINT_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    gl_PointSize = 9.0;
}"""

        private const val POINT_FRAGMENT = """#version 300 es
precision mediump float;
out vec4 fragColor;
void main() {
    fragColor = vec4(0.18, 0.88, 0.96, 0.82);
}"""
    }
}
