package com.example.ui.viewer.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.example.data.local.AngleMeasurementEntity
import com.example.data.local.LengthMeasurementEntity
import com.example.domain.math.AngleMath
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Ray3D
import com.example.domain.math.SnapEngine
import com.example.domain.math.SnapHit
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.model.CadDocument
import com.example.domain.model.CadTool
import com.example.domain.model.DisplayStyle
import com.example.domain.model.SnapType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

sealed interface LengthDotTarget {
    data class SavedEndpoint(val lengthId: Long, val isStartA: Boolean) : LengthDotTarget
    data class ActivePick(val index: Int) : LengthDotTarget
}

data class ActiveLengthDotDrag(
    val target: LengthDotTarget,
    val originalPoint: Vec3,
    val currentPoint: Vec3,
    val snapType: SnapType? = null
)

object LengthDotDragMath {

    data class HitDot(
        val target: LengthDotTarget,
        val point: Vec3,
        val screenPos: Vec2,
        val distancePx: Double
    )

    fun findHitLengthDot(
        screenX: Float,
        screenY: Float,
        savedLengths: List<LengthMeasurementEntity>,
        activeLengthPicks: List<Vec3>,
        projectToScreen: (Vec3) -> Vec2?,
        hitRadiusPx: Double = 64.0
    ): HitDot? {
        var best: HitDot? = null
        var bestDist = hitRadiusPx

        fun checkCandidate(target: LengthDotTarget, pt: Vec3) {
            val sp = projectToScreen(pt) ?: return
            val dist = hypot(sp.x - screenX, sp.y - screenY)
            if (dist <= bestDist) {
                bestDist = dist
                best = HitDot(target = target, point = pt, screenPos = sp, distancePx = dist)
            }
        }

        // Prioritize active in-progress picks first when ties occur
        for (i in activeLengthPicks.indices) {
            checkCandidate(LengthDotTarget.ActivePick(i), activeLengthPicks[i])
        }
        for (len in savedLengths) {
            checkCandidate(
                LengthDotTarget.SavedEndpoint(len.id, isStartA = true),
                Vec3(len.ax, len.ay, len.az)
            )
            checkCandidate(
                LengthDotTarget.SavedEndpoint(len.id, isStartA = false),
                Vec3(len.bx, len.by, len.bz)
            )
        }
        return best
    }

    fun intersectRayWithPlane(ray: Ray3D, planeOrigin: Vec3, planeNormal: Vec3): Vec3? {
        val denom = ray.direction.dot(planeNormal)
        if (abs(denom) < 1e-6) return null
        val t = (planeOrigin - ray.origin).dot(planeNormal) / denom
        if (!t.isFinite() || t <= 0.0) return null
        return ray.origin + ray.direction * t
    }

    fun resolveDraggedPoint(
        doc: CadDocument,
        hiddenLayers: Set<String>,
        hiddenNodes: Set<String>,
        screenX: Float,
        screenY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        projectToScreen: (Vec3) -> Vec2?,
        rayFromScreen: (Float, Float) -> Ray3D?,
        cameraForward: Vec3,
        planeOrigin: Vec3,
        explodeOffsets: Map<String, Vec3> = emptyMap(),
        snapRadiusPx: Double = 52.0
    ): Pair<Vec3, SnapHit?>? {
        val snap = SnapEngine.findBestSnap(
            doc = doc,
            hiddenLayers = hiddenLayers,
            hiddenNodes = hiddenNodes,
            screenX = screenX,
            screenY = screenY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            projectToScreen = projectToScreen,
            rayFromScreen = rayFromScreen,
            snapRadiusPx = snapRadiusPx,
            explodeOffsets = explodeOffsets
        )
        if (snap != null) {
            return snap.point to snap
        }
        val ray = rayFromScreen(screenX, screenY) ?: return null
        val fallbackPt = intersectRayWithPlane(ray, planeOrigin, cameraForward) ?: return null
        return fallbackPt to null
    }

    fun effectiveSavedLengths(
        savedLengths: List<LengthMeasurementEntity>,
        activeDrag: ActiveLengthDotDrag?
    ): List<LengthMeasurementEntity> {
        val target = activeDrag?.target as? LengthDotTarget.SavedEndpoint ?: return savedLengths
        return savedLengths.map { len ->
            if (len.id != target.lengthId) {
                len
            } else {
                val p = activeDrag.currentPoint
                val a = if (target.isStartA) p else Vec3(len.ax, len.ay, len.az)
                val b = if (target.isStartA) Vec3(len.bx, len.by, len.bz) else p
                len.copy(
                    ax = a.x, ay = a.y, az = a.z,
                    bx = b.x, by = b.y, bz = b.z,
                    lengthMeters = a.distanceTo(b)
                )
            }
        }
    }

    fun effectiveActivePicks(
        activeLengthPicks: List<Vec3>,
        activeDrag: ActiveLengthDotDrag?
    ): List<Vec3> {
        val target = activeDrag?.target as? LengthDotTarget.ActivePick ?: return activeLengthPicks
        if (target.index !in activeLengthPicks.indices) return activeLengthPicks
        return activeLengthPicks.mapIndexed { idx, pt ->
            if (idx == target.index) activeDrag.currentPoint else pt
        }
    }
}

enum class AnglePointRole {
    VERTEX,
    RAY_A,
    RAY_B
}

sealed interface AngleDotTarget {
    data class SavedPoint(val angleId: Long, val role: AnglePointRole) : AngleDotTarget
    data class ActivePick(val index: Int) : AngleDotTarget
}

data class ActiveAngleDotDrag(
    val target: AngleDotTarget,
    val originalPoint: Vec3,
    val currentPoint: Vec3,
    val snapType: SnapType? = null
)

object AngleDotDragMath {

    data class HitDot(
        val target: AngleDotTarget,
        val point: Vec3,
        val screenPos: Vec2,
        val distancePx: Double
    )

    fun findHitAngleDot(
        screenX: Float,
        screenY: Float,
        savedAngles: List<AngleMeasurementEntity>,
        activeAnglePicks: List<Vec3>,
        projectToScreen: (Vec3) -> Vec2?,
        hitRadiusPx: Double = 64.0
    ): HitDot? {
        var best: HitDot? = null
        var bestDist = hitRadiusPx

        fun checkCandidate(target: AngleDotTarget, pt: Vec3) {
            val sp = projectToScreen(pt) ?: return
            val dist = hypot(sp.x - screenX, sp.y - screenY)
            if (dist <= bestDist) {
                bestDist = dist
                best = HitDot(target = target, point = pt, screenPos = sp, distancePx = dist)
            }
        }

        for (i in activeAnglePicks.indices) {
            checkCandidate(AngleDotTarget.ActivePick(i), activeAnglePicks[i])
        }
        for (ang in savedAngles) {
            checkCandidate(
                AngleDotTarget.SavedPoint(ang.id, AnglePointRole.VERTEX),
                Vec3(ang.vx, ang.vy, ang.vz)
            )
            checkCandidate(
                AngleDotTarget.SavedPoint(ang.id, AnglePointRole.RAY_A),
                Vec3(ang.ax, ang.ay, ang.az)
            )
            checkCandidate(
                AngleDotTarget.SavedPoint(ang.id, AnglePointRole.RAY_B),
                Vec3(ang.bx, ang.by, ang.bz)
            )
        }
        return best
    }

    fun effectiveSavedAngles(
        savedAngles: List<AngleMeasurementEntity>,
        activeDrag: ActiveAngleDotDrag?
    ): List<AngleMeasurementEntity> {
        val target = activeDrag?.target as? AngleDotTarget.SavedPoint ?: return savedAngles
        return savedAngles.map { ang ->
            if (ang.id != target.angleId) {
                ang
            } else {
                val p = activeDrag.currentPoint
                val vertex = if (target.role == AnglePointRole.VERTEX) p else Vec3(ang.vx, ang.vy, ang.vz)
                val rayA = if (target.role == AnglePointRole.RAY_A) p else Vec3(ang.ax, ang.ay, ang.az)
                val rayB = if (target.role == AnglePointRole.RAY_B) p else Vec3(ang.bx, ang.by, ang.bz)
                val comp = AngleMath.computeThreePointAngle(rayA, vertex, rayB)
                ang.copy(
                    vx = vertex.x, vy = vertex.y, vz = vertex.z,
                    ax = rayA.x, ay = rayA.y, az = rayA.z,
                    bx = rayB.x, by = rayB.y, bz = rayB.z,
                    angleDegrees = comp.degrees
                )
            }
        }
    }

    fun effectiveActivePicks(
        activeAnglePicks: List<Vec3>,
        activeDrag: ActiveAngleDotDrag?
    ): List<Vec3> {
        val target = activeDrag?.target as? AngleDotTarget.ActivePick ?: return activeAnglePicks
        if (target.index !in activeAnglePicks.indices) return activeAnglePicks
        return activeAnglePicks.mapIndexed { idx, pt ->
            if (idx == target.index) activeDrag.currentPoint else pt
        }
    }
}

sealed interface MeasurementContextTarget {
    data class Length(val entity: LengthMeasurementEntity) : MeasurementContextTarget
    data class Angle(val entity: AngleMeasurementEntity) : MeasurementContextTarget
}

object MeasurementHitMath {

    data class HitMeasurement(
        val target: MeasurementContextTarget,
        val screenPos: Vec2,
        val distancePx: Double
    )

    fun distanceToSegment2D(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-8) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return hypot(px - cx, py - cy)
    }

    fun findHitSavedMeasurement(
        screenX: Float,
        screenY: Float,
        activeTool: CadTool,
        savedLengths: List<LengthMeasurementEntity>,
        savedAngles: List<AngleMeasurementEntity>,
        projectToScreen: (Vec3) -> Vec2?,
        hitRadiusPx: Double = 72.0
    ): HitMeasurement? {
        val sx = screenX.toDouble()
        val sy = screenY.toDouble()

        var bestLength: HitMeasurement? = null
        var bestLengthDist = hitRadiusPx
        for (len in savedLengths) {
            val spA = projectToScreen(Vec3(len.ax, len.ay, len.az))
            val spB = projectToScreen(Vec3(len.bx, len.by, len.bz))
            val spMid = projectToScreen(
                Vec3((len.ax + len.bx) * 0.5, (len.ay + len.by) * 0.5, (len.az + len.bz) * 0.5)
            )
            var minDist = Double.POSITIVE_INFINITY
            if (spA != null && spB != null) {
                minDist = minOf(minDist, distanceToSegment2D(sx, sy, spA.x, spA.y, spB.x, spB.y))
            }
            if (spA != null) minDist = minOf(minDist, hypot(sx - spA.x, sy - spA.y))
            if (spB != null) minDist = minOf(minDist, hypot(sx - spB.x, sy - spB.y))
            if (spMid != null) minDist = minOf(minDist, hypot(sx - spMid.x, sy - spMid.y))
            if (minDist <= bestLengthDist) {
                bestLengthDist = minDist
                bestLength = HitMeasurement(
                    target = MeasurementContextTarget.Length(len),
                    screenPos = spMid ?: Vec2(sx, sy),
                    distancePx = minDist
                )
            }
        }

        var bestAngle: HitMeasurement? = null
        var bestAngleDist = hitRadiusPx
        for (ang in savedAngles) {
            val v = Vec3(ang.vx, ang.vy, ang.vz)
            val a = Vec3(ang.ax, ang.ay, ang.az)
            val b = Vec3(ang.bx, ang.by, ang.bz)
            val spV = projectToScreen(v)
            val spA = projectToScreen(a)
            val spB = projectToScreen(b)
            var minDist = Double.POSITIVE_INFINITY
            if (spV != null && spA != null) {
                minDist = minOf(minDist, distanceToSegment2D(sx, sy, spV.x, spV.y, spA.x, spA.y))
            }
            if (spV != null && spB != null) {
                minDist = minOf(minDist, distanceToSegment2D(sx, sy, spV.x, spV.y, spB.x, spB.y))
            }
            if (spV != null) minDist = minOf(minDist, hypot(sx - spV.x, sy - spV.y))
            if (spA != null) minDist = minOf(minDist, hypot(sx - spA.x, sy - spA.y))
            if (spB != null) minDist = minOf(minDist, hypot(sx - spB.x, sy - spB.y))

            val arcPts = AngleMath.buildAngleArc(v, a - v, b - v)
            for (i in 0 until arcPts.size - 1) {
                val p0 = projectToScreen(arcPts[i])
                val p1 = projectToScreen(arcPts[i + 1])
                if (p0 != null && p1 != null) {
                    minDist = minOf(minDist, distanceToSegment2D(sx, sy, p0.x, p0.y, p1.x, p1.y))
                }
            }

            if (minDist <= bestAngleDist) {
                bestAngleDist = minDist
                bestAngle = HitMeasurement(
                    target = MeasurementContextTarget.Angle(ang),
                    screenPos = spV ?: Vec2(sx, sy),
                    distancePx = minDist
                )
            }
        }

        return when {
            bestLength != null && bestAngle != null -> when (activeTool) {
                CadTool.MEASURE_LENGTH -> if (bestLength.distancePx <= bestAngle.distancePx + 12.0) bestLength else bestAngle
                CadTool.MEASURE_ANGLE -> if (bestAngle.distancePx <= bestLength.distancePx + 12.0) bestAngle else bestLength
                else -> if (bestLength.distancePx <= bestAngle.distancePx) bestLength else bestAngle
            }
            bestLength != null -> bestLength
            else -> bestAngle
        }
    }
}

data class OverlayRenderState(
    val selectedFaceIds: Set<String> = emptySet(),
    val selectedEdgeIds: Set<String> = emptySet(),
    val selectedNodeIds: Set<String> = emptySet(),
    val highlightedCutNodeIds: Set<String> = emptySet(),
    val explodeOffsets: Map<String, Vec3> = emptyMap(),
    val hiddenLayers: Set<String> = emptySet(),
    val hiddenNodes: Set<String> = emptySet(),
    val displayStyle: DisplayStyle = DisplayStyle.SHADED_EDGES,
    val darkCadMode: Boolean = false,
    val activeLengthPicks: List<Vec3> = emptyList(),
    val activeAnglePicks: List<Vec3> = emptyList(),
    val activeSnap: SnapHit? = null,
    val activeLengthDotDrag: ActiveLengthDotDrag? = null,
    val activeAngleDotDrag: ActiveAngleDotDrag? = null,
    val arTrackingDot: com.example.ui.ar.ArModelTrackingDot? = null,
    val savedLengths: List<LengthMeasurementEntity> = emptyList(),
    val savedAngles: List<AngleMeasurementEntity> = emptyList(),
    val isolatedNodeIds: Set<String>? = null
)

class CadGlRenderer(
    val camera: CadCamera,
    onCameraMatrixUpdated: () -> Unit = {}
) : GLSurfaceView.Renderer {

    @Volatile
    private var matrixUpdateListener: () -> Unit = onCameraMatrixUpdated

    private var lastNotifiedMvp: FloatArray? = null

    fun setOnCameraMatrixUpdated(listener: () -> Unit) {
        matrixUpdateListener = listener
    }

    private fun notifyIfMatrixChanged(mvp: FloatArray) {
        val prev = lastNotifiedMvp
        if (prev == null || !prev.contentEquals(mvp)) {
            lastNotifiedMvp = mvp.copyOf()
            matrixUpdateListener()
        }
    }

    private var meshProgram = 0
    private var lineProgram = 0

    // Mesh shader uniforms
    private var uMvpMesh = 0
    private var uLightDir = 0
    private var uUseTexture = 0
    private var uTextureSampler = 0
    private var uMonochrome = 0
    private var uAlphaScale = 0
    private var uGlowHighlight = 0

    // Line shader uniforms
    private var uMvpLine = 0

    private val textureIds = HashMap<String, Int>()
    private var gridVertexBuffer: FloatBuffer? = null
    private var gridVertexCount = 0
    private var glViewportW = 1
    private var glViewportH = 1

    @Volatile
    private var currentDoc: CadDocument? = null

    @Volatile
    private var renderState = OverlayRenderState()

    @Volatile
    private var geometryDirty = false

    @Volatile
    private var texturesDirty = false

    private data class SnapshotRequest(
        val isolatedNodeIds: Set<String>?,
        val onCaptured: (Bitmap?) -> Unit
    )

    @Volatile
    private var pendingSnapshotRequest: SnapshotRequest? = null

    private data class MeshBatch(
        val textureKey: String?,
        val buffer: FloatBuffer,
        val vertexCount: Int
    )

    private var opaqueBatches: List<MeshBatch> = emptyList()
    private var selectedBatch: MeshBatch? = null
    private var cutHighlightBatch: MeshBatch? = null
    private var edgeBuffer: FloatBuffer? = null
    private var edgeVertexCount = 0
    private var selectedEdgeBuffer: FloatBuffer? = null
    private var selectedEdgeVertexCount = 0
    private var cutHighlightEdgeBuffer: FloatBuffer? = null
    private var cutHighlightEdgeVertexCount = 0

    fun setDocument(doc: CadDocument?) {
        currentDoc = doc
        texturesDirty = true
        geometryDirty = true
    }

    fun requestSnapshot(
        isolatedNodeIds: Set<String>? = null,
        onCaptured: (Bitmap?) -> Unit
    ) {
        pendingSnapshotRequest = SnapshotRequest(
            isolatedNodeIds = isolatedNodeIds?.takeIf { it.isNotEmpty() },
            onCaptured = onCaptured
        )
    }

    fun updateOverlayState(state: OverlayRenderState) {
        val prev = renderState
        val needRebuild = prev.hiddenLayers != state.hiddenLayers ||
            prev.hiddenNodes != state.hiddenNodes ||
            prev.selectedFaceIds != state.selectedFaceIds ||
            prev.selectedEdgeIds != state.selectedEdgeIds ||
            prev.selectedNodeIds != state.selectedNodeIds ||
            prev.highlightedCutNodeIds != state.highlightedCutNodeIds ||
            prev.explodeOffsets != state.explodeOffsets ||
            prev.displayStyle != state.displayStyle ||
            prev.isolatedNodeIds != state.isolatedNodeIds
        renderState = state
        if (needRebuild) {
            geometryDirty = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        uMvpMesh = GLES30.glGetUniformLocation(meshProgram, "uMvp")
        uLightDir = GLES30.glGetUniformLocation(meshProgram, "uLightDir")
        uUseTexture = GLES30.glGetUniformLocation(meshProgram, "uUseTexture")
        uTextureSampler = GLES30.glGetUniformLocation(meshProgram, "uTex")
        uMonochrome = GLES30.glGetUniformLocation(meshProgram, "uMonochrome")
        uAlphaScale = GLES30.glGetUniformLocation(meshProgram, "uAlphaScale")
        uGlowHighlight = GLES30.glGetUniformLocation(meshProgram, "uGlowHighlight")

        lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        uMvpLine = GLES30.glGetUniformLocation(lineProgram, "uMvp")

        buildGridAndAxesBuffer()
        texturesDirty = true
        geometryDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewportW = width.coerceAtLeast(1)
        glViewportH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, glViewportW, glViewportH)
        val mvp = camera.updateMatrices(glViewportW, glViewportH)
        notifyIfMatrixChanged(mvp)
    }

    override fun onDrawFrame(gl: GL10?) {
        val state = renderState
        if (state.darkCadMode) {
            GLES30.glClearColor(0.043f, 0.067f, 0.125f, 1f) // #0B1120
        } else {
            GLES30.glClearColor(0.957f, 0.965f, 0.973f, 1f) // #F4F6F8
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val doc = currentDoc
        if (texturesDirty && doc != null) {
            uploadTextures(doc.textures)
            texturesDirty = false
        }

        // Handle isolated selected-parts snapshot BEFORE drawing the live viewport frame,
        // then restore camera & batches so the user sees zero flicker on screen.
        val snapReq = pendingSnapshotRequest
        var capturedIsolatedSnap = false
        if (snapReq != null && !snapReq.isolatedNodeIds.isNullOrEmpty() && doc != null) {
            pendingSnapshotRequest = null
            capturedIsolatedSnap = true
            val isolatedState = state.copy(
                isolatedNodeIds = snapReq.isolatedNodeIds,
                highlightedCutNodeIds = emptySet(),
                selectedFaceIds = emptySet(),
                selectedEdgeIds = emptySet(),
                selectedNodeIds = emptySet(),
                savedLengths = emptyList(),
                savedAngles = emptyList(),
                activeLengthPicks = emptyList(),
                activeAnglePicks = emptyList(),
                activeSnap = null,
                activeLengthDotDrag = null,
                activeAngleDotDrag = null
            )
            rebuildGeometryBatches(doc, isolatedState)

            val savedTarget = camera.target
            val savedDistance = camera.distance
            val isolatedBounds = computeIsolatedBounds(doc, snapReq.isolatedNodeIds, state.explodeOffsets)
            if (isolatedBounds.isValid) {
                camera.target = isolatedBounds.center
                camera.distance = camera.targetDistanceForBounds(isolatedBounds, paddingFactor = 1.05)
            }
            val isolatedMvp = camera.updateMatrices(glViewportW, glViewportH)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            drawGridAndAxes(isolatedMvp)
            if (isolatedState.displayStyle != DisplayStyle.WIREFRAME) {
                drawMeshBatches(isolatedMvp, isolatedState)
            }
            if (isolatedState.displayStyle != DisplayStyle.TEXTURED) {
                drawEdges(isolatedMvp, isolatedState)
            }
            GLES30.glFinish()
            val bmp = captureFramebufferBitmap(glViewportW, glViewportH)

            // Restore live camera and geometry before rendering the visible frame
            camera.target = savedTarget
            camera.distance = savedDistance
            rebuildGeometryBatches(doc, state)
            geometryDirty = false
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            snapReq.onCaptured(bmp)
        } else if (geometryDirty) {
            rebuildGeometryBatches(doc, state)
            geometryDirty = false
        }

        val mvp = camera.updateMatrices(camera.viewportWidth, camera.viewportHeight)
        notifyIfMatrixChanged(mvp)

        // 1. Draw Ground Reference Grid & RGB Axes
        drawGridAndAxes(mvp)

        // 2. Draw Shaded / Textured Faces
        if (state.displayStyle != DisplayStyle.WIREFRAME) {
            drawMeshBatches(mvp, state)
        }

        // 3. Draw Model Edges, Selected Edges & Cut-List Highlight Edges
        if (state.displayStyle != DisplayStyle.TEXTURED ||
            state.selectedEdgeIds.isNotEmpty() ||
            state.highlightedCutNodeIds.isNotEmpty()
        ) {
            drawEdges(mvp, state)
        }

        // 4. Draw Measurement Overlays & Active Snap Indicator (drawn on top with depth bias)
        drawMeasurementOverlays(mvp, state)

        // 5. Capture live framebuffer screenshot if full-viewport snapshot was requested
        if (!capturedIsolatedSnap) {
            val fullSnapReq = pendingSnapshotRequest
            if (fullSnapReq != null) {
                pendingSnapshotRequest = null
                GLES30.glFinish()
                val bmp = captureFramebufferBitmap(glViewportW, glViewportH)
                fullSnapReq.onCaptured(bmp)
            }
        }
    }

    private fun drawGridAndAxes(mvp: FloatArray) {
        val buf = gridVertexBuffer ?: return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uMvpLine, 1, false, mvp, 0)
        GLES30.glLineWidth(1.5f)
        bindLineBuffer(buf)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridVertexCount)
        disableLineAttribs()
    }

    private fun drawMeshBatches(mvp: FloatArray, state: OverlayRenderState) {
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(uMvpMesh, 1, false, mvp, 0)
        GLES30.glUniform3f(uLightDir, 0.45f, 0.35f, 0.82f)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.0f, 1.0f)

        val isXray = state.displayStyle == DisplayStyle.XRAY
        val isMono = state.displayStyle == DisplayStyle.MONOCHROME
        val useTextures = state.displayStyle == DisplayStyle.TEXTURED ||
            state.displayStyle == DisplayStyle.SHADED_EDGES

        if (isXray) {
            GLES30.glDepthMask(false)
        }

        GLES30.glUniform1f(uAlphaScale, if (isXray) 0.42f else 1.0f)
        GLES30.glUniform1i(uMonochrome, if (isMono) 1 else 0)
        GLES30.glUniform1i(uGlowHighlight, 0)

        for (batch in opaqueBatches) {
            val texId = if (useTextures && batch.textureKey != null) textureIds[batch.textureKey] else null
            if (texId != null && !isMono) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                GLES30.glUniform1i(uTextureSampler, 0)
                GLES30.glUniform1i(uUseTexture, 1)
            } else {
                GLES30.glUniform1i(uUseTexture, 0)
            }
            bindMeshBuffer(batch.buffer)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, batch.vertexCount)
        }

        selectedBatch?.let { sel ->
            GLES30.glUniform1i(uUseTexture, 0)
            GLES30.glUniform1i(uMonochrome, 0)
            GLES30.glUniform1i(uGlowHighlight, 0)
            GLES30.glUniform1f(uAlphaScale, if (isXray) 0.65f else 1.0f)
            bindMeshBuffer(sel.buffer)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, sel.vertexCount)
        }

        cutHighlightBatch?.let { cutSel ->
            GLES30.glUniform1i(uUseTexture, 0)
            GLES30.glUniform1i(uMonochrome, 0)
            GLES30.glUniform1i(uGlowHighlight, 1)
            GLES30.glUniform1f(uAlphaScale, 1.0f)
            bindMeshBuffer(cutSel.buffer)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, cutSel.vertexCount)
            GLES30.glUniform1i(uGlowHighlight, 0)
        }

        if (isXray) {
            GLES30.glDepthMask(true)
        }
        disableMeshAttribs()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    private fun drawEdges(mvp: FloatArray, state: OverlayRenderState) {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uMvpLine, 1, false, mvp, 0)

        edgeBuffer?.let { buf ->
            if (edgeVertexCount > 0) {
                GLES30.glLineWidth(if (state.displayStyle == DisplayStyle.WIREFRAME) 2.2f else 1.6f)
                bindLineBuffer(buf)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, edgeVertexCount)
            }
        }
        selectedEdgeBuffer?.let { buf ->
            if (selectedEdgeVertexCount > 0) {
                GLES30.glLineWidth(4.5f)
                bindLineBuffer(buf)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, selectedEdgeVertexCount)
            }
        }
        cutHighlightEdgeBuffer?.let { buf ->
            if (cutHighlightEdgeVertexCount > 0) {
                GLES30.glLineWidth(5.2f)
                bindLineBuffer(buf)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, cutHighlightEdgeVertexCount)
            }
        }
        disableLineAttribs()
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

        fun addLine(
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

        fun isNotEmpty(): Boolean = size > 0

        fun toFloatBuffer(): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(data, 0, size)
            fb.position(0)
            return fb
        }
    }

    private fun drawMeasurementOverlays(mvp: FloatArray, state: OverlayRenderState) {
        val verts = FloatArrayBuilder(256)
        fun addLine(a: Vec3, b: Vec3, r: Float, g: Float, bl: Float) {
            verts.addLine(
                a.x.toFloat(), a.y.toFloat(), a.z.toFloat(),
                b.x.toFloat(), b.y.toFloat(), b.z.toFloat(),
                r, g, bl, 1f
            )
        }

        // Saved lengths (blueprint cyan; highlighted orange while dragging an endpoint)
        val draggedSavedTarget = state.activeLengthDotDrag?.target as? LengthDotTarget.SavedEndpoint
        val effectiveLengths = LengthDotDragMath.effectiveSavedLengths(state.savedLengths, state.activeLengthDotDrag)
        for (l in effectiveLengths) {
            val isBeingEdited = draggedSavedTarget?.lengthId == l.id
            if (isBeingEdited) {
                addLine(Vec3(l.ax, l.ay, l.az), Vec3(l.bx, l.by, l.bz), 0.92f, 0.35f, 0.05f)
            } else {
                addLine(Vec3(l.ax, l.ay, l.az), Vec3(l.bx, l.by, l.bz), 0.01f, 0.52f, 0.78f)
            }
        }
        // Active length rubber-band (precision orange)
        val effectivePicks = LengthDotDragMath.effectiveActivePicks(state.activeLengthPicks, state.activeLengthDotDrag)
        val isDraggingActivePick = state.activeLengthDotDrag?.target is LengthDotTarget.ActivePick
        if (effectivePicks.size == 1 && !isDraggingActivePick && state.activeSnap != null) {
            addLine(effectivePicks[0], state.activeSnap.point, 0.92f, 0.35f, 0.05f)
        } else if (effectivePicks.size >= 2) {
            addLine(effectivePicks[0], effectivePicks[1], 0.92f, 0.35f, 0.05f)
        }

        // Saved angles (emerald green; highlighted precision orange while dragging any dot)
        val draggedSavedAngleId = (state.activeAngleDotDrag?.target as? AngleDotTarget.SavedPoint)?.angleId
        val effectiveAngles = AngleDotDragMath.effectiveSavedAngles(state.savedAngles, state.activeAngleDotDrag)
        for (ang in effectiveAngles) {
            val v = Vec3(ang.vx, ang.vy, ang.vz)
            val a = Vec3(ang.ax, ang.ay, ang.az)
            val b = Vec3(ang.bx, ang.by, ang.bz)
            val isEdited = draggedSavedAngleId == ang.id
            val r = if (isEdited) 0.92f else 0.09f
            val g = if (isEdited) 0.35f else 0.64f
            val bl = if (isEdited) 0.05f else 0.29f
            addLine(v, a, r, g, bl)
            addLine(v, b, r, g, bl)
            val arc = AngleMath.buildAngleArc(v, a - v, b - v)
            for (i in 0 until arc.size - 1) {
                addLine(arc[i], arc[i + 1], r, g, bl)
            }
        }

        // Active angle preview
        val effectiveAnglePicks = AngleDotDragMath.effectiveActivePicks(state.activeAnglePicks, state.activeAngleDotDrag)
        val isDraggingAnglePick = state.activeAngleDotDrag?.target is AngleDotTarget.ActivePick
        if (effectiveAnglePicks.size == 2) {
            val p1 = effectiveAnglePicks[0]
            val apex = effectiveAnglePicks[1]
            addLine(apex, p1, 0.92f, 0.35f, 0.05f)
            if (!isDraggingAnglePick) {
                state.activeSnap?.let { s ->
                    addLine(apex, s.point, 0.92f, 0.35f, 0.05f)
                    val arc = AngleMath.buildAngleArc(apex, p1 - apex, s.point - apex)
                    for (i in 0 until arc.size - 1) {
                        addLine(arc[i], arc[i + 1], 0.92f, 0.35f, 0.05f)
                    }
                }
            }
        } else if (effectiveAnglePicks.size >= 3) {
            val p1 = effectiveAnglePicks[0]
            val apex = effectiveAnglePicks[1]
            val p2 = effectiveAnglePicks[2]
            addLine(apex, p1, 0.92f, 0.35f, 0.05f)
            addLine(apex, p2, 0.92f, 0.35f, 0.05f)
            val arc = AngleMath.buildAngleArc(apex, p1 - apex, p2 - apex)
            for (i in 0 until arc.size - 1) {
                addLine(arc[i], arc[i + 1], 0.92f, 0.35f, 0.05f)
            }
        }

        if (!verts.isNotEmpty()) return
        val fb = verts.toFloatBuffer()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uMvpLine, 1, false, mvp, 0)
        GLES30.glLineWidth(4.0f)
        bindLineBuffer(fb)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, verts.size / 7)
        disableLineAttribs()
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

    private fun rebuildGeometryBatches(doc: CadDocument?, state: OverlayRenderState) {
        if (doc == null) {
            opaqueBatches = emptyList()
            selectedBatch = null
            cutHighlightBatch = null
            edgeBuffer = null
            edgeVertexCount = 0
            selectedEdgeBuffer = null
            selectedEdgeVertexCount = 0
            cutHighlightEdgeBuffer = null
            cutHighlightEdgeVertexCount = 0
            return
        }

        val groupedVerts = LinkedHashMap<String?, FloatArrayBuilder>()
        val selVerts = FloatArrayBuilder(512)
        val cutVerts = FloatArrayBuilder(512)

        val isolatedIds = state.isolatedNodeIds
        for (face in doc.faces) {
            if (isolatedIds != null && face.nodeId !in isolatedIds) continue
            if (face.hidden || face.layer in state.hiddenLayers || face.nodeId in state.hiddenNodes) continue
            val isCutHighlighted = face.nodeId in state.highlightedCutNodeIds
            val isSelected = !isCutHighlighted &&
                (face.id in state.selectedFaceIds || face.nodeId in state.selectedNodeIds)
            val targetList = when {
                isCutHighlighted -> cutVerts
                isSelected -> selVerts
                else -> groupedVerts.getOrPut(face.textureKey) { FloatArrayBuilder(4096) }
            }

            val offset = state.explodeOffsets[face.nodeId] ?: Vec3.ZERO
            val ox = offset.x.toFloat()
            val oy = offset.y.toFloat()
            val oz = offset.z.toFloat()

            val nx = face.normal.x.toFloat()
            val ny = face.normal.y.toFloat()
            val nz = face.normal.z.toFloat()
            val r = when {
                isCutHighlighted -> 1.00f
                isSelected -> 0.15f
                else -> face.color.r
            }
            val g = when {
                isCutHighlighted -> 0.45f
                isSelected -> 0.56f
                else -> face.color.g
            }
            val b = when {
                isCutHighlighted -> 0.06f
                isSelected -> 0.96f
                else -> face.color.b
            }
            val a = when {
                isCutHighlighted -> 0.98f
                isSelected -> 0.95f
                else -> face.color.a
            }

            val tris = face.triangleVertices
            val uvs = face.triangleUvs
            for (k in tris.indices) {
                val p = tris[k]
                val uv = uvs.getOrNull(k)
                targetList.addMeshVertex(
                    p.x.toFloat() + ox, p.y.toFloat() + oy, p.z.toFloat() + oz,
                    nx, ny, nz,
                    uv?.x?.toFloat() ?: 0f, uv?.y?.toFloat() ?: 0f,
                    r, g, b, a
                )
            }
        }

        opaqueBatches = groupedVerts.map { (texKey, builder) ->
            MeshBatch(texKey, builder.toFloatBuffer(), builder.size / 12)
        }
        selectedBatch = if (selVerts.isNotEmpty()) {
            MeshBatch(null, selVerts.toFloatBuffer(), selVerts.size / 12)
        } else null
        cutHighlightBatch = if (cutVerts.isNotEmpty()) {
            MeshBatch(null, cutVerts.toFloatBuffer(), cutVerts.size / 12)
        } else null

        val edgeFloats = FloatArrayBuilder((doc.edges.size * 14).coerceAtLeast(256))
        val selEdgeFloats = FloatArrayBuilder(256)
        val cutEdgeFloats = FloatArrayBuilder(256)
        val er = if (state.darkCadMode) 0.85f else 0.13f
        val eg = if (state.darkCadMode) 0.90f else 0.17f
        val eb = if (state.darkCadMode) 0.96f else 0.23f

        for (edge in doc.edges) {
            if (isolatedIds != null && edge.nodeId !in isolatedIds) continue
            if (edge.hidden || edge.soft || edge.layer in state.hiddenLayers || edge.nodeId in state.hiddenNodes) continue
            val isCutSel = edge.nodeId in state.highlightedCutNodeIds
            val isSel = !isCutSel && (edge.id in state.selectedEdgeIds || edge.nodeId in state.selectedNodeIds)
            val out = when {
                isCutSel -> cutEdgeFloats
                isSel -> selEdgeFloats
                else -> edgeFloats
            }
            val cr = when {
                isCutSel -> 1.00f
                isSel -> 0.95f
                else -> er
            }
            val cg = when {
                isCutSel -> 0.62f
                isSel -> 0.42f
                else -> eg
            }
            val cb = when {
                isCutSel -> 0.12f
                isSel -> 0.08f
                else -> eb
            }
            val offset = state.explodeOffsets[edge.nodeId] ?: Vec3.ZERO
            out.addLine(
                (edge.a.x + offset.x).toFloat(),
                (edge.a.y + offset.y).toFloat(),
                (edge.a.z + offset.z).toFloat(),
                (edge.b.x + offset.x).toFloat(),
                (edge.b.y + offset.y).toFloat(),
                (edge.b.z + offset.z).toFloat(),
                cr, cg, cb, 1f
            )
        }

        edgeBuffer = if (edgeFloats.isNotEmpty()) edgeFloats.toFloatBuffer() else null
        edgeVertexCount = edgeFloats.size / 7
        selectedEdgeBuffer = if (selEdgeFloats.isNotEmpty()) selEdgeFloats.toFloatBuffer() else null
        selectedEdgeVertexCount = selEdgeFloats.size / 7
        cutHighlightEdgeBuffer = if (cutEdgeFloats.isNotEmpty()) cutEdgeFloats.toFloatBuffer() else null
        cutHighlightEdgeVertexCount = cutEdgeFloats.size / 7
    }

    private fun captureFramebufferBitmap(width: Int, height: Int): Bitmap? {
        if (width <= 1 || height <= 1) return null
        return try {
            val pixelBuf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuf)
            pixelBuf.rewind()

            val argb = IntArray(width * height)
            val rowBytes = ByteArray(width * 4)
            for (y in 0 until height) {
                pixelBuf.get(rowBytes)
                val destRow = (height - 1 - y) * width
                var bi = 0
                for (x in 0 until width) {
                    val r = rowBytes[bi++].toInt() and 0xFF
                    val g = rowBytes[bi++].toInt() and 0xFF
                    val b = rowBytes[bi++].toInt() and 0xFF
                    val a = rowBytes[bi++].toInt() and 0xFF
                    argb[destRow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val full = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
            val maxEdge = maxOf(width, height)
            if (maxEdge > 1400) {
                val scale = 1400f / maxEdge.toFloat()
                val scaled = Bitmap.createScaledBitmap(
                    full,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                    true
                )
                full.recycle()
                scaled
            } else {
                full
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildGridAndAxesBuffer() {
        val verts = FloatArrayBuilder(1024)

        // Ground grid (-15..15m, step 1m)
        for (i in -15..15) {
            val f = i.toFloat()
            if (i != 0) {
                verts.addLine(f, -15f, -0.002f, f, 15f, -0.002f, 0.55f, 0.62f, 0.70f, 0.25f)
                verts.addLine(-15f, f, -0.002f, 15f, f, -0.002f, 0.55f, 0.62f, 0.70f, 0.25f)
            }
        }
        // IngeTrazo / SketchUp RGB Axes (+X Red, +Y Green, +Z Blue)
        verts.addLine(0f, 0f, 0f, 20f, 0f, 0f, 0.86f, 0.15f, 0.15f, 0.90f)
        verts.addLine(-20f, 0f, 0f, 0f, 0f, 0f, 0.86f, 0.15f, 0.15f, 0.35f)
        verts.addLine(0f, 0f, 0f, 0f, 20f, 0f, 0.09f, 0.64f, 0.29f, 0.90f)
        verts.addLine(0f, -20f, 0f, 0f, 0f, 0f, 0.09f, 0.64f, 0.29f, 0.35f)
        verts.addLine(0f, 0f, 0f, 0f, 0f, 20f, 0.15f, 0.39f, 0.92f, 0.90f)
        verts.addLine(0f, 0f, -20f, 0f, 0f, 0f, 0.15f, 0.39f, 0.92f, 0.35f)

        gridVertexBuffer = verts.toFloatBuffer()
        gridVertexCount = verts.size / 7
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

    private fun bindLineBuffer(buf: FloatBuffer) {
        val stride = 7 * 4
        buf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, buf)
    }

    private fun disableLineAttribs() {
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun createProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSource)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        return shader
    }

    companion object {
        fun computeIsolatedBounds(
            doc: CadDocument,
            isolatedNodeIds: Set<String>,
            explodeOffsets: Map<String, Vec3> = emptyMap()
        ): BoundingBox3D {
            var bounds = BoundingBox3D.EMPTY
            for (face in doc.faces) {
                if (face.nodeId !in isolatedNodeIds || face.hidden) continue
                val off = explodeOffsets[face.nodeId] ?: Vec3.ZERO
                for (v in face.triangleVertices) {
                    bounds = bounds.expand(v + off)
                }
            }
            if (!bounds.isValid) {
                for (edge in doc.edges) {
                    if (edge.nodeId !in isolatedNodeIds || edge.hidden) continue
                    val off = explodeOffsets[edge.nodeId] ?: Vec3.ZERO
                    bounds = bounds.expand(edge.a + off).expand(edge.b + off)
                }
            }
            return bounds
        }

        private const val MESH_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;

uniform mat4 uMvp;

out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;

void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vNormal = aNormal;
    vUv = aUv;
    vColor = aColor;
}"""

        private const val MESH_FRAGMENT_SHADER = """#version 300 es
precision mediump float;

in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;

uniform vec3 uLightDir;
uniform int uUseTexture;
uniform sampler2D uTex;
uniform int uMonochrome;
uniform float uAlphaScale;
uniform int uGlowHighlight;

out vec4 fragColor;

void main() {
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    float ndotl = max(dot(n, normalize(uLightDir)), 0.0);
    float fill = abs(dot(n, normalize(vec3(-0.6, -0.5, 0.35))));
    float hemi = 0.52 + 0.36 * ndotl + 0.14 * fill;
    vec4 base = (uMonochrome == 1) ? vec4(0.92, 0.94, 0.96, vColor.a) : vColor;
    if (uUseTexture == 1 && uMonochrome == 0) {
        vec4 tex = texture(uTex, vec2(vUv.x, 1.0 - vUv.y));
        if (tex.a < 0.08) discard;
        base = vec4(tex.rgb * base.rgb, tex.a * base.a);
    }
    vec3 lit = base.rgb * hemi;
    if (uGlowHighlight == 1) {
        lit = mix(lit, vec3(1.0, 0.52, 0.08), 0.55) + vec3(0.18, 0.07, 0.0);
    } else if (!gl_FrontFacing && uMonochrome == 0 && uUseTexture == 0) {
        lit = mix(lit, vec3(0.65, 0.72, 0.80), 0.35);
    }
    fragColor = vec4(lit, base.a * uAlphaScale);
}"""

        private const val LINE_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;

uniform mat4 uMvp;
out vec4 vColor;

void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vColor = aColor;
}"""

        private const val LINE_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;

void main() {
    fragColor = vColor;
}"""
    }
}
