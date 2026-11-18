package com.example.ui.ar

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
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

data class ArTrackingStatus(
    val planesCount: Int = 0,
    val featurePointCount: Int = 0,
    val isPlaced: Boolean = false,
    val phase: ObjectTrackingPhase = ObjectTrackingPhase.SCANNING,
    val targetDescription: String = "Point camera at a surface or 3D object",
    val anchorDistanceMeters: Float? = null
)

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
    var viewportWidth: Int = 1

    @Volatile
    var viewportHeight: Int = 1

    @Volatile
    private var displayGeometryDirty: Boolean = true

    private var hasTransformedTexCoords: Boolean = false

    @Volatile
    var modelScale: Float = ArScaleMath.recommendedInitialScale(document.bounds)
        set(value) {
            field = ArScaleMath.clampScale(value)
        }

    @Volatile
    var rotationDeg: Float = 0f

    @Volatile
    var verticalOffsetMeters: Float = 0f

    @Volatile
    var anchorOrigin: ArAnchorOrigin = ArAnchorOrigin.AXIS

    @Volatile
    var alwaysLevel: Boolean = true

    @Volatile
    var showFoundation: Boolean = false

    @Volatile
    var showTextures: Boolean = true

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
    private var currentPhase: ObjectTrackingPhase = ObjectTrackingPhase.SCANNING

    @Volatile
    private var currentTargetLabel: String = "Point camera at an object or surface"

    private var cameraTexId = 0
    private var bgProgram = 0
    private var uBgTex = 0

    private var modelProgram = 0
    private var uMvpModel = 0
    private var uModelMat = 0
    private var uLightDir = 0
    private var uUseTexture = 0
    private var uTextureSampler = 0

    private var lineProgram = 0
    private var uMvpLine = 0
    private var uLineColor = 0

    private var colorLineProgram = 0
    private var uMvpColorLine = 0

    private var pointProgram = 0
    private var uMvpPoint = 0

    private val textureIds = HashMap<String, Int>()

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
    private var pendingCaptureWidthMeters: Float? = null

    private var captureCallback: ((Boolean, String) -> Unit)? = null

    fun requestPlaceAt(x: Float, y: Float) {
        pendingTapX = x
        pendingTapY = y
    }

    fun requestPlaceAtCenter() {
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

    fun clearAnchor() {
        anchor?.detach()
        anchor = null
        liveTrackedPose = null
        currentPhase = if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
            ObjectTrackingPhase.TARGET_SEARCHING
        } else {
            ObjectTrackingPhase.SCANNING
        }
        currentTargetLabel = if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
            "Aim center frame at object & tap Capture Target"
        } else {
            "Tap any object or surface to pin model"
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        cameraTexId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        bgProgram = createProgram(BG_VERTEX, BG_FRAGMENT)
        uBgTex = GLES30.glGetUniformLocation(bgProgram, "sTex")

        modelProgram = createProgram(MODEL_VERTEX, MODEL_FRAGMENT)
        uMvpModel = GLES30.glGetUniformLocation(modelProgram, "uMvp")
        uModelMat = GLES30.glGetUniformLocation(modelProgram, "uModelMat")
        uLightDir = GLES30.glGetUniformLocation(modelProgram, "uLightDir")
        uUseTexture = GLES30.glGetUniformLocation(modelProgram, "uUseTexture")
        uTextureSampler = GLES30.glGetUniformLocation(modelProgram, "uTex")

        lineProgram = createProgram(LINE_VERTEX, LINE_FRAGMENT)
        uMvpLine = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        uLineColor = GLES30.glGetUniformLocation(lineProgram, "uColor")

        colorLineProgram = createProgram(COLOR_LINE_VERTEX, COLOR_LINE_FRAGMENT)
        uMvpColorLine = GLES30.glGetUniformLocation(colorLineProgram, "uMvp")

        pointProgram = createProgram(POINT_VERTEX, POINT_FRAGMENT)
        uMvpPoint = GLES30.glGetUniformLocation(pointProgram, "uMvp")

        uploadTextures(document.textures)
        buildModelBuffers()
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
            arSession.setCameraTextureName(cameraTexId)
            arSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            val frame: Frame = arSession.update()

            ensureTransformedBackgroundTexCoords(frame)
            val isVr = vrModeEnabled

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                if (isVr) {
                    renderVrBackgroundOnly()
                } else {
                    drawCameraBackground(null)
                }
                onTrackingUpdated(
                    ArTrackingStatus(
                        planesCount = 0,
                        featurePointCount = 0,
                        isPlaced = anchor != null || liveTrackedPose != null,
                        phase = ObjectTrackingPhase.SCANNING,
                        targetDescription = "Initializing camera tracking — move slowly"
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

            // Handle tap / center reticle object placement
            val tx = pendingTapX
            val ty = pendingTapY
            if (tx != null && ty != null) {
                pendingTapX = null
                pendingTapY = null
                val chosenHit = selectBestObjectOrSurfaceHit(frame.hitTest(tx, ty))
                if (chosenHit != null) {
                    anchor?.detach()
                    anchor = chosenHit.hit.createAnchor()
                    liveTrackedPose = null
                    currentPhase = ObjectTrackingPhase.PINNED_TO_OBJECT
                    currentTargetLabel = chosenHit.label
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

            val pointCount = uploadFeaturePointCloud(frame)

            val activePose: Pose? = when {
                trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER && liveTrackedPose != null -> liveTrackedPose
                anchor?.trackingState == TrackingState.TRACKING -> anchor?.pose
                else -> null
            }

            val anchorDist = activePose?.let { poseEuclideanDistance(camera.pose, it) }

            onTrackingUpdated(
                ArTrackingStatus(
                    planesCount = trackingPlanes,
                    featurePointCount = pointCount,
                    isPlaced = activePose != null,
                    phase = currentPhase,
                    targetDescription = currentTargetLabel,
                    anchorDistanceMeters = anchorDist
                )
            )

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

            if (!isVr) {
                drawCameraBackground(null)
                renderEyeOverlay(
                    eyeViewMat = viewMat,
                    eyeProjMat = projMat,
                    pointCount = pointCount,
                    geom = geom,
                    worldMat = worldMat
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
                            pointCount = pointCount,
                            geom = geom,
                            worldMat = worldMat
                        )
                    }
                } finally {
                    GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                    GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
                }
            }
        } catch (_: Throwable) {
            // Gracefully absorb transient ARCore frame exceptions
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

        val activeBounds = geom.bounds
        val (alignX, alignY, alignZ) = when (anchorOrigin) {
            ArAnchorOrigin.AXIS -> Triple(0f, 0f, 0f)
            ArAnchorOrigin.BASE -> Triple(
                activeBounds.center.x.toFloat(),
                activeBounds.center.y.toFloat(),
                activeBounds.min.z.toFloat()
            )
            ArAnchorOrigin.CENTER -> Triple(
                activeBounds.center.x.toFloat(),
                activeBounds.center.y.toFloat(),
                activeBounds.center.z.toFloat()
            )
        }

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
        pointCount: Int,
        geom: GeometrySet?,
        worldMat: FloatArray?
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

        drawGeometryBatches(geom, worldMat, mvpMat)
        drawEdgesAndBounds(geom, mvpMat)
        drawAxisGizmo(mvpMat)
    }

    private fun drawGeometryBatches(
        geom: GeometrySet,
        worldMat: FloatArray,
        mvpMat: FloatArray
    ) {
        if (geom.opaqueBatches.isEmpty() && geom.translucentBatches.isEmpty()) return

        GLES30.glUseProgram(modelProgram)
        GLES30.glUniformMatrix4fv(uMvpModel, 1, false, mvpMat, 0)
        GLES30.glUniformMatrix4fv(uModelMat, 1, false, worldMat, 0)
        // World-space Earth-gravity upper sun + ambient fill
        GLES30.glUniform3f(uLightDir, 0.35f, 0.85f, 0.40f)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.0f, 1.0f)

        fun renderBatchList(batches: List<MeshBatch>) {
            for (batch in batches) {
                val texId = if (showTextures && batch.textureKey != null) textureIds[batch.textureKey] else null
                if (texId != null) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                    GLES30.glUniform1i(uTextureSampler, 1)
                    GLES30.glUniform1i(uUseTexture, 1)
                } else {
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
    }

    private fun drawEdgesAndBounds(geom: GeometrySet, mvpMat: FloatArray) {
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
    }

    private fun drawAxisGizmo(mvpMat: FloatArray) {
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

    private data class RankedHit(val hit: HitResult, val priority: Int, val label: String)

    private fun selectBestObjectOrSurfaceHit(hits: List<HitResult>): RankedHit? {
        var best: RankedHit? = null
        for (hit in hits) {
            val t = hit.trackable
            if (t.trackingState != TrackingState.TRACKING) continue
            val candidate: RankedHit? = when {
                t is Plane && t.isPoseInPolygon(hit.hitPose) ->
                    RankedHit(
                        hit,
                        0,
                        if (t.type == Plane.Type.VERTICAL) "Anchored to vertical surface"
                        else "Anchored to horizontal surface"
                    )
                t is DepthPoint && hit.distance >= 0.15f ->
                    RankedHit(hit, 1, "Anchored to 3D Depth object")
                t is Point && hit.distance >= 0.15f &&
                    t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL ->
                    RankedHit(hit, 2, "Anchored to 3D object feature")
                t is Point && hit.distance >= 0.20f ->
                    RankedHit(hit, 3, "Pinned to 3D feature point")
                t is InstantPlacementPoint ->
                    RankedHit(hit, 4, "Pinned via instant placement")
                else -> null
            }
            if (candidate != null && (best == null || candidate.priority < best.priority)) {
                best = candidate
            }
        }
        return best
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
        if (added <= 0) return
        val buf = pointCloudBuffer ?: return
        buf.position(0)
        GLES30.glUseProgram(pointProgram)
        GLES30.glUniformMatrix4fv(uMvpPoint, 1, false, vpMat, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, buf)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, added)
        GLES30.glDisableVertexAttribArray(0)
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
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun uploadTextures(textures: Map<String, Bitmap>) {
        if (textureIds.isNotEmpty()) {
            val arr = textureIds.values.toIntArray()
            GLES30.glDeleteTextures(arr.size, arr, 0)
            textureIds.clear()
        }
        if (textures.isEmpty()) return
        val handles = IntArray(textures.size)
        GLES30.glGenTextures(textures.size, handles, 0)
        var i = 0
        for ((key, bmp) in textures) {
            if (bmp.isRecycled) continue
            val id = handles[i++]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            textureIds[key] = id
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

        return GeometrySet(
            opaqueBatches = opaqueBatches,
            translucentBatches = translucentBatches,
            edgeBuffer = edgeBuffer,
            edgeCount = edgeCount,
            boundsBoxBuffer = boundsBoxBuf,
            boundsBoxVertexCount = boundsBoxCount,
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
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            GLES30.glShaderSource(it, vs); GLES30.glCompileShader(it)
        }
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            GLES30.glShaderSource(it, fs); GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v)
            GLES30.glAttachShader(it, f)
            GLES30.glLinkProgram(it)
        }
    }

    companion object {
        private const val BG_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTex;
out vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = aTex;
}"""

        private const val BG_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTex;
uniform samplerExternalOES sTex;
out vec4 fragColor;
void main() {
    fragColor = texture(sTex, vTex);
}"""

        private const val MODEL_VERTEX = """#version 300 es
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
    vWorldNormal = mat3(uModelMat) * aNormal;
    vUv = aUv;
    vColor = aColor;
}"""

        private const val MODEL_FRAGMENT = """#version 300 es
precision mediump float;
in vec3 vWorldNormal;
in vec2 vUv;
in vec4 vColor;
uniform vec3 uLightDir;
uniform int uUseTexture;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vWorldNormal);
    if (!gl_FrontFacing) n = -n;
    float ndotl = max(dot(n, normalize(uLightDir)), 0.0);
    float fill = abs(dot(n, normalize(vec3(-0.6, 0.35, -0.5))));
    float hemi = 0.56 + 0.32 * ndotl + 0.14 * fill;
    vec4 base = vColor;
    if (uUseTexture == 1) {
        vec4 tex = texture(uTex, vec2(vUv.x, 1.0 - vUv.y));
        if (tex.a < 0.08) discard;
        base = vec4(tex.rgb * base.rgb, tex.a * base.a);
    }
    vec3 lit = base.rgb * hemi;
    if (!gl_FrontFacing && uUseTexture == 0) {
        lit = mix(lit, vec3(0.65, 0.72, 0.80), 0.30);
    }
    fragColor = vec4(lit, base.a);
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
