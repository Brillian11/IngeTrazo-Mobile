package com.example.domain.math

import com.example.domain.model.CadEdge
import com.example.domain.model.CadFace
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin

data class AngleComputation(
    val vertex: Vec3,
    val rayAEnd: Vec3,
    val rayBEnd: Vec3,
    val degrees: Double,
    val arcPoints: List<Vec3>
)

data class DetectedAngleCandidate(
    val angleDegrees: Double,
    val vertex: Vec3,
    val rayAEnd: Vec3,
    val rayBEnd: Vec3,
    val locationLabel: String,
    val entityRef: String
)

data class AngleBucketSummary(
    val bucketKey: String,
    val nominalDegrees: Double,
    val count: Int,
    val averageDegrees: Double
)

object AngleMath {

    /**
     * Computes angle in degrees [0, 180] at [vertex] between rays vertex->p1 and vertex->p2.
     */
    fun computeThreePointAngle(p1: Vec3, vertex: Vec3, p2: Vec3): AngleComputation {
        val v1 = p1 - vertex
        val v2 = p2 - vertex
        val deg = angleBetweenVectorsDeg(v1, v2)
        val arc = buildAngleArc(vertex, v1, v2)
        return AngleComputation(vertex, p1, p2, deg, arc)
    }

    /**
     * Computes angle between two 3D edges, finding their shared vertex or closest intersection/midpoint vertex.
     */
    fun computeTwoEdgeAngle(e1: CadEdge, e2: CadEdge): AngleComputation {
        val tol = 1e-4
        val shared = when {
            e1.a.distanceTo(e2.a) < tol -> Triple(e1.a, e1.b, e2.b)
            e1.a.distanceTo(e2.b) < tol -> Triple(e1.a, e1.b, e2.a)
            e1.b.distanceTo(e2.a) < tol -> Triple(e1.b, e1.a, e2.b)
            e1.b.distanceTo(e2.b) < tol -> Triple(e1.b, e1.a, e2.a)
            else -> null
        }
        if (shared != null) {
            return computeThreePointAngle(shared.second, shared.first, shared.third)
        }
        // Skew/non-touching edges: project midpoint of e1 as virtual apex using edge directions
        val dir1 = (e1.b - e1.a).normalized()
        val dir2 = (e2.b - e2.a).normalized()
        val deg = angleBetweenVectorsDeg(dir1, dir2)
        val apex = e1.midpoint
        val armLen = minOf(e1.lengthMeters, e2.lengthMeters).coerceIn(0.15, 1.5)
        val rayA = apex + dir1 * armLen
        val rayB = apex + dir2 * armLen
        return AngleComputation(apex, rayA, rayB, deg, buildAngleArc(apex, dir1 * armLen, dir2 * armLen))
    }

    /**
     * Computes the dihedral angle between two faces (interior fold angle between normals, 0..180 deg).
     */
    fun computeTwoFaceDihedralAngle(f1: CadFace, f2: CadFace): AngleComputation {
        val n1 = f1.normal.normalized()
        val n2 = f2.normal.normalized()
        val rawDeg = angleBetweenVectorsDeg(n1, n2)
        // Interior dihedral angle between planes
        val interiorDeg = 180.0 - rawDeg
        val mid = (f1.centroid + f2.centroid) * 0.5
        val len = (f1.centroid.distanceTo(f2.centroid) * 0.45).coerceIn(0.15, 1.0)
        val rayA = mid + n1 * len
        val rayB = mid + n2 * len
        return AngleComputation(mid, rayA, rayB, rawDeg.coerceIn(0.0, 180.0), buildAngleArc(mid, n1 * len, n2 * len))
            .copy(degrees = interiorDeg.coerceIn(0.0, 180.0))
    }

    fun buildAngleArc(vertex: Vec3, vecA: Vec3, vecB: Vec3, segments: Int = 20): List<Vec3> {
        val lenA = vecA.length()
        val lenB = vecB.length()
        if (lenA < 1e-8 || lenB < 1e-8) return emptyList()
        val dirA = vecA / lenA
        val dirB = vecB / lenB
        val radius = (minOf(lenA, lenB) * 0.35).coerceIn(0.04, 0.75)

        val dot = clamp(dirA.dot(dirB), -1.0, 1.0)
        val theta = kotlin.math.acos(dot)
        if (theta < 1e-5) return emptyList()

        val axis = dirA.cross(dirB)
        val perp = if (axis.lengthSquared() > 1e-12) {
            axis.normalized().cross(dirA).normalized()
        } else {
            buildOrthonormalBasis(dirA).first
        }

        val pts = ArrayList<Vec3>(segments + 1)
        for (i in 0..segments) {
            val t = theta * (i.toDouble() / segments)
            val dir = dirA * kotlin.math.cos(t) + perp * sin(t)
            pts.add(vertex + dir * radius)
        }
        return pts
    }

    /**
     * Auto-detects interior vertex angles and face dihedral angles for a selected subset of faces/edges.
     */
    fun detectAnglesInSelection(
        selectedFaces: List<CadFace>,
        selectedEdges: List<CadEdge>
    ): List<DetectedAngleCandidate> {
        val candidates = mutableListOf<DetectedAngleCandidate>()

        // 1. Interior corner angles of each selected face's outer boundary
        for (face in selectedFaces) {
            val loop = face.outerLoop
            val n = loop.size
            if (n < 3) continue
            for (i in 0 until n) {
                val prev = loop[(i - 1 + n) % n]
                val curr = loop[i]
                val next = loop[(i + 1) % n]
                val deg = angleBetweenVectorsDeg(prev - curr, next - curr)
                if (deg in 0.1..179.9) {
                    candidates.add(
                        DetectedAngleCandidate(
                            angleDegrees = deg,
                            vertex = curr,
                            rayAEnd = prev,
                            rayBEnd = next,
                            locationLabel = "Face ${face.layer} corner (${formatCoord(curr)})",
                            entityRef = face.id
                        )
                    )
                }
            }
        }

        // 2. Dihedral angles between pairs of selected faces that share an edge
        for (i in selectedFaces.indices) {
            for (j in i + 1 until selectedFaces.size) {
                val f1 = selectedFaces[i]
                val f2 = selectedFaces[j]
                if (facesShareSegment(f1.outerLoop, f2.outerLoop)) {
                    val comp = computeTwoFaceDihedralAngle(f1, f2)
                    if (comp.degrees in 0.1..179.9) {
                        candidates.add(
                            DetectedAngleCandidate(
                                angleDegrees = comp.degrees,
                                vertex = comp.vertex,
                                rayAEnd = comp.rayAEnd,
                                rayBEnd = comp.rayBEnd,
                                locationLabel = "Dihedral ${f1.layer}/${f2.layer}",
                                entityRef = "${f1.id}|${f2.id}"
                            )
                        )
                    }
                }
            }
        }

        // 3. Angles between pairs of selected edges sharing an endpoint
        if (selectedEdges.size >= 2) {
            for (i in selectedEdges.indices) {
                for (j in i + 1 until selectedEdges.size) {
                    val e1 = selectedEdges[i]
                    val e2 = selectedEdges[j]
                    val tol = 1e-4
                    val touches = e1.a.distanceTo(e2.a) < tol || e1.a.distanceTo(e2.b) < tol ||
                        e1.b.distanceTo(e2.a) < tol || e1.b.distanceTo(e2.b) < tol
                    if (touches) {
                        val comp = computeTwoEdgeAngle(e1, e2)
                        if (comp.degrees in 0.1..179.9) {
                            candidates.add(
                                DetectedAngleCandidate(
                                    angleDegrees = comp.degrees,
                                    vertex = comp.vertex,
                                    rayAEnd = comp.rayAEnd,
                                    rayBEnd = comp.rayBEnd,
                                    locationLabel = "Edge joint (${formatCoord(comp.vertex)})",
                                    entityRef = "${e1.id}+${e2.id}"
                                )
                            )
                        }
                    }
                }
            }
        }

        return candidates
    }

    fun groupAnglesByTolerance(
        anglesDegrees: List<Double>,
        toleranceDeg: Double
    ): List<AngleBucketSummary> {
        if (anglesDegrees.isEmpty()) return emptyList()
        val tol = toleranceDeg.coerceAtLeast(0.05)
        val sorted = anglesDegrees.sorted()
        val buckets = mutableListOf<MutableList<Double>>()
        for (a in sorted) {
            val last = buckets.lastOrNull()
            if (last == null || abs(a - last.average()) > tol) {
                buckets.add(mutableListOf(a))
            } else {
                last.add(a)
            }
        }
        return buckets.map { group ->
            val avg = group.average()
            val snapped = round(avg / tol) * tol
            val key = String.format(java.util.Locale.US, "%.2f°", snapped)
            AngleBucketSummary(
                bucketKey = key,
                nominalDegrees = snapped,
                count = group.size,
                averageDegrees = avg
            )
        }.sortedByDescending { it.count }
    }

    private fun facesShareSegment(loopA: List<Vec3>, loopB: List<Vec3>): Boolean {
        val tol = 1e-4
        var sharedVerts = 0
        for (pa in loopA) {
            if (loopB.any { it.distanceTo(pa) < tol }) {
                sharedVerts++
                if (sharedVerts >= 2) return true
            }
        }
        return false
    }

    private fun formatCoord(v: Vec3): String =
        String.format(java.util.Locale.US, "%.2f, %.2f, %.2f", v.x, v.y, v.z)
}
