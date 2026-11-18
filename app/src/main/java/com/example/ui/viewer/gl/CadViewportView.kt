package com.example.ui.viewer.gl

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.model.CadDocument
import com.example.domain.model.CadTool
import com.example.domain.model.SnapType
import com.example.domain.model.UnitSystem
import com.example.ui.theme.CadNumericStyle
import com.example.ui.theme.SnapEdgeRed
import com.example.ui.theme.SnapFaceBlue
import com.example.ui.theme.SnapMidpointCyan
import com.example.ui.theme.SnapVertexGreen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object CadToolGesturePolicy {
    fun allowsTap(tool: CadTool): Boolean = when (tool) {
        CadTool.SELECT, CadTool.MEASURE_LENGTH, CadTool.MEASURE_ANGLE -> true
        CadTool.ORBIT, CadTool.PAN -> false
    }

    fun allowsOrbit(tool: CadTool): Boolean = tool == CadTool.ORBIT

    fun allowsPan(tool: CadTool): Boolean = tool == CadTool.PAN

    fun allowsSnapHover(tool: CadTool): Boolean =
        tool == CadTool.MEASURE_LENGTH || tool == CadTool.MEASURE_ANGLE
}

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
    onRegisterSnapshotRequester: ((suspend () -> Bitmap?) -> Unit) = {},
    onTapAt: (Float, Float) -> Unit,
    onDoubleTap: () -> Unit,
    onSnapHover: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    var renderTick by remember { mutableIntStateOf(0) }
    val currentActiveTool by rememberUpdatedState(activeTool)
    val currentOnTapAt by rememberUpdatedState(onTapAt)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnSnapHover by rememberUpdatedState(onSnapHover)

    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }

    LaunchedEffect(renderer, glSurfaceView) {
        onRegisterSnapshotRequester {
            withTimeoutOrNull(2500L) {
                suspendCancellableCoroutine { cont ->
                    renderer.requestSnapshot { bmp ->
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
        glSurfaceView.requestRender()
    }

    DisposableEffect(glSurfaceView) {
        onDispose { glSurfaceView.onPause() }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                var prevX = 0f
                var prevY = 0f
                var prevTwoFingerCx = 0f
                var prevTwoFingerCy = 0f
                var prevTwoFingerDist = 0f
                var isMultiTouch = false

                val gestureDetector = GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (!CadToolGesturePolicy.allowsTap(currentActiveTool)) return false
                            currentOnTapAt(e.x, e.y)
                            glSurfaceView.requestRender()
                            renderTick++
                            return true
                        }

                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (CadToolGesturePolicy.allowsSnapHover(currentActiveTool)) return false
                            currentOnDoubleTap()
                            glSurfaceView.requestRender()
                            renderTick++
                            return true
                        }
                    }
                )

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

                glSurfaceView.setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    scaleDetector.onTouchEvent(event)

                    val tool = currentActiveTool
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            prevX = event.x
                            prevY = event.y
                            isMultiTouch = false
                            if (CadToolGesturePolicy.allowsSnapHover(tool)) {
                                currentOnSnapHover(event.x, event.y)
                            }
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            isMultiTouch = true
                            if (event.pointerCount >= 2) {
                                prevTwoFingerCx = (event.getX(0) + event.getX(1)) * 0.5f
                                prevTwoFingerCy = (event.getY(0) + event.getY(1)) * 0.5f
                                val dx = event.getX(1) - event.getX(0)
                                val dy = event.getY(1) - event.getY(0)
                                prevTwoFingerDist = sqrt(dx * dx + dy * dy)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (event.pointerCount >= 2) {
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
                                when {
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
                    true
                }
                glSurfaceView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    val w = right - left
                    val h = bottom - top
                    val oldW = oldRight - oldLeft
                    val oldH = oldBottom - oldTop
                    if (w > 0 && h > 0 && (w != oldW || h != oldH)) {
                        glSurfaceView.post {
                            glSurfaceView.requestRender()
                            renderTick++
                        }
                    }
                }
                glSurfaceView
            },
            update = { view ->
                view.requestRender()
            }
        )

        // Screen-space Snap Marker & Dimension Labels Overlay
        val _tick = renderTick
        Canvas(modifier = Modifier.fillMaxSize()) {
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
        }

        // Projected floating dimension labels
        val density = LocalDensity.current
        for (len in overlayState.savedLengths) {
            val mid = Vec3((len.ax + len.bx) * 0.5, (len.ay + len.by) * 0.5, (len.az + len.bz) * 0.5)
            val sp = camera.projectToScreen(mid)
            if (sp != null) {
                ViewportTagBadge(
                    text = unitSystem.formatMeters(len.lengthMeters, decimals),
                    screenPos = sp,
                    bgColor = Color(0xFF0284C7),
                    densityScale = density.density
                )
            }
        }

        // Active live length preview badge
        if (overlayState.activeLengthPicks.size == 1 && overlayState.activeSnap != null) {
            val a = overlayState.activeLengthPicks[0]
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

        // Saved angle badges
        for (ang in overlayState.savedAngles) {
            val v = Vec3(ang.vx, ang.vy, ang.vz)
            camera.projectToScreen(v)?.let { sp ->
                ViewportTagBadge(
                    text = String.format(Locale.US, "%.1f°", ang.angleDegrees),
                    screenPos = sp,
                    bgColor = Color(0xFF16A34A),
                    densityScale = density.density
                )
            }
        }
    }
}

@Composable
private fun ViewportTagBadge(
    text: String,
    screenPos: Vec2,
    bgColor: Color,
    densityScale: Float
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        modifier = Modifier.offset {
            IntOffset(
                x = (screenPos.x - 28 * densityScale).roundToInt(),
                y = (screenPos.y - 14 * densityScale).roundToInt()
            )
        }
    ) {
        Text(
            text = text,
            style = CadNumericStyle,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
