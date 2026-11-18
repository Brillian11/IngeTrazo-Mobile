package com.example.domain.math

import kotlin.math.abs

/**
 * Pure-Kotlin 3D planar polygon triangulator using ear-clipping with hole bridge merging.
 * Supports convex, concave, and multi-hole polygons from IngeTrazo (.igz) and SketchUp (.skp).
 */
object EarcutTriangulator {

    fun computePolygonNormal(points: List<Vec3>): Vec3 {
        if (points.size < 3) return Vec3.UNIT_Z
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        val n = points.size
        for (i in 0 until n) {
            val curr = points[i]
            val next = points[(i + 1) % n]
            nx += (curr.y - next.y) * (curr.z + next.z)
            ny += (curr.z - next.z) * (curr.x + next.x)
            nz += (curr.x - next.x) * (curr.y + next.y)
        }
        val normal = Vec3(nx, ny, nz)
        return if (normal.lengthSquared() > 1e-16) normal.normalized() else Vec3.UNIT_Z
    }

    fun triangulate(
        outerLoop: List<Vec3>,
        holes: List<List<Vec3>> = emptyList(),
        hintNormal: Vec3? = null
    ): List<Vec3> {
        val cleanOuter = dedupConsecutive(outerLoop)
        if (cleanOuter.size < 3) return emptyList()
        if (cleanOuter.size == 3 && holes.isEmpty()) return cleanOuter

        val normal = if (hintNormal != null && hintNormal.lengthSquared() > 1e-12) {
            hintNormal.normalized()
        } else {
            computePolygonNormal(cleanOuter)
        }
        val (uAxis, vAxis) = buildOrthonormalBasis(normal)
        val origin = cleanOuter.first()

        fun project(p: Vec3): Vec2 {
            val d = p - origin
            return Vec2(d.dot(uAxis), d.dot(vAxis))
        }

        val outer2d = cleanOuter.map(::project).toMutableList()
        val combined3d = cleanOuter.toMutableList()

        // Ensure outer ring is CCW in projected 2D coordinates
        if (signedArea2D(outer2d) < 0.0) {
            outer2d.reverse()
            combined3d.reverse()
        }

        // Bridge each hole into the outer ring using a mutually visible vertex pair
        val validHoles = holes.map { dedupConsecutive(it) }.filter { it.size >= 3 }
        if (validHoles.isNotEmpty()) {
            val holePairs = validHoles.map { hole3d ->
                val h2d = hole3d.map(::project).toMutableList()
                val h3d = hole3d.toMutableList()
                // Holes must be CW in 2D
                if (signedArea2D(h2d) > 0.0) {
                    h2d.reverse()
                    h3d.reverse()
                }
                val maxXIdx = h2d.indices.maxByOrNull { h2d[it].x } ?: 0
                Triple(h2d, h3d, maxXIdx)
            }.sortedByDescending { it.first[it.third].x }

            for ((h2d, h3d, holeIdx) in holePairs) {
                val bridgeOuterIdx = findBridgeOuterVertex(outer2d, h2d[holeIdx])
                if (bridgeOuterIdx >= 0) {
                    val merged2d = ArrayList<Vec2>(outer2d.size + h2d.size + 2)
                    val merged3d = ArrayList<Vec3>(combined3d.size + h3d.size + 2)
                    for (i in 0..bridgeOuterIdx) {
                        merged2d.add(outer2d[i])
                        merged3d.add(combined3d[i])
                    }
                    for (k in 0..h2d.size) {
                        val idx = (holeIdx + k) % h2d.size
                        merged2d.add(h2d[idx])
                        merged3d.add(h3d[idx])
                    }
                    merged2d.add(outer2d[bridgeOuterIdx])
                    merged3d.add(combined3d[bridgeOuterIdx])
                    for (i in (bridgeOuterIdx + 1) until outer2d.size) {
                        merged2d.add(outer2d[i])
                        merged3d.add(combined3d[i])
                    }
                    outer2d.clear()
                    outer2d.addAll(merged2d)
                    combined3d.clear()
                    combined3d.addAll(merged3d)
                }
            }
        }

        val indices = earClip2D(outer2d)
        if (indices.isEmpty()) {
            // Safe fan fallback for near-degenerate geometry
            val out = ArrayList<Vec3>((cleanOuter.size - 2) * 3)
            for (i in 1 until cleanOuter.size - 1) {
                out.add(cleanOuter[0])
                out.add(cleanOuter[i])
                out.add(cleanOuter[i + 1])
            }
            return out
        }
        return indices.map { combined3d[it] }
    }

    private fun dedupConsecutive(pts: List<Vec3>): List<Vec3> {
        if (pts.isEmpty()) return emptyList()
        val out = ArrayList<Vec3>(pts.size)
        for (p in pts) {
            if (out.isEmpty() || out.last().distanceTo(p) > 1e-7) {
                out.add(p)
            }
        }
        if (out.size > 1 && out.first().distanceTo(out.last()) <= 1e-7) {
            out.removeAt(out.lastIndex)
        }
        return out
    }

    private fun signedArea2D(pts: List<Vec2>): Double {
        var area = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            area += (a.x * b.y - b.x * a.y)
        }
        return area * 0.5
    }

    private fun findBridgeOuterVertex(outer: List<Vec2>, holePt: Vec2): Int {
        var bestIdx = -1
        var bestX = Double.POSITIVE_INFINITY
        for (i in outer.indices) {
            val a = outer[i]
            val b = outer[(i + 1) % outer.size]
            if ((a.y <= holePt.y && b.y > holePt.y) || (b.y <= holePt.y && a.y > holePt.y)) {
                val t = (holePt.y - a.y) / (b.y - a.y)
                val ix = a.x + t * (b.x - a.x)
                if (ix >= holePt.x && ix < bestX) {
                    bestX = ix
                    bestIdx = if (a.x >= b.x) i else (i + 1) % outer.size
                }
            }
        }
        if (bestIdx >= 0) return bestIdx
        return outer.indices.minByOrNull { outer[it].distanceTo(holePt) } ?: -1
    }

    private fun earClip2D(poly: List<Vec2>): List<Int> {
        val n = poly.size
        if (n < 3) return emptyList()
        if (n == 3) return listOf(0, 1, 2)

        val remaining = MutableList(n) { it }
        val result = ArrayList<Int>((n - 2) * 3)
        var guard = n * n * 2
        var i = 0
        while (remaining.size > 3 && guard-- > 0) {
            val m = remaining.size
            val prevIdx = remaining[(i - 1 + m) % m]
            val currIdx = remaining[i % m]
            val nextIdx = remaining[(i + 1) % m]
            val a = poly[prevIdx]
            val b = poly[currIdx]
            val c = poly[nextIdx]

            if (isConvexEar(a, b, c, poly, remaining, prevIdx, currIdx, nextIdx)) {
                result.add(prevIdx)
                result.add(currIdx)
                result.add(nextIdx)
                remaining.removeAt(i % m)
                i %= remaining.size
            } else {
                i = (i + 1) % m
            }
        }
        if (remaining.size == 3) {
            result.add(remaining[0])
            result.add(remaining[1])
            result.add(remaining[2])
        }
        return result
    }

    private fun cross2D(a: Vec2, b: Vec2, c: Vec2): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun pointInTriangle(p: Vec2, a: Vec2, b: Vec2, c: Vec2): Boolean {
        val c1 = cross2D(a, b, p)
        val c2 = cross2D(b, c, p)
        val c3 = cross2D(c, a, p)
        return c1 >= -1e-9 && c2 >= -1e-9 && c3 >= -1e-9
    }

    private fun isConvexEar(
        a: Vec2,
        b: Vec2,
        c: Vec2,
        poly: List<Vec2>,
        remaining: List<Int>,
        prevIdx: Int,
        currIdx: Int,
        nextIdx: Int
    ): Boolean {
        if (cross2D(a, b, c) <= 1e-11) return false
        for (idx in remaining) {
            if (idx == prevIdx || idx == currIdx || idx == nextIdx) continue
            val p = poly[idx]
            // Twin bridge vertices share exact coordinates with a, b, or c
            if (p.distanceTo(a) < 1e-9 || p.distanceTo(b) < 1e-9 || p.distanceTo(c) < 1e-9) continue
            if (pointInTriangle(p, a, b, c)) return false
        }
        return true
    }
}
