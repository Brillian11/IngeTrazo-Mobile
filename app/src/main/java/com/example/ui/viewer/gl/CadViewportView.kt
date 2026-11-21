package com.example.ui.viewer.gl

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.model.CadDocument
import com.example.domain.model.CadTool
import com.example.domain.model.SnapType
import com.example.domain.model.UnitSystem
import com.example.ui.ar.drawIngeTrazoMoveDotMarker
import com.example.ui.theme.CadNumericStyle
import com.example.ui.theme.SnapEdgeRed
import com.example.ui.theme.SnapFaceBlue
import com.example.ui.theme.SnapMidpointCyan
import com.example.ui.theme.SnapVertexGreen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object CadToolGesturePolicy {
    fun allowsTap(tool: CadTool): Boolean = when (tool) {
        CadTool.SELECT, CadTool.MEASURE_LENGTH, CadTool.MEASURE_ANGLE, CadTool.AR_TRACKING_DOT -> true
        CadTool.ORBIT, CadTool.PAN -> false
    }

    fun allowsOrbit(tool: CadTool): Boolean = tool == CadTool.ORBIT

    fun allowsPan(tool: CadTool): Boolean = tool == CadTool.PAN

    fun allowsSnapHover(tool: CadTool): Boolean =
        tool == CadTool.MEASURE_LENGTH || tool == CadTool.MEASURE_ANGLE || tool == CadTool.AR_TRACKING_DOT

    fun allowsLengthDotDrag(tool: CadTool): Boolean =
        tool == CadTool.MEASURE_LENGTH || tool == CadTool.SELECT

    fun allowsAngleDotDrag(tool: CadTool): Boolean =
        tool == CadTool.MEASURE_ANGLE || tool == CadTool.SELECT

    fun allowsMeasurementLongPress(tool: CadTool): Boolean =
        tool == CadTool.MEASURE_LENGTH || tool == CadTool.MEASURE_ANGLE
}

private enum class ActiveDotDragKind {
    NONE,
    LENGTH,
    ANGLE
}

data class MeasurementContextMenuState(
    val target: MeasurementContextTarget,
    val anchorScreenPos: Vec2
)

@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CadViewportView(
    document: CadDocument?,
    camera: CadCamera,
    renderer: CadGlRenderer,
    activeTool: CadTool,
    overlayState: OverlayRenderState,
    unitSystem: UnitSystem,
    decimals: Int,
    cameraRenderSignal: Long = 0L,
    modifier: Modifier = Modifier,
    onRegisterSnapshotRequester: ((suspend (Set<String>?) -> Bitmap?) -> Unit) = {},
    onTapAt: (Float, Float) -> Unit,
    onDoubleTap: () -> Unit,
    onSnapHover: (Float, Float) -> Unit,
    onBeginLengthDotDrag: (Float, Float) -> Boolean = { _, _ -> false },
    onUpdateLengthDotDrag: (Float, Float) -> Unit = { _, _ -> },
    onEndLengthDotDrag: (cancelled: Boolean) -> Unit = {},
    onBeginAngleDotDrag: (Float, Float) -> Boolean = { _, _ -> false },
    onUpdateAngleDotDrag: (Float, Float) -> Unit = { _, _ -> },
    onEndAngleDotDrag: (cancelled: Boolean) -> Unit = {},
    onDeleteLength: (Long) -> Unit = {},
    onDeleteAngle: (Long) -> Unit = {},
    onOpenMeasurementContextMenu: ((MeasurementContextMenuState) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val dragSlopPx = with(density) { 8.dp.toPx() }
    val longPressHitRadiusPx = with(density) { 30.dp.toPx().toDouble() }

    var renderTick by remember { mutableIntStateOf(0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var contextMenuState by remember { mutableStateOf<MeasurementContextMenuState?>(null) }

    val currentActiveTool by rememberUpdatedState(activeTool)
    val currentOverlayState by rememberUpdatedState(overlayState)
    val currentOnTapAt by rememberUpdatedState(onTapAt)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnSnapHover by rememberUpdatedState(onSnapHover)
    val currentOnBeginLengthDotDrag by rememberUpdatedState(onBeginLengthDotDrag)
    val currentOnUpdateLengthDotDrag by rememberUpdatedState(onUpdateLengthDotDrag)
    val currentOnEndLengthDotDrag by rememberUpdatedState(onEndLengthDotDrag)
    val currentOnBeginAngleDotDrag by rememberUpdatedState(onBeginAngleDotDrag)
    val currentOnUpdateAngleDotDrag by rememberUpdatedState(onUpdateAngleDotDrag)
    val currentOnEndAngleDotDrag by rememberUpdatedState(onEndAngleDotDrag)
    val currentOnOpenMeasurementContextMenu by rememberUpdatedState(onOpenMeasurementContextMenu)

    fun showContextMenu(menu: MeasurementContextMenuState) {
        val hoisted = currentOnOpenMeasurementContextMenu
        if (hoisted != null) {
            hoisted(menu)
        } else {
            contextMenuState = menu
        }
    }

    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }

    LaunchedEffect(activeTool, document?.documentKey) {
        contextMenuState = null
    }

    LaunchedEffect(renderer, glSurfaceView) {
        onRegisterSnapshotRequester { isolatedNodeIds ->
            withTimeoutOrNull(2500L) {
                suspendCancellableCoroutine { cont ->
                    renderer.requestSnapshot(isolatedNodeIds = isolatedNodeIds) { bmp ->
                        if (cont.isActive) cont.resume(bmp)
                    }
                    glSurfaceView.post {
                        glSurfaceView.requestRender()
                    }
                }
            }
        }
    }

    LaunchedEffect(document) {
        renderer.setDocument(document)
        glSurfaceView.requestRender()
        renderTick++
    }

    LaunchedEffect(overlayState) {
        renderer.updateOverlayState(overlayState)
        glSurfaceView.requestRender()
    }

    LaunchedEffect(
        camera.target,
        camera.distance,
        camera.yawRad,
        camera.pitchRad,
        camera.isOrthographic,
        cameraRenderSignal
    ) {
        camera.refreshMatrices()
        glSurfaceView.requestRender()
        renderTick++
    }

    DisposableEffect(renderer, glSurfaceView) {
        renderer.setOnCameraMatrixUpdated {
            glSurfaceView.post { renderTick++ }
        }
        onDispose {
            renderer.setOnCameraMatrixUpdated {}
            glSurfaceView.onPause()
        }
    }

    Box(
        modifier = modifier.onSizeChanged { newSize ->
            viewportSize = newSize
            if (newSize.width > 0 && newSize.height > 0) {
                camera.updateMatrices(newSize.width, newSize.height)
                renderTick++
            }
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                var downX = 0f
                var downY = 0f
                var prevX = 0f
                var prevY = 0f
                var prevTwoFingerCx = 0f
                var prevTwoFingerCy = 0f
                var prevTwoFingerDist = 0f
                var isMultiTouch = false
                var activeDragKind = ActiveDotDragKind.NONE
                var longPressTriggered = false
                var blockNextSingleTap = false

                fun cancelActiveDrag() {
                    when (activeDragKind) {
                        ActiveDotDragKind.LENGTH -> currentOnEndLengthDotDrag(true)
                        ActiveDotDragKind.ANGLE -> currentOnEndAngleDotDrag(true)
                        ActiveDotDragKind.NONE -> Unit
                    }
                    activeDragKind = ActiveDotDragKind.NONE
                }

                val gestureDetector = GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (activeDragKind != ActiveDotDragKind.NONE || blockNextSingleTap || longPressTriggered) {
                                return false
                            }
                            if (!CadToolGesturePolicy.allowsTap(currentActiveTool)) return false
                            currentOnTapAt(e.x, e.y)
                            glSurfaceView.requestRender()
                            renderTick++
                            return true
                        }

                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (activeDragKind != ActiveDotDragKind.NONE || blockNextSingleTap || longPressTriggered) {
                                return false
                            }
                            if (CadToolGesturePolicy.allowsSnapHover(currentActiveTool)) return false
                            currentOnDoubleTap()
                            camera.refreshMatrices()
                            glSurfaceView.requestRender()
                            renderTick++
                            return true
                        }

                        override fun onLongPress(e: MotionEvent) {
                            val tool = currentActiveTool
                            if (activeDragKind != ActiveDotDragKind.NONE || isMultiTouch) return
                            if (!CadToolGesturePolicy.allowsMeasurementLongPress(tool)) return
                            val state = currentOverlayState
                            val hit = MeasurementHitMath.findHitSavedMeasurement(
                                screenX = e.x,
                                screenY = e.y,
                                activeTool = tool,
                                savedLengths = state.savedLengths,
                                savedAngles = state.savedAngles,
                                projectToScreen = camera::projectToScreen,
                                hitRadiusPx = longPressHitRadiusPx
                            ) ?: return
                            longPressTriggered = true
                            blockNextSingleTap = true
                            glSurfaceView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showContextMenu(
                                MeasurementContextMenuState(
                                    target = hit.target,
                                    anchorScreenPos = Vec2(e.x.toDouble(), e.y.toDouble())
                                )
                            )
                        }
                    }
                )
                gestureDetector.setIsLongpressEnabled(true)

                val scaleDetector = ScaleGestureDetector(
                    context,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            camera.zoom(detector.scaleFactor)
                            glSurfaceView.requestRender()
                            renderTick++
                            return true
                        }
                    }
                )

                fun tryBeginDotDrag(tool: CadTool, startX: Float, startY: Float): ActiveDotDragKind {
                    return when (tool) {
                        CadTool.MEASURE_LENGTH -> {
                            if (currentOnBeginLengthDotDrag(startX, startY)) ActiveDotDragKind.LENGTH
                            else ActiveDotDragKind.NONE
                        }
                        CadTool.MEASURE_ANGLE -> {
                            if (currentOnBeginAngleDotDrag(startX, startY)) ActiveDotDragKind.ANGLE
                            else ActiveDotDragKind.NONE
                        }
                        CadTool.SELECT -> {
                            if (currentOnBeginLengthDotDrag(startX, startY)) {
                                ActiveDotDragKind.LENGTH
                            } else if (currentOnBeginAngleDotDrag(startX, startY)) {
                                ActiveDotDragKind.ANGLE
                            } else {
                                ActiveDotDragKind.NONE
                            }
                        }
                        else -> ActiveDotDragKind.NONE
                    }
                }

                glSurfaceView.setOnTouchListener { _, event ->
                    val tool = currentActiveTool
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            prevX = event.x
                            prevY = event.y
                            isMultiTouch = false
                            activeDragKind = ActiveDotDragKind.NONE
                            longPressTriggered = false
                            blockNextSingleTap = false
                            contextMenuState = null
                            if (CadToolGesturePolicy.allowsSnapHover(tool)) {
                                currentOnSnapHover(event.x, event.y)
                            }
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            isMultiTouch = true
                            if (activeDragKind != ActiveDotDragKind.NONE) {
                                cancelActiveDrag()
                            }
                            if (event.pointerCount >= 2) {
                                prevTwoFingerCx = (event.getX(0) + event.getX(1)) * 0.5f
                                prevTwoFingerCy = (event.getY(0) + event.getY(1)) * 0.5f
                                val dx = event.getX(1) - event.getX(0)
                                val dy = event.getY(1) - event.getY(0)
                                prevTwoFingerDist = sqrt(dx * dx + dy * dy)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (longPressTriggered) {
                                // Hold still after opening long-press context menu
                            } else if (event.pointerCount >= 2) {
                                val cx = (event.getX(0) + event.getX(1)) * 0.5f
                                val cy = (event.getY(0) + event.getY(1)) * 0.5f
                                if (isMultiTouch && CadToolGesturePolicy.allowsPan(tool)) {
                                    camera.pan(cx - prevTwoFingerCx, cy - prevTwoFingerCy)
                                }
                                prevTwoFingerCx = cx
                                prevTwoFingerCy = cy
                                isMultiTouch = true
                            } else if (!isMultiTouch && !scaleDetector.isInProgress) {
                                val dx = event.x - prevX
                                val dy = event.y - prevY
                                if (activeDragKind == ActiveDotDragKind.NONE &&
                                    (CadToolGesturePolicy.allowsLengthDotDrag(tool) ||
                                        CadToolGesturePolicy.allowsAngleDotDrag(tool)) &&
                                    hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) >= dragSlopPx
                                ) {
                                    val started = tryBeginDotDrag(tool, downX, downY)
                                    if (started != ActiveDotDragKind.NONE) {
                                        activeDragKind = started
                                        blockNextSingleTap = true
                                        val cancelEv = MotionEvent.obtain(event).apply {
                                            action = MotionEvent.ACTION_CANCEL
                                        }
                                        gestureDetector.onTouchEvent(cancelEv)
                                        cancelEv.recycle()
                                    }
                                }
                                when {
                                    activeDragKind == ActiveDotDragKind.LENGTH -> {
                                        currentOnUpdateLengthDotDrag(event.x, event.y)
                                    }
                                    activeDragKind == ActiveDotDragKind.ANGLE -> {
                                        currentOnUpdateAngleDotDrag(event.x, event.y)
                                    }
                                    CadToolGesturePolicy.allowsPan(tool) -> camera.pan(dx, dy)
                                    CadToolGesturePolicy.allowsOrbit(tool) -> camera.orbit(dx, dy)
                                    CadToolGesturePolicy.allowsSnapHover(tool) -> {
                                        if (sqrt(dx * dx + dy * dy) > 1.5f) {
                                            currentOnSnapHover(event.x, event.y)
                                        }
                                    }
                                    else -> Unit // SELECT mode: tap selects only; does not orbit or pan
                                }
                                prevX = event.x
                                prevY = event.y
                            }
                            glSurfaceView.requestRender()
                            renderTick++
                        }
                        MotionEvent.ACTION_UP -> {
                            when (activeDragKind) {
                                ActiveDotDragKind.LENGTH -> {
                                    activeDragKind = ActiveDotDragKind.NONE
                                    currentOnEndLengthDotDrag(false)
                                    glSurfaceView.requestRender()
                                    renderTick++
                                }
                                ActiveDotDragKind.ANGLE -> {
                                    activeDragKind = ActiveDotDragKind.NONE
                                    currentOnEndAngleDotDrag(false)
                                    glSurfaceView.requestRender()
                                    renderTick++
                                }
                                ActiveDotDragKind.NONE -> Unit
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (activeDragKind != ActiveDotDragKind.NONE) {
                                cancelActiveDrag()
                                glSurfaceView.requestRender()
                                renderTick++
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            if (event.pointerCount <= 2) {
                                isMultiTouch = false
                                val remaining = if (event.actionIndex == 0) 1 else 0
                                if (remaining < event.pointerCount) {
                                    prevX = event.getX(remaining)
                                    prevY = event.getY(remaining)
                                }
                            }
                        }
                    }

                    if (activeDragKind == ActiveDotDragKind.NONE && !longPressTriggered) {
                        gestureDetector.onTouchEvent(event)
                        scaleDetector.onTouchEvent(event)
                    }
                    true
                }
                glSurfaceView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    val w = right - left
                    val h = bottom - top
                    val oldW = oldRight - oldLeft
                    val oldH = oldBottom - oldTop
                    if (w > 0 && h > 0 && (w != oldW || h != oldH)) {
                        camera.updateMatrices(w, h)
                        glSurfaceView.post {
                            glSurfaceView.requestRender()
                            renderTick++
                        }
                    }
                }
                glSurfaceView
            }
        )

        // Screen-space Snap Marker, Draggable Tape & Angle Measurement Dots & Dimension Labels Overlay
        val currentTick = renderTick
        val currentSignal = cameraRenderSignal
        val effectiveLengths = LengthDotDragMath.effectiveSavedLengths(
            overlayState.savedLengths,
            overlayState.activeLengthDotDrag
        )
        val effectiveLengthPicks = LengthDotDragMath.effectiveActivePicks(
            overlayState.activeLengthPicks,
            overlayState.activeLengthDotDrag
        )
        val effectiveAngles = AngleDotDragMath.effectiveSavedAngles(
            overlayState.savedAngles,
            overlayState.activeAngleDotDrag
        )
        val effectiveAnglePicks = AngleDotDragMath.effectiveActivePicks(
            overlayState.activeAnglePicks,
            overlayState.activeAngleDotDrag
        )
        val lengthHandlesInteractive = CadToolGesturePolicy.allowsLengthDotDrag(activeTool)
        val angleHandlesInteractive = CadToolGesturePolicy.allowsAngleDotDrag(activeTool)
        val longPressBadgeEnabled = CadToolGesturePolicy.allowsMeasurementLongPress(activeTool)

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read both camera state ticks directly inside DrawScope so Compose always invalidates draw on orbit/pan/zoom
            @Suppress("UNUSED_VARIABLE")
            val frameSync = renderTick.toLong() + cameraRenderSignal + currentTick.toLong() + currentSignal
            val activeLengthDrag = overlayState.activeLengthDotDrag
            val activeAngleDrag = overlayState.activeAngleDotDrag

            fun drawHandleDot(
                screenPos: Vec2,
                baseColor: Color,
                isInteractive: Boolean,
                isDragged: Boolean,
                isHighlighted: Boolean,
                isVertexEmphasis: Boolean = false
            ) {
                val center = Offset(screenPos.x.toFloat(), screenPos.y.toFloat())
                val fillColor = if (isDragged || isHighlighted) Color(0xFFEA580C) else baseColor
                if (isInteractive || isDragged) {
                    drawCircle(
                        color = fillColor.copy(alpha = if (isDragged) 0.30f else 0.18f),
                        radius = (if (isDragged) 20.dp else 13.dp).toPx(),
                        center = center
                    )
                }
                if (isDragged) {
                    drawCircle(
                        color = fillColor,
                        radius = 14.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                val baseRadiusDp = if (isVertexEmphasis) 6.2.dp else 5.5.dp
                val dotRadius = (if (isDragged) 7.5.dp else baseRadiusDp).toPx()
                drawCircle(
                    color = Color.White,
                    radius = dotRadius + 2.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = fillColor,
                    radius = dotRadius,
                    center = center
                )
            }

            // Draw draggable endpoint handle dots on all saved tape measurements
            val draggedSavedLength = activeLengthDrag?.target as? LengthDotTarget.SavedEndpoint
            for (len in effectiveLengths) {
                val ptA = Vec3(len.ax, len.ay, len.az)
                val ptB = Vec3(len.bx, len.by, len.bz)
                val dragA = draggedSavedLength?.lengthId == len.id && draggedSavedLength.isStartA
                val dragB = draggedSavedLength?.lengthId == len.id && !draggedSavedLength.isStartA
                camera.projectToScreen(ptA)?.let { sp ->
                    drawHandleDot(
                        screenPos = sp,
                        baseColor = Color(0xFF0284C7),
                        isInteractive = lengthHandlesInteractive,
                        isDragged = dragA,
                        isHighlighted = dragA || dragB
                    )
                }
                camera.projectToScreen(ptB)?.let { sp ->
                    drawHandleDot(
                        screenPos = sp,
                        baseColor = Color(0xFF0284C7),
                        isInteractive = lengthHandlesInteractive,
                        isDragged = dragB,
                        isHighlighted = dragA || dragB
                    )
                }
            }

            // Draw draggable dots for active in-progress tape picks
            val draggedLengthPick = activeLengthDrag?.target as? LengthDotTarget.ActivePick
            for (idx in effectiveLengthPicks.indices) {
                val isDrag = draggedLengthPick?.index == idx
                camera.projectToScreen(effectiveLengthPicks[idx])?.let { sp ->
                    drawHandleDot(
                        screenPos = sp,
                        baseColor = Color(0xFFEA580C),
                        isInteractive = lengthHandlesInteractive,
                        isDragged = isDrag,
                        isHighlighted = true
                    )
                }
            }

            // Draw draggable vertex & arm endpoint handle dots on all saved angle measurements
            val draggedSavedAngle = activeAngleDrag?.target as? AngleDotTarget.SavedPoint
            for (ang in effectiveAngles) {
                val ptV = Vec3(ang.vx, ang.vy, ang.vz)
                val ptA = Vec3(ang.ax, ang.ay, ang.az)
                val ptB = Vec3(ang.bx, ang.by, ang.bz)
                val editingAngle = draggedSavedAngle?.angleId == ang.id
                val dragV = editingAngle && draggedSavedAngle?.role == AnglePointRole.VERTEX
                val dragA = editingAngle && draggedSavedAngle?.role == AnglePointRole.RAY_A
                val dragB = editingAngle && draggedSavedAngle?.role == AnglePointRole.RAY_B
                camera.projectToScreen(ptV)?.let { sp ->
                    drawHandleDot(
                        screenPos = sp,
                        baseColor = Color(0xFF16A34A),
                        isInteractive = angleHandlesInteractive,
                        isDragged = dragV,
                        isHighlighted = editingAngle,
                        isVertexEmphasis = true
                    )
                }
                camera.projectToScreen(ptA)?.let { sp ->
                    drawHandleDot(
                        screenPos = sp,
                        baseColor = Color(0xFF16A34A),
                        isInteractive = angleHandlesInteractive,
                        isDragged = dragA,
                        isHighlighted = editingAngle
                    )
                }
                camera.projectToScreen(ptB)?.let { sp ->
                    drawHandleDot(
                        screenPos = sp,
                        baseColor = Color(0xFF16A34A),
                        isInteractive = angleHandlesInteractive,
                        isDragged = dragB,
                        isHighlighted = editingAngle
                    )
                }
            }

            // Draw draggable dots for active in-progress angle picks
            val draggedAnglePick = activeAngleDrag?.target as? AngleDotTarget.ActivePick
            for (idx in effectiveAnglePicks.indices) {
                val isDrag = draggedAnglePick?.index == idx
                camera.projectToScreen(effectiveAnglePicks[idx])?.let { sp ->
                    drawHandleDot(
                        screenPos = sp,
                        baseColor = Color(0xFFEA580C),
                        isInteractive = angleHandlesInteractive,
                        isDragged = isDrag,
                        isHighlighted = true,
                        isVertexEmphasis = idx == 1
                    )
                }
            }

            overlayState.activeSnap?.let { snap ->
                val sp = camera.projectToScreen(snap.point) ?: snap.screenPos
                val cx = sp.x.toFloat()
                val cy = sp.y.toFloat()
                val snapColor = when (snap.type) {
                    SnapType.VERTEX -> SnapVertexGreen
                    SnapType.MIDPOINT -> SnapMidpointCyan
                    SnapType.EDGE -> SnapEdgeRed
                    SnapType.FACE -> SnapFaceBlue
                }
                when (snap.type) {
                    SnapType.VERTEX, SnapType.EDGE -> {
                        drawRect(
                            color = snapColor,
                            topLeft = Offset(cx - 10f, cy - 10f),
                            size = Size(20f, 20f),
                            style = Stroke(width = 4f)
                        )
                    }
                    SnapType.MIDPOINT -> {
                        drawCircle(
                            color = snapColor,
                            radius = 11f,
                            center = Offset(cx, cy),
                            style = Stroke(width = 4f)
                        )
                    }
                    SnapType.FACE -> {
                        val diamond = Path().apply {
                            moveTo(cx, cy - 12f)
                            lineTo(cx + 12f, cy)
                            lineTo(cx, cy + 12f)
                            lineTo(cx - 12f, cy)
                            close()
                        }
                        drawPath(diamond, color = snapColor, style = Stroke(width = 4f))
                    }
                }
            }

            overlayState.arTrackingDot?.let { dot ->
                camera.projectToScreen(dot.point)?.let { sp ->
                    drawIngeTrazoMoveDotMarker(
                        center = Offset(sp.x.toFloat(), sp.y.toFloat()),
                        snapType = dot.snapType
                    )
                }
            }
        }

        // Projected AR Move Tool Tracking Dot label
        overlayState.arTrackingDot?.let { dot ->
            camera.projectToScreen(dot.point)?.let { sp ->
                ViewportTagBadge(
                    text = "AR Dot · ${dot.label}",
                    screenPos = Vec2(sp.x, sp.y - 22.0 * density.density),
                    bgColor = Color(0xFFE65100),
                    densityScale = density.density
                )
            }
        }

        val draggedSavedLengthId = (overlayState.activeLengthDotDrag?.target as? LengthDotTarget.SavedEndpoint)?.lengthId
        val draggedSavedAngleId = (overlayState.activeAngleDotDrag?.target as? AngleDotTarget.SavedPoint)?.angleId

        @Composable
        fun RenderLengthBadges() {
            for (len in effectiveLengths) {
                val mid = Vec3((len.ax + len.bx) * 0.5, (len.ay + len.by) * 0.5, (len.az + len.bz) * 0.5)
                val sp = camera.projectToScreen(mid)
                if (sp != null) {
                    val isEditingThis = draggedSavedLengthId == len.id
                    val formatted = unitSystem.formatMeters(len.lengthMeters, decimals)
                    ViewportTagBadge(
                        text = formatted,
                        screenPos = sp,
                        bgColor = if (isEditingThis) Color(0xFFEA580C) else Color(0xFF0284C7),
                        densityScale = density.density,
                        badgeTag = "viewport_length_badge_${len.id}",
                        description = "${len.label} $formatted",
                        onLongPress = if (longPressBadgeEnabled) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showContextMenu(
                                    MeasurementContextMenuState(
                                        target = MeasurementContextTarget.Length(len),
                                        anchorScreenPos = sp
                                    )
                                )
                            }
                        } else null
                    )
                }
            }
        }

        @Composable
        fun RenderAngleBadges() {
            for (ang in effectiveAngles) {
                val v = Vec3(ang.vx, ang.vy, ang.vz)
                camera.projectToScreen(v)?.let { sp ->
                    val isEditingThis = draggedSavedAngleId == ang.id
                    val formatted = String.format(Locale.US, "%.1f°", ang.angleDegrees)
                    ViewportTagBadge(
                        text = formatted,
                        screenPos = Vec2(sp.x, sp.y - 18.0 * density.density),
                        bgColor = if (isEditingThis) Color(0xFFEA580C) else Color(0xFF16A34A),
                        densityScale = density.density,
                        badgeTag = "viewport_angle_badge_${ang.id}",
                        description = "${ang.label} $formatted",
                        onLongPress = if (longPressBadgeEnabled) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showContextMenu(
                                    MeasurementContextMenuState(
                                        target = MeasurementContextTarget.Angle(ang),
                                        anchorScreenPos = sp
                                    )
                                )
                            }
                        } else null
                    )
                }
            }
        }

        // Render active tool's badges on top so overlapping badges prioritize the active measurement tool
        if (activeTool == CadTool.MEASURE_LENGTH) {
            RenderAngleBadges()
            RenderLengthBadges()
        } else {
            RenderLengthBadges()
            RenderAngleBadges()
        }

        // Active live length preview badge
        val isDraggingLengthPick = overlayState.activeLengthDotDrag?.target is LengthDotTarget.ActivePick
        if (effectiveLengthPicks.size == 1 && !isDraggingLengthPick && overlayState.activeSnap != null) {
            val a = effectiveLengthPicks[0]
            val b = overlayState.activeSnap.point
            val mid = (a + b) * 0.5
            camera.projectToScreen(mid)?.let { sp ->
                ViewportTagBadge(
                    text = unitSystem.formatMeters(a.distanceTo(b), decimals),
                    screenPos = sp,
                    bgColor = Color(0xFFEA580C),
                    densityScale = density.density
                )
            }
        }

        // Fallback in-viewport context menu when not hoisted by parent screen
        if (onOpenMeasurementContextMenu == null) {
            contextMenuState?.let { menu ->
                MeasurementContextMenuOverlay(
                    menu = menu,
                    containerSize = viewportSize,
                    unitSystem = unitSystem,
                    decimals = decimals,
                    onDeleteLength = { id ->
                        onDeleteLength(id)
                        contextMenuState = null
                    },
                    onDeleteAngle = { id ->
                        onDeleteAngle(id)
                        contextMenuState = null
                    },
                    onDismiss = { contextMenuState = null }
                )
            }
        }
    }
}

@Composable
fun MeasurementContextMenuOverlay(
    menu: MeasurementContextMenuState,
    containerSize: IntSize,
    unitSystem: UnitSystem,
    decimals: Int,
    onDeleteLength: (Long) -> Unit,
    onDeleteAngle: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val menuWidthDp = 204.dp
    val menuWidthPx = with(density) { menuWidthDp.roundToPx() }
    val menuHeightPx = with(density) { 104.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val maxAnchorX = (containerSize.width - menuWidthPx - marginPx).coerceAtLeast(marginPx)
    val maxAnchorY = (containerSize.height - menuHeightPx - marginPx).coerceAtLeast(marginPx)
    val anchorX = menu.anchorScreenPos.x.roundToInt().coerceIn(marginPx, maxAnchorX)
    val anchorY = menu.anchorScreenPos.y.roundToInt().coerceIn(marginPx, maxAnchorY)

    val headerText = when (val t = menu.target) {
        is MeasurementContextTarget.Length -> {
            val formatted = unitSystem.formatMeters(t.entity.lengthMeters, decimals)
            "${t.entity.label} · $formatted"
        }
        is MeasurementContextTarget.Angle -> {
            String.format(Locale.US, "%s · %.1f°", t.entity.label, t.entity.angleDegrees)
        }
    }
    val deleteLabel = when (menu.target) {
        is MeasurementContextTarget.Length -> "Delete line"
        is MeasurementContextTarget.Angle -> "Delete angle"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim sibling behind the menu so tapping outside dismisses it without merging semantics
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .offset { IntOffset(anchorX, anchorY) }
                .width(menuWidthDp)
                .testTag("measurement_context_menu")
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = headerText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            when (val t = menu.target) {
                                is MeasurementContextTarget.Length -> onDeleteLength(t.entity.id)
                                is MeasurementContextTarget.Angle -> onDeleteAngle(t.entity.id)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("measurement_context_delete_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = deleteLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewportTagBadge(
    text: String,
    screenPos: Vec2,
    bgColor: Color,
    densityScale: Float,
    badgeTag: String? = null,
    description: String? = null,
    onLongPress: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(6.dp)
    var mod = Modifier.offset {
        IntOffset(
            x = (screenPos.x - 28 * densityScale).roundToInt(),
            y = (screenPos.y - 14 * densityScale).roundToInt()
        )
    }
    if (badgeTag != null) {
        mod = mod.testTag(badgeTag)
    }
    if (description != null) {
        mod = mod.semantics { contentDescription = description }
    }
    if (onLongPress != null) {
        mod = mod
            .clip(shape)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            )
    }
    Surface(
        shape = shape,
        color = bgColor.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        modifier = mod
    ) {
        Text(
            text = text,
            style = CadNumericStyle,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
