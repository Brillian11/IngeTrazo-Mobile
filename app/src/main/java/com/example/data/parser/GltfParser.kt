package com.example.data.parser

import android.graphics.Bitmap
import android.util.Base64
import com.example.domain.math.EarcutTriangulator
import com.example.domain.math.Mat4
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
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin glTF 2.0 (.gltf with embedded base64 data URIs) and GLB binary container parser.
 * Converts glTF Y-up meters to IngeTrazo Z-up meters via (x, -z, y).
 */
class GltfParser : DocumentParser {
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GLTF, ModelFormat.GLB)

    override fun parse(
        bytes: ByteArray,
        documentKey: String,
        title: String,
        formatHint: ModelFormat?
    ): CadParseResult {
        return try {
            var jsonStr: String? = null
            var binChunk: ByteArray? = null
            val isGlb = bytes.size >= 12 &&
                bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() &&
                bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()

            if (isGlb) {
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                buf.int // magic
                val version = buf.int
                if (version != 2) {
                    return CadParseResult.Failure("Unsupported GLB version v$version", "Only glTF 2.0 is supported.")
                }
                val totalLength = buf.int.coerceAtMost(bytes.size)
                while (buf.position() + 8 <= totalLength) {
                    val chunkLen = buf.int
                    val chunkType = buf.int
                    if (chunkLen < 0 || buf.position() + chunkLen > totalLength) break
                    val chunkBytes = ByteArray(chunkLen)
                    buf.get(chunkBytes)
                    when (chunkType) {
                        0x4E4F534A -> jsonStr = chunkBytes.toString(Charsets.UTF_8) // JSON
                        0x004E4942 -> binChunk = chunkBytes // BIN
                    }
                }
            } else {
                jsonStr = bytes.toString(Charsets.UTF_8)
            }

            val root = JSONObject(jsonStr ?: return CadParseResult.Failure("Empty glTF payload"))
            val buffers = resolveBuffers(root, binChunk)
            val bufferViews = root.optJSONArray("bufferViews")
            val accessors = root.optJSONArray("accessors")
            val meshesArr = root.optJSONArray("meshes")
            val nodesArr = root.optJSONArray("nodes")
            val materialsArr = root.optJSONArray("materials")
            val imagesArr = root.optJSONArray("images")
            val texturesArr = root.optJSONArray("textures")

            val textures = HashMap<String, Bitmap>()
            if (imagesArr != null) {
                for (i in 0 until imagesArr.length()) {
                    val imgObj = imagesArr.optJSONObject(i) ?: continue
                    val imgBytes = extractImageBytes(imgObj, buffers, bufferViews) ?: continue
                    CadGeometryHelper.decodeSampledBitmap(imgBytes, 1024)?.let { bmp ->
                        textures["gltf_img_$i"] = bmp
                    }
                }
            }

            val materials = HashMap<String, CadMaterial>()
            if (materialsArr != null) {
                for (i in 0 until materialsArr.length()) {
                    val mObj = materialsArr.optJSONObject(i) ?: continue
                    val name = mObj.optString("name", "Material $i")
                    val pbr = mObj.optJSONObject("pbrMetallicRoughness")
                    val color = CadGeometryHelper.parseColorArray(pbr?.optJSONArray("baseColorFactor"), CadColor.DEFAULT_FRONT)
                    val texIdx = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                    val imgIdx = if (texIdx >= 0 && texturesArr != null && texIdx < texturesArr.length()) {
                        texturesArr.optJSONObject(texIdx)?.optInt("source", -1) ?: -1
                    } else -1
                    val texKey = if (imgIdx >= 0 && textures.containsKey("gltf_img_$imgIdx")) "gltf_img_$imgIdx" else null
                    materials["mat_$i"] = CadMaterial(
                        id = "mat_$i",
                        name = name,
                        color = color,
                        opacity = color.a,
                        textureKey = texKey
                    )
                }
            }

            val faces = mutableListOf<CadFace>()
            val edges = mutableListOf<CadEdge>()
            val cadNodes = mutableListOf<CadNode>()
            var idSeq = 0

            // glTF Y-up to IngeTrazo Z-up conversion matrix: (x, y, z) -> (x, -z, y)
            val yUpToZUp = Mat4(
                doubleArrayOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, -1.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 1.0
                )
            )

            fun emitMesh(meshIdx: Int, nodeId: String, nodeName: String, worldMat: Mat4): Pair<Int, Int> {
                if (meshesArr == null || meshIdx !in 0 until meshesArr.length()) return 0 to 0
                val meshObj = meshesArr.optJSONObject(meshIdx) ?: return 0 to 0
                val prims = meshObj.optJSONArray("primitives") ?: return 0 to 0
                var fCount = 0
                var eCount = 0
                val flip = worldMat.determinant3x3() < 0.0

                for (pIdx in 0 until prims.length()) {
                    val prim = prims.optJSONObject(pIdx) ?: continue
                    val mode = prim.optInt("mode", 4) // 4 = TRIANGLES, 1 = LINES
                    val attrs = prim.optJSONObject("attributes") ?: continue
                    val posAccessorIdx = attrs.optInt("POSITION", -1)
                    if (posAccessorIdx < 0 || accessors == null || bufferViews == null) continue

                    val posPts = readVec3Accessor(posAccessorIdx, accessors, bufferViews, buffers)
                        .map { worldMat.transformPoint(it) }
                    if (posPts.isEmpty()) continue

                    val uvAccessorIdx = attrs.optInt("TEXCOORD_0", -1)
                    val uvPts = if (uvAccessorIdx >= 0) {
                        readVec2Accessor(uvAccessorIdx, accessors, bufferViews, buffers)
                    } else emptyList()

                    val idxAccessorIdx = prim.optInt("indices", -1)
                    val indices = if (idxAccessorIdx >= 0) {
                        readIndexAccessor(idxAccessorIdx, accessors, bufferViews, buffers)
                    } else {
                        posPts.indices.toList()
                    }

                    if (mode == 1) {
                        var k = 0
                        while (k + 1 < indices.size) {
                            val ia = indices[k]
                            val ib = indices[k + 1]
                            if (ia in posPts.indices && ib in posPts.indices) {
                                edges.add(CadEdge("gl_e_${idSeq++}", nodeId, nodeName, posPts[ia], posPts[ib]))
                                eCount++
                            }
                            k += 2
                        }
                        continue
                    }

                    if (mode != 4) continue
                    val matKey = prim.optInt("material", -1).takeIf { it >= 0 }?.let { "mat_$it" }
                    val mat = matKey?.let { materials[it] }
                    val color = mat?.color ?: CadColor.DEFAULT_FRONT
                    val texKey = mat?.textureKey

                    val sharpEdges = HashSet<Pair<Int, Int>>()
                    var k = 0
                    while (k + 2 < indices.size) {
                        val i0 = indices[k]
                        val i1 = if (flip) indices[k + 2] else indices[k + 1]
                        val i2 = if (flip) indices[k + 1] else indices[k + 2]
                        if (i0 in posPts.indices && i1 in posPts.indices && i2 in posPts.indices) {
                            val p0 = posPts[i0]
                            val p1 = posPts[i1]
                            val p2 = posPts[i2]
                            val loop = listOf(p0, p1, p2)
                            val normal = EarcutTriangulator.computePolygonNormal(loop)
                            val triUvs = if (i0 in uvPts.indices && i1 in uvPts.indices && i2 in uvPts.indices) {
                                listOf(uvPts[i0], uvPts[i1], uvPts[i2])
                            } else {
                                CadGeometryHelper.computePlanarUvs(loop, normal)
                            }
                            faces.add(
                                CadFace(
                                    id = "gl_f_${idSeq++}",
                                    nodeId = nodeId,
                                    layer = nodeName,
                                    outerLoop = loop,
                                    normal = normal,
                                    triangleVertices = loop,
                                    triangleUvs = triUvs,
                                    color = color,
                                    materialKey = matKey,
                                    textureKey = texKey
                                )
                            )
                            fCount++
                            val pairs = listOf(
                                minOf(i0, i1) to maxOf(i0, i1),
                                minOf(i1, i2) to maxOf(i1, i2),
                                minOf(i2, i0) to maxOf(i2, i0)
                            )
                            for ((a, b) in pairs) {
                                if (sharpEdges.add(a to b)) {
                                    edges.add(
                                        CadEdge(
                                            id = "gl_e_${idSeq++}",
                                            nodeId = nodeId,
                                            layer = nodeName,
                                            a = posPts[a],
                                            b = posPts[b],
                                            soft = true
                                        )
                                    )
                                    eCount++
                                }
                            }
                        }
                        k += 3
                    }
                }
                return fCount to eCount
            }

            fun visitNode(nodeIdx: Int, parentId: String?, parentWorld: Mat4): String {
                val nObj = nodesArr?.optJSONObject(nodeIdx) ?: return "node_$nodeIdx"
                val nodeId = "gltf_n_$nodeIdx"
                val name = nObj.optString("name", "").ifBlank { "Node $nodeIdx" }
                val local = parseNodeTransform(nObj)
                val world = parentWorld * local

                var fCount = 0
                var eCount = 0
                val meshIdx = nObj.optInt("mesh", -1)
                if (meshIdx >= 0) {
                    val (af, ae) = emitMesh(meshIdx, nodeId, name, world)
                    fCount += af
                    eCount += ae
                }
                val childrenIds = mutableListOf<String>()
                nObj.optJSONArray("children")?.let { ch ->
                    for (i in 0 until ch.length()) {
                        val ci = ch.optInt(i, -1)
                        if (ci >= 0) childrenIds.add(visitNode(ci, nodeId, world))
                    }
                }
                cadNodes.add(
                    CadNode(
                        id = nodeId,
                        name = name,
                        parentId = parentId,
                        layer = name,
                        isComponent = fCount > 0,
                        localTransform = local,
                        faceCount = fCount,
                        edgeCount = eCount,
                        childrenIds = childrenIds
                    )
                )
                return nodeId
            }

            val scenesArr = root.optJSONArray("scenes")
            val activeSceneIdx = root.optInt("scene", 0)
            val rootNodeIndices = scenesArr?.optJSONObject(activeSceneIdx)?.optJSONArray("nodes")
            if (rootNodeIndices != null && nodesArr != null) {
                for (i in 0 until rootNodeIndices.length()) {
                    val ni = rootNodeIndices.optInt(i, -1)
                    if (ni >= 0) visitNode(ni, null, yUpToZUp)
                }
            } else if (meshesArr != null) {
                for (i in 0 until meshesArr.length()) {
                    val nodeId = "gltf_m_$i"
                    val name = meshesArr.optJSONObject(i)?.optString("name", "Mesh $i") ?: "Mesh $i"
                    val (af, ae) = emitMesh(i, nodeId, name, yUpToZUp)
                    cadNodes.add(CadNode(nodeId, name, layer = name, isComponent = af > 0, faceCount = af, edgeCount = ae))
                }
            }

            if (faces.isEmpty() && edges.isEmpty()) {
                return CadParseResult.Failure("No renderable geometry in glTF/GLB", "External .bin files are not accessible from SAF streams; embed buffers or use .glb.")
            }

            val layers = cadNodes.map { it.layer }.distinct().map { CadLayer(name = it, isVisible = true) }
            val (bounds, stats) = CadGeometryHelper.computeStatsAndBounds(
                nodeCount = cadNodes.size,
                faces = faces,
                edges = edges,
                layerCount = layers.size,
                savedViewCount = 0,
                textureCount = textures.size
            )

            CadParseResult.Success(
                CadDocument(
                    documentKey = documentKey,
                    title = title,
                    format = if (isGlb) ModelFormat.GLB else ModelFormat.GLTF,
                    formatVersion = "glTF 2.0 (Y-up → Z-up)",
                    sourceUnits = UnitSystem.METERS,
                    nodes = cadNodes,
                    faces = faces,
                    edges = edges,
                    layers = layers,
                    materials = materials,
                    textures = textures,
                    savedViews = emptyList(),
                    bounds = bounds,
                    stats = stats
                )
            )
        } catch (e: Exception) {
            CadParseResult.Failure("Failed to parse glTF/GLB file", e.localizedMessage)
        }
    }

    private fun resolveBuffers(root: JSONObject, binChunk: ByteArray?): List<ByteArray> {
        val buffersArr = root.optJSONArray("buffers") ?: return if (binChunk != null) listOf(binChunk) else emptyList()
        val out = ArrayList<ByteArray>(buffersArr.length())
        for (i in 0 until buffersArr.length()) {
            val bObj = buffersArr.optJSONObject(i)
            val uri = bObj?.optString("uri", "") ?: ""
            when {
                uri.isEmpty() && i == 0 && binChunk != null -> out.add(binChunk)
                uri.startsWith("data:") -> {
                    val b64 = uri.substringAfter("base64,", "")
                    out.add(if (b64.isNotEmpty()) Base64.decode(b64, Base64.DEFAULT) else ByteArray(0))
                }
                else -> out.add(ByteArray(0))
            }
        }
        return out
    }

    private fun extractImageBytes(
        imgObj: JSONObject,
        buffers: List<ByteArray>,
        bufferViews: JSONArray?
    ): ByteArray? {
        val bvIdx = imgObj.optInt("bufferView", -1)
        if (bvIdx >= 0 && bufferViews != null && bvIdx < bufferViews.length()) {
            val bv = bufferViews.optJSONObject(bvIdx) ?: return null
            val bIdx = bv.optInt("buffer", 0)
            val buf = buffers.getOrNull(bIdx) ?: return null
            val off = bv.optInt("byteOffset", 0)
            val len = bv.optInt("byteLength", 0)
            if (off >= 0 && off + len <= buf.size) return buf.copyOfRange(off, off + len)
        }
        val uri = imgObj.optString("uri", "")
        if (uri.startsWith("data:") && uri.contains("base64,")) {
            return Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
        }
        return null
    }

    private fun parseNodeTransform(nObj: JSONObject): Mat4 {
        nObj.optJSONArray("matrix")?.let { mArr ->
            if (mArr.length() == 16) {
                return Mat4(DoubleArray(16) { mArr.optDouble(it, 0.0) })
            }
        }
        val t = nObj.optJSONArray("translation")
        val r = nObj.optJSONArray("rotation")
        val s = nObj.optJSONArray("scale")
        val tMat = if (t != null && t.length() >= 3) {
            Mat4.translation(t.optDouble(0), t.optDouble(1), t.optDouble(2))
        } else Mat4.IDENTITY
        val rMat = if (r != null && r.length() >= 4) {
            Mat4.fromQuaternion(r.optDouble(0), r.optDouble(1), r.optDouble(2), r.optDouble(3))
        } else Mat4.IDENTITY
        val sMat = if (s != null && s.length() >= 3) {
            Mat4.scale(s.optDouble(0, 1.0), s.optDouble(1, 1.0), s.optDouble(2, 1.0))
        } else Mat4.IDENTITY
        return tMat * rMat * sMat
    }

    private fun readVec3Accessor(
        idx: Int,
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray>
    ): List<Vec3> {
        val acc = accessors.optJSONObject(idx) ?: return emptyList()
        val bvIdx = acc.optInt("bufferView", -1)
        if (bvIdx < 0) return emptyList()
        val bv = bufferViews.optJSONObject(bvIdx) ?: return emptyList()
        val buf = buffers.getOrNull(bv.optInt("buffer", 0)) ?: return emptyList()
        val count = acc.optInt("count", 0)
        val baseOff = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val stride = bv.optInt("byteStride", 12).coerceAtLeast(12)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Vec3>(count)
        for (i in 0 until count) {
            val pos = baseOff + i * stride
            if (pos + 12 > buf.size) break
            out.add(Vec3(bb.getFloat(pos).toDouble(), bb.getFloat(pos + 4).toDouble(), bb.getFloat(pos + 8).toDouble()))
        }
        return out
    }

    private fun readVec2Accessor(
        idx: Int,
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray>
    ): List<Vec2> {
        val acc = accessors.optJSONObject(idx) ?: return emptyList()
        val bvIdx = acc.optInt("bufferView", -1)
        if (bvIdx < 0) return emptyList()
        val bv = bufferViews.optJSONObject(bvIdx) ?: return emptyList()
        val buf = buffers.getOrNull(bv.optInt("buffer", 0)) ?: return emptyList()
        val count = acc.optInt("count", 0)
        val baseOff = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val stride = bv.optInt("byteStride", 8).coerceAtLeast(8)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Vec2>(count)
        for (i in 0 until count) {
            val pos = baseOff + i * stride
            if (pos + 8 > buf.size) break
            out.add(Vec2(bb.getFloat(pos).toDouble(), bb.getFloat(pos + 4).toDouble()))
        }
        return out
    }

    private fun readIndexAccessor(
        idx: Int,
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray>
    ): List<Int> {
        val acc = accessors.optJSONObject(idx) ?: return emptyList()
        val bvIdx = acc.optInt("bufferView", -1)
        if (bvIdx < 0) return emptyList()
        val bv = bufferViews.optJSONObject(bvIdx) ?: return emptyList()
        val buf = buffers.getOrNull(bv.optInt("buffer", 0)) ?: return emptyList()
        val count = acc.optInt("count", 0)
        val compType = acc.optInt("componentType", 5123)
        val elemSize = when (compType) {
            5121 -> 1
            5123 -> 2
            5125 -> 4
            else -> return emptyList()
        }
        val baseOff = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val stride = bv.optInt("byteStride", elemSize).coerceAtLeast(elemSize)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Int>(count)
        for (i in 0 until count) {
            val pos = baseOff + i * stride
            if (pos + elemSize > buf.size) break
            val v = when (compType) {
                5121 -> buf[pos].toInt() and 0xFF
                5123 -> bb.getShort(pos).toInt() and 0xFFFF
                else -> bb.getInt(pos)
            }
            out.add(v)
        }
        return out
    }
}
