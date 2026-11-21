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
import com.example.ui.ar.ArModelTrackingDot
import com.example.ui.ar.ArTrackingDotMath
import com.example.ui.viewer.gl.ActiveAngleDotDrag
import com.example.ui.viewer.gl.ActiveLengthDotDrag
import com.example.ui.viewer.gl.AngleDotDragMath
import com.example.ui.viewer.gl.AngleDotTarget
import com.example.ui.viewer.gl.AnglePointRole
import com.example.ui.viewer.gl.CadCamera
import com.example.ui.viewer.gl.LengthDotDragMath
import com.example.ui.viewer.gl.LengthDotTarget
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
    val isExportSelectionMode: Boolean = false,
    val checkedGroupIds: Set<String> = emptySet(),
    val explodeFraction: Float = 0f,
    val isExportingPdf: Boolean = false,
    val lastExportedPdf: CutListPdfExportResult? = null,
    val errorMessage: String? = null
)

object CutListExportFilter {
    fun resolveEntriesForExport(
        allEntries: List<CutListEntry>,
        isSelectionMode: Boolean = true,
        checkedGroupIds: Set<String>
    ): Pair<List<CutListEntry>, Int> {
        if (checkedGroupIds.isEmpty()) return emptyList<CutListEntry>() to 0
        val selected = allEntries
            .filter { it.groupId in checkedGroupIds }
            .mapIndexed { idx, entry -> entry.copy(partNumber = idx + 1) }
        return selected to selected.sumOf { it.quantity }
    }

    fun collectSelectedNodeIds(entries: List<CutListEntry>): Set<String> =
        entries.flatMapTo(LinkedHashSet()) { it.memberNodeIds }

    fun computeSelectedBounds(
        entries: List<CutListEntry>,
        explodeOffsets: Map<String, Vec3> = emptyMap()
    ): BoundingBox3D {
        var bounds = BoundingBox3D.EMPTY
        for (entry in entries) {
            for (inst in entry.instances) {
                val off = explodeOffsets[inst.nodeId] ?: Vec3.ZERO
                bounds = bounds
                    .expand(inst.worldBounds.min + off)
                    .expand(inst.worldBounds.max + off)
            }
            if (entry.instances.isEmpty() && entry.combinedWorldBounds.isValid) {
                bounds = bounds
                    .expand(entry.combinedWorldBounds.min)
                    .expand(entry.combinedWorldBounds.max)
            }
        }
        return bounds
    }
}

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
    val activeLengthDotDrag: ActiveLengthDotDrag? = null,
    val activeAngleDotDrag: ActiveAngleDotDrag? = null,
    val arTrackingDot: ArModelTrackingDot? = null,
    val statusBanner: String? = null,
    val canUndoTape: Boolean = false,
    val canRedoTape: Boolean = false,
    val canUndoAngle: Boolean = false,
    val canRedoAngle: Boolean = false
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

    private class SavedLengthRef(
        @Volatile var entity: LengthMeasurementEntity,
        @Volatile var insertJob: Job? = null
    )

    private class SavedAngleRef(
        @Volatile var entity: AngleMeasurementEntity,
        @Volatile var insertJob: Job? = null
    )

    private sealed interface TapeUndoAction {
        data class PickAdded(val point: Vec3) : TapeUndoAction
        data class ActivePickEdited(val index: Int, val before: Vec3, val after: Vec3) : TapeUndoAction
        data class LengthAdded(val ref: SavedLengthRef) : TapeUndoAction
        data class LengthEdited(val before: LengthMeasurementEntity, val after: LengthMeasurementEntity) : TapeUndoAction
        data class LengthDeleted(val entity: LengthMeasurementEntity) : TapeUndoAction
    }

    private sealed interface AngleUndoAction {
        data class ThreePointPickAdded(val point: Vec3) : AngleUndoAction
        data class ActivePickEdited(val index: Int, val before: Vec3, val after: Vec3) : AngleUndoAction
        data class PendingEdgeSelected(val edge: CadEdge) : AngleUndoAction
        data class PendingFaceSelected(val face: CadFace) : AngleUndoAction
        data class AngleAdded(val ref: SavedAngleRef) : AngleUndoAction
        data class AngleEdited(val before: AngleMeasurementEntity, val after: AngleMeasurementEntity) : AngleUndoAction
        data class AngleDeleted(val entity: AngleMeasurementEntity) : AngleUndoAction
    }

    private val tapeUndoStack = ArrayDeque<TapeUndoAction>()
    private val tapeRedoStack = ArrayDeque<TapeUndoAction>()
    private val angleUndoStack = ArrayDeque<AngleUndoAction>()
    private val angleRedoStack = ArrayDeque<AngleUndoAction>()

    private fun ViewerUiState.withUndoAvailability(): ViewerUiState = copy(
        canUndoTape = tapeUndoStack.isNotEmpty(),
        canRedoTape = tapeRedoStack.isNotEmpty(),
        canUndoAngle = angleUndoStack.isNotEmpty(),
        canRedoAngle = angleRedoStack.isNotEmpty()
    )

    private fun clearInProgressPickHistory(
        clearTapePicks: Boolean = false,
        clearAnglePicks: Boolean = false
    ) {
        if (clearTapePicks) {
            tapeUndoStack.removeAll { it is TapeUndoAction.PickAdded || it is TapeUndoAction.ActivePickEdited }
            tapeRedoStack.removeAll { it is TapeUndoAction.PickAdded || it is TapeUndoAction.ActivePickEdited }
        }
        if (clearAnglePicks) {
            angleUndoStack.removeAll {
                it is AngleUndoAction.ThreePointPickAdded ||
                    it is AngleUndoAction.ActivePickEdited ||
                    it is AngleUndoAction.PendingEdgeSelected ||
                    it is AngleUndoAction.PendingFaceSelected
            }
            angleRedoStack.removeAll {
                it is AngleUndoAction.ThreePointPickAdded ||
                    it is AngleUndoAction.ActivePickEdited ||
                    it is AngleUndoAction.PendingEdgeSelected ||
                    it is AngleUndoAction.PendingFaceSelected
            }
        }
    }

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
                tapeUndoStack.clear()
                tapeRedoStack.clear()
                angleUndoStack.clear()
                angleRedoStack.clear()
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
        clearInProgressPickHistory(clearTapePicks = true, clearAnglePicks = true)
        _viewerState.update {
            it.copy(
                activeTool = tool,
                activeLengthPicks = emptyList(),
                activeAnglePicks = emptyList(),
                pendingAngleEdge = null,
                pendingAngleFace = null,
                activeSnap = null,
                activeLengthDotDrag = null,
                activeAngleDotDrag = null,
                statusBanner = when (tool) {
                    CadTool.MEASURE_LENGTH -> "Tap 2 points to measure · Drag dot to edit · Long-press line to delete"
                    CadTool.MEASURE_ANGLE -> "Angle mode (${it.angleInputMode.label}) · Drag dot to edit · Long-press line to delete"
                    CadTool.AR_TRACKING_DOT -> "AR Move Dot: tap any corner, edge, or face to place tracking dot"
                    CadTool.SELECT -> "Select mode: tap faces or edges · Drag dots to edit"
                    CadTool.ORBIT -> "Orbit mode: drag to rotate view"
                    CadTool.PAN -> "Pan mode: drag to move view"
                }
            ).withUndoAvailability()
        }
    }

    fun setArTrackingDot(dot: ArModelTrackingDot?) {
        _viewerState.update { state ->
            val prefs = userPreferences.value
            val banner = if (dot != null) {
                "AR Move Dot set: ${dot.label} ${ArTrackingDotMath.formatDotCoordinates(dot.point, prefs.unitSystem, prefs.precisionDecimals)}"
            } else {
                "AR Move Dot reset to Axis (0, 0, 0)"
            }
            state.copy(arTrackingDot = dot, statusBanner = banner)
        }
    }

    fun setAngleInputMode(mode: AngleInputMode) {
        clearInProgressPickHistory(clearAnglePicks = true)
        _viewerState.update {
            it.copy(
                angleInputMode = mode,
                activeAnglePicks = emptyList(),
                activeAngleDotDrag = null,
                pendingAngleEdge = null,
                pendingAngleFace = null,
                statusBanner = when (mode) {
                    AngleInputMode.THREE_POINT -> "Pick arm point → apex → second arm point"
                    AngleInputMode.TWO_EDGE -> "Tap two edges to measure angle"
                    AngleInputMode.TWO_FACE -> "Tap two faces to measure dihedral angle"
                }
            ).withUndoAvailability()
        }
    }

    fun setDisplayStyle(style: DisplayStyle) {
        _viewerState.update { it.copy(displayStyle = style) }
    }

    fun toggleOrthographic(camera: CadCamera) {
        val next = !camera.isOrthographic
        camera.isOrthographic = next
        camera.refreshMatrices()
        _viewerState.update { it.copy(isOrthographic = next, cameraRenderSignal = it.cameraRenderSignal + 1L) }
    }

    fun applyStandardView(view: StandardView, camera: CadCamera) {
        val doc = activeDocument.value ?: return
        camera.applyStandardView(view, doc.bounds)
        _viewerState.update {
            it.copy(
                statusBanner = "${view.label} View",
                cameraRenderSignal = it.cameraRenderSignal + 1L
            )
        }
    }

    fun applySavedView(view: CadSavedView, camera: CadCamera) {
        camera.applySavedView(view)
        _viewerState.update {
            it.copy(
                isOrthographic = view.parallel,
                hiddenLayers = view.hiddenLayers,
                statusBanner = "Scene: ${view.name}",
                cameraRenderSignal = it.cameraRenderSignal + 1L
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
        val currentTool = _viewerState.value.activeTool
        when (currentTool) {
            CadTool.MEASURE_LENGTH -> {
                val action = tapeUndoStack.removeLastOrNull() ?: return
                tapeRedoStack.addLast(action)
                val prefs = userPreferences.value
                when (action) {
                    is TapeUndoAction.PickAdded -> {
                        _viewerState.update { state ->
                            state.copy(
                                activeLengthPicks = state.activeLengthPicks.dropLast(1),
                                statusBanner = "Undid point pick"
                            ).withUndoAvailability()
                        }
                    }
                    is TapeUndoAction.ActivePickEdited -> {
                        _viewerState.update { state ->
                            val updatedPicks = state.activeLengthPicks.mapIndexed { idx, pt ->
                                if (idx == action.index) action.before else pt
                            }
                            state.copy(
                                activeLengthPicks = updatedPicks,
                                statusBanner = "Undid point adjustment"
                            ).withUndoAvailability()
                        }
                    }
                    is TapeUndoAction.LengthAdded -> {
                        val ref = action.ref
                        viewModelScope.launch {
                            ref.insertJob?.join()
                            val id = ref.entity.id
                            if (id > 0L) repository.deleteLength(id)
                        }
                        _viewerState.update { state ->
                            state.copy(
                                statusBanner = "Undid ${ref.entity.label}",
                                activeLengthPicks = emptyList()
                            ).withUndoAvailability()
                        }
                    }
                    is TapeUndoAction.LengthEdited -> {
                        viewModelScope.launch {
                            repository.updateLength(action.before)
                        }
                        _viewerState.update { state ->
                            val formatted = prefs.unitSystem.formatMeters(action.before.lengthMeters, prefs.precisionDecimals)
                            state.copy(
                                statusBanner = "Restored ${action.before.label}: $formatted"
                            ).withUndoAvailability()
                        }
                    }
                    is TapeUndoAction.LengthDeleted -> {
                        viewModelScope.launch {
                            repository.addLength(action.entity)
                        }
                        val formatted = prefs.unitSystem.formatMeters(action.entity.lengthMeters, prefs.precisionDecimals)
                        _viewerState.update { state ->
                            state.copy(
                                statusBanner = "Restored ${action.entity.label}: $formatted"
                            ).withUndoAvailability()
                        }
                    }
                }
            }
            CadTool.MEASURE_ANGLE -> {
                val action = angleUndoStack.removeLastOrNull() ?: return
                angleRedoStack.addLast(action)
                when (action) {
                    is AngleUndoAction.ThreePointPickAdded -> {
                        _viewerState.update { state ->
                            state.copy(
                                activeAnglePicks = state.activeAnglePicks.dropLast(1),
                                statusBanner = "Undid angle point"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.ActivePickEdited -> {
                        _viewerState.update { state ->
                            val updatedPicks = state.activeAnglePicks.mapIndexed { idx, pt ->
                                if (idx == action.index) action.before else pt
                            }
                            state.copy(
                                activeAnglePicks = updatedPicks,
                                statusBanner = "Undid angle point adjustment"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.PendingEdgeSelected -> {
                        _viewerState.update { state ->
                            state.copy(
                                pendingAngleEdge = null,
                                selectedEdgeIds = emptySet(),
                                statusBanner = "Undid edge selection"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.PendingFaceSelected -> {
                        _viewerState.update { state ->
                            state.copy(
                                pendingAngleFace = null,
                                selectedFaceIds = emptySet(),
                                statusBanner = "Undid face selection"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.AngleAdded -> {
                        val ref = action.ref
                        viewModelScope.launch {
                            ref.insertJob?.join()
                            val id = ref.entity.id
                            if (id > 0L) repository.deleteAngle(id)
                        }
                        _viewerState.update { state ->
                            state.copy(
                                activeAnglePicks = emptyList(),
                                pendingAngleEdge = null,
                                pendingAngleFace = null,
                                statusBanner = "Undid ${ref.entity.label}"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.AngleEdited -> {
                        viewModelScope.launch {
                            repository.updateAngle(action.before)
                        }
                        _viewerState.update { state ->
                            state.copy(
                                statusBanner = String.format(Locale.US, "Restored %s: %.2f°", action.before.label, action.before.angleDegrees)
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.AngleDeleted -> {
                        viewModelScope.launch {
                            repository.addAngle(action.entity)
                        }
                        _viewerState.update { state ->
                            state.copy(
                                statusBanner = String.format(Locale.US, "Restored %s: %.2f°", action.entity.label, action.entity.angleDegrees)
                            ).withUndoAvailability()
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    fun redoActivePick() {
        val currentTool = _viewerState.value.activeTool
        when (currentTool) {
            CadTool.MEASURE_LENGTH -> {
                val action = tapeRedoStack.removeLastOrNull() ?: return
                tapeUndoStack.addLast(action)
                val prefs = userPreferences.value
                when (action) {
                    is TapeUndoAction.PickAdded -> {
                        _viewerState.update { state ->
                            state.copy(
                                activeLengthPicks = state.activeLengthPicks + action.point,
                                statusBanner = "Redid start point · Tap end point"
                            ).withUndoAvailability()
                        }
                    }
                    is TapeUndoAction.ActivePickEdited -> {
                        _viewerState.update { state ->
                            val updatedPicks = state.activeLengthPicks.mapIndexed { idx, pt ->
                                if (idx == action.index) action.after else pt
                            }
                            state.copy(
                                activeLengthPicks = updatedPicks,
                                statusBanner = "Redid point adjustment"
                            ).withUndoAvailability()
                        }
                    }
                    is TapeUndoAction.LengthAdded -> {
                        val ref = action.ref
                        ref.insertJob = viewModelScope.launch {
                            val newId = repository.addLength(ref.entity)
                            ref.entity = ref.entity.copy(id = newId)
                        }
                        val formatted = prefs.unitSystem.formatMeters(ref.entity.lengthMeters, prefs.precisionDecimals)
                        _viewerState.update { state ->
                            state.copy(
                                activeLengthPicks = emptyList(),
                                statusBanner = "Redid ${ref.entity.label}: $formatted"
                            ).withUndoAvailability()
                        }
                    }
                    is TapeUndoAction.LengthEdited -> {
                        viewModelScope.launch {
                            repository.updateLength(action.after)
                        }
                        val formatted = prefs.unitSystem.formatMeters(action.after.lengthMeters, prefs.precisionDecimals)
                        _viewerState.update { state ->
                            state.copy(
                                statusBanner = "Redid ${action.after.label}: $formatted"
                            ).withUndoAvailability()
                        }
                    }
                    is TapeUndoAction.LengthDeleted -> {
                        viewModelScope.launch {
                            repository.deleteLength(action.entity.id)
                        }
                        _viewerState.update { state ->
                            state.copy(
                                statusBanner = "Deleted ${action.entity.label}"
                            ).withUndoAvailability()
                        }
                    }
                }
            }
            CadTool.MEASURE_ANGLE -> {
                val action = angleRedoStack.removeLastOrNull() ?: return
                angleUndoStack.addLast(action)
                when (action) {
                    is AngleUndoAction.ThreePointPickAdded -> {
                        _viewerState.update { state ->
                            val next = state.activeAnglePicks + action.point
                            state.copy(
                                activeAnglePicks = next,
                                statusBanner = if (next.size == 1) "Tap apex (vertex) point" else "Tap second arm point"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.ActivePickEdited -> {
                        _viewerState.update { state ->
                            val updatedPicks = state.activeAnglePicks.mapIndexed { idx, pt ->
                                if (idx == action.index) action.after else pt
                            }
                            state.copy(
                                activeAnglePicks = updatedPicks,
                                statusBanner = "Redid angle point adjustment"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.PendingEdgeSelected -> {
                        _viewerState.update { state ->
                            state.copy(
                                pendingAngleEdge = action.edge,
                                selectedEdgeIds = setOf(action.edge.id),
                                statusBanner = "Edge 1 selected · Tap second edge"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.PendingFaceSelected -> {
                        _viewerState.update { state ->
                            state.copy(
                                pendingAngleFace = action.face,
                                selectedFaceIds = setOf(action.face.id),
                                statusBanner = "Face 1 selected · Tap second face"
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.AngleAdded -> {
                        val ref = action.ref
                        ref.insertJob = viewModelScope.launch {
                            val newId = repository.addAngle(ref.entity)
                            ref.entity = ref.entity.copy(id = newId)
                        }
                        _viewerState.update { state ->
                            state.copy(
                                activeAnglePicks = emptyList(),
                                pendingAngleEdge = null,
                                pendingAngleFace = null,
                                statusBanner = String.format(Locale.US, "Redid %s: %.2f°", ref.entity.label, ref.entity.angleDegrees)
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.AngleEdited -> {
                        viewModelScope.launch {
                            repository.updateAngle(action.after)
                        }
                        _viewerState.update { state ->
                            state.copy(
                                statusBanner = String.format(Locale.US, "Redid %s: %.2f°", action.after.label, action.after.angleDegrees)
                            ).withUndoAvailability()
                        }
                    }
                    is AngleUndoAction.AngleDeleted -> {
                        viewModelScope.launch {
                            repository.deleteAngle(action.entity.id)
                        }
                        _viewerState.update { state ->
                            state.copy(
                                statusBanner = "Deleted ${action.entity.label}"
                            ).withUndoAvailability()
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    fun clearActivePicks() {
        clearInProgressPickHistory(clearTapePicks = true, clearAnglePicks = true)
        _viewerState.update {
            it.copy(
                activeLengthPicks = emptyList(),
                activeAnglePicks = emptyList(),
                pendingAngleEdge = null,
                pendingAngleFace = null,
                activeSnap = null,
                activeLengthDotDrag = null,
                activeAngleDotDrag = null
            ).withUndoAvailability()
        }
    }

    fun beginLengthDotDrag(x: Float, y: Float, camera: CadCamera, hitRadiusPx: Double): Boolean {
        val state = _viewerState.value
        val hit = LengthDotDragMath.findHitLengthDot(
            screenX = x,
            screenY = y,
            savedLengths = lengths.value,
            activeLengthPicks = state.activeLengthPicks,
            projectToScreen = camera::projectToScreen,
            hitRadiusPx = hitRadiusPx
        ) ?: return false

        val drag = ActiveLengthDotDrag(
            target = hit.target,
            originalPoint = hit.point,
            currentPoint = hit.point
        )
        val banner = when (val t = hit.target) {
            is LengthDotTarget.SavedEndpoint -> {
                val label = lengths.value.find { it.id == t.lengthId }?.label ?: "Measurement"
                "Dragging $label dot · Drop on corner, edge, or face"
            }
            is LengthDotTarget.ActivePick -> "Dragging start dot · Release to reposition"
        }
        _viewerState.update {
            it.copy(
                activeLengthDotDrag = drag,
                activeSnap = null,
                statusBanner = banner
            )
        }
        return true
    }

    fun updateLengthDotDrag(x: Float, y: Float, camera: CadCamera) {
        val doc = activeDocument.value ?: return
        val state = _viewerState.value
        val drag = state.activeLengthDotDrag ?: return

        val resolved = LengthDotDragMath.resolveDraggedPoint(
            doc = doc,
            hiddenLayers = state.hiddenLayers,
            hiddenNodes = state.hiddenNodes,
            screenX = x,
            screenY = y,
            viewportWidth = camera.viewportWidth,
            viewportHeight = camera.viewportHeight,
            projectToScreen = camera::projectToScreen,
            rayFromScreen = camera::rayFromScreen,
            cameraForward = camera.forwardVector(),
            planeOrigin = drag.originalPoint,
            explodeOffsets = state.explodeOffsets
        ) ?: return

        val (newPoint, snap) = resolved
        val updatedDrag = drag.copy(currentPoint = newPoint, snapType = snap?.type)
        val prefs = userPreferences.value
        val snapSuffix = snap?.let { "Snap: ${it.type.label}" } ?: "Free move"
        val banner = when (val t = drag.target) {
            is LengthDotTarget.SavedEndpoint -> {
                val saved = lengths.value.find { it.id == t.lengthId }
                if (saved != null) {
                    val a = if (t.isStartA) newPoint else Vec3(saved.ax, saved.ay, saved.az)
                    val b = if (t.isStartA) Vec3(saved.bx, saved.by, saved.bz) else newPoint
                    val formatted = prefs.unitSystem.formatMeters(a.distanceTo(b), prefs.precisionDecimals)
                    "${saved.label}: $formatted · $snapSuffix"
                } else {
                    snapSuffix
                }
            }
            is LengthDotTarget.ActivePick -> "Start dot · $snapSuffix"
        }
        _viewerState.update {
            it.copy(
                activeLengthDotDrag = updatedDrag,
                activeSnap = snap,
                statusBanner = banner
            )
        }
    }

    fun endLengthDotDrag(cancelled: Boolean) {
        val state = _viewerState.value
        val drag = state.activeLengthDotDrag ?: return
        if (cancelled) {
            _viewerState.update {
                it.copy(
                    activeLengthDotDrag = null,
                    activeSnap = null,
                    statusBanner = "Edit cancelled"
                )
            }
            return
        }

        val prefs = userPreferences.value
        val snapSuffix = drag.snapType?.let { " (${it.label})" } ?: ""
        when (val t = drag.target) {
            is LengthDotTarget.SavedEndpoint -> {
                val saved = lengths.value.find { it.id == t.lengthId }
                if (saved != null && drag.currentPoint != drag.originalPoint) {
                    val p = drag.currentPoint
                    val a = if (t.isStartA) p else Vec3(saved.ax, saved.ay, saved.az)
                    val b = if (t.isStartA) Vec3(saved.bx, saved.by, saved.bz) else p
                    val newDist = a.distanceTo(b)
                    val updated = saved.copy(
                        ax = a.x, ay = a.y, az = a.z,
                        bx = b.x, by = b.y, bz = b.z,
                        lengthMeters = newDist
                    )
                    tapeUndoStack.addLast(TapeUndoAction.LengthEdited(before = saved, after = updated))
                    tapeRedoStack.clear()
                    viewModelScope.launch {
                        repository.updateLength(updated)
                    }
                    _viewerState.update {
                        it.copy(
                            activeLengthDotDrag = null,
                            activeSnap = null,
                            statusBanner = "Updated ${saved.label}: ${prefs.unitSystem.formatMeters(newDist, prefs.precisionDecimals)}$snapSuffix"
                        ).withUndoAvailability()
                    }
                } else {
                    _viewerState.update { it.copy(activeLengthDotDrag = null, activeSnap = null) }
                }
            }
            is LengthDotTarget.ActivePick -> {
                val prevPoint = state.activeLengthPicks.getOrNull(t.index)
                val updatedPicks = state.activeLengthPicks.mapIndexed { idx, pt ->
                    if (idx == t.index) drag.currentPoint else pt
                }
                if (prevPoint != null && prevPoint != drag.currentPoint) {
                    tapeUndoStack.addLast(
                        TapeUndoAction.ActivePickEdited(
                            index = t.index,
                            before = prevPoint,
                            after = drag.currentPoint
                        )
                    )
                    tapeRedoStack.clear()
                }
                _viewerState.update {
                    it.copy(
                        activeLengthPicks = updatedPicks,
                        activeLengthDotDrag = null,
                        activeSnap = null,
                        statusBanner = if (prevPoint != null && prevPoint != drag.currentPoint) {
                            "Moved start dot$snapSuffix · Tap end point"
                        } else {
                            it.statusBanner
                        }
                    ).withUndoAvailability()
                }
            }
        }
    }

    fun beginAngleDotDrag(x: Float, y: Float, camera: CadCamera, hitRadiusPx: Double): Boolean {
        val state = _viewerState.value
        val hit = AngleDotDragMath.findHitAngleDot(
            screenX = x,
            screenY = y,
            savedAngles = angles.value,
            activeAnglePicks = state.activeAnglePicks,
            projectToScreen = camera::projectToScreen,
            hitRadiusPx = hitRadiusPx
        ) ?: return false

        val drag = ActiveAngleDotDrag(
            target = hit.target,
            originalPoint = hit.point,
            currentPoint = hit.point
        )
        val banner = when (val t = hit.target) {
            is AngleDotTarget.SavedPoint -> {
                val label = angles.value.find { it.id == t.angleId }?.label ?: "Angle"
                val roleName = when (t.role) {
                    AnglePointRole.VERTEX -> "vertex"
                    AnglePointRole.RAY_A -> "arm 1"
                    AnglePointRole.RAY_B -> "arm 2"
                }
                "Dragging $label $roleName dot · Drop on corner, edge, or face"
            }
            is AngleDotTarget.ActivePick -> "Dragging angle dot · Release to reposition"
        }
        _viewerState.update {
            it.copy(
                activeAngleDotDrag = drag,
                activeSnap = null,
                statusBanner = banner
            )
        }
        return true
    }

    fun updateAngleDotDrag(x: Float, y: Float, camera: CadCamera) {
        val doc = activeDocument.value ?: return
        val state = _viewerState.value
        val drag = state.activeAngleDotDrag ?: return

        val resolved = LengthDotDragMath.resolveDraggedPoint(
            doc = doc,
            hiddenLayers = state.hiddenLayers,
            hiddenNodes = state.hiddenNodes,
            screenX = x,
            screenY = y,
            viewportWidth = camera.viewportWidth,
            viewportHeight = camera.viewportHeight,
            projectToScreen = camera::projectToScreen,
            rayFromScreen = camera::rayFromScreen,
            cameraForward = camera.forwardVector(),
            planeOrigin = drag.originalPoint,
            explodeOffsets = state.explodeOffsets
        ) ?: return

        val (newPoint, snap) = resolved
        val updatedDrag = drag.copy(currentPoint = newPoint, snapType = snap?.type)
        val snapSuffix = snap?.let { "Snap: ${it.type.label}" } ?: "Free move"
        val banner = when (val t = drag.target) {
            is AngleDotTarget.SavedPoint -> {
                val saved = angles.value.find { it.id == t.angleId }
                if (saved != null) {
                    val vertex = if (t.role == AnglePointRole.VERTEX) newPoint else Vec3(saved.vx, saved.vy, saved.vz)
                    val rayA = if (t.role == AnglePointRole.RAY_A) newPoint else Vec3(saved.ax, saved.ay, saved.az)
                    val rayB = if (t.role == AnglePointRole.RAY_B) newPoint else Vec3(saved.bx, saved.by, saved.bz)
                    val comp = AngleMath.computeThreePointAngle(rayA, vertex, rayB)
                    String.format(Locale.US, "%s: %.2f° · %s", saved.label, comp.degrees, snapSuffix)
                } else {
                    snapSuffix
                }
            }
            is AngleDotTarget.ActivePick -> "Angle dot · $snapSuffix"
        }
        _viewerState.update {
            it.copy(
                activeAngleDotDrag = updatedDrag,
                activeSnap = snap,
                statusBanner = banner
            )
        }
    }

    fun endAngleDotDrag(cancelled: Boolean) {
        val state = _viewerState.value
        val drag = state.activeAngleDotDrag ?: return
        if (cancelled) {
            _viewerState.update {
                it.copy(
                    activeAngleDotDrag = null,
                    activeSnap = null,
                    statusBanner = "Edit cancelled"
                )
            }
            return
        }

        val snapSuffix = drag.snapType?.let { " (${it.label})" } ?: ""
        when (val t = drag.target) {
            is AngleDotTarget.SavedPoint -> {
                val saved = angles.value.find { it.id == t.angleId }
                if (saved != null && drag.currentPoint != drag.originalPoint) {
                    val p = drag.currentPoint
                    val vertex = if (t.role == AnglePointRole.VERTEX) p else Vec3(saved.vx, saved.vy, saved.vz)
                    val rayA = if (t.role == AnglePointRole.RAY_A) p else Vec3(saved.ax, saved.ay, saved.az)
                    val rayB = if (t.role == AnglePointRole.RAY_B) p else Vec3(saved.bx, saved.by, saved.bz)
                    val comp = AngleMath.computeThreePointAngle(rayA, vertex, rayB)
                    val updated = saved.copy(
                        vx = vertex.x, vy = vertex.y, vz = vertex.z,
                        ax = rayA.x, ay = rayA.y, az = rayA.z,
                        bx = rayB.x, by = rayB.y, bz = rayB.z,
                        angleDegrees = comp.degrees
                    )
                    angleUndoStack.addLast(AngleUndoAction.AngleEdited(before = saved, after = updated))
                    angleRedoStack.clear()
                    viewModelScope.launch {
                        repository.updateAngle(updated)
                    }
                    _viewerState.update {
                        it.copy(
                            activeAngleDotDrag = null,
                            activeSnap = null,
                            statusBanner = String.format(Locale.US, "Updated %s: %.2f°%s", saved.label, comp.degrees, snapSuffix)
                        ).withUndoAvailability()
                    }
                } else {
                    _viewerState.update { it.copy(activeAngleDotDrag = null, activeSnap = null) }
                }
            }
            is AngleDotTarget.ActivePick -> {
                val prevPoint = state.activeAnglePicks.getOrNull(t.index)
                val updatedPicks = state.activeAnglePicks.mapIndexed { idx, pt ->
                    if (idx == t.index) drag.currentPoint else pt
                }
                if (prevPoint != null && prevPoint != drag.currentPoint) {
                    angleUndoStack.addLast(
                        AngleUndoAction.ActivePickEdited(
                            index = t.index,
                            before = prevPoint,
                            after = drag.currentPoint
                        )
                    )
                    angleRedoStack.clear()
                }
                _viewerState.update {
                    it.copy(
                        activeAnglePicks = updatedPicks,
                        activeAngleDotDrag = null,
                        activeSnap = null,
                        statusBanner = if (prevPoint != null && prevPoint != drag.currentPoint) {
                            "Moved angle dot$snapSuffix"
                        } else {
                            it.statusBanner
                        }
                    ).withUndoAvailability()
                }
            }
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
                    val entity = LengthMeasurementEntity(
                        documentKey = doc.documentKey,
                        label = label,
                        ax = a.x, ay = a.y, az = a.z,
                        bx = b.x, by = b.y, bz = b.z,
                        lengthMeters = dist
                    )
                    clearInProgressPickHistory(clearTapePicks = true)
                    val ref = SavedLengthRef(entity)
                    ref.insertJob = viewModelScope.launch {
                        val id = repository.addLength(entity)
                        ref.entity = entity.copy(id = id)
                    }
                    tapeUndoStack.addLast(TapeUndoAction.LengthAdded(ref))
                    tapeRedoStack.clear()
                    _viewerState.update {
                        it.copy(
                            activeLengthPicks = emptyList(),
                            activeSnap = snap,
                            statusBanner = "Saved $label: ${prefs.unitSystem.formatMeters(dist, prefs.precisionDecimals)}"
                        ).withUndoAvailability()
                    }
                } else {
                    tapeUndoStack.addLast(TapeUndoAction.PickAdded(snap.point))
                    tapeRedoStack.clear()
                    _viewerState.update {
                        it.copy(
                            activeLengthPicks = picks,
                            activeSnap = snap,
                            statusBanner = "Snapped start (${snap.type.label}) · Tap end point"
                        ).withUndoAvailability()
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

            CadTool.AR_TRACKING_DOT -> {
                val snap = SnapEngine.findBestSnap(
                    doc, state.hiddenLayers, state.hiddenNodes, x, y,
                    camera.viewportWidth, camera.viewportHeight,
                    camera::projectToScreen, camera::rayFromScreen,
                    explodeOffsets = state.explodeOffsets
                )
                if (snap == null) {
                    _viewerState.update { it.copy(statusBanner = "Tap a vertex, midpoint, edge, or face to place AR Move Dot") }
                    return
                }
                val rawPoint = hitPointWithoutExplode(doc, snap)
                val newDot = ArModelTrackingDot(
                    point = rawPoint,
                    snapType = snap.type,
                    label = snap.type.label
                )
                val prefs = userPreferences.value
                val coords = ArTrackingDotMath.formatDotCoordinates(rawPoint, prefs.unitSystem, prefs.precisionDecimals)
                _viewerState.update {
                    it.copy(
                        arTrackingDot = newDot,
                        activeSnap = snap,
                        statusBanner = "AR Move Dot placed on ${snap.type.label} $coords · Tap AR to place model"
                    )
                }
            }

            CadTool.ORBIT, CadTool.PAN -> Unit // Navigation-only modes do not select geometry on tap
        }
    }

    private fun hitPointWithoutExplode(doc: CadDocument, snap: SnapHit): Vec3 {
        val nodeId = snap.edge?.nodeId ?: snap.face?.nodeId
        val offset = if (nodeId != null) (_viewerState.value.explodeOffsets[nodeId] ?: Vec3.ZERO) else Vec3.ZERO
        return snap.point - offset
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
                    val entity = AngleMeasurementEntity(
                        documentKey = doc.documentKey,
                        label = label,
                        vx = comp.vertex.x, vy = comp.vertex.y, vz = comp.vertex.z,
                        ax = comp.rayAEnd.x, ay = comp.rayAEnd.y, az = comp.rayAEnd.z,
                        bx = comp.rayBEnd.x, by = comp.rayBEnd.y, bz = comp.rayBEnd.z,
                        angleDegrees = comp.degrees,
                        mode = "3-Point"
                    )
                    clearInProgressPickHistory(clearAnglePicks = true)
                    val ref = SavedAngleRef(entity)
                    ref.insertJob = viewModelScope.launch {
                        val id = repository.addAngle(entity)
                        ref.entity = entity.copy(id = id)
                    }
                    angleUndoStack.addLast(AngleUndoAction.AngleAdded(ref))
                    angleRedoStack.clear()
                    _viewerState.update {
                        it.copy(
                            activeAnglePicks = emptyList(),
                            activeSnap = snap,
                            statusBanner = String.format(Locale.US, "Saved %s: %.2f°", label, comp.degrees)
                        ).withUndoAvailability()
                    }
                } else {
                    angleUndoStack.addLast(AngleUndoAction.ThreePointPickAdded(snap.point))
                    angleRedoStack.clear()
                    val prompt = if (picks.size == 1) "Tap apex (vertex) point" else "Tap second arm point"
                    _viewerState.update {
                        it.copy(
                            activeAnglePicks = picks,
                            activeSnap = snap,
                            statusBanner = prompt
                        ).withUndoAvailability()
                    }
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
                    angleUndoStack.addLast(AngleUndoAction.PendingEdgeSelected(edge))
                    angleRedoStack.clear()
                    _viewerState.update {
                        it.copy(
                            pendingAngleEdge = edge,
                            selectedEdgeIds = setOf(edge.id),
                            statusBanner = "Edge 1 selected · Tap second edge"
                        ).withUndoAvailability()
                    }
                } else if (first.id != edge.id) {
                    val comp = AngleMath.computeTwoEdgeAngle(first, edge)
                    val label = "A${angles.value.size + 1}"
                    val entity = AngleMeasurementEntity(
                        documentKey = doc.documentKey,
                        label = label,
                        vx = comp.vertex.x, vy = comp.vertex.y, vz = comp.vertex.z,
                        ax = comp.rayAEnd.x, ay = comp.rayAEnd.y, az = comp.rayAEnd.z,
                        bx = comp.rayBEnd.x, by = comp.rayBEnd.y, bz = comp.rayBEnd.z,
                        angleDegrees = comp.degrees,
                        mode = "2-Edge"
                    )
                    clearInProgressPickHistory(clearAnglePicks = true)
                    val ref = SavedAngleRef(entity)
                    ref.insertJob = viewModelScope.launch {
                        val id = repository.addAngle(entity)
                        ref.entity = entity.copy(id = id)
                    }
                    angleUndoStack.addLast(AngleUndoAction.AngleAdded(ref))
                    angleRedoStack.clear()
                    _viewerState.update {
                        it.copy(
                            pendingAngleEdge = null,
                            selectedEdgeIds = setOf(first.id, edge.id),
                            statusBanner = String.format(Locale.US, "Saved %s (2-Edge): %.2f°", label, comp.degrees)
                        ).withUndoAvailability()
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
                    angleUndoStack.addLast(AngleUndoAction.PendingFaceSelected(face))
                    angleRedoStack.clear()
                    _viewerState.update {
                        it.copy(
                            pendingAngleFace = face,
                            selectedFaceIds = setOf(face.id),
                            statusBanner = "Face 1 selected · Tap second face"
                        ).withUndoAvailability()
                    }
                } else if (first.id != face.id) {
                    val comp = AngleMath.computeTwoFaceDihedralAngle(first, face)
                    val label = "A${angles.value.size + 1}"
                    val entity = AngleMeasurementEntity(
                        documentKey = doc.documentKey,
                        label = label,
                        vx = comp.vertex.x, vy = comp.vertex.y, vz = comp.vertex.z,
                        ax = comp.rayAEnd.x, ay = comp.rayAEnd.y, az = comp.rayAEnd.z,
                        bx = comp.rayBEnd.x, by = comp.rayBEnd.y, bz = comp.rayBEnd.z,
                        angleDegrees = comp.degrees,
                        mode = "2-Face"
                    )
                    clearInProgressPickHistory(clearAnglePicks = true)
                    val ref = SavedAngleRef(entity)
                    ref.insertJob = viewModelScope.launch {
                        val id = repository.addAngle(entity)
                        ref.entity = entity.copy(id = id)
                    }
                    angleUndoStack.addLast(AngleUndoAction.AngleAdded(ref))
                    angleRedoStack.clear()
                    _viewerState.update {
                        it.copy(
                            pendingAngleFace = null,
                            selectedFaceIds = setOf(first.id, face.id),
                            statusBanner = String.format(Locale.US, "Saved %s (Dihedral): %.2f°", label, comp.degrees)
                        ).withUndoAvailability()
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
        val existing = lengths.value.find { it.id == id }
        if (existing != null) {
            tapeUndoStack.addLast(TapeUndoAction.LengthDeleted(existing))
            tapeRedoStack.clear()
            _viewerState.update {
                it.copy(statusBanner = "Deleted ${existing.label}").withUndoAvailability()
            }
        }
        viewModelScope.launch { repository.deleteLength(id) }
    }

    fun renameAngle(item: AngleMeasurementEntity, newLabel: String) {
        viewModelScope.launch { repository.updateAngleLabel(item, newLabel) }
    }

    fun deleteAngle(id: Long) {
        val existing = angles.value.find { it.id == id }
        if (existing != null) {
            angleUndoStack.addLast(AngleUndoAction.AngleDeleted(existing))
            angleRedoStack.clear()
            _viewerState.update {
                it.copy(statusBanner = "Deleted ${existing.label}").withUndoAvailability()
            }
        }
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
        camera.refreshMatrices()
        _viewerState.update {
            it.copy(
                statusBanner = "Focused on measurement",
                cameraRenderSignal = it.cameraRenderSignal + 1L
            )
        }
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
        val current = _cutListState.value
        val entry = current.entries.find { it.groupId == groupId } ?: return
        val wasChecked = groupId in current.checkedGroupIds
        val nextChecked = if (wasChecked) {
            current.checkedGroupIds - groupId
        } else {
            current.checkedGroupIds + groupId
        }
        val nextFocusedId = if (wasChecked) {
            nextChecked.lastOrNull()
        } else {
            groupId
        }
        _cutListState.update {
            it.copy(
                selectedGroupId = nextFocusedId,
                isExportSelectionMode = nextChecked.isNotEmpty(),
                checkedGroupIds = nextChecked,
                errorMessage = null
            )
        }
        syncSelectionHighlight(nextChecked)

        if (wasChecked) {
            if (nextChecked.isEmpty()) {
                _viewerState.update { it.copy(statusBanner = null) }
            }
            return
        }

        _viewerState.update {
            it.copy(statusBanner = "${entry.partName} (×${entry.quantity})")
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
                camera.refreshMatrices()
                _viewerState.update { it.copy(cameraRenderSignal = it.cameraRenderSignal + 1L) }
                delay(16L)
            }
        }
    }

    fun onLongPressCutListRow(groupId: String) {
        onToggleCutListCheck(groupId)
    }

    fun onToggleCutListCheck(groupId: String) {
        val current = _cutListState.value
        if (current.entries.none { it.groupId == groupId }) return
        val nextChecked = if (groupId in current.checkedGroupIds) {
            current.checkedGroupIds - groupId
        } else {
            current.checkedGroupIds + groupId
        }
        _cutListState.update {
            it.copy(
                selectedGroupId = if (groupId in nextChecked) groupId else nextChecked.lastOrNull(),
                isExportSelectionMode = nextChecked.isNotEmpty(),
                checkedGroupIds = nextChecked,
                errorMessage = null
            )
        }
        syncSelectionHighlight(nextChecked)
        if (nextChecked.isEmpty()) {
            _viewerState.update { it.copy(statusBanner = null) }
        }
    }

    fun onToggleSelectAllCutList() {
        val current = _cutListState.value
        val allIds = current.entries.map { it.groupId }.toSet()
        val nextChecked = if (current.checkedGroupIds.size == allIds.size && allIds.isNotEmpty()) {
            emptySet()
        } else {
            allIds
        }
        _cutListState.update {
            it.copy(
                selectedGroupId = nextChecked.firstOrNull(),
                isExportSelectionMode = nextChecked.isNotEmpty(),
                checkedGroupIds = nextChecked,
                errorMessage = null
            )
        }
        syncSelectionHighlight(nextChecked)
        if (nextChecked.isEmpty()) {
            _viewerState.update { it.copy(statusBanner = null) }
        }
    }

    fun exitCutListSelectionMode() {
        _cutListState.update {
            it.copy(
                selectedGroupId = null,
                isExportSelectionMode = false,
                checkedGroupIds = emptySet(),
                errorMessage = null
            )
        }
        _viewerState.update {
            it.copy(
                highlightedCutNodeIds = emptySet(),
                statusBanner = null
            )
        }
    }

    private fun syncSelectionHighlight(checkedGroupIds: Set<String>) {
        val checkedNodeIds = _cutListState.value.entries
            .filter { it.groupId in checkedGroupIds }
            .flatMapTo(LinkedHashSet()) { it.memberNodeIds }
        _viewerState.update {
            it.copy(highlightedCutNodeIds = checkedNodeIds)
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
        captureSnapshot: suspend (isolatedNodeIds: Set<String>?) -> Bitmap?
    ) {
        val doc = activeDocument.value ?: return
        val currentState = _cutListState.value
        if (currentState.isExportingPdf) return
        val checkedIdsSnapshot = currentState.checkedGroupIds
        if (checkedIdsSnapshot.isEmpty()) {
            _cutListState.update { it.copy(errorMessage = "Select at least one part to export.") }
            return
        }

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
                val (entriesToExport, totalPiecesToExport) = CutListExportFilter.resolveEntriesForExport(
                    allEntries = extraction.entries,
                    isSelectionMode = true,
                    checkedGroupIds = checkedIdsSnapshot
                )
                if (entriesToExport.isEmpty()) {
                    _cutListState.update {
                        it.copy(
                            isExportingPdf = false,
                            errorMessage = "Select at least one part to export."
                        )
                    }
                    return@launch
                }
                val selectedNodeIds = CutListExportFilter.collectSelectedNodeIds(entriesToExport)
                val explodeOffsets = _viewerState.value.explodeOffsets
                val selectedBounds = CutListExportFilter.computeSelectedBounds(entriesToExport, explodeOffsets)
                    .takeIf { it.isValid } ?: doc.bounds
                snapshot = withContext(Dispatchers.Main) { captureSnapshot(selectedNodeIds) }
                val prefs = userPreferences.value
                val explodeFrac = _cutListState.value.explodeFraction
                val result = withContext(Dispatchers.IO) {
                    CutListPdfExporter.exportToDownloadsAndShareCache(
                        context = context,
                        projectTitle = doc.title,
                        entries = entriesToExport,
                        totalPieces = totalPiecesToExport,
                        modelBounds = selectedBounds,
                        unitSystem = prefs.unitSystem,
                        precisionDecimals = prefs.precisionDecimals,
                        explodeFraction = explodeFrac,
                        viewportSnapshot = snapshot,
                        selectedPartsOnly = true
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
