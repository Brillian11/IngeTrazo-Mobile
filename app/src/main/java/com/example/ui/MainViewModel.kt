package com.example.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.export.CsvExporter
import com.example.data.export.CutListPdfExportResult
import com.example.data.export.CutListPdfExporter
import com.example.data.local.AngleMeasurementEntity
import com.example.data.local.CountedAngleEntity
import com.example.data.local.LengthMeasurementEntity
import com.example.data.local.PreferencesRepository
import com.example.data.local.RecentFileEntity
import com.example.data.local.UserPreferences
import com.example.data.parser.CadParseResult
import com.example.data.repository.CadRepository
import com.example.data.repository.SampleModelInfo
import com.example.domain.bom.CutListEntry
import com.example.domain.bom.CutListExtractionResult
import com.example.domain.bom.CutListExtractor
import com.example.domain.math.AngleBucketSummary
import com.example.domain.math.AngleMath
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.SnapEngine
import com.example.domain.math.SnapHit
import com.example.domain.math.Vec3
import com.example.domain.model.CadDocument
import com.example.domain.model.CadEdge
import com.example.domain.model.CadFace
import com.example.domain.model.CadSavedView
import com.example.domain.model.CadTool
import com.example.domain.model.DisplayStyle
import com.example.domain.model.StandardView
import com.example.domain.model.UnitSystem
import com.example.ui.viewer.gl.CadCamera
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

sealed interface DocumentLoadState {
    data object Idle : DocumentLoadState
    data class Loading(val title: String) : DocumentLoadState
    data class Loaded(val document: CadDocument) : DocumentLoadState
    data class Error(val title: String, val details: String?) : DocumentLoadState
}

enum class AngleInputMode(val label: String) {
    THREE_POINT("3 Points"),
    TWO_EDGE("2 Edges"),
    TWO_FACE("2 Faces")
}

data class CutListUiState(
    val isLoading: Boolean = false,
    val entries: List<CutListEntry> = emptyList(),
    val totalPieces: Int = 0,
    val selectedGroupId: String? = null,
    val explodeFraction: Float = 0f,
    val isExportingPdf: Boolean = false,
    val lastExportedPdf: CutListPdfExportResult? = null,
    val errorMessage: String? = null
)

data class ViewerUiState(
    val activeTool: CadTool = CadTool.SELECT,
    val angleInputMode: AngleInputMode = AngleInputMode.THREE_POINT,
    val displayStyle: DisplayStyle = DisplayStyle.SHADED_EDGES,
    val isOrthographic: Boolean = false,
    val hiddenLayers: Set<String> = emptySet(),
    val hiddenNodes: Set<String> = emptySet(),
    val selectedFaceIds: Set<String> = emptySet(),
    val selectedEdgeIds: Set<String> = emptySet(),
    val selectedNodeIds: Set<String> = emptySet(),
    val highlightedCutNodeIds: Set<String> = emptySet(),
    val explodeFraction: Float = 0f,
    val explodeOffsets: Map<String, Vec3> = emptyMap(),
    val cameraRenderSignal: Long = 0L,
    val activeLengthPicks: List<Vec3> = emptyList(),
    val activeAnglePicks: List<Vec3> = emptyList(),
    val pendingAngleEdge: CadEdge? = null,
    val pendingAngleFace: CadFace? = null,
    val activeSnap: SnapHit? = null,
    val statusBanner: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: CadRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val userPreferences: StateFlow<UserPreferences> = preferencesRepository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserPreferences()
    )

    val recentFiles: StateFlow<List<RecentFileEntity>> = repository.recentFiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val bundledSamples: List<SampleModelInfo> = repository.bundledSamples

    private val _loadState = MutableStateFlow<DocumentLoadState>(DocumentLoadState.Idle)
    val loadState: StateFlow<DocumentLoadState> = _loadState.asStateFlow()

    val activeDocument: StateFlow<CadDocument?> = repository.activeDocument

    private val _viewerState = MutableStateFlow(ViewerUiState())
    val viewerState: StateFlow<ViewerUiState> = _viewerState.asStateFlow()

    private val _cutListState = MutableStateFlow(CutListUiState())
    val cutListState: StateFlow<CutListUiState> = _cutListState.asStateFlow()

    private var cutListCache: CutListExtractionResult? = null
    private var cutListCacheDocKey: String? = null
    private var cameraFocusJob: Job? = null

    val lengths: StateFlow<List<LengthMeasurementEntity>> = activeDocument
        .flatMapLatest { doc ->
            if (doc == null) flowOf(emptyList()) else repository.observeLengths(doc.documentKey)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val angles: StateFlow<List<AngleMeasurementEntity>> = activeDocument
        .flatMapLatest { doc ->
            if (doc == null) flowOf(emptyList()) else repository.observeAngles(doc.documentKey)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val countedAngles: StateFlow<List<CountedAngleEntity>> = activeDocument
        .flatMapLatest { doc ->
            if (doc == null) flowOf(emptyList()) else repository.observeCountedAngles(doc.documentKey)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val angleBuckets: StateFlow<List<AngleBucketSummary>> = combine(
        countedAngles,
        userPreferences
    ) { items, prefs ->
        AngleMath.groupAnglesByTolerance(items.map { it.angleDegrees }, prefs.angleToleranceDeg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setDarkCadMode(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setDarkCadMode(enabled) }
    }

    fun setUnitSystem(unit: UnitSystem) {
        viewModelScope.launch { preferencesRepository.setUnitSystem(unit) }
        if (activeDocument.value != null) {
            _viewerState.update { it.copy(statusBanner = "Units: ${unit.label}") }
        }
    }

    fun setPrecisionDecimals(decimals: Int) {
        viewModelScope.launch { preferencesRepository.setPrecisionDecimals(decimals) }
        if (activeDocument.value != null) {
            _viewerState.update { it.copy(statusBanner = "Precision: $decimals decimal(s)") }
        }
    }

    fun setAngleToleranceDeg(tol: Double) {
        viewModelScope.launch { preferencesRepository.setAngleToleranceDeg(tol) }
    }

    fun openUri(uri: Uri, onOpened: () -> Unit = {}) {
        viewModelScope.launch {
            _loadState.value = DocumentLoadState.Loading("Opening model…")
            handleParseResult(repository.openUri(uri), onOpened)
        }
    }

    fun openSample(sample: SampleModelInfo, onOpened: () -> Unit = {}) {
        viewModelScope.launch {
            _loadState.value = DocumentLoadState.Loading("Loading ${sample.title}…")
            handleParseResult(repository.openSampleAsset(sample.assetPath, sample.title), onOpened)
        }
    }

    fun openRecent(entity: RecentFileEntity, onOpened: () -> Unit = {}) {
        viewModelScope.launch {
            _loadState.value = DocumentLoadState.Loading("Opening ${entity.displayName}…")
            handleParseResult(repository.openRecent(entity), onOpened)
        }
    }

    fun removeRecent(documentKey: String) {
        viewModelScope.launch { repository.removeRecent(documentKey) }
    }

    fun dismissError() {
        _loadState.value = DocumentLoadState.Idle
    }

    private suspend fun handleParseResult(result: CadParseResult, onOpened: () -> Unit) {
        when (result) {
            is CadParseResult.Success -> {
                val doc = result.document
                cameraFocusJob?.cancel()
                cutListCache = null
                cutListCacheDocKey = null
                _cutListState.value = CutListUiState()
                val hiddenByDefault = doc.layers.filter { !it.isVisible }.map { it.name }.toSet()
                val hiddenNodesByDefault = doc.nodes.filter { !it.isVisible }.map { it.id }.toSet()
                _viewerState.value = ViewerUiState(
                    hiddenLayers = hiddenByDefault,
                    hiddenNodes = hiddenNodesByDefault,
                    statusBanner = if (doc.warnings.isNotEmpty()) doc.warnings.first() else null
                )
                _loadState.value = DocumentLoadState.Loaded(doc)
                ensureCutListExtracted()
                onOpened()
            }
            is CadParseResult.Failure -> {
                _loadState.value = DocumentLoadState.Error(result.message, result.details)
            }
        }
    }

    fun setTool(tool: CadTool) {
        _viewerState.update {
            it.copy(
                activeTool = tool,
                activeLengthPicks = emptyList(),
                activeAnglePicks = emptyList(),
                pendingAngleEdge = null,
                pendingAngleFace = null,
                activeSnap = null,
                statusBanner = when (tool) {
                    CadTool.MEASURE_LENGTH -> "Tap or drag to snap 2 points for distance"
                    CadTool.MEASURE_ANGLE -> "Angle mode: ${it.angleInputMode.label}"
                    CadTool.SELECT -> "Select mode: tap faces or edges"
                    CadTool.ORBIT -> "Orbit mode: drag to rotate view"
                    CadTool.PAN -> "Pan mode: drag to move view"
                }
            )
        }
    }

    fun setAngleInputMode(mode: AngleInputMode) {
        _viewerState.update {
            it.copy(
                angleInputMode = mode,
                activeAnglePicks = emptyList(),
                pendingAngleEdge = null,
                pendingAngleFace = null,
                statusBanner = when (mode) {
                    AngleInputMode.THREE_POINT -> "Pick arm point → apex → second arm point"
                    AngleInputMode.TWO_EDGE -> "Tap two edges to measure angle"
                    AngleInputMode.TWO_FACE -> "Tap two faces to measure dihedral angle"
                }
            )
        }
    }

    fun setDisplayStyle(style: DisplayStyle) {
        _viewerState.update { it.copy(displayStyle = style) }
    }

    fun toggleOrthographic(camera: CadCamera) {
        val next = !camera.isOrthographic
        camera.isOrthographic = next
        _viewerState.update { it.copy(isOrthographic = next) }
    }

    fun applyStandardView(view: StandardView, camera: CadCamera) {
        val doc = activeDocument.value ?: return
        camera.applyStandardView(view, doc.bounds)
        _viewerState.update { it.copy(statusBanner = "${view.label} View") }
    }

    fun applySavedView(view: CadSavedView, camera: CadCamera) {
        camera.applySavedView(view)
        _viewerState.update {
            it.copy(
                isOrthographic = view.parallel,
                hiddenLayers = view.hiddenLayers,
                statusBanner = "Scene: ${view.name}"
            )
        }
    }

    fun toggleLayerVisibility(layerName: String) {
        _viewerState.update { state ->
            val updated = state.hiddenLayers.toMutableSet()
            if (!updated.add(layerName)) updated.remove(layerName)
            state.copy(hiddenLayers = updated)
        }
    }

    fun toggleNodeVisibility(nodeId: String) {
        _viewerState.update { state ->
            val updated = state.hiddenNodes.toMutableSet()
            if (!updated.add(nodeId)) updated.remove(nodeId)
            state.copy(hiddenNodes = updated)
        }
    }

    fun showAllHierarchy() {
        _viewerState.update { it.copy(hiddenLayers = emptySet(), hiddenNodes = emptySet()) }
    }

    fun selectNode(nodeId: String) {
        _viewerState.update { state ->
            val next = if (nodeId in state.selectedNodeIds) state.selectedNodeIds - nodeId else state.selectedNodeIds + nodeId
            state.copy(selectedNodeIds = next)
        }
    }

    fun clearSelection() {
        _cutListState.update { it.copy(selectedGroupId = null) }
        _viewerState.update {
            it.copy(
                selectedFaceIds = emptySet(),
                selectedEdgeIds = emptySet(),
                selectedNodeIds = emptySet(),
                highlightedCutNodeIds = emptySet()
            )
        }
    }

    fun undoActivePick() {
        _viewerState.update { state ->
            when (state.activeTool) {
                CadTool.MEASURE_LENGTH -> state.copy(activeLengthPicks = state.activeLengthPicks.dropLast(1))
                CadTool.MEASURE_ANGLE -> state.copy(
                    activeAnglePicks = state.activeAnglePicks.dropLast(1),
                    pendingAngleEdge = null,
                    pendingAngleFace = null
                )
                else -> state
            }
        }
    }

    fun clearActivePicks() {
        _viewerState.update {
            it.copy(
                activeLengthPicks = emptyList(),
                activeAnglePicks = emptyList(),
                pendingAngleEdge = null,
                pendingAngleFace = null,
                activeSnap = null
            )
        }
    }

    fun onSnapHover(x: Float, y: Float, camera: CadCamera) {
        val doc = activeDocument.value ?: return
        val state = _viewerState.value
        val hit = SnapEngine.findBestSnap(
            doc = doc,
            hiddenLayers = state.hiddenLayers,
            hiddenNodes = state.hiddenNodes,
            screenX = x,
            screenY = y,
            viewportWidth = camera.viewportWidth,
            viewportHeight = camera.viewportHeight,
            projectToScreen = camera::projectToScreen,
            rayFromScreen = camera::rayFromScreen,
            explodeOffsets = state.explodeOffsets
        )
        _viewerState.update { it.copy(activeSnap = hit) }
    }

    fun onViewportTap(x: Float, y: Float, camera: CadCamera) {
        val doc = activeDocument.value ?: return
        val state = _viewerState.value

        when (state.activeTool) {
            CadTool.MEASURE_LENGTH -> {
                val snap = SnapEngine.findBestSnap(
                    doc, state.hiddenLayers, state.hiddenNodes, x, y,
                    camera.viewportWidth, camera.viewportHeight,
                    camera::projectToScreen, camera::rayFromScreen,
                    explodeOffsets = state.explodeOffsets
                )
                if (snap == null) {
                    _viewerState.update { it.copy(statusBanner = "Tap closer to a vertex, edge, or face") }
                    return
                }
                val picks = state.activeLengthPicks + snap.point
                if (picks.size >= 2) {
                    val a = picks[0]
                    val b = picks[1]
                    val dist = a.distanceTo(b)
                    val prefs = userPreferences.value
                    val label = "L${lengths.value.size + 1}"
                    viewModelScope.launch {
                        repository.addLength(
                            LengthMeasurementEntity(
                                documentKey = doc.documentKey,
                                label = label,
                                ax = a.x, ay = a.y, az = a.z,
                                bx = b.x, by = b.y, bz = b.z,
                                lengthMeters = dist
                            )
                        )
                    }
                    _viewerState.update {
                        it.copy(
                            activeLengthPicks = emptyList(),
                            activeSnap = snap,
                            statusBanner = "Saved $label: ${prefs.unitSystem.formatMeters(dist, prefs.precisionDecimals)}"
                        )
                    }
                } else {
                    _viewerState.update {
                        it.copy(
                            activeLengthPicks = picks,
                            activeSnap = snap,
                            statusBanner = "Snapped start (${snap.type.label}) · Tap end point"
                        )
                    }
                }
            }

            CadTool.MEASURE_ANGLE -> {
                handleAngleTap(doc, state, x, y, camera)
            }

            CadTool.SELECT -> {
                val hit = SnapEngine.pickGeometry(
                    doc, state.hiddenLayers, state.hiddenNodes, x, y,
                    camera::projectToScreen, camera::rayFromScreen,
                    explodeOffsets = state.explodeOffsets
                )
                if (hit == null) {
                    clearSelection()
                    return
                }
                val matchedGroupId = hit.nodeId?.let { nid ->
                    if (cutListCacheDocKey == doc.documentKey) cutListCache?.nodeToGroupId?.get(nid) else null
                }
                val matchedEntry = matchedGroupId?.let { gid ->
                    cutListCache?.entries?.find { it.groupId == gid }
                }
                if (matchedEntry != null) {
                    _cutListState.update { it.copy(selectedGroupId = matchedEntry.groupId) }
                }
                _viewerState.update { cur ->
                    val nextFaces = cur.selectedFaceIds.toMutableSet()
                    val nextEdges = cur.selectedEdgeIds.toMutableSet()
                    hit.face?.let { f -> if (!nextFaces.add(f.id)) nextFaces.remove(f.id) }
                    hit.edge?.let { e -> if (!nextEdges.add(e.id)) nextEdges.remove(e.id) }
                    val msg = if (matchedEntry != null) {
                        "${matchedEntry.partName} (×${matchedEntry.quantity}) · ${nextFaces.size}f ${nextEdges.size}e"
                    } else {
                        "Selected: ${nextFaces.size} face(s), ${nextEdges.size} edge(s)"
                    }
                    cur.copy(
                        selectedFaceIds = nextFaces,
                        selectedEdgeIds = nextEdges,
                        highlightedCutNodeIds = matchedEntry?.memberNodeIds ?: cur.highlightedCutNodeIds,
                        statusBanner = msg
                    )
                }
            }

            CadTool.ORBIT, CadTool.PAN -> Unit // Navigation-only modes do not select geometry on tap
        }
    }

    private fun handleAngleTap(
        doc: CadDocument,
        state: ViewerUiState,
        x: Float,
        y: Float,
        camera: CadCamera
    ) {
        when (state.angleInputMode) {
            AngleInputMode.THREE_POINT -> {
                val snap = SnapEngine.findBestSnap(
                    doc, state.hiddenLayers, state.hiddenNodes, x, y,
                    camera.viewportWidth, camera.viewportHeight,
                    camera::projectToScreen, camera::rayFromScreen,
                    explodeOffsets = state.explodeOffsets
                ) ?: return
                val picks = state.activeAnglePicks + snap.point
                if (picks.size >= 3) {
                    val comp = AngleMath.computeThreePointAngle(picks[0], picks[1], picks[2])
                    val label = "A${angles.value.size + 1}"
                    viewModelScope.launch {
                        repository.addAngle(
                            AngleMeasurementEntity(
                                documentKey = doc.documentKey,
                                label = label,
                                vx = comp.vertex.x, vy = comp.vertex.y, vz = comp.vertex.z,
                                ax = comp.rayAEnd.x, ay = comp.rayAEnd.y, az = comp.rayAEnd.z,
                                bx = comp.rayBEnd.x, by = comp.rayBEnd.y, bz = comp.rayBEnd.z,
                                angleDegrees = comp.degrees,
                                mode = "3-Point"
                            )
                        )
                    }
                    _viewerState.update {
                        it.copy(
                            activeAnglePicks = emptyList(),
                            activeSnap = snap,
                            statusBanner = String.format(Locale.US, "Saved %s: %.2f°", label, comp.degrees)
                        )
                    }
                } else {
                    val prompt = if (picks.size == 1) "Tap apex (vertex) point" else "Tap second arm point"
                    _viewerState.update { it.copy(activeAnglePicks = picks, activeSnap = snap, statusBanner = prompt) }
                }
            }

            AngleInputMode.TWO_EDGE -> {
                val pick = SnapEngine.pickGeometry(
                    doc, state.hiddenLayers, state.hiddenNodes, x, y,
                    camera::projectToScreen, camera::rayFromScreen, edgePickRadiusPx = 56.0,
                    explodeOffsets = state.explodeOffsets
                )
                val edge = pick?.edge
                if (edge == null) {
                    _viewerState.update { it.copy(statusBanner = "Tap directly on an edge") }
                    return
                }
                val first = state.pendingAngleEdge
                if (first == null) {
                    _viewerState.update {
                        it.copy(
                            pendingAngleEdge = edge,
                            selectedEdgeIds = setOf(edge.id),
                            statusBanner = "Edge 1 selected · Tap second edge"
                        )
                    }
                } else if (first.id != edge.id) {
                    val comp = AngleMath.computeTwoEdgeAngle(first, edge)
                    val label = "A${angles.value.size + 1}"
                    viewModelScope.launch {
                        repository.addAngle(
                            AngleMeasurementEntity(
                                documentKey = doc.documentKey,
                                label = label,
                                vx = comp.vertex.x, vy = comp.vertex.y, vz = comp.vertex.z,
                                ax = comp.rayAEnd.x, ay = comp.rayAEnd.y, az = comp.rayAEnd.z,
                                bx = comp.rayBEnd.x, by = comp.rayBEnd.y, bz = comp.rayBEnd.z,
                                angleDegrees = comp.degrees,
                                mode = "2-Edge"
                            )
                        )
                    }
                    _viewerState.update {
                        it.copy(
                            pendingAngleEdge = null,
                            selectedEdgeIds = setOf(first.id, edge.id),
                            statusBanner = String.format(Locale.US, "Saved %s (2-Edge): %.2f°", label, comp.degrees)
                        )
                    }
                }
            }

            AngleInputMode.TWO_FACE -> {
                val pick = SnapEngine.pickGeometry(
                    doc, state.hiddenLayers, state.hiddenNodes, x, y,
                    camera::projectToScreen, camera::rayFromScreen,
                    explodeOffsets = state.explodeOffsets
                )
                val face = pick?.face
                if (face == null) {
                    _viewerState.update { it.copy(statusBanner = "Tap directly on a face") }
                    return
                }
                val first = state.pendingAngleFace
                if (first == null) {
                    _viewerState.update {
                        it.copy(
                            pendingAngleFace = face,
                            selectedFaceIds = setOf(face.id),
                            statusBanner = "Face 1 selected · Tap second face"
                        )
                    }
                } else if (first.id != face.id) {
                    val comp = AngleMath.computeTwoFaceDihedralAngle(first, face)
                    val label = "A${angles.value.size + 1}"
                    viewModelScope.launch {
                        repository.addAngle(
                            AngleMeasurementEntity(
                                documentKey = doc.documentKey,
                                label = label,
                                vx = comp.vertex.x, vy = comp.vertex.y, vz = comp.vertex.z,
                                ax = comp.rayAEnd.x, ay = comp.rayAEnd.y, az = comp.rayAEnd.z,
                                bx = comp.rayBEnd.x, by = comp.rayBEnd.y, bz = comp.rayBEnd.z,
                                angleDegrees = comp.degrees,
                                mode = "2-Face"
                            )
                        )
                    }
                    _viewerState.update {
                        it.copy(
                            pendingAngleFace = null,
                            selectedFaceIds = setOf(first.id, face.id),
                            statusBanner = String.format(Locale.US, "Saved %s (Dihedral): %.2f°", label, comp.degrees)
                        )
                    }
                }
            }
        }
    }

    fun markMeasuredAngleIntoCounter(angle: AngleMeasurementEntity) {
        val doc = activeDocument.value ?: return
        viewModelScope.launch {
            repository.addCountedAngle(
                CountedAngleEntity(
                    documentKey = doc.documentKey,
                    angleDegrees = angle.angleDegrees,
                    locationLabel = angle.label,
                    source = "Manual (${angle.mode})",
                    entityRef = "angle_${angle.id}",
                    vx = angle.vx, vy = angle.vy, vz = angle.vz,
                    ax = angle.ax, ay = angle.ay, az = angle.az,
                    bx = angle.bx, by = angle.by, bz = angle.bz
                )
            )
            _viewerState.update {
                it.copy(statusBanner = String.format(Locale.US, "Marked %.2f° into Angle Counter", angle.angleDegrees))
            }
        }
    }

    fun autoCountSelectionAngles() {
        val doc = activeDocument.value ?: return
        val state = _viewerState.value
        val selFaces = doc.faces.filter {
            it.id in state.selectedFaceIds || it.nodeId in state.selectedNodeIds
        }
        val selEdges = doc.edges.filter {
            it.id in state.selectedEdgeIds || it.nodeId in state.selectedNodeIds
        }

        val targetFaces = if (selFaces.isEmpty() && selEdges.isEmpty()) {
            // If nothing explicitly selected, analyze visible faces/edges capped to avoid excessive noise
            doc.faces.filter { !it.hidden && it.layer !in state.hiddenLayers && it.nodeId !in state.hiddenNodes }.take(300)
        } else selFaces

        val targetEdges = if (selFaces.isEmpty() && selEdges.isEmpty()) {
            doc.edges.filter { !it.hidden && it.layer !in state.hiddenLayers && it.nodeId !in state.hiddenNodes }.take(300)
        } else selEdges

        viewModelScope.launch {
            val detected = withContext(Dispatchers.Default) {
                AngleMath.detectAnglesInSelection(targetFaces, targetEdges)
            }
            if (detected.isEmpty()) {
                _viewerState.update { it.copy(statusBanner = "No distinct angles detected in selection") }
                return@launch
            }
            val entities = detected.map { d ->
                CountedAngleEntity(
                    documentKey = doc.documentKey,
                    angleDegrees = d.angleDegrees,
                    locationLabel = d.locationLabel,
                    source = if (selFaces.isEmpty() && selEdges.isEmpty()) "Auto-Model" else "Auto-Selection",
                    entityRef = d.entityRef,
                    vx = d.vertex.x, vy = d.vertex.y, vz = d.vertex.z,
                    ax = d.rayAEnd.x, ay = d.rayAEnd.y, az = d.rayAEnd.z,
                    bx = d.rayBEnd.x, by = d.rayBEnd.y, bz = d.rayBEnd.z
                )
            }
            repository.addCountedAngles(entities)
            _viewerState.update {
                it.copy(statusBanner = "Counted ${entities.size} angles from geometry")
            }
        }
    }

    fun renameLength(item: LengthMeasurementEntity, newLabel: String) {
        viewModelScope.launch { repository.updateLengthLabel(item, newLabel) }
    }

    fun deleteLength(id: Long) {
        viewModelScope.launch { repository.deleteLength(id) }
    }

    fun renameAngle(item: AngleMeasurementEntity, newLabel: String) {
        viewModelScope.launch { repository.updateAngleLabel(item, newLabel) }
    }

    fun deleteAngle(id: Long) {
        viewModelScope.launch { repository.deleteAngle(id) }
    }

    fun updateCountedAngle(item: CountedAngleEntity) {
        viewModelScope.launch { repository.updateCountedAngle(item) }
    }

    fun deleteCountedAngle(id: Long) {
        viewModelScope.launch { repository.deleteCountedAngle(id) }
    }

    fun clearCountedAngles() {
        val doc = activeDocument.value ?: return
        viewModelScope.launch { repository.clearCountedAngles(doc.documentKey) }
    }

    fun focusOnPoint(point: Vec3, camera: CadCamera) {
        camera.target = point
        _viewerState.update { it.copy(statusBanner = "Focused on measurement") }
    }

    fun exportCsvToUri(uri: Uri, contentResolver: ContentResolver) {
        val doc = activeDocument.value ?: return
        val prefs = userPreferences.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val csv = CsvExporter.buildMeasurementAndCountCsv(
                    documentTitle = doc.title,
                    unitSystem = prefs.unitSystem,
                    decimals = prefs.precisionDecimals,
                    angleToleranceDeg = prefs.angleToleranceDeg,
                    lengths = lengths.value,
                    angles = angles.value,
                    countedAngles = countedAngles.value
                )
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(csv.toByteArray(Charsets.UTF_8))
                }
                _viewerState.update { it.copy(statusBanner = "Exported measurements & angle counts to CSV") }
            } catch (e: Exception) {
                _viewerState.update { it.copy(statusBanner = "CSV export failed: ${e.localizedMessage}") }
            }
        }
    }

    fun ensureCutListExtracted() {
        val doc = activeDocument.value ?: return
        if (cutListCacheDocKey == doc.documentKey && cutListCache != null) {
            val cached = cutListCache!!
            _cutListState.update {
                it.copy(
                    isLoading = false,
                    entries = cached.entries,
                    totalPieces = cached.totalPieceCount,
                    errorMessage = null
                )
            }
            return
        }
        viewModelScope.launch {
            _cutListState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val extracted = withContext(Dispatchers.Default) {
                    CutListExtractor.extract(doc)
                }
                cutListCache = extracted
                cutListCacheDocKey = doc.documentKey
                val currentExplode = _cutListState.value.explodeFraction
                val offsets = extracted.computeExplodeOffsets(doc.bounds, currentExplode)
                _cutListState.update {
                    it.copy(
                        isLoading = false,
                        entries = extracted.entries,
                        totalPieces = extracted.totalPieceCount,
                        errorMessage = null
                    )
                }
                _viewerState.update {
                    it.copy(explodeOffsets = offsets)
                }
            } catch (t: Throwable) {
                _cutListState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Cut list failed: ${t.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun onSelectCutListRow(groupId: String, camera: CadCamera) {
        val entry = _cutListState.value.entries.find { it.groupId == groupId } ?: return
        if (_cutListState.value.selectedGroupId == groupId) {
            _cutListState.update { it.copy(selectedGroupId = null) }
            _viewerState.update { it.copy(highlightedCutNodeIds = emptySet(), statusBanner = null) }
            return
        }

        _cutListState.update { it.copy(selectedGroupId = groupId) }
        _viewerState.update {
            it.copy(
                highlightedCutNodeIds = entry.memberNodeIds,
                statusBanner = "${entry.partName} (×${entry.quantity})"
            )
        }

        val offsets = _viewerState.value.explodeOffsets
        var explodedBounds = BoundingBox3D.EMPTY
        for (inst in entry.instances) {
            val off = offsets[inst.nodeId] ?: Vec3.ZERO
            explodedBounds = explodedBounds
                .expand(inst.worldBounds.min + off)
                .expand(inst.worldBounds.max + off)
        }
        val fitBounds = if (explodedBounds.isValid) explodedBounds else entry.combinedWorldBounds

        cameraFocusJob?.cancel()
        cameraFocusJob = viewModelScope.launch {
            val startTarget = camera.target
            val startDist = camera.distance
            val endTarget = fitBounds.center
            val endDist = camera.targetDistanceForBounds(fitBounds, paddingFactor = 0.85)
            val steps = 16
            for (step in 1..steps) {
                val u = step.toDouble() / steps
                val ease = 1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u)
                camera.target = startTarget + (endTarget - startTarget) * ease
                camera.distance = startDist + (endDist - startDist) * ease
                _viewerState.update { it.copy(cameraRenderSignal = it.cameraRenderSignal + 1L) }
                delay(16L)
            }
        }
    }

    fun setExplodeFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val doc = activeDocument.value ?: return
        val cached = cutListCache
        if (cached == null || cutListCacheDocKey != doc.documentKey) {
            viewModelScope.launch {
                val extracted = withContext(Dispatchers.Default) { CutListExtractor.extract(doc) }
                cutListCache = extracted
                cutListCacheDocKey = doc.documentKey
                applyExplodeOffsets(extracted, doc.bounds, clamped)
            }
            return
        }
        applyExplodeOffsets(cached, doc.bounds, clamped)
    }

    private fun applyExplodeOffsets(
        extracted: CutListExtractionResult,
        docBounds: BoundingBox3D,
        clamped: Float
    ) {
        val offsets = extracted.computeExplodeOffsets(docBounds, clamped)
        _cutListState.update {
            it.copy(
                entries = if (it.entries.isEmpty()) extracted.entries else it.entries,
                totalPieces = extracted.totalPieceCount,
                explodeFraction = clamped
            )
        }
        _viewerState.update {
            it.copy(
                explodeFraction = clamped,
                explodeOffsets = offsets
            )
        }
    }

    fun exportCutListPdf(
        context: Context,
        captureSnapshot: suspend () -> Bitmap?
    ) {
        val doc = activeDocument.value ?: return
        if (_cutListState.value.isExportingPdf) return
        viewModelScope.launch {
            _cutListState.update { it.copy(isExportingPdf = true, errorMessage = null) }
            var snapshot: Bitmap? = null
            try {
                val extraction = if (cutListCache != null && cutListCacheDocKey == doc.documentKey) {
                    cutListCache!!
                } else {
                    withContext(Dispatchers.Default) { CutListExtractor.extract(doc) }.also {
                        cutListCache = it
                        cutListCacheDocKey = doc.documentKey
                    }
                }
                snapshot = withContext(Dispatchers.Main) { captureSnapshot() }
                val prefs = userPreferences.value
                val explodeFrac = _cutListState.value.explodeFraction
                val result = withContext(Dispatchers.IO) {
                    CutListPdfExporter.exportToDownloadsAndShareCache(
                        context = context,
                        projectTitle = doc.title,
                        entries = extraction.entries,
                        totalPieces = extraction.totalPieceCount,
                        modelBounds = doc.bounds,
                        unitSystem = prefs.unitSystem,
                        precisionDecimals = prefs.precisionDecimals,
                        explodeFraction = explodeFrac,
                        viewportSnapshot = snapshot
                    )
                }
                _cutListState.update {
                    it.copy(
                        isExportingPdf = false,
                        entries = extraction.entries,
                        totalPieces = extraction.totalPieceCount,
                        lastExportedPdf = result
                    )
                }
                _viewerState.update {
                    it.copy(statusBanner = "PDF saved: ${result.savedLocationLabel}")
                }
            } catch (t: Throwable) {
                _cutListState.update {
                    it.copy(
                        isExportingPdf = false,
                        errorMessage = "PDF export failed: ${t.localizedMessage ?: "Unknown error"}"
                    )
                }
            } finally {
                snapshot?.recycle()
            }
        }
    }

    fun dismissExportedPdfBanner() {
        _cutListState.update { it.copy(lastExportedPdf = null, errorMessage = null) }
    }

    fun clearStatusBanner() {
        _viewerState.update { it.copy(statusBanner = null) }
    }
}
