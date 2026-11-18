package com.example.data.parser

import com.example.domain.math.EarcutTriangulator
import com.example.domain.math.Vec2
import com.example.domain.math.Vec3
import com.example.domain.model.CadColor
import com.example.domain.model.CadDocument
import com.example.domain.model.CadEdge
import com.example.domain.model.CadFace
import com.example.domain.model.CadLayer
import com.example.domain.model.CadMaterial
import com.example.domain.model.CadNode
import com.example.domain.model.ModelFormat
import com.example.domain.model.UnitSystem

/**
 * Wavefront OBJ parser supporting vertices, texture coordinates, faces (with earcut triangulation),
 * polylines, object/group hierarchy, and material color naming.
 */
class ObjParser : DocumentParser {
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.OBJ)

    override fun parse(
        bytes: ByteArray,
        documentKey: String,
        title: String,
        formatHint: ModelFormat?
    ): CadParseResult {
        return try {
            val text = bytes.toString(Charsets.UTF_8)
            val positions = mutableListOf<Vec3>()
            val uvs = mutableListOf<Vec2>()
            val faces = mutableListOf<CadFace>()
            val edges = mutableListOf<CadEdge>()
            val edgeDedup = HashSet<Pair<Int, Int>>()
            val nodes = mutableListOf<CadNode>()
            val nodeFaceCounts = HashMap<String, Int>()
            val nodeEdgeCounts = HashMap<String, Int>()
            val materials = HashMap<String, CadMaterial>()

            var currentGroup = "Model Root"
            var currentMtl: String? = null
            var idSeq = 0

            fun ensureNode(name: String): String {
                if (!nodeFaceCounts.containsKey(name)) {
                    nodeFaceCounts[name] = 0
                    nodeEdgeCounts[name] = 0
                }
                return name
            }

            fun resolveIndex(rawIdx: Int, size: Int): Int {
                return if (rawIdx > 0) rawIdx - 1 else size + rawIdx
            }

            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "v" -> if (parts.size >= 4) {
                        val x = parts[1].toDoubleOrNull()
                        val y = parts[2].toDoubleOrNull()
                        val z = parts[3].toDoubleOrNull()
                        if (x != null && y != null && z != null) {
                            // OBJ is commonly Y-up; map (x, -z, y) to IngeTrazo Z-up
                            positions.add(Vec3(x, -z, y))
                        }
                    }
                    "vt" -> if (parts.size >= 3) {
                        val u = parts[1].toDoubleOrNull() ?: 0.0
                        val v = parts[2].toDoubleOrNull() ?: 0.0
                        uvs.add(Vec2(u, v))
                    }
                    "o", "g" -> {
                        val name = parts.drop(1).joinToString(" ").ifBlank { "Group" }
                        currentGroup = ensureNode(name)
                    }
                    "usemtl" -> {
                        currentMtl = parts.drop(1).joinToString(" ").ifBlank { null }
                        currentMtl?.let { matName ->
                            materials.putIfAbsent(
                                matName,
                                CadMaterial(
                                    id = matName,
                                    name = matName,
                                    color = deterministicMaterialColor(matName)
                                )
                            )
                        }
                    }
                    "l" -> if (parts.size >= 3) {
                        ensureNode(currentGroup)
                        val idxs = parts.drop(1).mapNotNull { token ->
                            val vi = token.substringBefore('/').toIntOrNull() ?: return@mapNotNull null
                            val resolved = resolveIndex(vi, positions.size)
                            if (resolved in positions.indices) resolved else null
                        }
                        for (i in 0 until idxs.size - 1) {
                            val aIdx = idxs[i]
                            val bIdx = idxs[i + 1]
                            val key = minOf(aIdx, bIdx) to maxOf(aIdx, bIdx)
                            if (edgeDedup.add(key)) {
                                edges.add(
                                    CadEdge(
                                        id = "obj_e_${idSeq++}",
                                        nodeId = currentGroup,
                                        layer = currentGroup,
                                        a = positions[aIdx],
                                        b = positions[bIdx]
                                    )
                                )
                                nodeEdgeCounts[currentGroup] = (nodeEdgeCounts[currentGroup] ?: 0) + 1
                            }
                        }
                    }
                    "f" -> if (parts.size >= 4) {
                        ensureNode(currentGroup)
                        val vIndices = mutableListOf<Int>()
                        val vtIndices = mutableListOf<Int?>()
                        for (token in parts.drop(1)) {
                            val segs = token.split('/')
                            val vi = segs.getOrNull(0)?.toIntOrNull() ?: continue
                            val resolvedV = resolveIndex(vi, positions.size)
                            if (resolvedV !in positions.indices) continue
                            vIndices.add(resolvedV)
                            val vti = segs.getOrNull(1)?.toIntOrNull()?.let { resolveIndex(it, uvs.size) }
                            vtIndices.add(if (vti != null && vti in uvs.indices) vti else null)
                        }
                        if (vIndices.size >= 3) {
                            val loopPts = vIndices.map { positions[it] }
                            val normal = EarcutTriangulator.computePolygonNormal(loopPts)
                            val tris = EarcutTriangulator.triangulate(loopPts, emptyList(), normal)
                            if (tris.isNotEmpty()) {
                                val faceUvs = CadGeometryHelper.computePlanarUvs(tris, normal)
                                val matColor = currentMtl?.let { materials[it]?.color } ?: CadColor.DEFAULT_FRONT
                                faces.add(
                                    CadFace(
                                        id = "obj_f_${idSeq++}",
                                        nodeId = currentGroup,
                                        layer = currentGroup,
                                        outerLoop = loopPts,
                                        normal = normal,
                                        triangleVertices = tris,
                                        triangleUvs = faceUvs,
                                        color = matColor,
                                        materialKey = currentMtl
                                    )
                                )
                                nodeFaceCounts[currentGroup] = (nodeFaceCounts[currentGroup] ?: 0) + 1
                            }
                            for (i in vIndices.indices) {
                                val aIdx = vIndices[i]
                                val bIdx = vIndices[(i + 1) % vIndices.size]
                                val key = minOf(aIdx, bIdx) to maxOf(aIdx, bIdx)
                                if (edgeDedup.add(key)) {
                                    edges.add(
                                        CadEdge(
                                            id = "obj_e_${idSeq++}",
                                            nodeId = currentGroup,
                                            layer = currentGroup,
                                            a = positions[aIdx],
                                            b = positions[bIdx]
                                        )
                                    )
                                    nodeEdgeCounts[currentGroup] = (nodeEdgeCounts[currentGroup] ?: 0) + 1
                                }
                            }
                        }
                    }
                }
            }

            if (positions.isEmpty()) {
                return CadParseResult.Failure("No geometry in OBJ file", "Could not find any vertex ('v') records.")
            }

            if (nodeFaceCounts.isEmpty()) ensureNode("Model Root")
            val layers = nodeFaceCounts.keys.map { CadLayer(name = it, isVisible = true) }
            nodeFaceCounts.keys.forEach { name ->
                val fCnt = nodeFaceCounts[name] ?: 0
                nodes.add(
                    CadNode(
                        id = name,
                        name = name,
                        layer = name,
                        isComponent = fCnt >= 2 && name != "Model Root",
                        faceCount = fCnt,
                        edgeCount = nodeEdgeCounts[name] ?: 0
                    )
                )
            }

            val (bounds, stats) = CadGeometryHelper.computeStatsAndBounds(
                nodeCount = nodes.size,
                faces = faces,
                edges = edges,
                layerCount = layers.size,
                savedViewCount = 0,
                textureCount = 0
            )

            CadParseResult.Success(
                CadDocument(
                    documentKey = documentKey,
                    title = title,
                    format = ModelFormat.OBJ,
                    formatVersion = "Wavefront OBJ (Y-up → Z-up)",
                    sourceUnits = UnitSystem.METERS,
                    nodes = nodes,
                    faces = faces,
                    edges = edges,
                    layers = layers,
                    materials = materials,
                    textures = emptyMap(),
                    savedViews = emptyList(),
                    bounds = bounds,
                    stats = stats
                )
            )
        } catch (e: Exception) {
            CadParseResult.Failure("Failed to parse OBJ file", e.localizedMessage)
        }
    }

    private fun deterministicMaterialColor(name: String): CadColor {
        val h = name.hashCode()
        val r = 0.45f + ((h and 0xFF) / 255f) * 0.45f
        val g = 0.45f + (((h shr 8) and 0xFF) / 255f) * 0.45f
        val b = 0.45f + (((h shr 16) and 0xFF) / 255f) * 0.45f
        return CadColor(r, g, b, 1f)
    }
}
