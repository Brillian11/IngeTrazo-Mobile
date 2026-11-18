package com.example.data.parser

import android.graphics.Bitmap
import com.example.domain.math.EarcutTriangulator
import com.example.domain.math.Mat4
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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin OpenSKP-inspired reader for SketchUp (.skp) files.
 *
 * Supports VFF 2021+ (.skp) containers (FF FE FF 0E / embedded ZIP with model.dat TLV stream,
 * materials/{id}/material.xml, embedded textures, layers, definitions, and nested instances).
 * Scales internal SketchUp inches -> metres (x 0.0254) in Z-up coordinates.
 * Classic pre-2021 MFC CArchive .skp files are detected cleanly and routed to an actionable
 * message referencing tools/ingetrazo_converter.py.
 */
class SkpParser : DocumentParser {
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.SKP)

    private data class TlvNode(
        val tag: String,
        val payloadStart: Int,
        val payloadEnd: Int,
        val children: List<TlvNode>
    )

    private data class SkpEdgeRaw(
        val id: Long,
        val v1: Long,
        val v2: Long,
        val flags: Int,
        val layerId: Long?
    )

    private data class SkpFaceRaw(
        val id: Long,
        val normal: Vec3,
        val loops: List<List<Long>>,
        val matId: Long?,
        val backMatId: Long?,
        val layerId: Long?,
        val hidden: Boolean
    )

    private data class SkpInstanceRaw(
        val name: String,
        val defId: Long?,
        val matrix13: DoubleArray?,
        val matId: Long?,
        val layerId: Long?,
        val hidden: Boolean,
        val childInstances: List<SkpInstanceRaw>
    )

    private data class SkpDefData(
        val id: Long,
        val name: String,
        val isGroup: Boolean,
        val vertices: Map<Long, Vec3>,
        val edges: Map<Long, SkpEdgeRaw>,
        val faces: List<SkpFaceRaw>,
        val instances: List<SkpInstanceRaw>
    )

    override fun parse(
        bytes: ByteArray,
        documentKey: String,
        title: String,
        formatHint: ModelFormat?
    ): CadParseResult {
        return try {
            val isVffMagic = bytes.size >= 4 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
                bytes[2] == 0xFF.toByte() && bytes[3] == 0x0E.toByte()
            val zipOffset = findZipHeader(bytes)

            if (zipOffset < 0) {
                val isLegacyMfc = isVffMagic && containsAscii(bytes, 0, minOf(bytes.size, 512), "CVersionMap")
                return if (isLegacyMfc) {
                    CadParseResult.Failure(
                        "Classic pre-2021 MFC .skp detected",
                        "On-device .skp parsing supports VFF 2021+ (.skp) files. Convert older 2013–2020 MFC .skp files using tools/ingetrazo_converter.py to .igz or .glb."
                    )
                } else {
                    CadParseResult.Failure(
                        "Unrecognized .skp file structure",
                        "No SketchUp VFF/ZIP container found in this file."
                    )
                }
            }

            val versionStr = readVffVersion(bytes)
            val zipBytes = bytes.copyOfRange(zipOffset, bytes.size)

            var modelDat: ByteArray? = null
            val materialXmls = HashMap<String, ByteArray>()
            val rawTextures = HashMap<String, ByteArray>()

            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val data = zis.readBytes()
                        when {
                            name == "model.dat" -> modelDat = data
                            name.startsWith("materials/") && name.endsWith("material.xml") -> {
                                materialXmls[name] = data
                            }
                            name.startsWith("materials/") && isImageFile(name) -> {
                                rawTextures[name] = data
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val dat = modelDat
                ?: return CadParseResult.Failure("Invalid .skp archive", "Missing model.dat inside VFF ZIP container.")

            val materialsByName = HashMap<String, CadMaterial>()
            val textures = HashMap<String, Bitmap>()
            parseMaterialsXml(materialXmls, rawTextures, materialsByName, textures)

            val layerIdToName = HashMap<Long, String>()
            val layerHidden = HashMap<String, Boolean>()
            val materialIdToName = HashMap<Long, String>()
            val defsById = HashMap<Long, SkpDefData>()
            val rootVerts = HashMap<Long, Vec3>()
            val rootEdges = HashMap<Long, SkpEdgeRaw>()
            val rootFaces = mutableListOf<SkpFaceRaw>()
            val rootInstances = mutableListOf<SkpInstanceRaw>()

            val topNodes = parseTopLevelTlvs(dat)
            for (node in topNodes) {
                walkTopLevel(
                    node = node,
                    data = dat,
                    layerIdToName = layerIdToName,
                    layerHidden = layerHidden,
                    materialIdToName = materialIdToName,
                    defsById = defsById,
                    rootVerts = rootVerts,
                    rootEdges = rootEdges,
                    rootFaces = rootFaces,
                    rootInstances = rootInstances
                )
            }

            val allFaces = mutableListOf<CadFace>()
            val allEdges = mutableListOf<CadEdge>()
            val allNodes = mutableListOf<CadNode>()
            val layersSeen = HashSet<String>()
            val layers = mutableListOf<CadLayer>()

            fun ensureLayer(name: String) {
                if (layersSeen.add(name)) {
                    layers.add(CadLayer(name = name, isVisible = !(layerHidden[name] ?: false)))
                }
            }
            ensureLayer("Layer0")
            layerIdToName.values.forEach(::ensureLayer)

            var idSeq = 0
            fun nextId(prefix: String) = "${prefix}_${idSeq++}"

            fun resolveMat(matId: Long?, fallbackMatId: Long?): CadMaterial? {
                val id = matId ?: fallbackMatId ?: return null
                val name = materialIdToName[id] ?: return null
                return materialsByName[name]
            }

            fun emitDefinitionGeometry(
                nodeId: String,
                verts: Map<Long, Vec3>,
                edges: Map<Long, SkpEdgeRaw>,
                faces: List<SkpFaceRaw>,
                worldTransform: Mat4,
                inheritedLayer: String,
                inheritedMatId: Long?
            ): Pair<Int, Int> {
                var fCount = 0
                var eCount = 0
                val flip = worldTransform.determinant3x3() < 0.0

                for (e in edges.values) {
                    val va = verts[e.v1] ?: continue
                    val vb = verts[e.v2] ?: continue
                    val eLayer = e.layerId?.let { layerIdToName[it] } ?: inheritedLayer
                    ensureLayer(eLayer)
                    allEdges.add(
                        CadEdge(
                            id = nextId("skp_e"),
                            nodeId = nodeId,
                            layer = eLayer,
                            a = worldTransform.transformPoint(va),
                            b = worldTransform.transformPoint(vb),
                            soft = (e.flags and 0x18) != 0,
                            hidden = (e.flags and 0x01) != 0
                        )
                    )
                    eCount++
                }

                for (f in faces) {
                    if (f.loops.isEmpty()) continue
                    val rings = f.loops.mapNotNull { loopEdgeIds ->
                        resolveRingPoints(loopEdgeIds, edges, verts)
                    }
                    if (rings.isEmpty() || rings[0].size < 3) continue
                    val transformedOuter = rings[0].map { worldTransform.transformPoint(it) }.let {
                        if (flip) it.reversed() else it
                    }
                    val transformedHoles = rings.drop(1).map { hole ->
                        hole.map { worldTransform.transformPoint(it) }.let {
                            if (flip) it.reversed() else it
                        }
                    }

                    val normal = EarcutTriangulator.computePolygonNormal(transformedOuter)
                    val tris = EarcutTriangulator.triangulate(transformedOuter, transformedHoles, normal)
                    if (tris.isEmpty()) continue

                    val fLayer = f.layerId?.let { layerIdToName[it] } ?: inheritedLayer
                    ensureLayer(fLayer)
                    val frontMat = resolveMat(f.matId, inheritedMatId)
                    val backMat = resolveMat(f.backMatId, null)
                    val color = frontMat?.color ?: CadColor.DEFAULT_FRONT
                    val backColor = backMat?.color ?: CadColor.DEFAULT_BACK
                    val sw = frontMat?.tileWidthMeters ?: 1.0
                    val sh = frontMat?.tileHeightMeters ?: 1.0
                    val uvs = CadGeometryHelper.computePlanarUvs(tris, normal, sw, sh)

                    allFaces.add(
                        CadFace(
                            id = nextId("skp_f"),
                            nodeId = nodeId,
                            layer = fLayer,
                            outerLoop = transformedOuter,
                            holes = transformedHoles,
                            normal = normal,
                            triangleVertices = tris,
                            triangleUvs = uvs,
                            color = color,
                            backColor = backColor,
                            materialKey = frontMat?.name,
                            textureKey = frontMat?.textureKey,
                            hidden = f.hidden
                        )
                    )
                    fCount++
                }
                return fCount to eCount
            }

            fun emitInstanceTree(
                inst: SkpInstanceRaw,
                parentId: String?,
                parentWorld: Mat4,
                inheritedLayer: String,
                inheritedMatId: Long?,
                depth: Int
            ): String {
                val nodeId = nextId("skp_n")
                if (depth > 32) return nodeId
                val localInches = inst.matrix13?.let { Mat4.fromSkp13(it) } ?: Mat4.IDENTITY
                val world = parentWorld * localInches
                val layer = inst.layerId?.let { layerIdToName[it] } ?: inheritedLayer
                val matId = inst.matId ?: inheritedMatId
                val def = inst.defId?.let { defsById[it] }
                val nodeName = inst.name.ifBlank { def?.name?.ifBlank { null } ?: "Component" }

                var fCount = 0
                var eCount = 0
                val childrenIds = mutableListOf<String>()
                if (def != null) {
                    val (af, ae) = emitDefinitionGeometry(
                        nodeId = nodeId,
                        verts = def.vertices,
                        edges = def.edges,
                        faces = def.faces,
                        worldTransform = world,
                        inheritedLayer = layer,
                        inheritedMatId = matId
                    )
                    fCount += af
                    eCount += ae
                    for (sub in def.instances) {
                        childrenIds.add(emitInstanceTree(sub, nodeId, world, layer, matId, depth + 1))
                    }
                }
                for (sub in inst.childInstances) {
                    childrenIds.add(emitInstanceTree(sub, nodeId, world, layer, matId, depth + 1))
                }

                allNodes.add(
                    CadNode(
                        id = nodeId,
                        name = nodeName,
                        parentId = parentId,
                        layer = layer,
                        isComponent = !(def?.isGroup ?: false),
                        isVisible = !inst.hidden,
                        localTransform = localInches,
                        faceCount = fCount,
                        edgeCount = eCount,
                        childrenIds = childrenIds
                    )
                )
                return nodeId
            }

            // SketchUp stores lengths in inches; convert root world transform to metres
            val inchesToMeters = Mat4.scale(INCH_TO_METERS, INCH_TO_METERS, INCH_TO_METERS)
            val rootId = "skp_root"
            val (rf, re) = emitDefinitionGeometry(
                nodeId = rootId,
                verts = rootVerts,
                edges = rootEdges,
                faces = rootFaces,
                worldTransform = inchesToMeters,
                inheritedLayer = "Layer0",
                inheritedMatId = null
            )
            val rootChildren = rootInstances.map {
                emitInstanceTree(it, rootId, inchesToMeters, "Layer0", null, 0)
            }
            allNodes.add(
                0,
                CadNode(
                    id = rootId,
                    name = "Model Root",
                    layer = "Layer0",
                    faceCount = rf,
                    edgeCount = re,
                    childrenIds = rootChildren
                )
            )

            if (allFaces.isEmpty() && allEdges.isEmpty()) {
                return CadParseResult.Failure(
                    "No geometry decoded from .skp",
                    "This .skp file contains no readable faces or edges (version: $versionStr). Try converting via tools/ingetrazo_converter.py."
                )
            }

            val (bounds, stats) = CadGeometryHelper.computeStatsAndBounds(
                nodeCount = allNodes.size,
                faces = allFaces,
                edges = allEdges,
                layerCount = layers.size,
                savedViewCount = 0,
                textureCount = textures.size
            )

            CadParseResult.Success(
                CadDocument(
                    documentKey = documentKey,
                    title = title,
                    format = ModelFormat.SKP,
                    formatVersion = "SKP VFF $versionStr (OpenSKP-Kotlin)",
                    sourceUnits = UnitSystem.METERS,
                    nodes = allNodes,
                    faces = allFaces,
                    edges = allEdges,
                    layers = layers,
                    materials = materialsByName,
                    textures = textures,
                    savedViews = emptyList(),
                    bounds = bounds,
                    stats = stats
                )
            )
        } catch (e: Exception) {
            CadParseResult.Failure(
                "Failed to parse SketchUp (.skp) file",
                e.localizedMessage ?: e.javaClass.simpleName
            )
        }
    }

    private fun resolveRingPoints(
        loopEdgeIds: List<Long>,
        edges: Map<Long, SkpEdgeRaw>,
        verts: Map<Long, Vec3>
    ): List<Vec3>? {
        val n = loopEdgeIds.size
        if (n < 3) return null
        val edgeObjs = ArrayList<SkpEdgeRaw>(n)
        for (eid in loopEdgeIds) {
            val e = edges[eid] ?: return null
            edgeObjs.add(e)
        }
        val pts = ArrayList<Vec3>(n)
        for (i in 0 until n) {
            val curr = edgeObjs[i]
            val nxt = edgeObjs[(i + 1) % n]
            val vid = when {
                curr !== nxt && (curr.v2 == nxt.v1 || curr.v2 == nxt.v2) -> curr.v2
                curr !== nxt && (curr.v1 == nxt.v1 || curr.v1 == nxt.v2) -> curr.v1
                else -> curr.v2
            }
            val p = verts[vid] ?: return null
            pts.add(p)
        }
        return pts
    }

    private fun walkTopLevel(
        node: TlvNode,
        data: ByteArray,
        layerIdToName: MutableMap<Long, String>,
        layerHidden: MutableMap<String, Boolean>,
        materialIdToName: MutableMap<Long, String>,
        defsById: MutableMap<Long, SkpDefData>,
        rootVerts: MutableMap<Long, Vec3>,
        rootEdges: MutableMap<Long, SkpEdgeRaw>,
        rootFaces: MutableList<SkpFaceRaw>,
        rootInstances: MutableList<SkpInstanceRaw>
    ) {
        when (node.tag) {
            "993A" -> {
                for (c in node.children) {
                    if (c.tag == "8C3C") {
                        val dc05 = findChild(c.children, "DC05")
                        val nameNode = findChild(c.children, "8D3C")
                        if (dc05 != null && nameNode != null) {
                            val id = extractEntityKey(data, dc05)
                            val name = decodeUtf8(data, nameNode)
                            layerIdToName[id] = name
                            val hid = findChild(c.children, "8E3C")
                            if (hid != null && hid.payloadStart < hid.payloadEnd) {
                                layerHidden[name] = data[hid.payloadStart].toInt() == 1
                            }
                        }
                    }
                }
            }
            "C832" -> {
                val dc05 = findChild(node.children, "DC05")
                val nameNode = findChild(node.children, "CC32")
                if (dc05 != null && nameNode != null) {
                    materialIdToName[extractEntityKey(data, dc05)] = decodeUtf8(data, nameNode)
                }
            }
            "7C15" -> {
                val entId = extractEntityId(data, node) ?: return
                var defName = ""
                var isGroup = false
                for (c in node.children) {
                    when (c.tag) {
                        "7E15" -> defName = decodeUtf8(data, c)
                        "8315" -> {
                            val len = c.payloadEnd - c.payloadStart
                            if (len > 0) isGroup = parseVarInt(data, c.payloadStart, len) == 1L
                        }
                    }
                }
                val verts = HashMap<Long, Vec3>()
                val edges = HashMap<Long, SkpEdgeRaw>()
                val faces = mutableListOf<SkpFaceRaw>()
                val insts = mutableListOf<SkpInstanceRaw>()
                extractGeometryTree(node.children, data, verts, edges, faces, insts)
                defsById[entId] = SkpDefData(entId, defName, isGroup, verts, edges, faces, insts)
            }
            "C409", "B80B", "AC0D", "6419" -> {
                extractGeometryTree(listOf(node), data, rootVerts, rootEdges, rootFaces, rootInstances)
            }
            else -> {
                for (child in node.children) {
                    walkTopLevel(
                        child, data, layerIdToName, layerHidden, materialIdToName,
                        defsById, rootVerts, rootEdges, rootFaces, rootInstances
                    )
                }
            }
        }
    }

    private fun extractGeometryTree(
        nodes: List<TlvNode>,
        data: ByteArray,
        verts: MutableMap<Long, Vec3>,
        edges: MutableMap<Long, SkpEdgeRaw>,
        faces: MutableList<SkpFaceRaw>,
        instances: MutableList<SkpInstanceRaw>
    ) {
        for (el in nodes) {
            when (el.tag) {
                "C409" -> {
                    val vid = extractEntityId(data, el)
                    val c509 = findTagRecursive(el.children, "C509")
                    if (vid != null && c509 != null && c509.payloadEnd - c509.payloadStart >= 24) {
                        val bb = ByteBuffer.wrap(data, c509.payloadStart, 24).order(ByteOrder.LITTLE_ENDIAN)
                        verts[vid] = Vec3(bb.double, bb.double, bb.double)
                    }
                }
                "B80B" -> {
                    val eid = extractEntityId(data, el)
                    if (eid != null) {
                        val v1n = findTagRecursive(el.children, "B90B")
                        val v2n = findTagRecursive(el.children, "BA0B")
                        val v1 = v1n?.let { parseVarInt(data, it.payloadStart, it.payloadEnd - it.payloadStart) }
                        val v2 = v2n?.let { parseVarInt(data, it.payloadStart, it.payloadEnd - it.payloadStart) }
                        if (v1 != null && v2 != null) {
                            val d007 = findChild(el.children, "D007")
                            val d307 = d007?.let { findChild(it.children, "D307") }
                            val flags = if (d307 != null && d307.payloadStart < d307.payloadEnd) {
                                data[d307.payloadStart].toInt() and 0xFF
                            } else 0
                            val d207 = d007?.let { findChild(it.children, "D207") }
                            val layerId = if (d207 != null && d207.payloadStart < d207.payloadEnd) {
                                parseVarInt(data, d207.payloadStart, d207.payloadEnd - d207.payloadStart)
                            } else null
                            edges[eid] = SkpEdgeRaw(eid, v1, v2, flags, layerId)
                        }
                    }
                }
                "AC0D" -> {
                    val fid = extractEntityId(data, el)
                    if (fid != null) {
                        var normal = Vec3.UNIT_Z
                        val ad0d = findTagRecursive(el.children, "AD0D")
                        if (ad0d != null && ad0d.payloadEnd - ad0d.payloadStart >= 24) {
                            val bb = ByteBuffer.wrap(data, ad0d.payloadStart, 24).order(ByteOrder.LITTLE_ENDIAN)
                            normal = Vec3(bb.double, bb.double, bb.double)
                        }
                        val loops = mutableListOf<List<Long>>()
                        val ae0d = findTagRecursive(el.children, "AE0D")
                        if (ae0d != null) {
                            val loopNodes = mutableListOf<TlvNode>()
                            collectTagRecursive(ae0d.children, "9411", loopNodes)
                            for (ln in loopNodes) {
                                val coNodes = mutableListOf<TlvNode>()
                                collectTagRecursive(ln.children, "A00F", coNodes)
                                val edgeIds = mutableListOf<Long>()
                                for (cn in coNodes) {
                                    var pos = cn.payloadStart
                                    while (pos + 6 <= cn.payloadEnd) {
                                        val st = hexTag(data, pos)
                                        val sz = readU32(data, pos + 2).toInt()
                                        if (sz < 0 || pos + 6 + sz > cn.payloadEnd) break
                                        if (st == "A10F") {
                                            edgeIds.add(parseVarInt(data, pos + 6, sz))
                                        }
                                        pos += 6 + sz
                                    }
                                }
                                if (edgeIds.size >= 3) loops.add(edgeIds)
                            }
                        }
                        val d007 = findChild(el.children, "D007")
                        val matId = d007?.let { findChild(it.children, "D107") }?.let {
                            parseVarInt(data, it.payloadStart, it.payloadEnd - it.payloadStart)
                        }
                        val layerId = d007?.let { findChild(it.children, "D207") }?.let {
                            parseVarInt(data, it.payloadStart, it.payloadEnd - it.payloadStart)
                        }
                        val hidden = d007?.let { findChild(it.children, "D307") }?.let {
                            it.payloadStart < it.payloadEnd && (data[it.payloadStart].toInt() and 0x01) != 0
                        } ?: false
                        val backMatId = findChild(el.children, "AF0D")?.let {
                            parseVarInt(data, it.payloadStart, it.payloadEnd - it.payloadStart)
                        }
                        faces.add(SkpFaceRaw(fid, normal, loops, matId, backMatId, layerId, hidden))
                    }
                }
                "6419" -> {
                    val searchList = el.children.ifEmpty { listOf(el) }
                    val defIdx = findTagRecursive(searchList, "6719")?.let {
                        parseVarInt(data, it.payloadStart, it.payloadEnd - it.payloadStart)
                    }
                    val name = findTagRecursive(searchList, "6519")?.let { decodeUtf8(data, it) } ?: ""
                    val matNode = findTagRecursive(searchList, "6619")
                    val mat13 = if (matNode != null && matNode.payloadEnd - matNode.payloadStart >= 104) {
                        val bb = ByteBuffer.wrap(data, matNode.payloadStart, 104).order(ByteOrder.LITTLE_ENDIAN)
                        DoubleArray(13) { bb.double }
                    } else null
                    val d007 = findChild(el.children, "D007")
                    val matId = d007?.let { findChild(it.children, "D107") }?.let {
                        parseVarInt(data, it.payloadStart, it.payloadEnd - it.payloadStart)
                    }
                    val layerId = d007?.let { findChild(it.children, "D207") }?.let {
                        parseVarInt(data, it.payloadStart, it.payloadEnd - it.payloadStart)
                    }
                    val hidden = d007?.let { findChild(it.children, "D307") }?.let {
                        it.payloadStart < it.payloadEnd && (data[it.payloadStart].toInt() and 0x01) != 0
                    } ?: false

                    val childInsts = mutableListOf<SkpInstanceRaw>()
                    for (c in el.children) {
                        if (c.tag == "6419") {
                            extractGeometryTree(listOf(c), data, verts, edges, faces, childInsts)
                        }
                    }
                    instances.add(SkpInstanceRaw(name, defIdx, mat13, matId, layerId, hidden, childInsts))
                }
                else -> {
                    if (el.children.isNotEmpty()) {
                        extractGeometryTree(el.children, data, verts, edges, faces, instances)
                    }
                }
            }
        }
    }

    private fun parseTopLevelTlvs(data: ByteArray): List<TlvNode> {
        val raw = parseTlvRange(data, 0, data.size, 0)
        // Unwrap lone F401 container if present
        if (raw.size == 1 && raw[0].tag == "F401") {
            return raw[0].children
        }
        return raw
    }

    private fun parseTlvRange(data: ByteArray, start: Int, end: Int, depth: Int): List<TlvNode> {
        if (depth > MAX_TLV_DEPTH) return emptyList()
        val out = mutableListOf<TlvNode>()
        var pos = start
        while (pos + 6 <= end) {
            val tag = hexTag(data, pos)
            val sizeLong = readU32(data, pos + 2)
            val pStart = pos + 6
            val pEnd = (pStart + sizeLong).coerceAtMost(end.toLong()).toInt()
            if (sizeLong < 0 || pEnd < pStart || pStart + sizeLong > end.toLong()) {
                break
            }
            val children = if (tag in CONTAINER_TAGS) {
                parseTlvRange(data, pStart, pEnd, depth + 1)
            } else {
                emptyList()
            }
            out.add(TlvNode(tag, pStart, pEnd, children))
            pos = pEnd
        }
        return out
    }

    private fun parseMaterialsXml(
        materialXmls: Map<String, ByteArray>,
        rawTextures: Map<String, ByteArray>,
        materialsByName: MutableMap<String, CadMaterial>,
        texturesOut: MutableMap<String, Bitmap>
    ) {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        for ((path, xmlBytes) in materialXmls) {
            try {
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xmlBytes.toString(Charsets.UTF_8)))
                var matName = ""
                var r = 0.85f
                var g = 0.85f
                var b = 0.85f
                var alpha = 1f
                var texName: String? = null
                var xScale = 1.0
                var yScale = 1.0

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name.substringAfterLast(':')) {
                            "material" -> {
                                matName = parser.getAttributeValue(null, "name") ?: ""
                                val colorAttr = parser.getAttributeValue(null, "color")
                                if (colorAttr != null) {
                                    // SketchUp stores ABGR int or RGB depending on version
                                    colorAttr.toLongOrNull()?.let { v ->
                                        r = ((v and 0xFF) / 255f)
                                        g = (((v shr 8) and 0xFF) / 255f)
                                        b = (((v shr 16) and 0xFF) / 255f)
                                    }
                                }
                                parser.getAttributeValue(null, "colorRed")?.toFloatOrNull()?.let { r = it / 255f }
                                parser.getAttributeValue(null, "colorGreen")?.toFloatOrNull()?.let { g = it / 255f }
                                parser.getAttributeValue(null, "colorBlue")?.toFloatOrNull()?.let { b = it / 255f }
                                if (parser.getAttributeValue(null, "useTrans") == "1") {
                                    val tr = parser.getAttributeValue(null, "trans")?.toFloatOrNull() ?: 0f
                                    alpha = (1f - tr).coerceIn(0.05f, 1f)
                                }
                            }
                            "texture" -> {
                                texName = parser.getAttributeValue(null, "path")?.substringAfterLast('/')?.substringAfterLast('\\')
                                parser.getAttributeValue(null, "xScale")?.toDoubleOrNull()?.let { if (it > 0) xScale = it * INCH_TO_METERS }
                                parser.getAttributeValue(null, "yScale")?.toDoubleOrNull()?.let { if (it > 0) yScale = it * INCH_TO_METERS }
                            }
                        }
                    }
                    event = parser.next()
                }
                if (matName.isNotEmpty()) {
                    var resolvedTexKey: String? = null
                    val folderPrefix = path.substringBeforeLast('/') + "/"
                    val candidateEntry = texName?.let { tn ->
                        rawTextures.keys.firstOrNull { it.startsWith(folderPrefix) && it.endsWith(tn, ignoreCase = true) }
                            ?: rawTextures.keys.firstOrNull { it.startsWith(folderPrefix) }
                    }
                    if (candidateEntry != null) {
                        val imgBytes = rawTextures[candidateEntry]
                        if (imgBytes != null) {
                            CadGeometryHelper.decodeSampledBitmap(imgBytes, 1024)?.let { bmp ->
                                texturesOut[candidateEntry] = bmp
                                resolvedTexKey = candidateEntry
                            }
                        }
                    }
                    materialsByName[matName] = CadMaterial(
                        id = matName,
                        name = matName,
                        color = CadColor(r, g, b, alpha),
                        opacity = alpha,
                        textureKey = resolvedTexKey,
                        tileWidthMeters = xScale,
                        tileHeightMeters = yScale
                    )
                }
            } catch (_: Exception) {
                // Skip malformed material xml gracefully
            }
        }
    }

    private fun extractEntityId(data: ByteArray, node: TlvNode): Long? {
        for (c in node.children) {
            if (c.tag == "DE05") return parseVarInt(data, c.payloadStart, c.payloadEnd - c.payloadStart)
            if (c.tag == "DC05" && c.payloadEnd - c.payloadStart >= 6) {
                if (data[c.payloadStart] == 0xDE.toByte() && data[c.payloadStart + 1] == 0x05.toByte()) {
                    val len = readU32(data, c.payloadStart + 2).toInt()
                    if (c.payloadStart + 6 + len <= c.payloadEnd) {
                        return parseVarInt(data, c.payloadStart + 6, len)
                    }
                }
            }
        }
        for (c in node.children) {
            extractEntityId(data, c)?.let { return it }
        }
        return null
    }

    private fun extractEntityKey(data: ByteArray, dc05: TlvNode): Long {
        val len = dc05.payloadEnd - dc05.payloadStart
        if (len >= 6 && data[dc05.payloadStart] == 0xDE.toByte() && data[dc05.payloadStart + 1] == 0x05.toByte()) {
            val innerLen = readU32(data, dc05.payloadStart + 2).toInt()
            if (dc05.payloadStart + 6 + innerLen <= dc05.payloadEnd) {
                return parseVarInt(data, dc05.payloadStart + 6, innerLen)
            }
        }
        return parseVarInt(data, dc05.payloadStart, len)
    }

    private fun findChild(nodes: List<TlvNode>, tag: String): TlvNode? =
        nodes.firstOrNull { it.tag == tag }

    private fun findTagRecursive(nodes: List<TlvNode>, tag: String): TlvNode? {
        for (n in nodes) {
            if (n.tag == tag) return n
            findTagRecursive(n.children, tag)?.let { return it }
        }
        return null
    }

    private fun collectTagRecursive(nodes: List<TlvNode>, tag: String, out: MutableList<TlvNode>) {
        for (n in nodes) {
            if (n.tag == tag) out.add(n)
            if (n.children.isNotEmpty()) collectTagRecursive(n.children, tag, out)
        }
    }

    private fun decodeUtf8(data: ByteArray, node: TlvNode): String =
        String(data, node.payloadStart, (node.payloadEnd - node.payloadStart).coerceAtLeast(0), Charsets.UTF_8)

    private fun hexTag(data: ByteArray, pos: Int): String {
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        return HEX_CHARS[b0 ushr 4].toString() + HEX_CHARS[b0 and 0xF] +
            HEX_CHARS[b1 ushr 4] + HEX_CHARS[b1 and 0xF]
    }

    private fun readU32(data: ByteArray, pos: Int): Long {
        return ((data[pos].toLong() and 0xFF)) or
            ((data[pos + 1].toLong() and 0xFF) shl 8) or
            ((data[pos + 2].toLong() and 0xFF) shl 16) or
            ((data[pos + 3].toLong() and 0xFF) shl 24)
    }

    private fun parseVarInt(data: ByteArray, start: Int, len: Int): Long {
        var v = 0L
        val safeLen = len.coerceIn(0, 8)
        for (i in 0 until safeLen) {
            if (start + i >= data.size) break
            v = v or ((data[start + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun findZipHeader(data: ByteArray): Int {
        for (i in 0..(data.size - 4)) {
            if (data[i] == 0x50.toByte() && data[i + 1] == 0x4B.toByte() &&
                data[i + 2] == 0x03.toByte() && data[i + 3] == 0x04.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun containsAscii(data: ByteArray, start: Int, end: Int, needle: String): Boolean {
        val target = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in start..(end - target.size)) {
            for (j in target.indices) {
                if (data[i + j] != target[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun readVffVersion(data: ByteArray): String {
        for (i in 4..(minOf(data.size, 256) - 3)) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xFE.toByte() && data[i + 2] == 0xFF.toByte()) {
                val start = i + 4
                val end = minOf(data.size, start + 160)
                if (start < end) {
                    val txt = String(data, start, end - start, Charsets.UTF_16LE)
                    val b0 = txt.indexOf('{')
                    val b1 = txt.indexOf('}')
                    if (b0 >= 0 && b1 > b0) return txt.substring(b0, b1 + 1)
                }
            }
        }
        return "2021+"
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp")
    }

    companion object {
        private const val INCH_TO_METERS = 0.0254
        private const val MAX_TLV_DEPTH = 64
        private const val HEX_CHARS = "0123456789ABCDEF"
        private val CONTAINER_TAGS = setOf(
            "F401", "F701", "D430", "D530", "C832",
            "7C15", "8813", "8913", "8A13", "8B13", "8C13", "8D13", "4C1D", "6419",
            "F901", "7017", "7117", "D007", "C409", "9411", "9511", "0F01",
            "384A", "B80B", "9713", "2C4C", "AC0D", "AE0D", "F601", "F801",
            "983A", "993A", "8C3C", "8D3C", "9013", "401F"
        )
    }
}
