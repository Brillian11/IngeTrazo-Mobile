package com.example.ui.viewer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.local.AngleMeasurementEntity
import com.example.data.local.CountedAngleEntity
import com.example.data.local.LengthMeasurementEntity
import com.example.data.local.UserPreferences
import com.example.domain.math.AngleBucketSummary
import com.example.domain.math.Vec3
import com.example.domain.model.CadDocument
import com.example.domain.model.CadSavedView
import com.example.domain.model.CadTool
import com.example.domain.model.DisplayStyle
import com.example.domain.model.StandardView
import com.example.domain.model.UnitSystem
import com.example.ui.AngleInputMode
import com.example.ui.CutListUiState
import com.example.ui.ViewerUiState
import com.example.ui.components.UnitSettingsDialog
import com.example.ui.theme.CadNumericStyle
import com.example.ui.viewer.gl.CadCamera
import com.example.ui.viewer.gl.CadGlRenderer
import com.example.ui.viewer.gl.CadViewportView
import com.example.ui.viewer.gl.MeasurementContextMenuOverlay
import com.example.ui.viewer.gl.MeasurementContextMenuState
import com.example.ui.viewer.gl.OverlayRenderState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CadViewerScreen(
    document: CadDocument,
    viewerState: ViewerUiState,
    preferences: UserPreferences,
    lengths: List<LengthMeasurementEntity>,
    angles: List<AngleMeasurementEntity>,
    countedAngles: List<CountedAngleEntity>,
    angleBuckets: List<AngleBucketSummary>,
    onBack: () -> Unit,
    onOpenArMode: () -> Unit,
    onSetTool: (CadTool) -> Unit,
    onSetAngleInputMode: (AngleInputMode) -> Unit,
    onSetDisplayStyle: (DisplayStyle) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onToggleOrthographic: (CadCamera) -> Unit,
    onApplyStandardView: (StandardView, CadCamera) -> Unit,
    onApplySavedView: (CadSavedView, CadCamera) -> Unit,
    onTapViewport: (Float, Float, CadCamera) -> Unit,
    onSnapHover: (Float, Float, CadCamera) -> Unit,
    onUndoPick: () -> Unit,
    onRedoPick: () -> Unit = {},
    onClearPicks: () -> Unit,
    onClearSelection: () -> Unit,
    onToggleLayer: (String) -> Unit,
    onToggleNode: (String) -> Unit,
    onSelectNode: (String) -> Unit,
    onShowAllHierarchy: () -> Unit,
    onChangeUnits: (UnitSystem) -> Unit,
    onChangeDecimals: (Int) -> Unit,
    onChangeTolerance: (Double) -> Unit,
    onFocusPoint: (Vec3, CadCamera) -> Unit,
    onRenameLength: (LengthMeasurementEntity, String) -> Unit,
    onDeleteLength: (Long) -> Unit,
    onRenameAngle: (AngleMeasurementEntity, String) -> Unit,
    onDeleteAngle: (Long) -> Unit,
    onMarkAngleIntoCounter: (AngleMeasurementEntity) -> Unit,
    onAutoCountSelection: () -> Unit,
    onUpdateCountedNote: (CountedAngleEntity, String) -> Unit,
    onDeleteCountedAngle: (Long) -> Unit,
    onClearCountedAngles: () -> Unit,
    onExportCsv: () -> Unit,
    onDismissBanner: () -> Unit,
    onToggleLandscape: (currentlyLandscape: Boolean) -> Unit = {},
    cutListState: CutListUiState = CutListUiState(),
    onOpenCutList: () -> Unit = {},
    onSelectCutListRow: (String, CadCamera) -> Unit = { _, _ -> },
    onLongPressCutListRow: (String) -> Unit = {},
    onToggleCutListCheck: (String) -> Unit = {},
    onToggleSelectAllCutList: () -> Unit = {},
    onExitCutListSelectionMode: () -> Unit = {},
    onSetExplodeFraction: (Float) -> Unit = {},
    onExportCutListPdf: (suspend (Set<String>?) -> Bitmap?) -> Unit = {},
    onDismissExportedPdf: () -> Unit = {},
    onBeginLengthDotDrag: (Float, Float, CadCamera, Double) -> Boolean = { _, _, _, _ -> false },
    onUpdateLengthDotDrag: (Float, Float, CadCamera) -> Unit = { _, _, _ -> },
    onEndLengthDotDrag: (Boolean) -> Unit = {},
    onBeginAngleDotDrag: (Float, Float, CadCamera, Double) -> Boolean = { _, _, _, _ -> false },
    onUpdateAngleDotDrag: (Float, Float, CadCamera) -> Unit = { _, _, _ -> },
    onEndAngleDotDrag: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val hitRadiusPx = with(LocalDensity.current) { 28.dp.toPx().toDouble() }
    val camera = remember(document.documentKey) {
        CadCamera().apply { zoomExtents(document.bounds) }
    }
    val renderer = remember(document.documentKey) {
        CadGlRenderer(camera)
    }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showViewsMenu by remember { mutableStateOf(false) }
    var showStyleMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showUnitsDialog by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<ViewerSheetType?>(null) }
    var showCutListSplit by remember { mutableStateOf(false) }
    var tabletSideTab by remember { mutableIntStateOf(1) } // 0 = Hierarchy, 1 = Measure & Count, 2 = Cut List
    var snapshotRequester by remember { mutableStateOf<(suspend (Set<String>?) -> Bitmap?)?>(null) }
    var viewportPaneSize by remember { mutableStateOf(IntSize.Zero) }
    var measurementContextMenu by remember { mutableStateOf<MeasurementContextMenuState?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val bottomToolbarScrollState = rememberScrollState()
    val railScrollState = rememberScrollState()

    LaunchedEffect(viewerState.activeTool, document.documentKey) {
        measurementContextMenu = null
    }

    val overlayState = remember(viewerState, preferences.darkCadMode, lengths, angles) {
        OverlayRenderState(
            selectedFaceIds = viewerState.selectedFaceIds,
            selectedEdgeIds = viewerState.selectedEdgeIds,
            selectedNodeIds = viewerState.selectedNodeIds,
            highlightedCutNodeIds = viewerState.highlightedCutNodeIds,
            explodeOffsets = viewerState.explodeOffsets,
            hiddenLayers = viewerState.hiddenLayers,
            hiddenNodes = viewerState.hiddenNodes,
            displayStyle = viewerState.displayStyle,
            darkCadMode = preferences.darkCadMode,
            activeLengthPicks = viewerState.activeLengthPicks,
            activeAnglePicks = viewerState.activeAnglePicks,
            activeSnap = viewerState.activeSnap,
            activeLengthDotDrag = viewerState.activeLengthDotDrag,
            activeAngleDotDrag = viewerState.activeAngleDotDrag,
            arTrackingDot = null,
            savedLengths = lengths,
            savedAngles = angles
        )
    }

    LaunchedEffect(document.documentKey) {
        camera.zoomExtents(document.bounds)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideTablet = maxWidth >= 840.dp && maxHeight >= 520.dp
        val isLandscape = maxWidth > maxHeight
        val isCompactLandscape = isLandscape && !isWideTablet
        val showPortraitSplitCutList = !isWideTablet && !isCompactLandscape && showCutListSplit
        val showLandscapeSplitCutList = isCompactLandscape && showCutListSplit

        BackHandler {
            when {
                measurementContextMenu != null -> measurementContextMenu = null
                cutListState.isExportSelectionMode -> onExitCutListSelectionMode()
                !isWideTablet && showCutListSplit -> showCutListSplit = false
                else -> onBack()
            }
        }

        LaunchedEffect(isWideTablet, tabletSideTab) {
            if (isWideTablet && tabletSideTab == 2) {
                onOpenCutList()
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .weight(if (showPortraitSplitCutList) 0.48f else 1f)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .weight(if (showLandscapeSplitCutList) 0.56f else 1f)
                        .fillMaxHeight()
                        .onSizeChanged { viewportPaneSize = it }
                ) {
                    CadViewportView(
                        document = document,
                        camera = camera,
                        renderer = renderer,
                        activeTool = viewerState.activeTool,
                        overlayState = overlayState,
                        unitSystem = preferences.unitSystem,
                        decimals = preferences.precisionDecimals,
                        cameraRenderSignal = viewerState.cameraRenderSignal,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("cad_gl_viewport"),
                        onRegisterSnapshotRequester = { snapshotRequester = it },
                        onTapAt = { x, y -> onTapViewport(x, y, camera) },
                        onDoubleTap = { camera.zoomExtents(document.bounds) },
                        onSnapHover = { x, y -> onSnapHover(x, y, camera) },
                        onBeginLengthDotDrag = { x, y -> onBeginLengthDotDrag(x, y, camera, hitRadiusPx) },
                        onUpdateLengthDotDrag = { x, y -> onUpdateLengthDotDrag(x, y, camera) },
                        onEndLengthDotDrag = onEndLengthDotDrag,
                        onBeginAngleDotDrag = { x, y -> onBeginAngleDotDrag(x, y, camera, hitRadiusPx) },
                        onUpdateAngleDotDrag = { x, y -> onUpdateAngleDotDrag(x, y, camera) },
                        onEndAngleDotDrag = onEndAngleDotDrag,
                        onDeleteLength = onDeleteLength,
                        onDeleteAngle = onDeleteAngle,
                        onOpenMeasurementContextMenu = { menu -> measurementContextMenu = menu }
                    )

                // Top Floating CAD HUD
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        )
                        .padding(horizontal = 12.dp, vertical = if (isCompactLandscape) 4.dp else 8.dp)
                        .fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("viewer_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to Hub",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = document.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${document.formatVersion} · ${if (viewerState.isOrthographic) "Ortho" else "Persp"} · ${preferences.unitSystem.symbol}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { showMoreMenu = true },
                                    modifier = Modifier.testTag("viewer_more_menu_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Viewer Menu",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "AR Projection",
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.ViewInAr,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            onOpenArMode()
                                        },
                                        modifier = Modifier.testTag("viewer_ar_button")
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Camera Views…") },
                                        leadingIcon = {
                                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            showViewsMenu = true
                                        },
                                        modifier = Modifier.testTag("viewer_views_button")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Display Style: ${viewerState.displayStyle.label}") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Palette, contentDescription = null)
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            showStyleMenu = true
                                        },
                                        modifier = Modifier.testTag("viewer_style_button")
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (viewerState.isOrthographic) {
                                                    "Projection: Orthographic"
                                                } else {
                                                    "Projection: Perspective"
                                                }
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Architecture,
                                                contentDescription = null,
                                                tint = if (viewerState.isOrthographic) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            onToggleOrthographic(camera)
                                        },
                                        modifier = Modifier.testTag("viewer_ortho_toggle")
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (preferences.darkCadMode) "Light Mode" else "Dark CAD Mode")
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (preferences.darkCadMode) {
                                                    Icons.Default.LightMode
                                                } else {
                                                    Icons.Default.DarkMode
                                                },
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            onToggleTheme(!preferences.darkCadMode)
                                        },
                                        modifier = Modifier.testTag("viewer_theme_toggle")
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (isLandscape) "Switch to Portrait" else "Switch to Landscape")
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isLandscape) {
                                                    Icons.Default.StayCurrentPortrait
                                                } else {
                                                    Icons.Default.StayCurrentLandscape
                                                },
                                                contentDescription = null,
                                                tint = if (isLandscape) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            onToggleLandscape(isLandscape)
                                        },
                                        modifier = Modifier.testTag("viewer_landscape_toggle")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Model Info") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Info, contentDescription = null)
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            showInfoDialog = true
                                        },
                                        modifier = Modifier.testTag("viewer_info_button")
                                    )
                                }

                                DropdownMenu(
                                    expanded = showViewsMenu,
                                    onDismissRequest = { showViewsMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Zoom Extents") },
                                        leadingIcon = { Icon(Icons.Default.FitScreen, contentDescription = null) },
                                        onClick = {
                                            camera.zoomExtents(document.bounds)
                                            showViewsMenu = false
                                        }
                                    )
                                    HorizontalDivider()
                                    StandardView.entries.forEach { sv ->
                                        DropdownMenuItem(
                                            text = { Text("View: ${sv.label}") },
                                            onClick = {
                                                onApplyStandardView(sv, camera)
                                                showViewsMenu = false
                                            }
                                        )
                                    }
                                    if (document.savedViews.isNotEmpty()) {
                                        HorizontalDivider()
                                        document.savedViews.forEach { saved ->
                                            DropdownMenuItem(
                                                text = { Text("Scene: ${saved.name}") },
                                                onClick = {
                                                    onApplySavedView(saved, camera)
                                                    showViewsMenu = false
                                                }
                                            )
                                        }
                                    }
                                }

                                DropdownMenu(
                                    expanded = showStyleMenu,
                                    onDismissRequest = { showStyleMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DisplayStyle.entries.forEach { st ->
                                        DropdownMenuItem(
                                            text = { Text(st.label) },
                                            onClick = {
                                                onSetDisplayStyle(st)
                                                showStyleMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = cutListState.lastExportedPdf != null) {
                        cutListState.lastExportedPdf?.let { exported ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Spacer(Modifier.height(6.dp))
                                PdfExportToastBanner(
                                    exported = exported,
                                    onOpenPdf = { item ->
                                        if (!launchOpenPdf(context, item)) {
                                            launchPdfShareSheet(context, item)
                                        }
                                    },
                                    onSharePdf = { item -> launchPdfShareSheet(context, item) },
                                    onDismiss = onDismissExportedPdf
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = viewerState.statusBanner != null && cutListState.lastExportedPdf == null) {
                        viewerState.statusBanner?.let { msg ->
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                tonalElevation = 2.dp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = msg,
                                        style = CadNumericStyle,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = onDismissBanner,
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Dismiss status",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // In compact landscape, move the main tool dock to a vertical left rail so full height is open for dynamic 3D viewing
                if (isCompactLandscape) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
                            )
                            .padding(start = 10.dp, top = 62.dp, bottom = 10.dp)
                            .testTag("viewer_landscape_tool_rail")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (railScrollState.canScrollBackward) {
                                ToolbarScrollArrowButton(
                                    icon = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "More tools up",
                                    tag = "rail_toolbar_scroll_up",
                                    onClick = {
                                        coroutineScope.launch {
                                            val target = (railScrollState.value - 200).coerceAtLeast(0)
                                            railScrollState.animateScrollTo(target)
                                        }
                                    }
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(railScrollState)
                                    .padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                            DockToolButton(
                                icon = Icons.Default.AdsClick,
                                label = "Select",
                                selected = viewerState.activeTool == CadTool.SELECT,
                                tag = "tool_select",
                                onClick = { onSetTool(CadTool.SELECT) }
                            )
                            DockToolButton(
                                icon = Icons.Default._3dRotation,
                                label = "Orbit",
                                selected = viewerState.activeTool == CadTool.ORBIT,
                                tag = "tool_orbit",
                                onClick = { onSetTool(CadTool.ORBIT) }
                            )
                            DockToolButton(
                                icon = Icons.Default.PanTool,
                                label = "Pan",
                                selected = viewerState.activeTool == CadTool.PAN,
                                tag = "tool_pan",
                                onClick = { onSetTool(CadTool.PAN) }
                            )
                            DockToolButton(
                                icon = Icons.Default.Straighten,
                                label = "Tape",
                                selected = viewerState.activeTool == CadTool.MEASURE_LENGTH,
                                tag = "tool_tape",
                                onClick = { onSetTool(CadTool.MEASURE_LENGTH) }
                            )
                            DockToolButton(
                                icon = Icons.Default.OpenWith,
                                label = "Angle",
                                selected = viewerState.activeTool == CadTool.MEASURE_ANGLE,
                                tag = "tool_angle",
                                onClick = { onSetTool(CadTool.MEASURE_ANGLE) }
                            )
                            DockToolButton(
                                icon = Icons.Default.SquareFoot,
                                label = preferences.unitSystem.symbol,
                                selected = showUnitsDialog,
                                tag = "tool_units",
                                onClick = { showUnitsDialog = true }
                            )
                            HorizontalDivider(modifier = Modifier.width(36.dp).padding(vertical = 2.dp))
                            DockToolButton(
                                icon = Icons.Default.Layers,
                                label = "Layers",
                                selected = activeSheet == ViewerSheetType.HIERARCHY,
                                tag = "tool_hierarchy",
                                onClick = { activeSheet = ViewerSheetType.HIERARCHY }
                            )
                            DockToolButton(
                                icon = Icons.Default.TableChart,
                                label = "Count",
                                selected = activeSheet == ViewerSheetType.MEASUREMENTS,
                                tag = "tool_measurements",
                                onClick = { activeSheet = ViewerSheetType.MEASUREMENTS }
                            )
                            DockToolButton(
                                icon = Icons.Default.ReceiptLong,
                                label = "Cut List",
                                selected = showCutListSplit,
                                tag = "tool_cutlist",
                                onClick = {
                                    activeSheet = null
                                    val next = !showCutListSplit
                                    showCutListSplit = next
                                    if (next) onOpenCutList()
                                }
                            )
                            }
                            if (railScrollState.canScrollForward) {
                                ToolbarScrollArrowButton(
                                    icon = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "More tools down",
                                    tag = "rail_toolbar_scroll_down",
                                    onClick = {
                                        coroutineScope.launch {
                                            val target = (railScrollState.value + 200)
                                                .coerceAtMost(railScrollState.maxValue)
                                            railScrollState.animateScrollTo(target)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Bottom Tool Controls + Contextual Measurement/Snap Bar (hidden in portrait split so upper viewport stays clear)
                if (!showPortraitSplitCutList) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                            )
                            .padding(horizontal = 12.dp, vertical = if (isCompactLandscape) 6.dp else 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    // Contextual Tool Action Pill
                    if (viewerState.activeTool == CadTool.MEASURE_LENGTH ||
                        viewerState.activeTool == CadTool.MEASURE_ANGLE ||
                        viewerState.selectedFaceIds.isNotEmpty() ||
                        viewerState.selectedEdgeIds.isNotEmpty()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            tonalElevation = 4.dp,
                            modifier = Modifier.padding(bottom = if (isCompactLandscape) 2.dp else 8.dp)
                        ) {
                            FlowRow(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (viewerState.activeTool == CadTool.MEASURE_LENGTH) {
                                    viewerState.activeLengthDotDrag?.let { activeDrag ->
                                        FilterChip(
                                            selected = true,
                                            onClick = {},
                                            label = {
                                                val snapName = activeDrag.snapType?.label ?: "Free move"
                                                Text("Dragging Dot · $snapName", style = CadNumericStyle)
                                            },
                                            modifier = Modifier.testTag("viewer_dragging_dot_chip")
                                        )
                                    }
                                    FilterChip(
                                        selected = false,
                                        enabled = viewerState.canUndoTape,
                                        onClick = onUndoPick,
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = { Text("Undo") },
                                        modifier = Modifier.testTag("viewer_measure_undo_button")
                                    )
                                    FilterChip(
                                        selected = false,
                                        enabled = viewerState.canRedoTape,
                                        onClick = onRedoPick,
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Redo,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = { Text("Redo") },
                                        modifier = Modifier.testTag("viewer_measure_redo_button")
                                    )
                                }
                                if (viewerState.activeTool == CadTool.MEASURE_ANGLE) {
                                    viewerState.activeAngleDotDrag?.let { activeDrag ->
                                        FilterChip(
                                            selected = true,
                                            onClick = {},
                                            label = {
                                                val snapName = activeDrag.snapType?.label ?: "Free move"
                                                Text("Dragging Angle Dot · $snapName", style = CadNumericStyle)
                                            },
                                            modifier = Modifier.testTag("viewer_dragging_angle_dot_chip")
                                        )
                                    }
                                    AngleInputMode.entries.forEach { m ->
                                        FilterChip(
                                            selected = viewerState.angleInputMode == m,
                                            onClick = { onSetAngleInputMode(m) },
                                            label = { Text(m.label) }
                                        )
                                    }
                                    FilterChip(
                                        selected = false,
                                        enabled = viewerState.canUndoAngle,
                                        onClick = onUndoPick,
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = { Text("Undo") },
                                        modifier = Modifier.testTag("viewer_measure_undo_button")
                                    )
                                    FilterChip(
                                        selected = false,
                                        enabled = viewerState.canRedoAngle,
                                        onClick = onRedoPick,
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Redo,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = { Text("Redo") },
                                        modifier = Modifier.testTag("viewer_measure_redo_button")
                                    )
                                }
                                viewerState.activeSnap?.let { s ->
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text("Snap: ${s.type.label}", style = CadNumericStyle) }
                                    )
                                }
                                if (viewerState.activeLengthPicks.isNotEmpty() ||
                                    viewerState.activeAnglePicks.isNotEmpty() ||
                                    viewerState.pendingAngleEdge != null ||
                                    viewerState.pendingAngleFace != null
                                ) {
                                    FilterChip(
                                        selected = false,
                                        onClick = onClearPicks,
                                        label = { Text("Clear") },
                                        modifier = Modifier.testTag("viewer_measure_clear_button")
                                    )
                                }
                                if (viewerState.selectedFaceIds.isNotEmpty() || viewerState.selectedEdgeIds.isNotEmpty()) {
                                    FilterChip(
                                        selected = false,
                                        onClick = onAutoCountSelection,
                                        leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        label = { Text("Count Angles") },
                                        modifier = Modifier.testTag("quick_auto_count_chip")
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = onClearSelection,
                                        label = { Text("Deselect") }
                                    )
                                }
                            }
                        }
                    }

                    // Bottom CAD Tool Dock (Portrait & Wide Tablet)
                    if (!isCompactLandscape) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            tonalElevation = 6.dp,
                            shadowElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (bottomToolbarScrollState.canScrollBackward) {
                                    ToolbarScrollArrowButton(
                                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "More tools left",
                                        tag = "bottom_toolbar_scroll_left",
                                        onClick = {
                                            coroutineScope.launch {
                                                val target = (bottomToolbarScrollState.value - 240).coerceAtLeast(0)
                                                bottomToolbarScrollState.animateScrollTo(target)
                                            }
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .horizontalScroll(bottomToolbarScrollState)
                                        .padding(horizontal = 2.dp, vertical = 2.dp)
                                        .testTag("bottom_toolbar_tools_row"),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                DockToolButton(
                                    icon = Icons.Default.AdsClick,
                                    label = "Select",
                                    selected = viewerState.activeTool == CadTool.SELECT,
                                    tag = "tool_select",
                                    onClick = { onSetTool(CadTool.SELECT) }
                                )
                                DockToolButton(
                                    icon = Icons.Default._3dRotation,
                                    label = "Orbit",
                                    selected = viewerState.activeTool == CadTool.ORBIT,
                                    tag = "tool_orbit",
                                    onClick = { onSetTool(CadTool.ORBIT) }
                                )
                                DockToolButton(
                                    icon = Icons.Default.PanTool,
                                    label = "Pan",
                                    selected = viewerState.activeTool == CadTool.PAN,
                                    tag = "tool_pan",
                                    onClick = { onSetTool(CadTool.PAN) }
                                )
                                DockToolButton(
                                    icon = Icons.Default.Straighten,
                                    label = "Tape",
                                    selected = viewerState.activeTool == CadTool.MEASURE_LENGTH,
                                    tag = "tool_tape",
                                    onClick = { onSetTool(CadTool.MEASURE_LENGTH) }
                                )
                                DockToolButton(
                                    icon = Icons.Default.OpenWith,
                                    label = "Angle",
                                    selected = viewerState.activeTool == CadTool.MEASURE_ANGLE,
                                    tag = "tool_angle",
                                    onClick = { onSetTool(CadTool.MEASURE_ANGLE) }
                                )
                                DockToolButton(
                                    icon = Icons.Default.SquareFoot,
                                    label = "Unit: ${preferences.unitSystem.symbol}",
                                    selected = showUnitsDialog,
                                    tag = "tool_units",
                                    onClick = { showUnitsDialog = true }
                                )
                                if (!isWideTablet) {
                                    VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 2.dp))
                                    DockToolButton(
                                        icon = Icons.Default.Layers,
                                        label = "Layers",
                                        selected = activeSheet == ViewerSheetType.HIERARCHY,
                                        tag = "tool_hierarchy",
                                        onClick = { activeSheet = ViewerSheetType.HIERARCHY }
                                    )
                                    DockToolButton(
                                        icon = Icons.Default.TableChart,
                                        label = "Count",
                                        selected = activeSheet == ViewerSheetType.MEASUREMENTS,
                                        tag = "tool_measurements",
                                        onClick = { activeSheet = ViewerSheetType.MEASUREMENTS }
                                    )
                                    DockToolButton(
                                        icon = Icons.Default.ReceiptLong,
                                        label = "Cut List",
                                        selected = showCutListSplit,
                                        tag = "tool_cutlist",
                                        onClick = {
                                            activeSheet = null
                                            val next = !showCutListSplit
                                            showCutListSplit = next
                                            if (next) onOpenCutList()
                                        }
                                    )
                                }
                                }

                                if (bottomToolbarScrollState.canScrollForward) {
                                    ToolbarScrollArrowButton(
                                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "More tools right",
                                        tag = "bottom_toolbar_scroll_right",
                                        onClick = {
                                            coroutineScope.launch {
                                                val target = (bottomToolbarScrollState.value + 240)
                                                    .coerceAtMost(bottomToolbarScrollState.maxValue)
                                                bottomToolbarScrollState.animateScrollTo(target)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    }
                }

                // Long-press context menu rendered above Top HUD and Bottom Toolbars
                measurementContextMenu?.let { menu ->
                    MeasurementContextMenuOverlay(
                        menu = menu,
                        containerSize = viewportPaneSize,
                        unitSystem = preferences.unitSystem,
                        decimals = preferences.precisionDecimals,
                        onDeleteLength = { id ->
                            onDeleteLength(id)
                            measurementContextMenu = null
                        },
                        onDeleteAngle = { id ->
                            onDeleteAngle(id)
                            measurementContextMenu = null
                        },
                        onDismiss = { measurementContextMenu = null }
                    )
                }
                }

                // Right Split Pane for Cut List in Compact Landscape
                if (showLandscapeSplitCutList) {
                    Surface(
                        tonalElevation = 4.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .weight(0.44f)
                            .fillMaxHeight()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End
                                )
                            )
                    ) {
                        CutListPanel(
                            cutListState = cutListState,
                            unitSystem = preferences.unitSystem,
                            precisionDecimals = preferences.precisionDecimals,
                            activeTool = viewerState.activeTool,
                            showCloseButton = true,
                            showViewportModeStrip = false,
                            onSelectRow = { groupId -> onSelectCutListRow(groupId, camera) },
                            onLongPressRow = onLongPressCutListRow,
                            onToggleCheck = onToggleCutListCheck,
                            onToggleSelectAll = onToggleSelectAllCutList,
                            onExitSelectionMode = onExitCutListSelectionMode,
                            onExplodeChange = onSetExplodeFraction,
                            onOpenUnitDialog = { showUnitsDialog = true },
                            onSetTool = onSetTool,
                            onExportPdf = { onExportCutListPdf { isolated -> snapshotRequester?.invoke(isolated) } },
                            onSharePdf = { exported -> launchPdfShareSheet(context, exported) },
                            onDismissPdfResult = onDismissExportedPdf,
                            onClose = {
                                if (cutListState.isExportSelectionMode) onExitCutListSelectionMode()
                                showCutListSplit = false
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Persistent Side Pane on Expanded Tablet Screens
                if (isWideTablet) {
                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .width(400.dp)
                            .fillMaxHeight()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)
                            )
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                SegmentedButton(
                                    selected = tabletSideTab == 0,
                                    onClick = { tabletSideTab = 0 },
                                    shape = SegmentedButtonDefaults.itemShape(0, 3)
                                ) { Text("Layers", maxLines = 1) }
                                SegmentedButton(
                                    selected = tabletSideTab == 1,
                                    onClick = { tabletSideTab = 1 },
                                    shape = SegmentedButtonDefaults.itemShape(1, 3)
                                ) { Text("Measure", maxLines = 1) }
                                SegmentedButton(
                                    selected = tabletSideTab == 2,
                                    onClick = { tabletSideTab = 2 },
                                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                                    modifier = Modifier.testTag("tablet_tab_cutlist")
                                ) { Text("Cut List", maxLines = 1) }
                            }
                            when (tabletSideTab) {
                                0 -> HierarchyPanelContent(
                                    document = document,
                                    hiddenLayers = viewerState.hiddenLayers,
                                    hiddenNodes = viewerState.hiddenNodes,
                                    selectedNodeIds = viewerState.selectedNodeIds,
                                    onToggleLayer = onToggleLayer,
                                    onToggleNode = onToggleNode,
                                    onSelectNode = onSelectNode,
                                    onShowAll = onShowAllHierarchy,
                                    modifier = Modifier.weight(1f)
                                )
                                1 -> MeasurementsAndAnglesPanel(
                                    preferences = preferences,
                                    lengths = lengths,
                                    angles = angles,
                                    countedAngles = countedAngles,
                                    angleBuckets = angleBuckets,
                                    onChangeUnits = onChangeUnits,
                                    onChangeDecimals = onChangeDecimals,
                                    onChangeTolerance = onChangeTolerance,
                                    onFocusPoint = { pt -> onFocusPoint(pt, camera) },
                                    onRenameLength = onRenameLength,
                                    onDeleteLength = onDeleteLength,
                                    onRenameAngle = onRenameAngle,
                                    onDeleteAngle = onDeleteAngle,
                                    onMarkAngleIntoCounter = onMarkAngleIntoCounter,
                                    onAutoCountSelection = onAutoCountSelection,
                                    onUpdateCountedNote = onUpdateCountedNote,
                                    onDeleteCountedAngle = onDeleteCountedAngle,
                                    onClearCountedAngles = onClearCountedAngles,
                                    onExportCsv = onExportCsv,
                                    modifier = Modifier.weight(1f)
                                )
                                else -> CutListPanel(
                                    cutListState = cutListState,
                                    unitSystem = preferences.unitSystem,
                                    precisionDecimals = preferences.precisionDecimals,
                                    activeTool = viewerState.activeTool,
                                    showCloseButton = false,
                                    showViewportModeStrip = false,
                                    onSelectRow = { groupId -> onSelectCutListRow(groupId, camera) },
                                    onLongPressRow = onLongPressCutListRow,
                                    onToggleCheck = onToggleCutListCheck,
                                    onToggleSelectAll = onToggleSelectAllCutList,
                                    onExitSelectionMode = onExitCutListSelectionMode,
                                    onExplodeChange = onSetExplodeFraction,
                                    onOpenUnitDialog = { showUnitsDialog = true },
                                    onSetTool = onSetTool,
                                    onExportPdf = { onExportCutListPdf { isolated -> snapshotRequester?.invoke(isolated) } },
                                    onSharePdf = { exported -> launchPdfShareSheet(context, exported) },
                                    onDismissPdfResult = onDismissExportedPdf,
                                    onClose = {},
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Half-height Bottom Split Panel for Phone Portrait (3D viewport remains live above)
            if (showPortraitSplitCutList) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.52f)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                        )
                ) {
                    CutListPanel(
                        cutListState = cutListState,
                        unitSystem = preferences.unitSystem,
                        precisionDecimals = preferences.precisionDecimals,
                        activeTool = viewerState.activeTool,
                        showCloseButton = true,
                        showViewportModeStrip = true,
                        onSelectRow = { groupId -> onSelectCutListRow(groupId, camera) },
                        onLongPressRow = onLongPressCutListRow,
                        onToggleCheck = onToggleCutListCheck,
                        onToggleSelectAll = onToggleSelectAllCutList,
                        onExitSelectionMode = onExitCutListSelectionMode,
                        onExplodeChange = onSetExplodeFraction,
                        onOpenUnitDialog = { showUnitsDialog = true },
                        onSetTool = onSetTool,
                        onExportPdf = { onExportCutListPdf { isolated -> snapshotRequester?.invoke(isolated) } },
                        onSharePdf = { exported -> launchPdfShareSheet(context, exported) },
                        onDismissPdfResult = onDismissExportedPdf,
                        onClose = {
                            if (cutListState.isExportSelectionMode) onExitCutListSelectionMode()
                            showCutListSplit = false
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (!isWideTablet && activeSheet != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                sheetState = sheetState
            ) {
                when (activeSheet) {
                    ViewerSheetType.HIERARCHY -> HierarchyPanelContent(
                        document = document,
                        hiddenLayers = viewerState.hiddenLayers,
                        hiddenNodes = viewerState.hiddenNodes,
                        selectedNodeIds = viewerState.selectedNodeIds,
                        onToggleLayer = onToggleLayer,
                        onToggleNode = onToggleNode,
                        onSelectNode = onSelectNode,
                        onShowAll = onShowAllHierarchy
                    )
                    ViewerSheetType.MEASUREMENTS -> MeasurementsAndAnglesPanel(
                        preferences = preferences,
                        lengths = lengths,
                        angles = angles,
                        countedAngles = countedAngles,
                        angleBuckets = angleBuckets,
                        onChangeUnits = onChangeUnits,
                        onChangeDecimals = onChangeDecimals,
                        onChangeTolerance = onChangeTolerance,
                        onFocusPoint = { pt ->
                            onFocusPoint(pt, camera)
                            activeSheet = null
                        },
                        onRenameLength = onRenameLength,
                        onDeleteLength = onDeleteLength,
                        onRenameAngle = onRenameAngle,
                        onDeleteAngle = onDeleteAngle,
                        onMarkAngleIntoCounter = onMarkAngleIntoCounter,
                        onAutoCountSelection = onAutoCountSelection,
                        onUpdateCountedNote = onUpdateCountedNote,
                        onDeleteCountedAngle = onDeleteCountedAngle,
                        onClearCountedAngles = onClearCountedAngles,
                        onExportCsv = onExportCsv
                    )
                    null -> Unit
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showInfoDialog) {
        val size = document.bounds.size
        val dec = preferences.precisionDecimals
        val u = preferences.unitSystem
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(document.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Format: ${document.format.displayName} (${document.formatVersion})")
                    document.appVersion?.let { Text("Author App: IngeTrazo $it") }
                    Text("Source Units: ${document.sourceUnits.label} · Display Units: ${u.label}")
                    Text("Groups / Nodes: ${document.stats.nodeCount}")
                    Text("Faces: ${document.stats.faceCount} (${document.stats.triangleCount} tris)")
                    Text("Edges: ${document.stats.edgeCount}")
                    Text("Unique Vertices: ${document.stats.uniqueVertexCount}")
                    Text("Layers: ${document.stats.layerCount} · Saved Views: ${document.stats.savedViewCount}")
                    Text("Textures: ${document.stats.textureCount}")
                    Text(
                        text = "Extents: ${u.formatMeters(size.x, dec)} × ${u.formatMeters(size.y, dec)} × ${u.formatMeters(size.z, dec)}",
                        style = CadNumericStyle
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showInfoDialog = false
                    showUnitsDialog = true
                }) { Text("Change Units") }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("Close") }
            }
        )
    }

    if (showUnitsDialog) {
        UnitSettingsDialog(
            currentUnit = preferences.unitSystem,
            currentDecimals = preferences.precisionDecimals,
            onSelectUnit = onChangeUnits,
            onSelectDecimals = onChangeDecimals,
            onDismiss = { showUnitsDialog = false }
        )
    }
}

private enum class ViewerSheetType {
    HIERARCHY, MEASUREMENTS
}

@Composable
private fun DockToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .semantics { this.selected = selected }
            .testTag(tag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToolbarScrollArrowButton(
    icon: ImageVector,
    contentDescription: String,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(width = 28.dp, height = 36.dp)
            .testTag(tag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

