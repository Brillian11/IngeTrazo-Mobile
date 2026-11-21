package com.example.ui.viewer.gl

import android.opengl.Matrix
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Ray3D
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.math.clamp
import com.example.domain.model.CadSavedView
import com.example.domain.model.StandardView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Z-up CAD camera supporting smooth turntable orbit, pan, zoom,
 * perspective/orthographic projection, standard views, and screen <-> ray projection.
 */
class CadCamera {
    var target: Vec3 = Vec3.ZERO
    var distance: Double = 10.0
    var yawRad: Double = -PI / 4.0
    var pitchRad: Double = PI / 6.0
    var fovDeg: Float = 45f
    var isOrthographic: Boolean = false

    private val matrixLock = Any()
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val invVpMatrix = FloatArray(16)

    @Volatile
    var viewportWidth: Int = 1
        private set

    @Volatile
    var viewportHeight: Int = 1
        private set

    fun eyePosition(): Vec3 {
        val cp = cos(pitchRad)
        val sp = sin(pitchRad)
        val cy = cos(yawRad)
        val sy = sin(yawRad)
        return target + Vec3(cp * cy, cp * sy, sp) * distance
    }

    fun forwardVector(): Vec3 = (target - eyePosition()).normalized()

    fun orbit(deltaXPx: Float, deltaYPx: Float) {
        val sensitivity = 0.0065
        yawRad -= deltaXPx * sensitivity
        pitchRad = clamp(pitchRad + deltaYPx * sensitivity, -PI * 0.495, PI * 0.495)
        refreshMatricesIfSized()
    }

    fun pan(deltaXPx: Float, deltaYPx: Float) {
        val h = max(viewportHeight, 1)
        val worldPerPx = (2.0 * distance * tan(Math.toRadians(fovDeg * 0.5))) / h
        val fwd = forwardVector()
        val worldUp = if (kotlin.math.abs(fwd.z) > 0.99) {
            val sign = if (pitchRad > 0) -1.0 else 1.0
            Vec3(sign * cos(yawRad), sign * sin(yawRad), 0.0).normalized()
        } else Vec3.UNIT_Z
        val right = fwd.cross(worldUp).normalized()
        val camUp = right.cross(fwd).normalized()
        target = target - right * (deltaXPx * worldPerPx) + camUp * (deltaYPx * worldPerPx)
        refreshMatricesIfSized()
    }

    fun zoom(scaleFactor: Float) {
        if (scaleFactor <= 0.01f) return
        distance = (distance / scaleFactor).coerceIn(0.08, 5000.0)
        refreshMatricesIfSized()
    }

    fun zoomExtents(bounds: BoundingBox3D) {
        target = bounds.center
        distance = targetDistanceForBounds(bounds, paddingFactor = 0.55)
        refreshMatricesIfSized()
    }

    fun targetDistanceForBounds(bounds: BoundingBox3D, paddingFactor: Double = 0.72): Double {
        if (!bounds.isValid) return distance
        val radius = max(bounds.diagonal * paddingFactor, 0.20)
        val halfFov = tan(Math.toRadians(fovDeg * 0.5))
        return (radius / halfFov).coerceIn(0.35, 2500.0)
    }

    fun applyStandardView(view: StandardView, bounds: BoundingBox3D) {
        when (view) {
            StandardView.ISO -> {
                yawRad = -PI * 0.25
                pitchRad = PI * 0.195
            }
            StandardView.TOP -> {
                yawRad = -PI * 0.5
                pitchRad = PI * 0.495
            }
            StandardView.BOTTOM -> {
                yawRad = -PI * 0.5
                pitchRad = -PI * 0.495
            }
            StandardView.FRONT -> {
                yawRad = -PI * 0.5
                pitchRad = 0.0
            }
            StandardView.BACK -> {
                yawRad = PI * 0.5
                pitchRad = 0.0
            }
            StandardView.RIGHT -> {
                yawRad = 0.0
                pitchRad = 0.0
            }
            StandardView.LEFT -> {
                yawRad = PI
                pitchRad = 0.0
            }
        }
        zoomExtents(bounds)
    }

    fun applySavedView(view: CadSavedView) {
        target = view.target
        distance = view.distance.coerceIn(0.1, 3000.0)
        yawRad = view.yawRad
        pitchRad = clamp(view.pitchRad, -PI * 0.495, PI * 0.495)
        isOrthographic = view.parallel
        refreshMatricesIfSized()
    }

    fun refreshMatrices() {
        refreshMatricesIfSized()
    }

    private fun refreshMatricesIfSized() {
        val w = viewportWidth
        val h = viewportHeight
        if (w > 1 || h > 1) {
            updateMatrices(w, h)
        }
    }

    fun updateMatrices(width: Int, height: Int): FloatArray {
        synchronized(matrixLock) {
            viewportWidth = max(width, 1)
            viewportHeight = max(height, 1)
            val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()

            val eye = eyePosition()
            val fwd = (target - eye).normalized()
            val up = if (kotlin.math.abs(fwd.z) > 0.985) {
                val sign = if (pitchRad > 0) -1.0 else 1.0
                Vec3(sign * cos(yawRad), sign * sin(yawRad), 0.0).normalized()
            } else {
                Vec3.UNIT_Z
            }

            Matrix.setLookAtM(
                viewMatrix, 0,
                eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat(),
                target.x.toFloat(), target.y.toFloat(), target.z.toFloat(),
                up.x.toFloat(), up.y.toFloat(), up.z.toFloat()
            )

            val near = (distance * 0.005).toFloat().coerceIn(0.02f, 1.0f)
            val far = (distance * 25.0).toFloat().coerceAtLeast(200f)

            if (isOrthographic) {
                val halfH = (distance * tan(Math.toRadians(fovDeg * 0.5))).toFloat().coerceAtLeast(0.05f)
                val halfW = halfH * aspect
                Matrix.orthoM(projMatrix, 0, -halfW, halfW, -halfH, halfH, near, far)
            } else {
                Matrix.perspectiveM(projMatrix, 0, fovDeg, aspect, near, far)
            }

            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)
            return vpMatrix.copyOf()
        }
    }

    fun viewMatrixCopy(): FloatArray = synchronized(matrixLock) { viewMatrix.copyOf() }
    fun projMatrixCopy(): FloatArray = synchronized(matrixLock) { projMatrix.copyOf() }

    fun projectToScreen(p: Vec3): Vec2? {
        val w: Int
        val h: Int
        val outVec = FloatArray(4)
        synchronized(matrixLock) {
            w = viewportWidth
            h = viewportHeight
            if (w <= 0 || h <= 0) return null
            val inVec = floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f)
            Matrix.multiplyMV(outVec, 0, vpMatrix, 0, inVec, 0)
        }
        val clipW = outVec[3]
        if (clipW <= 1e-5f) return null
        val ndcX = outVec[0] / clipW
        val ndcY = outVec[1] / clipW
        val ndcZ = outVec[2] / clipW
        if (ndcZ < -1.2f || ndcZ > 1.2f) return null
        val sx = ((ndcX + 1f) * 0.5f) * w
        val sy = ((1f - ndcY) * 0.5f) * h
        return Vec2(sx.toDouble(), sy.toDouble())
    }

    fun rayFromScreen(screenX: Float, screenY: Float): Ray3D? {
        val w: Int
        val h: Int
        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)
        synchronized(matrixLock) {
            w = viewportWidth
            h = viewportHeight
            if (w <= 0 || h <= 0) return null
            val ndcX = (2f * screenX / w) - 1f
            val ndcY = 1f - (2f * screenY / h)

            val nearNdc = floatArrayOf(ndcX, ndcY, -1f, 1f)
            val farNdc = floatArrayOf(ndcX, ndcY, 1f, 1f)
            Matrix.multiplyMV(nearWorld, 0, invVpMatrix, 0, nearNdc, 0)
            Matrix.multiplyMV(farWorld, 0, invVpMatrix, 0, farNdc, 0)
        }

        if (kotlin.math.abs(nearWorld[3]) < 1e-6f || kotlin.math.abs(farWorld[3]) < 1e-6f) return null
        val p0 = Vec3(
            (nearWorld[0] / nearWorld[3]).toDouble(),
            (nearWorld[1] / nearWorld[3]).toDouble(),
            (nearWorld[2] / nearWorld[3]).toDouble()
        )
        val p1 = Vec3(
            (farWorld[0] / farWorld[3]).toDouble(),
            (farWorld[1] / farWorld[3]).toDouble(),
            (farWorld[2] / farWorld[3]).toDouble()
        )
        val dir = (p1 - p0).normalized()
        return Ray3D(p0, dir)
    }
}
