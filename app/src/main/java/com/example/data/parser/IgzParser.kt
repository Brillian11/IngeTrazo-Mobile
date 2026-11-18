package com.example.data.parser

import android.graphics.Bitmap
import android.util.JsonReader
import android.util.JsonToken
import com.example.domain.math.EarcutTriangulator
import com.example.domain.math.Mat4
import com.example.domain.math.Vec3
import com.example.domain.model.CadColor
import com.example.domain.model.CadDimensionAnnotation
import com.example.domain.model.CadDocument
import com.example.domain.model.CadEdge
import com.example.domain.model.CadFace
import com.example.domain.model.CadLayer
import com.example.domain.model.CadMaterial
import com.example.domain.model.CadNode
import com.example.domain.model.CadSavedView
import com.example.domain.model.CadSectionPlane
import com.example.domain.model.CadTextLabel
import com.example.domain.model.ModelFormat
import com.example.domain.model.UnitSystem
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Memory-efficient streaming parser for IngeTrazo (.igz) documents across all three format versions:
 * - Format 1: Plain UTF-8 JSON
 * - Format 2: ZIP container with document.json + textures/
 * - Format 3: ZIP container with document.json + textures/ + binary geom/ blocks
 *
 * Uses [android.util.JsonReader] instead of DOM [org.json.JSONObject] so multi-dozen-MB
 * document.json payloads stream cleanly within mobile heap limits.
 */
class IgzParser : DocumentParser {
    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.IGZ)

    private data class RawTexSpec(
        val embed: String? = null,
        val sw: Double? = null,
        val sh: Double? = null,
        val rot: Double = 0.0,
        val uvw: DoubleArray? = null
    )

    private data class RawMaterialOverride(
        val matName: String? = null,
        val colorFloats: DoubleArray? = null,
        val opacity: Float? = null,
        val texture: RawTexSpec? = null
    )

    private data class RawEdge(
        val a: Vec3,
        val b: Vec3,
        val layer: String?,
        val soft: Boolean,
        val hidden: Boolean
    )

    private data class RawFace(
        val vertices: List<Vec3>,
        val holes: List<List<Vec3>>,
        val layer: String?,
        val matName: String?,
        val colorFloats: DoubleArray?,
        val opacity: Float?,
        val texture: RawTexSpec?,
        val backMirror: Boolean,
        val backColorFloats: DoubleArray?,
        val hidden: Boolean
    )

    private data class BinaryArraySpec(
        val count: Int,
        val offset: Int
    )

    private data class RawFaceAttr(
        val layer: String? = null,
        val matName: String? = null,
        val colorFloats: DoubleArray? = null,
        val opacity: Float? = null,
        val texture: RawTexSpec? = null,
        val hidden: Boolean = false
    )

    private data class RawGeomMeta(
        val member: String,
        val arrays: Map<String, BinaryArraySpec>,
        val edgeLayers: List<String>,
        val faceAttrs: List<RawFaceAttr>
    )

    private class RawBlock {
        var name: String = ""
        var layer: String = ""
        var component: Boolean? = null
        var billboard: Boolean = false
        var protoIndex: Int? = null
        var hidden: Boolean = false
        var xform: Mat4? = null
        var material: RawMaterialOverride? = null
        var geom: RawGeomMeta? = null
        val edges: MutableList<RawEdge> = mutableListOf()
        val faces: MutableList<RawFace> = mutableListOf()
        val subGroups: MutableList<RawBlock> = mutableListOf()
    }

    private data class RawLayerEntry(
        val name: String,
        val visible: Boolean,
        val folder: String?,
        val colorFloats: DoubleArray?
    )

    private data class RawMaterialDef(
        val name: String,
        val colorFloats: DoubleArray?,
        val opacity: Float,
        val texture: RawTexSpec?
    )

    private class RawSceneData {
        var igzFormat: Int = 1
        var appVersion: String? = null
        var hasScene: Boolean = false
        var unitCode: String? = null
        var decimals: Int = 2
        var frontColorFloats: DoubleArray? = null
        var backColorFloats: DoubleArray? = null
        val rawMaterials = mutableListOf<RawMaterialDef>()
        val folderParents = HashMap<String, String>()
        val rawLayers = mutableListOf<RawLayerEntry>()
        val protos = mutableListOf<RawBlock>()
        val rootBlock = RawBlock()
        val savedViews = mutableListOf<CadSavedView>()
        val dimensions = mutableListOf<CadDimensionAnnotation>()
        val textLabels = mutableListOf<CadTextLabel>()
        val sectionPlanes = mutableListOf<CadSectionPlane>()
    }

    override fun parse(
        bytes: ByteArray,
        documentKey: String,
        title: String,
        formatHint: ModelFormat?
    ): CadParseResult {
        return try {
            val isZip = bytes.size >= 4 &&
                bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

            var rawScene: RawSceneData? = null
            val textures = HashMap<String, Bitmap>()
            val geomBlobs = HashMap<String, ByteArray>()

            if (isZip) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name
                            when {
                                name == "document.json" -> {
                                    // Stream directly from ZipInputStream entry without allocating giant String/DOM
                                    val reader = JsonReader(BufferedReader(InputStreamReader(zis, Charsets.UTF_8), 32768))
                                    reader.isLenient = true
                                    rawScene = parseRootJson(reader)
                                }
                                name.startsWith("textures/") -> {
                                    val entryBytes = zis.readBytes()
                                    CadGeometryHelper.decodeSampledBitmap(entryBytes, 1024)?.let { bmp ->
                                        textures[name] = bmp
                                    }
                                }
                                name.startsWith("geom/") -> {
                                    geomBlobs[name] = zis.readBytes()
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                JsonReader(BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8), 32768)).use { reader ->
                    reader.isLenient = true
                    rawScene = parseRootJson(reader)
                }
            }

            val scene = rawScene
                ?: return CadParseResult.Failure("Invalid .igz container", "Missing or malformed document.json in .igz file.")

            if (scene.igzFormat > 3) {
                return CadParseResult.Failure(
                    "Newer .igz format (v${scene.igzFormat})",
                    "This build supports IngeTrazo .igz formats v1–v3."
                )
            }
            if (!scene.hasScene) {
                return CadParseResult.Failure("Invalid .igz document", "Missing root 'scene' object.")
            }

            val warnings = mutableListOf<String>()
            val sourceUnits = UnitSystem.fromCode(scene.unitCode)
            val sourceDecimals = scene.decimals
            val defaultFront = parseColorFloats(scene.frontColorFloats, CadColor.DEFAULT_FRONT)
            val defaultBack = parseColorFloats(scene.backColorFloats, CadColor.DEFAULT_BACK)

            // Build materials registry (works regardless of key ordering in JSON)
            val materials = HashMap<String, CadMaterial>(scene.rawMaterials.size)
            for ((i, m) in scene.rawMaterials.withIndex()) {
                val mName = m.name.ifEmpty { "Material $i" }
                val col = parseColorFloats(m.colorFloats, defaultFront)
                materials[mName] = CadMaterial(
                    id = mName,
                    name = mName,
                    color = col,
                    opacity = m.opacity,
                    textureKey = m.texture?.embed?.takeIf { it.isNotEmpty() },
                    tileWidthMeters = m.texture?.sw ?: 1.0,
                    tileHeightMeters = m.texture?.sh ?: 1.0
                )
            }

            // Build layers
            val layers = mutableListOf<CadLayer>()
            val layerNamesSeen = HashSet<String>()
            for (l in scene.rawLayers) {
                val folder = l.folder?.takeIf { it.isNotEmpty() } ?: scene.folderParents[l.name]
                val col = l.colorFloats?.let { parseColorFloats(it, defaultFront) }
                if (layerNamesSeen.add(l.name)) {
                    layers.add(CadLayer(name = l.name, folder = folder, isVisible = l.visible, color = col))
                }
            }

            val allFaces = mutableListOf<CadFace>()
            val allEdges = mutableListOf<CadEdge>()
            val allNodes = mutableListOf<CadNode>()
            var idSeq = 0

            fun nextId(prefix: String) = "${prefix}_${idSeq++}"

            fun ensureLayer(name: String) {
                if (layerNamesSeen.add(name)) {
                    layers.add(CadLayer(name = name, isVisible = true))
                }
            }

            fun extractMeshBlock(
                block: RawBlock,
                nodeId: String,
                defaultLayer: String,
                worldTransform: Mat4,
                inheritedMaterial: RawMaterialOverride?
            ): Pair<Int, Int> {
                var addedFaces = 0
                var addedEdges = 0

                val geomMeta = block.geom
                if (geomMeta != null) {
                    val blob = geomBlobs[geomMeta.member]
                    if (blob == null) {
                        warnings.add("Missing binary geometry block: ${geomMeta.member}")
                        return 0 to 0
                    }
                    return parseBinaryGeometryBlock(
                        meta = geomMeta,
                        blob = blob,
                        nodeId = nodeId,
                        defaultLayer = defaultLayer,
                        worldTransform = worldTransform,
                        defaultFront = defaultFront,
                        defaultBack = defaultBack,
                        materials = materials,
                        nextId = ::nextId,
                        outFaces = allFaces,
                        outEdges = allEdges
                    )
                }

                val flipWinding = worldTransform.determinant3x3() < 0.0

                for (e in block.edges) {
                    val eLayer = e.layer?.takeIf { it.isNotEmpty() } ?: defaultLayer
                    ensureLayer(eLayer)
                    allEdges.add(
                        CadEdge(
                            id = nextId("e"),
                            nodeId = nodeId,
                            layer = eLayer,
                            a = worldTransform.transformPoint(e.a),
                            b = worldTransform.transformPoint(e.b),
                            soft = e.soft,
                            hidden = e.hidden
                        )
                    )
                    addedEdges++
                }

                for (f in block.faces) {
                    if (f.vertices.size < 3) continue
                    val transformedOuter = ArrayList<Vec3>(f.vertices.size)
                    for (v in f.vertices) {
                        transformedOuter.add(worldTransform.transformPoint(v))
                    }
                    val outer = if (flipWinding) transformedOuter.reversed() else transformedOuter

                    val holesList = if (f.holes.isEmpty()) {
                        emptyList()
                    } else {
                        f.holes.mapNotNull { ring ->
                            if (ring.size < 3) null else {
                                val pts = ring.map { worldTransform.transformPoint(it) }
                                if (flipWinding) pts.reversed() else pts
                            }
                        }
                    }

                    val faceLayer = f.layer?.takeIf { it.isNotEmpty() } ?: defaultLayer
                    ensureLayer(faceLayer)

                    val matName = f.matName?.takeIf { it.isNotEmpty() } ?: inheritedMaterial?.matName
                    val matObj = matName?.let { materials[it] }
                    val colFloats = f.colorFloats ?: inheritedMaterial?.colorFloats
                    var faceColor = when {
                        colFloats != null -> parseColorFloats(colFloats, defaultFront)
                        matObj != null -> matObj.color
                        else -> defaultFront
                    }
                    val op = f.opacity ?: inheritedMaterial?.opacity
                    if (op != null) {
                        faceColor = faceColor.copy(a = op.coerceIn(0.05f, 1f))
                    } else if (matObj != null && matObj.opacity < 1f) {
                        faceColor = faceColor.copy(a = matObj.opacity)
                    }

                    val texSpec = f.texture ?: inheritedMaterial?.texture
                    val texKey = texSpec?.embed?.takeIf { it.isNotEmpty() } ?: matObj?.textureKey
                    val sw = texSpec?.sw ?: matObj?.tileWidthMeters ?: 1.0
                    val sh = texSpec?.sh ?: matObj?.tileHeightMeters ?: 1.0
                    val rot = texSpec?.rot ?: 0.0

                    val backColor = when {
                        f.backMirror -> faceColor
                        f.backColorFloats != null -> parseColorFloats(f.backColorFloats, defaultBack)
                        else -> defaultBack
                    }

                    val normal = EarcutTriangulator.computePolygonNormal(outer)
                    val tris = EarcutTriangulator.triangulate(outer, holesList, normal)
                    if (tris.isEmpty()) continue
                    val uvs = CadGeometryHelper.computePlanarUvs(
                        points = tris,
                        normal = normal,
                        scaleW = sw,
                        scaleH = sh,
                        rotDeg = rot,
                        uvwAffine = texSpec?.uvw
                    )

                    allFaces.add(
                        CadFace(
                            id = nextId("f"),
                            nodeId = nodeId,
                            layer = faceLayer,
                            outerLoop = outer,
                            holes = holesList,
                            normal = normal,
                            triangleVertices = tris,
                            triangleUvs = uvs,
                            color = faceColor,
                            backColor = backColor,
                            materialKey = matName,
                            textureKey = texKey,
                            hidden = f.hidden
                        )
                    )
                    addedFaces++
                }
                return addedFaces to addedEdges
            }

            fun quantizeVertexKey(v: Vec3): String {
                val qx = kotlin.math.round(v.x * 2000.0).toLong()
                val qy = kotlin.math.round(v.y * 2000.0).toLong()
                val qz = kotlin.math.round(v.z * 2000.0).toLong()
                return "${qx}_${qy}_${qz}"
            }

            fun formatMaterialComponentLabel(faces: List<CadFace>, fallbackIndex: Int): String {
                val rawMat = faces.mapNotNull { it.materialKey?.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("imagen", ignoreCase = true) }
                if (rawMat != null) {
                    val pretty = rawMat.replace('_', ' ').replace('-', ' ').trim()
                        .split(Regex("\\s+"))
                        .joinToString(" ") { word ->
                            word.lowercase().replaceFirstChar { c -> c.titlecase() }
                        }
                    if (pretty.isNotEmpty()) return "$pretty Component"
                }
                return "Component $fallbackIndex"
            }

            var componentAutoCounter = 0

            fun parseGroupTree(
                gBlock: RawBlock,
                parentId: String?,
                parentTransform: Mat4,
                inheritedLayer: String,
                inheritedMaterial: RawMaterialOverride?,
                indexInParent: Int
            ): String {
                val nodeId = nextId("node")
                val groupLayer = gBlock.layer.ifEmpty { inheritedLayer }
                ensureLayer(groupLayer)

                val pIdx = gBlock.protoIndex
                val explicitComponent = gBlock.component ?: (pIdx != null)
                val groupMat = gBlock.material ?: inheritedMaterial

                val localXform = gBlock.xform ?: Mat4.IDENTITY
                val worldXform = parentTransform * localXform

                val meshBlock = if (pIdx != null) {
                    scene.protos.getOrNull(pIdx)
                } else {
                    gBlock
                }

                val startFaceIdx = allFaces.size
                val startEdgeIdx = allEdges.size
                var fCount = 0
                var eCount = 0
                if (meshBlock != null) {
                    val (af, ae) = extractMeshBlock(meshBlock, nodeId, groupLayer, worldXform, groupMat)
                    fCount += af
                    eCount += ae
                }

                val childIds = mutableListOf<String>()
                val subGroups = when {
                    gBlock.subGroups.isNotEmpty() -> gBlock.subGroups
                    meshBlock != null && meshBlock !== gBlock -> meshBlock.subGroups
                    else -> emptyList()
                }
                for (i in subGroups.indices) {
                    childIds.add(parseGroupTree(subGroups[i], nodeId, worldXform, groupLayer, groupMat, i))
                }

                // Partition faces inside non-billboard groups into connected 3D solid components
                var isComponentNode = explicitComponent && !gBlock.billboard
                if (!gBlock.billboard && fCount >= 2 && !explicitComponent && gBlock.component != false) {
                    val faceCountInBlock = allFaces.size - startFaceIdx
                    val parentArr = IntArray(faceCountInBlock) { it }
                    fun findRoot(x: Int): Int {
                        var r = x
                        while (parentArr[r] != r) {
                            parentArr[r] = parentArr[parentArr[r]]
                            r = parentArr[r]
                        }
                        return r
                    }
                    val vertexOwner = HashMap<String, Int>(faceCountInBlock * 4)
                    val faceVertKeys = Array(faceCountInBlock) { relIdx ->
                        val f = allFaces[startFaceIdx + relIdx]
                        val keys = ArrayList<String>(f.outerLoop.size)
                        for (pt in f.outerLoop) {
                            val k = quantizeVertexKey(pt)
                            keys.add(k)
                            val prev = vertexOwner.putIfAbsent(k, relIdx)
                            if (prev != null && prev != relIdx) {
                                val ra = findRoot(relIdx)
                                val rb = findRoot(prev)
                                if (ra != rb) parentArr[ra] = rb
                            }
                        }
                        keys
                    }
                    val solidGroups = LinkedHashMap<Int, MutableList<Int>>()
                    for (relIdx in 0 until faceCountInBlock) {
                        solidGroups.getOrPut(findRoot(relIdx)) { mutableListOf() }.add(relIdx)
                    }
                    val validSolids = solidGroups.values.filter { it.size >= 2 }

                    if (validSolids.size > 1) {
                        // Multi-component group (e.g. bench assembly or pergola roof rafters):
                        // keep parent node as Group (isComponent = false) and emit each solid as a Component child
                        val vertexToSolidIdx = HashMap<String, Int>(vertexOwner.size)
                        validSolids.forEachIndexed { sIdx, relIndices ->
                            for (relIdx in relIndices) {
                                for (k in faceVertKeys[relIdx]) {
                                    vertexToSolidIdx[k] = sIdx
                                }
                            }
                        }
                        val compNodeIds = Array(validSolids.size) { nextId("comp") }
                        val compEdgeCounts = IntArray(validSolids.size)
                        for (eIdx in startEdgeIdx until allEdges.size) {
                            val edge = allEdges[eIdx]
                            val sIdx = vertexToSolidIdx[quantizeVertexKey(edge.a)]
                                ?: vertexToSolidIdx[quantizeVertexKey(edge.b)]
                            if (sIdx != null) {
                                allEdges[eIdx] = edge.copy(nodeId = compNodeIds[sIdx])
                                compEdgeCounts[sIdx]++
                            }
                        }
                        validSolids.forEachIndexed { sIdx, relIndices ->
                            val cId = compNodeIds[sIdx]
                            val solidFaces = ArrayList<CadFace>(relIndices.size)
                            for (relIdx in relIndices) {
                                val absIdx = startFaceIdx + relIdx
                                val updated = allFaces[absIdx].copy(nodeId = cId)
                                allFaces[absIdx] = updated
                                solidFaces.add(updated)
                            }
                            componentAutoCounter++
                            val compName = if (gBlock.name.isNotEmpty()) {
                                "${gBlock.name} - Component ${sIdx + 1}"
                            } else {
                                formatMaterialComponentLabel(solidFaces, componentAutoCounter)
                            }
                            childIds.add(cId)
                            allNodes.add(
                                CadNode(
                                    id = cId,
                                    name = compName,
                                    parentId = nodeId,
                                    layer = groupLayer,
                                    isComponent = true,
                                    isVisible = !gBlock.hidden,
                                    localTransform = Mat4.IDENTITY,
                                    faceCount = solidFaces.size,
                                    edgeCount = compEdgeCounts[sIdx],
                                    childrenIds = emptyList()
                                )
                            )
                        }
                        isComponentNode = false
                        fCount = 0
                        eCount = 0
                    } else if (validSolids.size == 1 && childIds.isEmpty()) {
                        isComponentNode = true
                    }
                }

                val name = if (gBlock.name.isNotEmpty()) {
                    gBlock.name
                } else if (gBlock.billboard) {
                    "Reference Figure ${indexInParent + 1}"
                } else if (isComponentNode) {
                    componentAutoCounter++
                    formatMaterialComponentLabel(
                        allFaces.subList(startFaceIdx, allFaces.size),
                        componentAutoCounter
                    )
                } else {
                    "Group ${indexInParent + 1}"
                }

                allNodes.add(
                    CadNode(
                        id = nodeId,
                        name = name,
                        parentId = parentId,
                        layer = groupLayer,
                        isComponent = isComponentNode,
                        isVisible = !gBlock.hidden,
                        localTransform = localXform,
                        faceCount = fCount,
                        edgeCount = eCount,
                        childrenIds = childIds
                    )
                )
                return nodeId
            }

            val rootNodeId = "root_model"
            if (layerNamesSeen.add("Layer 0")) {
                layers.add(0, CadLayer(name = "Layer 0", isVisible = true))
            }
            val (rootFaces, rootEdges) = extractMeshBlock(scene.rootBlock, rootNodeId, "Layer 0", Mat4.IDENTITY, null)
            val rootChildren = mutableListOf<String>()
            for (i in scene.rootBlock.subGroups.indices) {
                rootChildren.add(
                    parseGroupTree(scene.rootBlock.subGroups[i], rootNodeId, Mat4.IDENTITY, "Layer 0", null, i)
                )
            }
            if (rootFaces > 0 || rootEdges > 0 || allNodes.isEmpty()) {
                allNodes.add(
                    0,
                    CadNode(
                        id = rootNodeId,
                        name = "Model Root",
                        parentId = null,
                        layer = "Layer 0",
                        isComponent = false,
                        isVisible = true,
                        faceCount = rootFaces,
                        edgeCount = rootEdges,
                        childrenIds = rootChildren
                    )
                )
            }

            // Release raw intermediate state before stats computation
            geomBlobs.clear()
            rawScene = null

            val (bounds, stats) = CadGeometryHelper.computeStatsAndBounds(
                nodeCount = allNodes.size,
                faces = allFaces,
                edges = allEdges,
                layerCount = layers.size,
                savedViewCount = scene.savedViews.size,
                textureCount = textures.size
            )

            CadParseResult.Success(
                CadDocument(
                    documentKey = documentKey,
                    title = title,
                    format = ModelFormat.IGZ,
                    formatVersion = "IGZ v${scene.igzFormat}",
                    appVersion = scene.appVersion,
                    sourceUnits = sourceUnits,
                    sourceDecimals = sourceDecimals,
                    nodes = allNodes,
                    faces = allFaces,
                    edges = allEdges,
                    layers = layers,
                    materials = materials,
                    textures = textures,
                    savedViews = scene.savedViews,
                    dimensions = scene.dimensions,
                    textLabels = scene.textLabels,
                    sectionPlanes = scene.sectionPlanes,
                    defaultFrontColor = defaultFront,
                    defaultBackColor = defaultBack,
                    bounds = bounds,
                    stats = stats,
                    warnings = warnings
                )
            )
        } catch (e: Exception) {
            CadParseResult.Failure(
                "Failed to parse IngeTrazo (.igz) file",
                e.localizedMessage ?: e.javaClass.simpleName
            )
        }
    }

    // =========================================================================
    // Streaming JsonReader implementation (zero JSONObject DOM allocations)
    // =========================================================================

    private fun parseRootJson(reader: JsonReader): RawSceneData? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
        val out = RawSceneData()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "igz_format" -> out.igzFormat = readIntSafe(reader, 1)
                "app_version" -> out.appVersion = readStringOrNull(reader)?.takeIf { it.isNotEmpty() }
                "scene" -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        out.hasScene = true
                        readSceneObject(reader, out)
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return out
    }

    private fun readSceneObject(reader: JsonReader, out: RawSceneData) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "dimension_style" -> readDimensionStyle(reader, out)
                "display_style" -> readDisplayStyle(reader, out)
                "back_face_color" -> out.backColorFloats = readDoubleArrayOrNull(reader)
                "materials" -> readMaterialsArray(reader, out.rawMaterials)
                "layer_folder_parents" -> readStringMap(reader, out.folderParents)
                "layers" -> readLayersArray(reader, out.rawLayers)
                "protos" -> readBlocksArray(reader, out.protos)
                "edges" -> readEdgesArray(reader, out.rootBlock.edges)
                "faces" -> readFacesArray(reader, out.rootBlock.faces)
                "groups", "children" -> readBlocksArray(reader, out.rootBlock.subGroups)
                "geom" -> out.rootBlock.geom = readGeomMetaOrNull(reader)
                "saved_views" -> readSavedViewsArray(reader, out.savedViews)
                "dimensions" -> readDimensionsArray(reader, out.dimensions)
                "text_labels" -> readTextLabelsArray(reader, out.textLabels)
                "section_planes" -> readSectionPlanesArray(reader, out.sectionPlanes)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readDimensionStyle(reader: JsonReader, out: RawSceneData) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue(); return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "units" -> out.unitCode = readStringOrNull(reader)
                "decimals" -> out.decimals = readIntSafe(reader, 2)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readDisplayStyle(reader: JsonReader, out: RawSceneData) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue(); return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "front_color" -> out.frontColorFloats = readDoubleArrayOrNull(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readMaterialsArray(reader: JsonReader, out: MutableList<RawMaterialDef>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); continue
            }
            var name = ""
            var col: DoubleArray? = null
            var opacity = 1f
            var tex: RawTexSpec? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = readStringOrNull(reader) ?: ""
                    "color" -> col = readDoubleArrayOrNull(reader)
                    "opacity" -> opacity = readDoubleSafe(reader, 1.0).toFloat().coerceIn(0.05f, 1f)
                    "texture" -> tex = readTexSpecOrNull(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            out.add(RawMaterialDef(name, col, opacity, tex))
        }
        reader.endArray()
    }

    private fun readLayersArray(reader: JsonReader, out: MutableList<RawLayerEntry>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        var idx = 0
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); idx++; continue
            }
            var name = "Layer $idx"
            var visible = true
            var folder: String? = null
            var col: DoubleArray? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = readStringOrNull(reader) ?: name
                    "visible" -> visible = readBooleanSafe(reader, true)
                    "folder" -> folder = readStringOrNull(reader)
                    "color" -> col = readDoubleArrayOrNull(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            out.add(RawLayerEntry(name, visible, folder, col))
            idx++
        }
        reader.endArray()
    }

    private fun readBlocksArray(reader: JsonReader, out: MutableList<RawBlock>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); continue
            }
            val block = RawBlock()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> block.name = readStringOrNull(reader) ?: ""
                    "layer" -> block.layer = readStringOrNull(reader) ?: ""
                    "component" -> block.component = readBooleanSafe(reader, false)
                    "billboard" -> {
                        block.billboard = when (reader.peek()) {
                            JsonToken.BOOLEAN -> reader.nextBoolean()
                            JsonToken.STRING -> reader.nextString().isNotEmpty()
                            JsonToken.NUMBER -> reader.nextInt() != 0
                            else -> { reader.skipValue(); false }
                        }
                    }
                    "proto" -> {
                        if (reader.peek() == JsonToken.NUMBER) {
                            block.protoIndex = readIntSafe(reader, -1).takeIf { it >= 0 }
                        } else {
                            reader.skipValue()
                        }
                    }
                    "hidden" -> block.hidden = readBooleanSafe(reader, false)
                    "xform" -> block.xform = readMat4OrNull(reader)
                    "material" -> block.material = readMaterialOverrideOrNull(reader)
                    "geom" -> block.geom = readGeomMetaOrNull(reader)
                    "edges" -> readEdgesArray(reader, block.edges)
                    "faces" -> readFacesArray(reader, block.faces)
                    "children", "groups" -> readBlocksArray(reader, block.subGroups)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            out.add(block)
        }
        reader.endArray()
    }

    private fun readEdgesArray(reader: JsonReader, out: MutableList<RawEdge>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); continue
            }
            var a: Vec3? = null
            var b: Vec3? = null
            var layer: String? = null
            var soft = false
            var hidden = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "a" -> a = readVec3OrNull(reader)
                    "b" -> b = readVec3OrNull(reader)
                    "layer" -> layer = readStringOrNull(reader)
                    "soft" -> soft = readBooleanSafe(reader, false)
                    "hidden" -> hidden = readBooleanSafe(reader, false)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (a != null && b != null) {
                out.add(RawEdge(a, b, layer, soft, hidden))
            }
        }
        reader.endArray()
    }

    private fun readFacesArray(reader: JsonReader, out: MutableList<RawFace>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); continue
            }
            var verts: List<Vec3>? = null
            var holes: List<List<Vec3>> = emptyList()
            var layer: String? = null
            var matName: String? = null
            var colorFloats: DoubleArray? = null
            var opacity: Float? = null
            var texSpec: RawTexSpec? = null
            var backMirror = false
            var backColorFloats: DoubleArray? = null
            var hidden = false

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "vertices" -> verts = readVec3List(reader)
                    "holes" -> holes = readHolesList(reader)
                    "layer" -> layer = readStringOrNull(reader)
                    "mat" -> matName = readStringOrNull(reader)
                    "color" -> colorFloats = readDoubleArrayOrNull(reader)
                    "opacity" -> opacity = readDoubleSafe(reader, 1.0).toFloat()
                    "texture" -> texSpec = readTexSpecOrNull(reader)
                    "back" -> {
                        when (reader.peek()) {
                            JsonToken.BOOLEAN -> backMirror = reader.nextBoolean()
                            JsonToken.BEGIN_OBJECT -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "color" -> backColorFloats = readDoubleArrayOrNull(reader)
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    "hidden" -> hidden = readBooleanSafe(reader, false)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (verts != null && verts.size >= 3) {
                out.add(
                    RawFace(
                        vertices = verts,
                        holes = holes,
                        layer = layer,
                        matName = matName,
                        colorFloats = colorFloats,
                        opacity = opacity,
                        texture = texSpec,
                        backMirror = backMirror,
                        backColorFloats = backColorFloats,
                        hidden = hidden
                    )
                )
            }
        }
        reader.endArray()
    }

    private fun readSavedViewsArray(reader: JsonReader, out: MutableList<CadSavedView>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        var idx = 0
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); idx++; continue
            }
            var name = "View ${idx + 1}"
            var target = Vec3.ZERO
            var distance = 10.0
            var yaw = -0.785398
            var pitch = 0.523599
            var parallel = false
            val hiddenSet = HashSet<String>()
            var styleName: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = readStringOrNull(reader) ?: name
                    "target" -> target = readVec3OrNull(reader) ?: Vec3.ZERO
                    "distance" -> distance = readDoubleSafe(reader, 10.0).coerceAtLeast(0.1)
                    "yaw" -> yaw = readDoubleSafe(reader, -0.785398)
                    "pitch" -> pitch = readDoubleSafe(reader, 0.523599)
                    "parallel" -> parallel = readBooleanSafe(reader, false)
                    "hidden_layers" -> {
                        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                readStringOrNull(reader)?.takeIf { it.isNotEmpty() }?.let(hiddenSet::add)
                            }
                            reader.endArray()
                        } else reader.skipValue()
                    }
                    "style" -> {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "name") {
                                    styleName = readStringOrNull(reader)?.takeIf { it.isNotEmpty() }
                                } else reader.skipValue()
                            }
                            reader.endObject()
                        } else reader.skipValue()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            out.add(CadSavedView(name, target, distance, yaw, pitch, parallel, hiddenSet, styleName))
            idx++
        }
        reader.endArray()
    }

    private fun readDimensionsArray(reader: JsonReader, out: MutableList<CadDimensionAnnotation>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); continue
            }
            var a: Vec3? = null
            var b: Vec3? = null
            var off = Vec3.ZERO
            var layer = "Layer 0"
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "a" -> a = readVec3OrNull(reader)
                    "b" -> b = readVec3OrNull(reader)
                    "offset" -> off = readVec3OrNull(reader) ?: Vec3.ZERO
                    "layer" -> layer = readStringOrNull(reader) ?: "Layer 0"
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (a != null && b != null) out.add(CadDimensionAnnotation(a, b, off, layer))
        }
        reader.endArray()
    }

    private fun readTextLabelsArray(reader: JsonReader, out: MutableList<CadTextLabel>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); continue
            }
            var anchor: Vec3? = null
            var off = Vec3.ZERO
            var text = ""
            var layer = "Layer 0"
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "anchor" -> anchor = readVec3OrNull(reader)
                    "offset" -> off = readVec3OrNull(reader) ?: Vec3.ZERO
                    "text" -> text = readStringOrNull(reader) ?: ""
                    "layer" -> layer = readStringOrNull(reader) ?: "Layer 0"
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (anchor != null) out.add(CadTextLabel(anchor, off, text, layer))
        }
        reader.endArray()
    }

    private fun readSectionPlanesArray(reader: JsonReader, out: MutableList<CadSectionPlane>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return
        }
        reader.beginArray()
        var idx = 0
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue(); idx++; continue
            }
            var name = "Section ${idx + 1}"
            var symbol = "S"
            var point: Vec3? = null
            var normal = Vec3.UNIT_Z
            var layer = "Layer 0"
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = readStringOrNull(reader) ?: name
                    "symbol" -> symbol = readStringOrNull(reader) ?: "S"
                    "point" -> point = readVec3OrNull(reader)
                    "normal" -> normal = readVec3OrNull(reader) ?: Vec3.UNIT_Z
                    "layer" -> layer = readStringOrNull(reader) ?: "Layer 0"
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (point != null) out.add(CadSectionPlane(name, symbol, point, normal, layer))
            idx++
        }
        reader.endArray()
    }

    private fun readGeomMetaOrNull(reader: JsonReader): RawGeomMeta? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue(); return null
        }
        var member = ""
        val arrays = HashMap<String, BinaryArraySpec>()
        val edgeLayers = mutableListOf<String>()
        val faceAttrs = mutableListOf<RawFaceAttr>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "member" -> member = readStringOrNull(reader) ?: ""
                "arrays" -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            readBinaryArraySpec(reader)?.let { arrays[key] = it }
                        }
                        reader.endObject()
                    } else reader.skipValue()
                }
                "edge_layers" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            edgeLayers.add(readStringOrNull(reader) ?: "Layer 0")
                        }
                        reader.endArray()
                    } else reader.skipValue()
                }
                "face_attrs" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                                reader.skipValue(); continue
                            }
                            var layer: String? = null
                            var matName: String? = null
                            var col: DoubleArray? = null
                            var op: Float? = null
                            var tex: RawTexSpec? = null
                            var hidden = false
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "layer" -> layer = readStringOrNull(reader)
                                    "mat" -> matName = readStringOrNull(reader)
                                    "color" -> col = readDoubleArrayOrNull(reader)
                                    "opacity" -> op = readDoubleSafe(reader, 1.0).toFloat()
                                    "texture" -> tex = readTexSpecOrNull(reader)
                                    "hidden" -> hidden = readBooleanSafe(reader, false)
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            faceAttrs.add(RawFaceAttr(layer, matName, col, op, tex, hidden))
                        }
                        reader.endArray()
                    } else reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return if (member.isNotEmpty()) RawGeomMeta(member, arrays, edgeLayers, faceAttrs) else null
    }

    private fun readBinaryArraySpec(reader: JsonReader): BinaryArraySpec? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return null
        }
        var count = 1
        var offset = 0
        var idx = 0
        reader.beginArray()
        while (reader.hasNext()) {
            when (idx) {
                1 -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            count *= readIntSafe(reader, 0)
                        }
                        reader.endArray()
                    } else reader.skipValue()
                }
                2 -> offset = readIntSafe(reader, 0)
                else -> reader.skipValue()
            }
            idx++
        }
        reader.endArray()
        return if (idx >= 3) BinaryArraySpec(count, offset) else null
    }

    private fun readMaterialOverrideOrNull(reader: JsonReader): RawMaterialOverride? {
        return when (reader.peek()) {
            JsonToken.STRING -> {
                val n = readStringOrNull(reader)?.takeIf { it.isNotEmpty() }
                n?.let { RawMaterialOverride(matName = it) }
            }
            JsonToken.BEGIN_OBJECT -> {
                var matName: String? = null
                var col: DoubleArray? = null
                var op: Float? = null
                var tex: RawTexSpec? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "name", "mat" -> matName = readStringOrNull(reader)
                        "color" -> col = readDoubleArrayOrNull(reader)
                        "opacity" -> op = readDoubleSafe(reader, 1.0).toFloat()
                        "texture" -> tex = readTexSpecOrNull(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                RawMaterialOverride(matName, col, op, tex)
            }
            else -> {
                reader.skipValue(); null
            }
        }
    }

    private fun readTexSpecOrNull(reader: JsonReader): RawTexSpec? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue(); return null
        }
        var embed: String? = null
        var sw: Double? = null
        var sh: Double? = null
        var rot = 0.0
        var uvw: DoubleArray? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "embed" -> embed = readStringOrNull(reader)
                "sw" -> sw = readDoubleSafe(reader, 1.0)
                "sh" -> sh = readDoubleSafe(reader, 1.0)
                "rot" -> rot = readDoubleSafe(reader, 0.0)
                "uvw" -> {
                    val arr = readDoubleArrayOrNull(reader)
                    if (arr != null && arr.size >= 8) uvw = arr
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RawTexSpec(embed, sw, sh, rot, uvw)
    }

    private fun readVec3List(reader: JsonReader): List<Vec3>? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return null
        }
        val list = ArrayList<Vec3>(4)
        reader.beginArray()
        while (reader.hasNext()) {
            readVec3OrNull(reader)?.let(list::add)
        }
        reader.endArray()
        return list
    }

    private fun readHolesList(reader: JsonReader): List<List<Vec3>> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return emptyList()
        }
        val holes = mutableListOf<List<Vec3>>()
        reader.beginArray()
        while (reader.hasNext()) {
            readVec3List(reader)?.let { ring ->
                if (ring.size >= 3) holes.add(ring)
            }
        }
        reader.endArray()
        return holes
    }

    private fun readVec3OrNull(reader: JsonReader): Vec3? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return null
        }
        var x = Double.NaN
        var y = Double.NaN
        var z = Double.NaN
        var i = 0
        reader.beginArray()
        while (reader.hasNext()) {
            when (i) {
                0 -> x = readDoubleSafe(reader, Double.NaN)
                1 -> y = readDoubleSafe(reader, Double.NaN)
                2 -> z = readDoubleSafe(reader, Double.NaN)
                else -> reader.skipValue()
            }
            i++
        }
        reader.endArray()
        if (i < 3 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        if (abs(x) > 1e12 || abs(y) > 1e12 || abs(z) > 1e12) return null
        return Vec3(x, y, z)
    }

    private fun readMat4OrNull(reader: JsonReader): Mat4? {
        val vals = readDoubleArrayOrNull(reader) ?: return null
        if (vals.size < 16) return null
        return Mat4(if (vals.size == 16) vals else vals.copyOf(16))
    }

    private fun readDoubleArrayOrNull(reader: JsonReader): DoubleArray? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue(); return null
        }
        val list = ArrayList<Double>(4)
        reader.beginArray()
        while (reader.hasNext()) {
            list.add(readDoubleSafe(reader, 0.0))
        }
        reader.endArray()
        return DoubleArray(list.size) { list[it] }
    }

    private fun readStringMap(reader: JsonReader, out: MutableMap<String, String>) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue(); return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val k = reader.nextName()
            val v = readStringOrNull(reader)
            if (v != null) out[k] = v
        }
        reader.endObject()
    }

    private fun readStringOrNull(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        else -> { reader.skipValue(); null }
    }

    private fun readIntSafe(reader: JsonReader, default: Int): Int = when (reader.peek()) {
        JsonToken.NUMBER -> reader.nextDouble().toInt()
        JsonToken.STRING -> reader.nextString().toDoubleOrNull()?.toInt() ?: default
        else -> { reader.skipValue(); default }
    }

    private fun readDoubleSafe(reader: JsonReader, default: Double): Double = when (reader.peek()) {
        JsonToken.NUMBER -> reader.nextDouble()
        JsonToken.STRING -> reader.nextString().toDoubleOrNull() ?: default
        else -> { reader.skipValue(); default }
    }

    private fun readBooleanSafe(reader: JsonReader, default: Boolean): Boolean = when (reader.peek()) {
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NUMBER -> reader.nextInt() != 0
        else -> { reader.skipValue(); default }
    }

    private fun parseColorFloats(arr: DoubleArray?, defaultColor: CadColor): CadColor {
        if (arr == null || arr.size < 3) return defaultColor
        val c0 = arr[0]
        val c1 = arr[1]
        val c2 = arr[2]
        val c3 = if (arr.size >= 4) arr[3] else 1.0
        val scale = if (c0 > 1.0 || c1 > 1.0 || c2 > 1.0) 1.0 / 255.0 else 1.0
        return CadColor(
            (c0 * scale).toFloat().coerceIn(0f, 1f),
            (c1 * scale).toFloat().coerceIn(0f, 1f),
            (c2 * scale).toFloat().coerceIn(0f, 1f),
            (if (c3 > 1.0) c3 / 255.0 else c3).toFloat().coerceIn(0f, 1f)
        )
    }

    private fun parseBinaryGeometryBlock(
        meta: RawGeomMeta,
        blob: ByteArray,
        nodeId: String,
        defaultLayer: String,
        worldTransform: Mat4,
        defaultFront: CadColor,
        defaultBack: CadColor,
        materials: Map<String, CadMaterial>,
        nextId: (String) -> String,
        outFaces: MutableList<CadFace>,
        outEdges: MutableList<CadEdge>
    ): Pair<Int, Int> {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)

        fun readFloatArray(key: String): FloatArray? {
            val spec = meta.arrays[key] ?: return null
            if (spec.offset < 0 || spec.offset + spec.count * 4 > blob.size) return null
            buf.position(spec.offset)
            return FloatArray(spec.count) { buf.float }
        }

        fun readIntArray(key: String): IntArray? {
            val spec = meta.arrays[key] ?: return null
            if (spec.offset < 0 || spec.offset + spec.count * 4 > blob.size) return null
            buf.position(spec.offset)
            return IntArray(spec.count) { buf.int }
        }

        fun readByteArray(key: String): ByteArray? {
            val spec = meta.arrays[key] ?: return null
            if (spec.offset < 0 || spec.offset + spec.count > blob.size) return null
            return blob.copyOfRange(spec.offset, spec.offset + spec.count)
        }

        val posRaw = readFloatArray("pos") ?: return 0 to 0
        val numVerts = posRaw.size / 3
        val verts = Array(numVerts) { i ->
            worldTransform.transformPoint(
                Vec3(
                    posRaw[i * 3].toDouble(),
                    posRaw[i * 3 + 1].toDouble(),
                    posRaw[i * 3 + 2].toDouble()
                )
            )
        }

        var addedEdges = 0
        val edgePairs = readIntArray("edges")
        val edgeFlags = readByteArray("edge_flags")
        val edgeLayerIdx = readIntArray("edge_layer")

        if (edgePairs != null) {
            val ne = edgePairs.size / 2
            for (i in 0 until ne) {
                val i0 = edgePairs[i * 2]
                val i1 = edgePairs[i * 2 + 1]
                if (i0 !in 0 until numVerts || i1 !in 0 until numVerts) continue
                val flags = edgeFlags?.getOrNull(i)?.toInt() ?: 0
                val lIdx = edgeLayerIdx?.getOrNull(i) ?: -1
                val eLayer = if (lIdx in meta.edgeLayers.indices) meta.edgeLayers[lIdx] else defaultLayer
                outEdges.add(
                    CadEdge(
                        id = nextId("e"),
                        nodeId = nodeId,
                        layer = eLayer,
                        a = verts[i0],
                        b = verts[i1],
                        soft = (flags and 1) != 0,
                        hidden = (flags and 2) != 0
                    )
                )
                addedEdges++
            }
        }

        var addedFaces = 0
        val rings = readIntArray("rings")
        val ringSizes = readIntArray("ring_sizes")
        val ringCounts = readIntArray("ring_counts")
        val faceAttrIdx = readIntArray("face_attr")
        val flipWinding = worldTransform.determinant3x3() < 0.0

        if (rings != null && ringSizes != null && ringCounts != null) {
            var ringCursor = 0
            var vertCursor = 0
            for (fIdx in ringCounts.indices) {
                val rc = ringCounts[fIdx]
                if (rc <= 0 || ringCursor + rc > ringSizes.size) break
                val loops = ArrayList<List<Vec3>>(rc)
                for (r in 0 until rc) {
                    val sz = ringSizes[ringCursor++]
                    if (vertCursor + sz > rings.size) break
                    val pts = ArrayList<Vec3>(sz)
                    for (k in 0 until sz) {
                        val vi = rings[vertCursor++]
                        if (vi in 0 until numVerts) pts.add(verts[vi])
                    }
                    loops.add(if (flipWinding) pts.reversed() else pts)
                }
                val outer = loops.firstOrNull() ?: continue
                if (outer.size < 3) continue
                val holes = if (loops.size > 1) loops.drop(1) else emptyList()

                val attrIdx = faceAttrIdx?.getOrNull(fIdx) ?: -1
                val attrs = if (attrIdx in meta.faceAttrs.indices) meta.faceAttrs[attrIdx] else null

                val layer = attrs?.layer?.takeIf { it.isNotEmpty() } ?: defaultLayer
                val matName = attrs?.matName?.takeIf { it.isNotEmpty() }
                val matObj = matName?.let { materials[it] }
                var faceColor = when {
                    attrs?.colorFloats != null -> parseColorFloats(attrs.colorFloats, defaultFront)
                    matObj != null -> matObj.color
                    else -> defaultFront
                }
                attrs?.opacity?.let { op ->
                    faceColor = faceColor.copy(a = op.coerceIn(0.05f, 1f))
                }
                val texSpec = attrs?.texture
                val texKey = texSpec?.embed?.takeIf { it.isNotEmpty() } ?: matObj?.textureKey
                val sw = texSpec?.sw ?: matObj?.tileWidthMeters ?: 1.0
                val sh = texSpec?.sh ?: matObj?.tileHeightMeters ?: 1.0
                val rot = texSpec?.rot ?: 0.0

                val normal = EarcutTriangulator.computePolygonNormal(outer)
                val tris = EarcutTriangulator.triangulate(outer, holes, normal)
                if (tris.isEmpty()) continue
                val uvs = CadGeometryHelper.computePlanarUvs(
                    points = tris,
                    normal = normal,
                    scaleW = sw,
                    scaleH = sh,
                    rotDeg = rot,
                    uvwAffine = texSpec?.uvw
                )

                outFaces.add(
                    CadFace(
                        id = nextId("f"),
                        nodeId = nodeId,
                        layer = layer,
                        outerLoop = outer,
                        holes = holes,
                        normal = normal,
                        triangleVertices = tris,
                        triangleUvs = uvs,
                        color = faceColor,
                        backColor = defaultBack,
                        materialKey = matName,
                        textureKey = texKey,
                        hidden = attrs?.hidden ?: false
                    )
                )
                addedFaces++
            }
        }
        return addedFaces to addedEdges
    }
}
