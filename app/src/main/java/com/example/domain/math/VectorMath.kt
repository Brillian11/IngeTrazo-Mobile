package com.example.domain.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    fun lengthSquared(): Double = x * x + y * y + z * z
    fun length(): Double = sqrt(lengthSquared())

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 1e-12) this / len else ZERO
    }

    fun distanceTo(o: Vec3): Double = (this - o).length()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    fun length(): Double = sqrt(x * x + y * y)
    fun distanceTo(o: Vec2): Double = (this - o).length()
}

/**
 * Column-major 4x4 affine matrix matching OpenGL ES and Qt QMatrix4x4.data().
 * Element at (row, col) is at index col * 4 + row.
 */
data class Mat4(val m: DoubleArray) {
    init {
        require(m.size == 16) { "Mat4 requires 16 elements" }
    }

    fun transformPoint(p: Vec3): Vec3 {
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        val invW = if (abs(w) > 1e-12) 1.0 / w else 1.0
        return Vec3(
            (m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]) * invW,
            (m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]) * invW,
            (m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]) * invW
        )
    }

    fun transformDirection(d: Vec3): Vec3 = Vec3(
        m[0] * d.x + m[4] * d.y + m[8] * d.z,
        m[1] * d.x + m[5] * d.y + m[9] * d.z,
        m[2] * d.x + m[6] * d.y + m[10] * d.z
    )

    operator fun times(other: Mat4): Mat4 {
        val out = DoubleArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += m[k * 4 + row] * other.m[col * 4 + k]
                }
                out[col * 4 + row] = sum
            }
        }
        return Mat4(out)
    }

    fun determinant3x3(): Double {
        return m[0] * (m[5] * m[10] - m[9] * m[6]) -
            m[4] * (m[1] * m[10] - m[9] * m[2]) +
            m[8] * (m[1] * m[6] - m[5] * m[2])
    }

    /**
     * Computes the 4x4 inverse of this column-major matrix via Gauss-Jordan elimination.
     * Returns null if the matrix is singular.
     */
    fun inverse(): Mat4? {
        val aug = Array(4) { row ->
            DoubleArray(8) { col ->
                if (col < 4) m[col * 4 + row]
                else if (col - 4 == row) 1.0 else 0.0
            }
        }
        for (col in 0 until 4) {
            var pivotRow = col
            var maxVal = abs(aug[col][col])
            for (r in col + 1 until 4) {
                val cand = abs(aug[r][col])
                if (cand > maxVal) {
                    maxVal = cand
                    pivotRow = r
                }
            }
            if (maxVal < 1e-12) return null
            if (pivotRow != col) {
                val tmp = aug[col]
                aug[col] = aug[pivotRow]
                aug[pivotRow] = tmp
            }
            val pivot = aug[col][col]
            for (c in col until 8) {
                aug[col][c] /= pivot
            }
            for (r in 0 until 4) {
                if (r == col) continue
                val factor = aug[r][col]
                if (abs(factor) > 1e-14) {
                    for (c in col until 8) {
                        aug[r][c] -= factor * aug[col][c]
                    }
                }
            }
        }
        val out = DoubleArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                out[col * 4 + row] = aug[row][col + 4]
            }
        }
        return Mat4(out)
    }

    fun toFloatArray(): FloatArray = FloatArray(16) { m[it].toFloat() }

    override fun equals(other: Any?): Boolean = other is Mat4 && m.contentEquals(other.m)
    override fun hashCode(): Int = m.contentHashCode()

    companion object {
        val IDENTITY = Mat4(
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0
            )
        )

        /**
         * Converts SketchUp 13-float instance matrix:
         * [r00,r01,r02, r10,r11,r12, r20,r21,r22, tx,ty,tz, scale]
         * folding homogeneous scale into rotation & translation.
         */
        fun fromSkp13(mat: DoubleArray): Mat4 {
            if (mat.size < 12) return IDENTITY
            val s = if (mat.size >= 13 && mat[12] != 0.0) mat[12] else 1.0
            return Mat4(
                doubleArrayOf(
                    mat[0] * s, mat[3] * s, mat[6] * s, 0.0,
                    mat[1] * s, mat[4] * s, mat[7] * s, 0.0,
                    mat[2] * s, mat[5] * s, mat[8] * s, 0.0,
                    mat[9] * s, mat[10] * s, mat[11] * s, 1.0
                )
            )
        }

        fun translation(x: Double, y: Double, z: Double): Mat4 = Mat4(
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                x, y, z, 1.0
            )
        )

        fun scale(sx: Double, sy: Double, sz: Double): Mat4 = Mat4(
            doubleArrayOf(
                sx, 0.0, 0.0, 0.0,
                0.0, sy, 0.0, 0.0,
                0.0, 0.0, sz, 0.0,
                0.0, 0.0, 0.0, 1.0
            )
        )

        fun fromQuaternion(qx: Double, qy: Double, qz: Double, qw: Double): Mat4 {
            val xx = qx * qx
            val yy = qy * qy
            val zz = qz * qz
            val xy = qx * qy
            val xz = qx * qz
            val yz = qy * qz
            val wx = qw * qx
            val wy = qw * qy
            val wz = qw * qz
            return Mat4(
                doubleArrayOf(
                    1.0 - 2.0 * (yy + zz), 2.0 * (xy + wz), 2.0 * (xz - wy), 0.0,
                    2.0 * (xy - wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz + wx), 0.0,
                    2.0 * (xz + wy), 2.0 * (yz - wx), 1.0 - 2.0 * (xx + yy), 0.0,
                    0.0, 0.0, 0.0, 1.0
                )
            )
        }
    }
}

data class BoundingBox3D(val min: Vec3, val max: Vec3) {
    val center: Vec3 get() = (min + max) * 0.5
    val size: Vec3 get() = max - min
    val diagonal: Double get() = size.length().coerceAtLeast(1e-4)
    val isValid: Boolean get() = min.x <= max.x && min.y <= max.y && min.z <= max.z

    fun expand(p: Vec3): BoundingBox3D = BoundingBox3D(
        Vec3(min(min.x, p.x), min(min.y, p.y), min(min.z, p.z)),
        Vec3(max(max.x, p.x), max(max.y, p.y), max(max.z, p.z))
    )

    fun merge(other: BoundingBox3D): BoundingBox3D {
        if (!isValid) return other
        if (!other.isValid) return this
        return BoundingBox3D(
            Vec3(min(min.x, other.min.x), min(min.y, other.min.y), min(min.z, other.min.z)),
            Vec3(max(max.x, other.max.x), max(max.y, other.max.y), max(max.z, other.max.z))
        )
    }

    companion object {
        val EMPTY = BoundingBox3D(
            Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
            Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        )
    }
}

data class Ray3D(val origin: Vec3, val direction: Vec3)

fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))

fun angleBetweenVectorsDeg(u: Vec3, v: Vec3): Double {
    val lenU = u.length()
    val lenV = v.length()
    if (lenU < 1e-10 || lenV < 1e-10) return 0.0
    val cosTheta = clamp(u.dot(v) / (lenU * lenV), -1.0, 1.0)
    return Math.toDegrees(acos(cosTheta))
}

fun buildOrthonormalBasis(normal: Vec3): Pair<Vec3, Vec3> {
    val n = normal.normalized()
    val ref = if (abs(n.z) < 0.9) Vec3.UNIT_Z else Vec3.UNIT_X
    val u = ref.cross(n).normalized()
    val v = n.cross(u).normalized()
    return u to v
}
