package com.example.domain.bom

import com.example.domain.math.BoundingBox3D
import com.example.domain.math.Mat4
import com.example.domain.math.Vec3
import com.example.domain.model.CadColor
import com.example.domain.model.CadDocument
import com.example.domain.model.CadFace
import com.example.domain.model.CadNode
import com.example.domain.model.UnitSystem
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class CutListPartInstance(
    val nodeId: String,
    val rawName: String,
    val layer: String,
    val worldBounds: BoundingBox3D,
    val localBounds: BoundingBox3D,
    val lengthMeters: Double,
    val widthMeters: Double,
    val heightMeters: Double,
    val faceCount: Int,
    val surfaceAreaM2: Double,
    val primaryColor: CadColor,
    val explodeDirection: Vec3,
    val explodeWeight: Double
)

data class CutListEntry(
    val groupId: String,
    val partNumber: Int,
    val partName: String,
    val lengthMeters: Double,
    val widthMeters: Double,
    val heightMeters: Double,
    val quantity: Int,
    val memberNodeIds: Set<String>,
    val instances: List<CutListPartInstance>,
    val combinedWorldBounds: BoundingBox3D,
    val totalSurfaceAreaM2: Double,
    val primaryColor: CadColor
) {
    fun formatDimensions(unit: UnitSystem, decimals: Int): String {
        val dec = decimals.coerceIn(0, 4)
        val l = String.format(Locale.US, "%.${dec}f", unit.fromMeters(lengthMeters))
        val w = String.format(Locale.US, "%.${dec}f", unit.fromMeters(widthMeters))
        val h = String.format(Locale.US, "%.${dec}f", unit.fromMeters(heightMeters))
        return "$l × $w × $h ${unit.symbol}"
    }
}

data class CutListExtractionResult(
    val entries: List<CutListEntry>,
    val nodeToGroupId: Map<String, String>,
    val partInstances: List<CutListPartInstance>,
    val totalPieceCount: Int
) {
    fun computeExplodeOffsets(
        documentBounds: BoundingBox3D,
        explodeFraction: Float
    ): Map<String, Vec3> {
        val frac = explodeFraction.coerceIn(0f, 1f).toDouble()
        if (frac <= 1e-4 || partInstances.isEmpty()) return emptyMap()
        val spread = max(documentBounds.diagonal * 0.55, 0.25) * frac
        val offsets = HashMap<String, Vec3>(partInstances.size)
        for (part in partInstances) {
            offsets[part.nodeId] = part.explodeDirection * (spread * part.explodeWeight)
        }
        return offsets
    }
}

object CutListExtractor {

    fun extract(document: CadDocument): CutListExtractionResult {
        val visibleFaces = document.faces.filterNot { it.hidden }
        if (visibleFaces.isEmpty()) {
            return CutListExtractionResult(emptyList(), emptyMap(), emptyList(), 0)
        }

        val nodeMap = document.nodes.associateBy { it.id }
        val worldTransformCache = HashMap<String, Mat4>()

        fun getWorldTransform(nodeId: String): Mat4 {
            worldTransformCache[nodeId]?.let { return it }
            val node = nodeMap[nodeId]
            val result = if (node == null) {
                Mat4.IDENTITY
            } else {
                val parentMat = node.parentId?.let { getWorldTransform(it) } ?: Mat4.IDENTITY
                parentMat * node.localTransform
            }
            worldTransformCache[nodeId] = result
            return result
        }

        // Strictly base the Cut List on Components (isComponent == true) and NOT Groups (isComponent == false)
        val componentFaces = if (document.nodes.isNotEmpty()) {
            visibleFaces.filter { face ->
                val node = nodeMap[face.nodeId]
                node != null && node.isComponent && node.isVisible
            }
        } else {
            visibleFaces
        }
        if (componentFaces.isEmpty()) {
            return CutListExtractionResult(emptyList(), emptyMap(), emptyList(), 0)
        }

        val facesByNode = componentFaces.groupBy { it.nodeId }
        val modelCenter = if (document.bounds.isValid) document.bounds.center else Vec3.ZERO

        // First pass: extract per-component geometry + world bounds + local oriented dimensions
        data class RawPart(
            val nodeId: String,
            val node: CadNode?,
            val worldBounds: BoundingBox3D,
            val localBounds: BoundingBox3D,
            val sortedDims: Triple<Double, Double, Double>,
            val faceCount: Int,
            val surfaceAreaM2: Double,
            val primaryColor: CadColor
        )

        val rawParts = ArrayList<RawPart>(facesByNode.size)
        var maxCenterDist = 1e-4

        for ((nodeId, nodeFaces) in facesByNode) {
            if (nodeFaces.isEmpty()) continue
            val node = nodeMap[nodeId]
            val worldXform = getWorldTransform(nodeId)

            var worldBounds = BoundingBox3D.EMPTY
            var areaSum = 0.0
            var bestFaceArea = -1.0
            var bestFace: CadFace = nodeFaces.first()

            for (face in nodeFaces) {
                val tris = face.triangleVertices
                var faceArea = 0.0
                var i = 0
                while (i + 2 < tris.size) {
                    val p0 = tris[i]
                    val p1 = tris[i + 1]
                    val p2 = tris[i + 2]
                    worldBounds = worldBounds.expand(p0).expand(p1).expand(p2)
                    val triArea = 0.5 * (p1 - p0).cross(p2 - p0).length()
                    faceArea += triArea
                    i += 3
                }
                if (tris.isEmpty()) {
                    for (pt in face.outerLoop) {
                        worldBounds = worldBounds.expand(pt)
                    }
                }
                areaSum += faceArea
                if (faceArea > bestFaceArea) {
                    bestFaceArea = faceArea
                    bestFace = face
                }
            }

            if (!worldBounds.isValid) continue

            val (localBounds, sortedDims) = computeLocalBoundsAndDims(
                faces = nodeFaces,
                dominantFace = bestFace,
                worldXform = worldXform,
                worldBounds = worldBounds
            )

            val dist = (worldBounds.center - modelCenter).length()
            if (dist > maxCenterDist) maxCenterDist = dist

            rawParts.add(
                RawPart(
                    nodeId = nodeId,
                    node = node,
                    worldBounds = worldBounds,
                    localBounds = localBounds,
                    sortedDims = sortedDims,
                    faceCount = nodeFaces.size,
                    surfaceAreaM2 = areaSum,
                    primaryColor = bestFace.color
                )
            )
        }

        val instances = rawParts.mapIndexed { idx, raw ->
            val delta = raw.worldBounds.center - modelCenter
            val dist = delta.length()
            val dir = if (dist > 1e-4) {
                delta / dist
            } else {
                // Deterministic radial fallback for concentric/nested parts centered at origin
                val angle = (idx * 137.508) * (PI / 180.0)
                Vec3(cos(angle), sin(angle), 0.35).normalized()
            }
            val weight = (0.35 + 0.65 * (dist / maxCenterDist)).coerceIn(0.35, 1.0)
            val cleanName = cleanPartDisplayName(raw.node?.name, idx)
            CutListPartInstance(
                nodeId = raw.nodeId,
                rawName = cleanName,
                layer = raw.node?.layer ?: "Layer 0",
                worldBounds = raw.worldBounds,
                localBounds = raw.localBounds,
                lengthMeters = raw.sortedDims.first,
                widthMeters = raw.sortedDims.second,
                heightMeters = raw.sortedDims.third,
                faceCount = raw.faceCount,
                surfaceAreaM2 = raw.surfaceAreaM2,
                primaryColor = raw.primaryColor,
                explodeDirection = dir,
                explodeWeight = weight
            )
        }

        // Group identical components by rotation-invariant L x W x H + geometry signature
        val grouped = LinkedHashMap<String, MutableList<CutListPartInstance>>()
        for (inst in instances) {
            val sig = buildGeometrySignature(inst)
            grouped.getOrPut(sig) { mutableListOf() }.add(inst)
        }

        val sortedGroups = grouped.entries.sortedWith(
            compareByDescending<Map.Entry<String, MutableList<CutListPartInstance>>> { it.value.size }
                .thenByDescending { it.value.first().lengthMeters }
                .thenByDescending { it.value.first().widthMeters }
                .thenBy { chooseGroupName(it.value, 0) }
        )

        val entries = ArrayList<CutListEntry>(sortedGroups.size)
        val nodeToGroup = HashMap<String, String>(instances.size)

        sortedGroups.forEachIndexed { index, (_, members) ->
            val first = members.first()
            val groupId = "cut_$index"
            var combined = BoundingBox3D.EMPTY
            val nodeIds = LinkedHashSet<String>(members.size)
            var totalArea = 0.0
            for (m in members) {
                combined = combined.merge(m.worldBounds)
                nodeIds.add(m.nodeId)
                nodeToGroup[m.nodeId] = groupId
                totalArea += m.surfaceAreaM2
            }
            val avgL = members.sumOf { it.lengthMeters } / members.size
            val avgW = members.sumOf { it.widthMeters } / members.size
            val avgH = members.sumOf { it.heightMeters } / members.size

            entries.add(
                CutListEntry(
                    groupId = groupId,
                    partNumber = index + 1,
                    partName = chooseGroupName(members, index),
                    lengthMeters = avgL,
                    widthMeters = avgW,
                    heightMeters = avgH,
                    quantity = members.size,
                    memberNodeIds = nodeIds,
                    instances = members,
                    combinedWorldBounds = combined,
                    totalSurfaceAreaM2 = totalArea,
                    primaryColor = first.primaryColor
                )
            )
        }

        return CutListExtractionResult(
            entries = entries,
            nodeToGroupId = nodeToGroup,
            partInstances = instances,
            totalPieceCount = instances.size
        )
    }

    private fun computeLocalBoundsAndDims(
        faces: List<CadFace>,
        dominantFace: CadFace,
        worldXform: Mat4,
        worldBounds: BoundingBox3D
    ): Pair<BoundingBox3D, Triple<Double, Double, Double>> {
        val m = worldXform.m
        val c0 = Vec3(m[0], m[1], m[2])
        val c1 = Vec3(m[4], m[5], m[6])
        val c2 = Vec3(m[8], m[9], m[10])
        val len0 = c0.length()
        val len1 = c1.length()
        val len2 = c2.length()

        val hasNonIdentityRotation = len0 > 1e-8 && len1 > 1e-8 && len2 > 1e-8 && (
            abs(c0.x / len0 - 1.0) > 1e-3 ||
                abs(c1.y / len1 - 1.0) > 1e-3 ||
                abs(c2.z / len2 - 1.0) > 1e-3
            )

        if (hasNonIdentityRotation) {
            // Project world vertices onto unit local coordinate axes so scale in meters is preserved
            // while 3D placement tilt/rotation is cleanly removed.
            val ax = c0 / len0
            var az = ax.cross(c1 / len1)
            if (az.lengthSquared() > 1e-10) {
                az = az.normalized()
                val ay = az.cross(ax).normalized()
                val oriented = projectFacesToAxes(faces, ax, ay, az)
                if (oriented.isValid) {
                    return oriented to sortedDims(oriented.size)
                }
            }
        }

        // For components whose rotation was baked into vertices (e.g. tilted backrest slats or diagonal rafters),
        // evaluate dominant-face oriented axes and pick whichever framing has the tightest volume.
        var bestBox = worldBounds
        var bestVol = volumeWithFloor(worldBounds.size)

        val candidateFaces = buildList {
            add(dominantFace)
            faces.take(6).forEach { if (it !== dominantFace) add(it) }
        }
        for (cand in candidateFaces) {
            val faceAxes = deriveAxesFromFace(cand) ?: continue
            val obb = projectFacesToAxes(faces, faceAxes.first, faceAxes.second, faceAxes.third)
            if (obb.isValid) {
                val obbVol = volumeWithFloor(obb.size)
                if (obbVol < bestVol * 0.98) {
                    bestVol = obbVol
                    bestBox = obb
                }
            }
        }

        return bestBox to sortedDims(bestBox.size)
    }

    private fun projectFacesToAxes(
        faces: List<CadFace>,
        ax: Vec3,
        ay: Vec3,
        az: Vec3
    ): BoundingBox3D {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY

        fun record(p: Vec3) {
            val px = p.dot(ax)
            val py = p.dot(ay)
            val pz = p.dot(az)
            minX = min(minX, px); minY = min(minY, py); minZ = min(minZ, pz)
            maxX = max(maxX, px); maxY = max(maxY, py); maxZ = max(maxZ, pz)
        }

        for (face in faces) {
            if (face.triangleVertices.isNotEmpty()) {
                for (pt in face.triangleVertices) record(pt)
            } else {
                for (pt in face.outerLoop) record(pt)
            }
        }
        return BoundingBox3D(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
    }

    private fun deriveAxesFromFace(face: CadFace): Triple<Vec3, Vec3, Vec3>? {
        val az = face.normal.normalized()
        if (az.lengthSquared() < 1e-8) return null
        val loop = face.outerLoop
        var bestEdgeDir = Vec3.ZERO
        var bestLen = 1e-6
        if (loop.size >= 2) {
            for (i in loop.indices) {
                val a = loop[i]
                val b = loop[(i + 1) % loop.size]
                val raw = b - a
                val planar = raw - az * raw.dot(az)
                val len = planar.length()
                if (len > bestLen) {
                    bestLen = len
                    bestEdgeDir = planar / len
                }
            }
        }
        val ax = if (bestEdgeDir.lengthSquared() > 1e-8) {
            bestEdgeDir
        } else {
            val ref = if (abs(az.z) < 0.9) Vec3.UNIT_Z else Vec3.UNIT_X
            ref.cross(az).normalized()
        }
        val ay = az.cross(ax).normalized()
        return Triple(ax, ay, az)
    }

    private fun volumeWithFloor(size: Vec3): Double =
        max(abs(size.x), 1e-4) * max(abs(size.y), 1e-4) * max(abs(size.z), 1e-4)

    private fun sortedDims(size: Vec3): Triple<Double, Double, Double> {
        val list = listOf(abs(size.x), abs(size.y), abs(size.z)).sortedDescending()
        return Triple(list[0], list[1], list[2])
    }

    private fun buildGeometrySignature(inst: CutListPartInstance): String {
        // Quantize L x W x H to 1 mm precision and surface area to 0.01 m² so floating jitter matches identically
        val qL = (inst.lengthMeters * 1000.0).roundToInt()
        val qW = (inst.widthMeters * 1000.0).roundToInt()
        val qH = (inst.heightMeters * 1000.0).roundToInt()
        val qArea = (inst.surfaceAreaM2 * 100.0).roundToInt()
        return "${qL}x${qW}x${qH}|f${inst.faceCount}|a${qArea}"
    }

    private fun cleanPartDisplayName(raw: String?, index: Int): String {
        val base = raw?.trim().orEmpty()
        if (base.isEmpty() || base == "Model Root") return "Component #${index + 1}"
        // Strip trailing instance numbers like "Post #2", "Beam (3)", or "Component 4" so identical copies group cleanly
        return base
            .replace(Regex("""^Group\s+""", RegexOption.IGNORE_CASE), "Component ")
            .replace(Regex("""\s*-\s*Component\s+\d+$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*#\d+$"""), "")
            .replace(Regex("""\s*\(\d+\)$"""), "")
            .ifBlank { "Component #${index + 1}" }
    }

    private fun chooseGroupName(members: List<CutListPartInstance>, index: Int): String {
        val customNames = members.map { it.rawName }
            .filter { !it.matches(Regex("""^Component\s+#?\d+$""", RegexOption.IGNORE_CASE)) }
        return customNames.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: "Component #${index + 1}"
    }
}
