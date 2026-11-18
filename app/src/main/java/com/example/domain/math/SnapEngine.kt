package com.example.domain.math

import com.example.domain.model.CadDocument
import com.example.domain.model.CadEdge
import com.example.domain.model.CadFace
import com.example.domain.model.SnapType
import kotlin.math.abs

data class SnapHit(
    val point: Vec3,
    val type: SnapType,
    val screenPos: Vec2,
    val entityId: String,
    val edge: CadEdge? = null,
    val face: CadFace? = null
)

data class PickHit(
    val point: Vec3,
    val face: CadFace? = null,
    val edge: CadEdge? = null,
    val nodeId: String? = null
)

object SnapEngine {

    /**
     * Finds the closest CAD snap point (Vertex > Midpoint > Edge > Face) within [snapRadiusPx].
     */
    fun findBestSnap(
        doc: CadDocument,
        hiddenLayers: Set<String>,
        hiddenNodes: Set<String>,
        screenX: Float,
        screenY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        projectToScreen: (Vec3) -> Vec2?,
        rayFromScreen: (Float, Float) -> Ray3D?,
        snapRadiusPx: Double = 56.0,
        explodeOffsets: Map<String, Vec3> = emptyMap()
    ): SnapHit? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val cursor = Vec2(screenX.toDouble(), screenY.toDouble())

        var bestVertex: SnapHit? = null
        var bestVertexDist = snapRadiusPx * 0.75

        var bestMidpoint: SnapHit? = null
        var bestMidDist = snapRadiusPx * 0.85

        var bestEdge: SnapHit? = null
        var bestEdgeDist = snapRadiusPx

        val visibleEdges = doc.edges.asSequence().filter {
            !it.hidden && it.layer !in hiddenLayers && it.nodeId !in hiddenNodes
        }

        for (edge in visibleEdges) {
            val offset = explodeOffsets[edge.nodeId] ?: Vec3.ZERO
            val ea = edge.a + offset
            val eb = edge.b + offset
            val sa = projectToScreen(ea)
            val sb = projectToScreen(eb)
            if (sa != null) {
                val da = sa.distanceTo(cursor)
                if (da < bestVertexDist) {
                    bestVertexDist = da
                    bestVertex = SnapHit(ea, SnapType.VERTEX, sa, edge.id, edge = edge)
                }
            }
            if (sb != null) {
                val db = sb.distanceTo(cursor)
                if (db < bestVertexDist) {
                    bestVertexDist = db
                    bestVertex = SnapHit(eb, SnapType.VERTEX, sb, edge.id, edge = edge)
                }
            }
            val mid = (ea + eb) * 0.5
            val sm = projectToScreen(mid)
            if (sm != null) {
                val dm = sm.distanceTo(cursor)
                if (dm < bestMidDist) {
                    bestMidDist = dm
                    bestMidpoint = SnapHit(mid, SnapType.MIDPOINT, sm, edge.id, edge = edge)
                }
            }
            if (sa != null && sb != null) {
                val t = closestTOnSegment2D(cursor, sa, sb)
                val proj2d = sa + (sb - sa) * t
                val de = proj2d.distanceTo(cursor)
                if (de < bestEdgeDist) {
                    bestEdgeDist = de
                    val worldPt = ea + (eb - ea) * t
                    bestEdge = SnapHit(worldPt, SnapType.EDGE, proj2d, edge.id, edge = edge)
                }
            }
        }

        // Also check face vertices for models with faces and few/no edges (e.g. OBJ/glTF)
        if (bestVertex == null) {
            for (face in doc.faces) {
                if (face.hidden || face.layer in hiddenLayers || face.nodeId in hiddenNodes) continue
                val offset = explodeOffsets[face.nodeId] ?: Vec3.ZERO
                for (rawPt in face.outerLoop) {
                    val pt = rawPt + offset
                    val sp = projectToScreen(pt) ?: continue
                    val d = sp.distanceTo(cursor)
                    if (d < bestVertexDist) {
                        bestVertexDist = d
                        bestVertex = SnapHit(pt, SnapType.VERTEX, sp, face.id, face = face)
                    }
                }
            }
        }

        if (bestVertex != null) return bestVertex
        if (bestMidpoint != null) return bestMidpoint
        if (bestEdge != null) return bestEdge

        val ray = rayFromScreen(screenX, screenY) ?: return null
        val faceHit = raycastFaces(doc, hiddenLayers, hiddenNodes, ray, explodeOffsets)
        if (faceHit != null && faceHit.face != null) {
            val sp = projectToScreen(faceHit.point) ?: cursor
            return SnapHit(faceHit.point, SnapType.FACE, sp, faceHit.face.id, face = faceHit.face)
        }
        return null
    }

    fun pickGeometry(
        doc: CadDocument,
        hiddenLayers: Set<String>,
        hiddenNodes: Set<String>,
        screenX: Float,
        screenY: Float,
        projectToScreen: (Vec3) -> Vec2?,
        rayFromScreen: (Float, Float) -> Ray3D?,
        edgePickRadiusPx: Double = 36.0,
        explodeOffsets: Map<String, Vec3> = emptyMap()
    ): PickHit? {
        val cursor = Vec2(screenX.toDouble(), screenY.toDouble())
        var bestEdge: CadEdge? = null
        var bestEdgeDist = edgePickRadiusPx
        var bestEdgePt = Vec3.ZERO

        for (edge in doc.edges) {
            if (edge.hidden || edge.layer in hiddenLayers || edge.nodeId in hiddenNodes) continue
            val offset = explodeOffsets[edge.nodeId] ?: Vec3.ZERO
            val ea = edge.a + offset
            val eb = edge.b + offset
            val sa = projectToScreen(ea) ?: continue
            val sb = projectToScreen(eb) ?: continue
            val t = closestTOnSegment2D(cursor, sa, sb)
            val d = (sa + (sb - sa) * t).distanceTo(cursor)
            if (d < bestEdgeDist) {
                bestEdgeDist = d
                bestEdge = edge
                bestEdgePt = ea + (eb - ea) * t
            }
        }

        val ray = rayFromScreen(screenX, screenY)
        val faceHit = if (ray != null) raycastFaces(doc, hiddenLayers, hiddenNodes, ray, explodeOffsets) else null

        return when {
            bestEdge != null && faceHit == null -> PickHit(bestEdgePt, edge = bestEdge, nodeId = bestEdge.nodeId)
            bestEdge != null && bestEdgeDist < 16.0 -> PickHit(bestEdgePt, face = faceHit?.face, edge = bestEdge, nodeId = bestEdge.nodeId)
            faceHit != null -> faceHit
            bestEdge != null -> PickHit(bestEdgePt, edge = bestEdge, nodeId = bestEdge.nodeId)
            else -> null
        }
    }

    fun raycastFaces(
        doc: CadDocument,
        hiddenLayers: Set<String>,
        hiddenNodes: Set<String>,
        ray: Ray3D,
        explodeOffsets: Map<String, Vec3> = emptyMap()
    ): PickHit? {
        var bestT = Double.POSITIVE_INFINITY
        var bestFace: CadFace? = null
        for (face in doc.faces) {
            if (face.hidden || face.layer in hiddenLayers || face.nodeId in hiddenNodes) continue
            val offset = explodeOffsets[face.nodeId] ?: Vec3.ZERO
            val tris = face.triangleVertices
            var i = 0
            while (i + 2 < tris.size) {
                val t = intersectRayTriangle(
                    ray,
                    tris[i] + offset,
                    tris[i + 1] + offset,
                    tris[i + 2] + offset
                )
                if (t != null && t > 1e-5 && t < bestT) {
                    bestT = t
                    bestFace = face
                }
                i += 3
            }
        }
        return bestFace?.let {
            PickHit(ray.origin + ray.direction * bestT, face = it, nodeId = it.nodeId)
        }
    }

    fun intersectRayTriangle(ray: Ray3D, v0: Vec3, v1: Vec3, v2: Vec3): Double? {
        val eps = 1e-7
        val e1 = v1 - v0
        val e2 = v2 - v0
        val pvec = ray.direction.cross(e2)
        val det = e1.dot(pvec)
        if (abs(det) < eps) return null
        val invDet = 1.0 / det
        val tvec = ray.origin - v0
        val u = tvec.dot(pvec) * invDet
        if (u < 0.0 || u > 1.0) return null
        val qvec = tvec.cross(e1)
        val v = ray.direction.dot(qvec) * invDet
        if (v < 0.0 || u + v > 1.0) return null
        val t = e2.dot(qvec) * invDet
        return if (t > eps) t else null
    }

    private fun closestTOnSegment2D(p: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val lenSq = ab.x * ab.x + ab.y * ab.y
        if (lenSq < 1e-9) return 0.0
        val t = ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lenSq
        return clamp(t, 0.0, 1.0)
    }
}
