package com.example.ui.ar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Vrpano
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.domain.model.CadDocument
import com.example.domain.model.UnitSystem
import com.example.ui.components.UnitSettingsDialog
import com.example.ui.components.findActivity
import com.example.ui.components.lockSensorLandscapeForVr
import com.example.ui.components.readCurrentDisplayRotation
import com.example.ui.components.restoreOrientationAfterVr
import com.example.ui.theme.CadNumericStyle
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlin.math.abs

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ArProjectionScreen(
    document: CadDocument,
    unitSystem: UnitSystem = document.sourceUnits,
    precisionDecimals: Int = document.sourceDecimals,
    onChangeUnits: (UnitSystem) -> Unit = {},
    onChangeDecimals: (Int) -> Unit = {},
    onToggleLandscape: (currentlyLandscape: Boolean) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var vrModeEnabled by remember(document.documentKey) { mutableStateOf(false) }
    var vrIpdMm by remember(document.documentKey) { mutableIntStateOf(64) }
    var vrLensFit by remember(document.documentKey) { mutableStateOf(false) }
    var preVrOrientation by remember { mutableStateOf<Int?>(null) }

    val setVrMode: (Boolean) -> Unit = { enabled ->
        if (enabled && !vrModeEnabled) {
            preVrOrientation = lockSensorLandscapeForVr(context.findActivity())
            vrModeEnabled = true
        } else if (!enabled && vrModeEnabled) {
            vrModeEnabled = false
            restoreOrientationAfterVr(context.findActivity(), preVrOrientation)
            preVrOrientation = null
        }
    }

    BackHandler {
        if (vrModeEnabled) {
            setVrMode(false)
        } else {
            onBack()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var arStatusError by remember { mutableStateOf<String?>(null) }
    var trackingStatus by remember { mutableStateOf(ArTrackingStatus()) }
    val hasFoundationBelowAxis = remember(document.documentKey) {
        ArGeometryClipper.hasFoundationBelowAxis(document.bounds)
    }
    var showFoundation by remember(document.documentKey) {
        mutableStateOf(document.bounds.max.z <= 0.005)
    }
    var alwaysLevel by remember(document.documentKey) { mutableStateOf(true) }
    var showTextures by remember(document.documentKey) { mutableStateOf(true) }
    val effectiveBounds = remember(document.documentKey, showFoundation) {
        ArGeometryClipper.computeEffectiveBounds(document, showFoundation)
    }
    var selectedScale by remember(document.documentKey) {
        mutableFloatStateOf(ArScaleMath.recommendedInitialScale(effectiveBounds))
    }
    var rotationDeg by remember(document.documentKey) { mutableFloatStateOf(0f) }
    var verticalOffsetMeters by remember(document.documentKey) { mutableFloatStateOf(0f) }
    var anchorOrigin by remember(document.documentKey) { mutableStateOf(ArAnchorOrigin.AXIS) }
    var trackingMode by remember(document.documentKey) { mutableStateOf(ArTrackingMode.OBJECT_SURFACE) }
    var targetWidthMeters by remember(document.documentKey) { mutableFloatStateOf(0.20f) }
    var captureFeedback by remember(document.documentKey) { mutableStateOf<String?>(null) }
    var showUnitDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val arSupported = remember {
        try {
            val avail = ArCoreApk.getInstance().checkAvailability(context)
            avail.isSupported || avail.isTransient
        } catch (_: Throwable) {
            false
        }
    }

    val arRenderer = remember(document.documentKey) {
        ArCoreRenderer(document) { status ->
            trackingStatus = status
        }
    }

    LaunchedEffect(
        arRenderer,
        selectedScale,
        rotationDeg,
        verticalOffsetMeters,
        anchorOrigin,
        alwaysLevel,
        showFoundation,
        showTextures,
        trackingMode,
        vrModeEnabled,
        vrIpdMm,
        vrLensFit
    ) {
        arRenderer.modelScale = selectedScale
        arRenderer.rotationDeg = rotationDeg
        arRenderer.verticalOffsetMeters = verticalOffsetMeters
        arRenderer.anchorOrigin = anchorOrigin
        arRenderer.alwaysLevel = alwaysLevel
        arRenderer.showFoundation = showFoundation
        arRenderer.showTextures = showTextures
        arRenderer.trackingMode = trackingMode
        arRenderer.vrModeEnabled = vrModeEnabled
        arRenderer.vrIpdMeters = vrIpdMm / 1000f
        arRenderer.vrLensZoom = if (vrLensFit) 0.86f else 1.0f
    }

    // Keep ARCore displayRotation synchronized across portrait, landscape, and reverse-landscape (90 <-> 270)
    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        arRenderer.displayRotation = context.readCurrentDisplayRotation()
    }

    DisposableEffect(context, arRenderer) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                arRenderer.displayRotation = context.readCurrentDisplayRotation()
            }
        }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose {
            displayManager?.unregisterDisplayListener(listener)
        }
    }

    var arSession by remember { mutableStateOf<Session?>(null) }

    LaunchedEffect(hasCameraPermission, arSupported) {
        if (hasCameraPermission && arSupported && arSession == null) {
            try {
                val created = Session(context).apply {
                    configure(
                        Config(this).apply {
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                            focusMode = Config.FocusMode.AUTO
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                            if (isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                                depthMode = Config.DepthMode.AUTOMATIC
                            }
                        }
                    )
                }
                created.resume()
                arRenderer.cameraSensorOrientationDeg = readBackCameraSensorOrientation(context)
                arRenderer.displayRotation = context.readCurrentDisplayRotation()
                arRenderer.session = created
                arSession = created
            } catch (e: Exception) {
                arStatusError = e.localizedMessage ?: "ARCore session unavailable on this device."
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (vrModeEnabled) {
                restoreOrientationAfterVr(context.findActivity(), preVrOrientation)
            }
            arSession?.pause()
            arSession?.close()
            arRenderer.session = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!hasCameraPermission) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Camera Permission Needed for AR Projection",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Experimental ARCore mode overlays ${document.title} at 1:1 or scaled sizes and can stick onto real-world objects.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.testTag("ar_grant_camera_button")
                    ) {
                        Text("Allow Camera for AR")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onBack) {
                        Text("Return to 3D Viewer")
                    }
                }
            }
        } else if (!arSupported || arStatusError != null) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "ARCore Unavailable on This Device",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = arStatusError ?: "Google Play Services for AR is not supported or not installed on this device/emulator.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onBack,
                        modifier = Modifier.testTag("ar_back_to_viewer_button")
                    ) {
                        Text("Back to 3D Viewer")
                    }
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    arRenderer.displayRotation = ctx.readCurrentDisplayRotation()
                    GLSurfaceView(ctx).apply {
                        preserveEGLContextOnPause = true
                        setEGLContextClientVersion(3)
                        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                        setRenderer(arRenderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        setOnTouchListener { _, ev ->
                            if (ev.action == MotionEvent.ACTION_UP) {
                                if (arRenderer.vrModeEnabled) {
                                    arRenderer.requestPlaceAtCenter()
                                } else {
                                    arRenderer.requestPlaceAt(ev.x, ev.y)
                                }
                            }
                            true
                        }
                    }
                },
                update = { _ ->
                    arRenderer.displayRotation = context.readCurrentDisplayRotation()
                }
            )

            ArHudOverlay(
                document = document,
                unitSystem = unitSystem,
                precisionDecimals = precisionDecimals,
                trackingStatus = trackingStatus,
                selectedScale = selectedScale,
                rotationDeg = rotationDeg,
                verticalOffsetMeters = verticalOffsetMeters,
                anchorOrigin = anchorOrigin,
                alwaysLevel = alwaysLevel,
                showFoundation = showFoundation,
                showTextures = showTextures,
                hasFoundationBelowAxis = hasFoundationBelowAxis,
                trackingMode = trackingMode,
                targetWidthMeters = targetWidthMeters,
                captureFeedback = captureFeedback,
                vrModeEnabled = vrModeEnabled,
                vrIpdMm = vrIpdMm,
                vrLensFit = vrLensFit,
                onBack = onBack,
                onOpenUnitDialog = { showUnitDialog = true },
                onToggleLandscape = onToggleLandscape,
                onToggleVrMode = setVrMode,
                onCycleVrIpd = {
                    val presets = ArTransformMath.IPD_PRESETS_MM
                    val idx = presets.indexOf(vrIpdMm)
                    vrIpdMm = presets[(idx + 1) % presets.size]
                },
                onToggleVrLensFit = { vrLensFit = !vrLensFit },
                onClearAnchor = {
                    captureFeedback = null
                    arRenderer.clearAnchor()
                },
                onSelectScale = { factor ->
                    val clamped = ArScaleMath.clampScale(factor)
                    selectedScale = clamped
                    arRenderer.modelScale = clamped
                },
                onFitScale = {
                    val fitted = ArScaleMath.recommendedInitialScale(effectiveBounds)
                    selectedScale = fitted
                    arRenderer.modelScale = fitted
                },
                onChangeRotation = { deg ->
                    rotationDeg = deg
                    arRenderer.rotationDeg = deg
                },
                onChangeVerticalOffset = { meters ->
                    verticalOffsetMeters = meters
                    arRenderer.verticalOffsetMeters = meters
                },
                onSelectAnchorOrigin = { origin ->
                    anchorOrigin = origin
                    arRenderer.anchorOrigin = origin
                },
                onToggleAlwaysLevel = { enabled ->
                    alwaysLevel = enabled
                    arRenderer.alwaysLevel = enabled
                },
                onToggleShowFoundation = { show ->
                    showFoundation = show
                    arRenderer.showFoundation = show
                },
                onToggleShowTextures = { show ->
                    showTextures = show
                    arRenderer.showTextures = show
                },
                onSelectTrackingMode = { mode ->
                    trackingMode = mode
                    arRenderer.trackingMode = mode
                    captureFeedback = null
                    arRenderer.clearAnchor()
                },
                onSelectTargetWidth = { widthMeters ->
                    targetWidthMeters = widthMeters
                },
                onPinAtCenter = {
                    captureFeedback = null
                    arRenderer.requestPlaceAtCenter()
                },
                onCaptureObjectTarget = {
                    captureFeedback = "Capturing target..."
                    arRenderer.requestCaptureObjectTarget(targetWidthMeters) { _, message ->
                        captureFeedback = message
                    }
                }
            )
        }
    }

    if (showUnitDialog) {
        UnitSettingsDialog(
            currentUnit = unitSystem,
            currentDecimals = precisionDecimals,
            onSelectUnit = onChangeUnits,
            onSelectDecimals = onChangeDecimals,
            onDismiss = { showUnitDialog = false }
        )
    }
}

@Composable
fun ArHudOverlay(
    document: CadDocument,
    unitSystem: UnitSystem,
    precisionDecimals: Int,
    trackingStatus: ArTrackingStatus,
    selectedScale: Float,
    rotationDeg: Float,
    verticalOffsetMeters: Float,
    anchorOrigin: ArAnchorOrigin,
    alwaysLevel: Boolean = true,
    showFoundation: Boolean = false,
    showTextures: Boolean = true,
    hasFoundationBelowAxis: Boolean = ArGeometryClipper.hasFoundationBelowAxis(document.bounds),
    trackingMode: ArTrackingMode,
    targetWidthMeters: Float,
    captureFeedback: String?,
    vrModeEnabled: Boolean = false,
    vrIpdMm: Int = 64,
    vrLensFit: Boolean = false,
    onBack: () -> Unit,
    onOpenUnitDialog: () -> Unit,
    onToggleLandscape: (currentlyLandscape: Boolean) -> Unit = {},
    onToggleVrMode: (Boolean) -> Unit = {},
    onCycleVrIpd: () -> Unit = {},
    onToggleVrLensFit: () -> Unit = {},
    onClearAnchor: () -> Unit,
    onSelectScale: (Float) -> Unit,
    onFitScale: () -> Unit = {},
    onChangeRotation: (Float) -> Unit,
    onChangeVerticalOffset: (Float) -> Unit,
    onSelectAnchorOrigin: (ArAnchorOrigin) -> Unit,
    onToggleAlwaysLevel: (Boolean) -> Unit = {},
    onToggleShowFoundation: (Boolean) -> Unit = {},
    onToggleShowTextures: (Boolean) -> Unit = {},
    onSelectTrackingMode: (ArTrackingMode) -> Unit,
    onSelectTargetWidth: (Float) -> Unit,
    onPinAtCenter: () -> Unit,
    onCaptureObjectTarget: () -> Unit
) {
    var panelExpanded by remember { mutableStateOf(true) }

    val effectiveBounds = remember(document.documentKey, showFoundation, document.bounds) {
        ArGeometryClipper.computeEffectiveBounds(document, showFoundation)
    }
    val scaleLabel = ArScaleMath.formatScaleRatio(selectedScale)
    val scalePct = ArScaleMath.formatScalePercent(selectedScale)
    val projectedSizeText = ArScaleMath.formatProjectedDimensions(
        bounds = effectiveBounds,
        scale = selectedScale,
        unit = unitSystem,
        decimals = precisionDecimals
    )

    if (vrModeEnabled) {
        VrBoxOverlay(
            trackingStatus = trackingStatus,
            trackingMode = trackingMode,
            scaleLabel = scaleLabel,
            projectedSizeText = projectedSizeText,
            vrIpdMm = vrIpdMm,
            vrLensFit = vrLensFit,
            showFoundation = showFoundation,
            hasFoundationBelowAxis = hasFoundationBelowAxis,
            onExitVr = { onToggleVrMode(false) },
            onPinAtCenter = onPinAtCenter,
            onCaptureObjectTarget = onCaptureObjectTarget,
            onCycleIpd = onCycleVrIpd,
            onToggleLensFit = onToggleVrLensFit,
            onToggleFoundation = { onToggleShowFoundation(!showFoundation) }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight

        // Center aiming reticle aligned with optical camera center
        if (!trackingStatus.isPlaced || trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
            TargetAimingReticle(
                isObjectTracker = trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER,
                isLocked = trackingStatus.phase == ObjectTrackingPhase.STICKING_TO_OBJECT ||
                    trackingStatus.isPlaced,
                compact = isLandscape,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Top HUD card
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                .padding(horizontal = 12.dp, vertical = if (isLandscape) 6.dp else 12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = if (isLandscape) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("ar_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to 3D Viewer")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AR Projection · $scaleLabel ($scalePct)",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val baseStatus = trackingStatus.targetDescription.ifBlank {
                            when {
                                trackingStatus.isPlaced -> "Anchored · Tap another object/surface to move"
                                trackingStatus.planesCount > 0 || trackingStatus.featurePointCount > 20 ->
                                    "${trackingStatus.planesCount} surface(s) · ${trackingStatus.featurePointCount} 3D pts"
                                else -> "Move camera slowly to scan object & surfaces"
                            }
                        }
                        val distSuffix = trackingStatus.anchorDistanceMeters?.let { dist ->
                            " · ${unitSystem.formatMeters(dist.toDouble(), precisionDecimals.coerceAtMost(2))} away"
                        } ?: ""
                        Text(
                            text = baseStatus + distSuffix,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenUnitDialog,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .testTag("ar_units_button")
                    ) {
                        Icon(
                            Icons.Default.Straighten,
                            contentDescription = "Units",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(unitSystem.symbol, style = CadNumericStyle)
                    }
                    IconButton(
                        onClick = { onToggleVrMode(true) },
                        modifier = Modifier.testTag("ar_vr_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Vrpano,
                            contentDescription = "VR Box Mode"
                        )
                    }
                    IconButton(
                        onClick = { onToggleLandscape(isLandscape) },
                        modifier = Modifier.testTag("ar_landscape_toggle")
                    ) {
                        Icon(
                            imageVector = if (isLandscape) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape,
                            contentDescription = if (isLandscape) "Exit Landscape Mode" else "Switch to Landscape Mode",
                            tint = if (isLandscape) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onClearAnchor,
                        modifier = Modifier.testTag("ar_clear_anchor_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Clear Anchor")
                    }
                }
            }

            // Live Physical Scale & Object Status Banner
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (trackingStatus.phase) {
                    ObjectTrackingPhase.STICKING_TO_OBJECT -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f)
                    ObjectTrackingPhase.QUALITY_ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f)
                    else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
                },
                modifier = if (isLandscape) {
                    Modifier
                        .fillMaxWidth(0.58f)
                        .testTag("ar_scale_readout_banner")
                } else {
                    Modifier
                        .fillMaxWidth()
                        .testTag("ar_scale_readout_banner")
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = captureFeedback ?: "Projected size: $projectedSizeText",
                        style = CadNumericStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = if (trackingStatus.phase == ObjectTrackingPhase.QUALITY_ERROR) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (selectedScale >= 0.995f) "TRUE 1:1" else scaleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Control Panel: Side dock in Landscape (unobstructed center view) vs Bottom dock in Portrait
        val dockModifier = if (isLandscape) {
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom + WindowInsetsSides.Top)
                )
                .padding(top = 68.dp, end = 12.dp, bottom = 10.dp)
                .width(if (panelExpanded) 292.dp else 232.dp)
                .then(if (panelExpanded) Modifier.fillMaxHeight() else Modifier)
                .testTag("ar_controls_panel")
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .padding(12.dp)
                .fillMaxWidth()
                .testTag("ar_controls_panel")
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            modifier = dockModifier
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = if (isLandscape) 12.dp else 14.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header row with Mode switch & Collapse toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = trackingMode == ArTrackingMode.OBJECT_SURFACE,
                            onClick = { onSelectTrackingMode(ArTrackingMode.OBJECT_SURFACE) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.MyLocation,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text(if (isLandscape) "Pin" else "Pin Object") },
                            modifier = Modifier.testTag("ar_mode_pin")
                        )
                        FilterChip(
                            selected = trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER,
                            onClick = { onSelectTrackingMode(ArTrackingMode.VISUAL_OBJECT_TRACKER) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CenterFocusStrong,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text(if (isLandscape) "Tracker" else "Track Target") },
                            modifier = Modifier.testTag("ar_mode_tracker")
                        )
                    }
                    IconButton(
                        onClick = { panelExpanded = !panelExpanded },
                        modifier = Modifier.testTag("ar_toggle_panel")
                    ) {
                        Icon(
                            imageVector = if (panelExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (panelExpanded) "Collapse AR controls" else "Expand AR controls"
                        )
                    }
                }

                AnimatedVisibility(visible = panelExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Orientation Leveling, Z<0 Foundation & Texture Toggles
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = alwaysLevel,
                                onClick = { onToggleAlwaysLevel(!alwaysLevel) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.VerticalAlignBottom,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(if (alwaysLevel) "Level (Gravity)" else "Surface Tilt")
                                },
                                modifier = Modifier.testTag("ar_level_toggle")
                            )
                            FilterChip(
                                selected = showFoundation,
                                onClick = { onToggleShowFoundation(!showFoundation) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Layers,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    val labelText = when {
                                        !hasFoundationBelowAxis && !showFoundation -> "Foundation: None"
                                        showFoundation -> "Foundation: Shown"
                                        else -> "Foundation: Buried"
                                    }
                                    Text(labelText)
                                },
                                modifier = Modifier.testTag("ar_foundation_toggle")
                            )
                            FilterChip(
                                selected = showTextures,
                                onClick = { onToggleShowTextures(!showTextures) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Texture,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text(if (showTextures) "Textures" else "Solid Color") },
                                modifier = Modifier.testTag("ar_textures_toggle")
                            )
                        }

                        // Scale Section (True 1:1 max down to 1:1000)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isLandscape) "Scale (1:1 max)" else "Render Scale (True 1:1 max)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(
                                    onClick = onFitScale,
                                    modifier = Modifier
                                        .heightIn(min = 32.dp)
                                        .testTag("ar_scale_fit_button")
                                ) {
                                    Icon(
                                        Icons.Default.AspectRatio,
                                        contentDescription = "Auto-fit scale",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Fit", style = MaterialTheme.typography.labelMedium)
                                }
                                Text(
                                    text = "$scaleLabel · $scalePct",
                                    style = CadNumericStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("ar_scale_value_label")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ArScaleMath.PRESETS.forEach { preset ->
                                val isSelected = abs(selectedScale - preset.factor) < 0.0005f
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectScale(preset.factor) },
                                    label = { Text(preset.label, style = CadNumericStyle) },
                                    modifier = Modifier.testTag(
                                        "ar_scale_${preset.label.replace(":", "_")}"
                                    )
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("1:1000", style = CadNumericStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = ArScaleMath.scaleToSliderFraction(selectedScale),
                                onValueChange = { frac ->
                                    onSelectScale(ArScaleMath.sliderFractionToScale(frac))
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .testTag("ar_scale_slider")
                            )
                            Text("1:1", style = CadNumericStyle, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }

                        // Origin Alignment (Respects Model Axis Z=0 vs Bounds Base vs Center)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Origin:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ArAnchorOrigin.entries.forEach { origin ->
                                FilterChip(
                                    selected = anchorOrigin == origin,
                                    onClick = { onSelectAnchorOrigin(origin) },
                                    label = { Text(origin.label) },
                                    modifier = Modifier.testTag("ar_origin_${origin.name.lowercase()}")
                                )
                            }
                        }

                        // Object Placement / Tracker Action
                        if (trackingMode == ArTrackingMode.OBJECT_SURFACE) {
                            Button(
                                onClick = onPinAtCenter,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("ar_pin_center_button")
                            ) {
                                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isLandscape) "Pin Center" else "Stick at Center", maxLines = 1)
                            }
                        } else {
                            Text(
                                text = "Target Object Width (${unitSystem.symbol})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ArScaleMath.TARGET_WIDTH_PRESETS_METERS.forEachIndexed { idx, wMeters ->
                                    FilterChip(
                                        selected = abs(targetWidthMeters - wMeters) < 0.005f,
                                        onClick = { onSelectTargetWidth(wMeters) },
                                        label = {
                                            Text(
                                                unitSystem.formatMeters(wMeters.toDouble(), precisionDecimals.coerceAtMost(2)),
                                                style = CadNumericStyle
                                            )
                                        },
                                        modifier = Modifier.testTag("ar_target_width_$idx")
                                    )
                                }
                            }
                            Button(
                                onClick = onCaptureObjectTarget,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("ar_capture_target_button")
                            ) {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isLandscape) "Capture Target" else "Capture & Stick to Object", maxLines = 1)
                            }
                        }

                        // Rotate & Height Offset sliders
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Rot ${rotationDeg.toInt()}°",
                                style = CadNumericStyle,
                                modifier = Modifier.width(if (isLandscape) 68.dp else 84.dp)
                            )
                            Slider(
                                value = rotationDeg,
                                onValueChange = onChangeRotation,
                                valueRange = 0f..360f,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ar_rotate_slider")
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Elev ${unitSystem.formatMeters(verticalOffsetMeters.toDouble(), precisionDecimals.coerceAtMost(2))}",
                                style = CadNumericStyle,
                                modifier = Modifier.width(if (isLandscape) 68.dp else 84.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Slider(
                                value = verticalOffsetMeters,
                                onValueChange = onChangeVerticalOffset,
                                valueRange = -0.5f..0.5f,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ar_elevation_slider")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VrBoxOverlay(
    trackingStatus: ArTrackingStatus,
    trackingMode: ArTrackingMode,
    scaleLabel: String,
    projectedSizeText: String,
    vrIpdMm: Int,
    vrLensFit: Boolean,
    showFoundation: Boolean,
    hasFoundationBelowAxis: Boolean,
    onExitVr: () -> Unit,
    onPinAtCenter: () -> Unit,
    onCaptureObjectTarget: () -> Unit,
    onCycleIpd: () -> Unit,
    onToggleLensFit: () -> Unit,
    onToggleFoundation: () -> Unit
) {
    val showDualReticles = !trackingStatus.isPlaced || trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER
    val reticleAccent = when {
        trackingStatus.phase == ObjectTrackingPhase.STICKING_TO_OBJECT || trackingStatus.isPlaced -> Color(0xFF20D8A4)
        trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER -> Color(0xFFFFB300)
        else -> Color(0xFF38BDF8)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ar_vr_overlay")
    ) {
        // Stereo optical center divider, lens framing borders, and symmetrical Left/Right reticles
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val halfW = w * 0.5f

            // Center divider for VR Box optical septum
            drawLine(
                color = Color.White.copy(alpha = 0.72f),
                start = Offset(halfW, 0f),
                end = Offset(halfW, h),
                strokeWidth = 2.dp.toPx()
            )

            // Subtle binocular lens mask borders
            val lensRatio = if (vrLensFit) 0.86f else 0.985f
            val boxW = halfW * lensRatio
            val boxH = h * lensRatio
            val topY = (h - boxH) * 0.5f
            val cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())
            val frameStroke = Stroke(width = 1.5.dp.toPx())
            for (eyeIdx in 0..1) {
                val slotX = if (eyeIdx == 0) 0f else halfW
                val leftX = slotX + (halfW - boxW) * 0.5f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.28f),
                    topLeft = Offset(leftX, topY),
                    size = Size(boxW, boxH),
                    cornerRadius = cornerRadius,
                    style = frameStroke
                )
            }

            if (showDualReticles) {
                val r = 18.dp.toPx()
                val ringStroke = Stroke(width = 2.dp.toPx())
                val dotR = 3.dp.toPx()
                for (cx in listOf(w * 0.25f, w * 0.75f)) {
                    val cy = h * 0.5f
                    drawCircle(
                        color = reticleAccent.copy(alpha = 0.85f),
                        radius = r,
                        center = Offset(cx, cy),
                        style = ringStroke
                    )
                    drawCircle(
                        color = reticleAccent,
                        radius = dotR,
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        // Compact top-center control bar kept clear of central binocular sweet spot
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color(0xD9101622),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                .padding(top = 8.dp, start = 12.dp, end = 12.dp)
                .widthIn(max = 640.dp)
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onExitVr,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ar_vr_exit_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Exit VR", style = MaterialTheme.typography.labelMedium)
                }

                TextButton(
                    onClick = if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
                        onCaptureObjectTarget
                    } else {
                        onPinAtCenter
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ar_vr_pin_button")
                ) {
                    Icon(
                        imageVector = if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
                            Icons.Default.CenterFocusStrong
                        } else {
                            Icons.Default.MyLocation
                        },
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) "Track" else "Pin",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                TextButton(
                    onClick = onCycleIpd,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ar_vr_ipd_button")
                ) {
                    Text(
                        text = "IPD ${vrIpdMm}mm",
                        color = Color.White,
                        style = CadNumericStyle
                    )
                }

                TextButton(
                    onClick = onToggleLensFit,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ar_vr_lens_button")
                ) {
                    Text(
                        text = if (vrLensFit) "Lens: Fit" else "Lens: Full",
                        color = Color(0xFF93C5FD),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (hasFoundationBelowAxis || showFoundation) {
                    TextButton(
                        onClick = onToggleFoundation,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("ar_vr_foundation_button")
                    ) {
                        Text(
                            text = if (showFoundation) "Fnd: On" else "Fnd: Buried",
                            color = if (showFoundation) Color(0xFF6EE7B7) else Color(0xFFFCD34D),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // Compact bottom readout outside lens center
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color(0xB8101622),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .padding(bottom = 8.dp)
                .testTag("ar_vr_status_pill")
        ) {
            Text(
                text = "VR Box · $scaleLabel · $projectedSizeText · ${
                    if (trackingStatus.isPlaced) "Locked (tap to re-pin)" else "Tap screen to place"
                }",
                color = Color.White.copy(alpha = 0.9f),
                style = CadNumericStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun TargetAimingReticle(
    isObjectTracker: Boolean,
    isLocked: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val accent = when {
        isLocked -> Color(0xFF20D8A4)
        isObjectTracker -> Color(0xFFFFB300)
        else -> Color(0xFF38BDF8)
    }
    val boxSize = when {
        isObjectTracker && compact -> 144.dp
        isObjectTracker -> 180.dp
        compact -> 56.dp
        else -> 72.dp
    }
    Canvas(modifier = modifier.size(boxSize)) {
        val w = size.width
        val h = size.height
        val stroke = 3.dp.toPx()
        if (isObjectTracker) {
            val bracket = w * 0.22f
            // Top-left
            drawLine(accent, Offset(0f, 0f), Offset(bracket, 0f), stroke, StrokeCap.Round)
            drawLine(accent, Offset(0f, 0f), Offset(0f, bracket), stroke, StrokeCap.Round)
            // Top-right
            drawLine(accent, Offset(w, 0f), Offset(w - bracket, 0f), stroke, StrokeCap.Round)
            drawLine(accent, Offset(w, 0f), Offset(w, bracket), stroke, StrokeCap.Round)
            // Bottom-left
            drawLine(accent, Offset(0f, h), Offset(bracket, h), stroke, StrokeCap.Round)
            drawLine(accent, Offset(0f, h), Offset(0f, h - bracket), stroke, StrokeCap.Round)
            // Bottom-right
            drawLine(accent, Offset(w, h), Offset(w - bracket, h), stroke, StrokeCap.Round)
            drawLine(accent, Offset(w, h), Offset(w, h - bracket), stroke, StrokeCap.Round)
            // Center crosshair
            val midX = w * 0.5f
            val midY = h * 0.5f
            drawLine(accent.copy(alpha = 0.7f), Offset(midX - 10f, midY), Offset(midX + 10f, midY), 2f)
            drawLine(accent.copy(alpha = 0.7f), Offset(midX, midY - 10f), Offset(midX, midY + 10f), 2f)
        } else {
            val r = w * 0.35f
            drawCircle(
                color = accent.copy(alpha = 0.85f),
                radius = r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawCircle(color = accent, radius = 4.dp.toPx())
        }
    }
}

private fun readBackCameraSensorOrientation(context: Context): Int {
    return try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return 90
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            }
        }
        90
    } catch (_: Throwable) {
        90
    }
}
