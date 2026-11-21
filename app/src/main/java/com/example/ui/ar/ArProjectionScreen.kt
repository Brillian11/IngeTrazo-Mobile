package com.example.ui.ar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Vrpano
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class ArCoreReadiness {
    SUPPORTED_INSTALLED,
    NEEDS_INSTALL_WITH_PLAY_STORE,
    UNAVAILABLE_NO_AR_SERVICES,
    CHECKING
}

enum class ArControlTab(val label: String, val tag: String) {
    MODE("Mode", "ar_tab_mode"),
    STYLE("Render", "ar_tab_style"),
    RECORD("Record", "ar_tab_record"),
    LIGHT("Light", "ar_tab_light"),
    SCALE("Scale", "ar_tab_scale"),
    DOT("Dot", "ar_tab_dot"),
    ADJUST("Adjust", "ar_tab_adjust")
}

internal object ArCoreAvailabilityHelper {
    private const val ARCORE_PACKAGE = "com.google.ar.core"
    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val PLAY_INSTALL_ACTION = "com.google.android.play.core.install.BIND_INSTALL_SERVICE"

    fun isArCorePackageInstalled(context: Context): Boolean = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(ARCORE_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ARCORE_PACKAGE, 0)
        }
        true
    } catch (_: Throwable) {
        false
    }

    fun hasPlayStoreInstallService(context: Context): Boolean = try {
        val pm = context.packageManager
        val intent = Intent(PLAY_INSTALL_ACTION).setPackage(PLAY_STORE_PACKAGE)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveService(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveService(intent, 0)
        }
        resolved != null
    } catch (_: Throwable) {
        false
    }

    fun evaluateReadiness(context: Context): ArCoreReadiness {
        // Guard: if com.google.ar.core is not installed, do NOT call ArCoreApk.checkAvailability()
        // or Session() automatically, because ARCore's InstallService attempts to bind to Play Store
        // and fails with "Play Store install service could not be bound." on emulators/AOSP devices.
        if (!isArCorePackageInstalled(context)) {
            return if (hasPlayStoreInstallService(context)) {
                ArCoreReadiness.NEEDS_INSTALL_WITH_PLAY_STORE
            } else {
                ArCoreReadiness.UNAVAILABLE_NO_AR_SERVICES
            }
        }
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCoreReadiness.SUPPORTED_INSTALLED
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    if (hasPlayStoreInstallService(context)) {
                        ArCoreReadiness.NEEDS_INSTALL_WITH_PLAY_STORE
                    } else {
                        ArCoreReadiness.UNAVAILABLE_NO_AR_SERVICES
                    }
                }
                ArCoreApk.Availability.UNKNOWN_CHECKING -> ArCoreReadiness.CHECKING
                else -> ArCoreReadiness.UNAVAILABLE_NO_AR_SERVICES
            }
        } catch (_: Throwable) {
            ArCoreReadiness.UNAVAILABLE_NO_AR_SERVICES
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ArProjectionScreen(
    document: CadDocument,
    unitSystem: UnitSystem = document.sourceUnits,
    precisionDecimals: Int = document.sourceDecimals,
    initialTrackingDot: ArModelTrackingDot? = null,
    onUpdateTrackingDot: (ArModelTrackingDot) -> Unit = {},
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
    var realisticRenderEnabled by remember(document.documentKey) { mutableStateOf(false) }
    var lightSettings by remember(document.documentKey) { mutableStateOf(ArLightSettings()) }
    val effectiveBounds = remember(document.documentKey, showFoundation) {
        ArGeometryClipper.computeEffectiveBounds(document, showFoundation)
    }
    var selectedScale by remember(document.documentKey) {
        mutableFloatStateOf(ArScaleMath.recommendedInitialScale(effectiveBounds))
    }
    var rotationDeg by remember(document.documentKey) { mutableFloatStateOf(0f) }
    var verticalOffsetMeters by remember(document.documentKey) { mutableFloatStateOf(0f) }
    var trackingDot by remember(document.documentKey, initialTrackingDot) {
        mutableStateOf(initialTrackingDot ?: ArTrackingDotMath.defaultTrackingDot(document, effectiveBounds))
    }
    var anchorOrigin by remember(document.documentKey) {
        mutableStateOf(ArAnchorOrigin.MODEL_DOT)
    }
    var tapActionMode by remember(document.documentKey) {
        mutableStateOf(ArTapActionMode.MOVE_IN_WORLD)
    }
    var show3dDotPicker by remember(document.documentKey) { mutableStateOf(false) }
    var trackingMode by remember(document.documentKey) { mutableStateOf(ArTrackingMode.OBJECT_SURFACE) }
    var targetWidthMeters by remember(document.documentKey) { mutableFloatStateOf(0.20f) }
    var captureFeedback by remember(document.documentKey) { mutableStateOf<String?>(null) }
    var placementLocked by remember(document.documentKey) { mutableStateOf(false) }
    var lockFeedbackHint by remember(document.documentKey) { mutableStateOf<String?>(null) }
    var showUnitDialog by remember { mutableStateOf(false) }

    // AR Photo & Video Recording state
    val coroutineScope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var glSurfaceViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }
    var captureMode by remember(document.documentKey) { mutableStateOf(ArCaptureMode.PHOTO) }
    var isRecordingVideo by remember(document.documentKey) { mutableStateOf(false) }
    var recordingStartMs by remember(document.documentKey) { mutableLongStateOf(0L) }
    var recordingElapsedMs by remember(document.documentKey) { mutableLongStateOf(0L) }
    var isCapturingPhoto by remember(document.documentKey) { mutableStateOf(false) }
    var shutterFlashVisible by remember(document.documentKey) { mutableStateOf(false) }
    var lastCapturedMedia by remember(document.documentKey) { mutableStateOf<ArCapturedMedia?>(null) }
    var recordStatusMessage by remember(document.documentKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            while (isRecordingVideo) {
                recordingElapsedMs = (SystemClock.elapsedRealtime() - recordingStartMs).coerceAtLeast(0L)
                delay(250L)
            }
        } else {
            recordingElapsedMs = 0L
        }
    }

    LaunchedEffect(shutterFlashVisible) {
        if (shutterFlashVisible) {
            delay(140L)
            shutterFlashVisible = false
        }
    }

    LaunchedEffect(recordStatusMessage) {
        if (recordStatusMessage != null && !isRecordingVideo && !isCapturingPhoto) {
            delay(3600L)
            recordStatusMessage = null
        }
    }

    LaunchedEffect(lockFeedbackHint) {
        if (lockFeedbackHint != null) {
            delay(2400L)
            lockFeedbackHint = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var arReadiness by remember {
        mutableStateOf(ArCoreAvailabilityHelper.evaluateReadiness(context))
    }
    var userRequestedInstall by remember { mutableStateOf(false) }

    LaunchedEffect(arReadiness) {
        if (arReadiness == ArCoreReadiness.CHECKING) {
            repeat(8) {
                delay(200L)
                val next = ArCoreAvailabilityHelper.evaluateReadiness(context)
                if (next != ArCoreReadiness.CHECKING) {
                    arReadiness = next
                    return@LaunchedEffect
                }
            }
            arReadiness = ArCoreReadiness.UNAVAILABLE_NO_AR_SERVICES
        }
    }

    val arRenderer = remember(document.documentKey) {
        ArCoreRenderer(
            document = document,
            initialScale = selectedScale,
            initialShowFoundation = showFoundation,
            initialTrackingDot = trackingDot
        ) { status ->
            trackingStatus = status
        }
    }

    val takeArPhoto: () -> Unit = {
        val surfaceView = glSurfaceViewRef
        if (surfaceView == null) {
            recordStatusMessage = "AR camera view is initializing…"
        } else if (!isCapturingPhoto) {
            isCapturingPhoto = true
            shutterFlashVisible = true
            recordStatusMessage = "Capturing photo…"
            ArMediaStoreHelper.captureGlSurfacePhoto(
                glSurfaceView = surfaceView,
                context = context,
                modelTitle = document.title
            ) { media, err ->
                isCapturingPhoto = false
                if (media != null) {
                    lastCapturedMedia = media
                    recordStatusMessage = if (media.savedToGallery) {
                        "Photo saved to Gallery (Pictures/IngeTrazo AR)"
                    } else {
                        "Photo saved · Tap View or Share"
                    }
                } else {
                    recordStatusMessage = err ?: "Could not capture AR photo."
                }
            }
        }
    }

    val stopArVideoRecording: () -> Unit = {
        if (isRecordingVideo || arRenderer.isRecordingVideo) {
            isRecordingVideo = false
            recordStatusMessage = "Saving video…"
            arRenderer.stopVideoRecording { tempFile, durationMs, err ->
                mainHandler.post {
                    if (tempFile != null && err == null) {
                        coroutineScope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                ArMediaStoreHelper.publishVideoToGallery(
                                    context = context,
                                    tempVideoFile = tempFile,
                                    modelTitle = document.title,
                                    durationMs = durationMs
                                )
                            }
                            if (saved != null) {
                                lastCapturedMedia = saved
                                val durText = ArVideoSizeCalculator.formatElapsedDuration(durationMs)
                                recordStatusMessage = if (saved.savedToGallery) {
                                    "Video ($durText) saved to Gallery"
                                } else {
                                    "Video ($durText) saved · Tap View or Share"
                                }
                            } else {
                                recordStatusMessage = "Could not save video to Gallery."
                            }
                        }
                    } else {
                        recordStatusMessage = err ?: "Video recording stopped."
                    }
                }
            }
        }
    }

    val startArVideoRecording: () -> Unit = {
        if (!isRecordingVideo) {
            val tempFile = ArMediaStoreHelper.createTempVideoFile(context, document.title)
            recordingStartMs = SystemClock.elapsedRealtime()
            recordingElapsedMs = 0L
            isRecordingVideo = true
            recordStatusMessage = null
            arRenderer.startVideoRecording(tempFile) { err ->
                mainHandler.post {
                    isRecordingVideo = false
                    recordStatusMessage = err
                }
            }
        }
    }

    DisposableEffect(arRenderer) {
        arRenderer.onModelDotPickedInAr = { pickedDot, newOrigin, newTapMode ->
            trackingDot = pickedDot
            anchorOrigin = newOrigin
            tapActionMode = newTapMode
            onUpdateTrackingDot(pickedDot)
        }
        arRenderer.onPlacementLockChanged = { locked ->
            placementLocked = locked
            if (locked) {
                lockFeedbackHint = null
            }
        }
        onDispose {
            arRenderer.onModelDotPickedInAr = null
            arRenderer.onPlacementLockChanged = null
        }
    }

    LaunchedEffect(
        arRenderer,
        selectedScale,
        rotationDeg,
        verticalOffsetMeters,
        anchorOrigin,
        trackingDot,
        tapActionMode,
        alwaysLevel,
        showFoundation,
        showTextures,
        realisticRenderEnabled,
        lightSettings,
        placementLocked,
        trackingMode,
        vrModeEnabled,
        vrIpdMm,
        vrLensFit
    ) {
        arRenderer.modelScale = selectedScale
        arRenderer.rotationDeg = rotationDeg
        arRenderer.verticalOffsetMeters = verticalOffsetMeters
        arRenderer.anchorOrigin = anchorOrigin
        arRenderer.trackingDot = trackingDot
        arRenderer.tapActionMode = tapActionMode
        arRenderer.alwaysLevel = alwaysLevel
        arRenderer.showFoundation = showFoundation
        arRenderer.showTextures = showTextures
        arRenderer.realisticRenderEnabled = realisticRenderEnabled
        arRenderer.lightSettings = lightSettings
        arRenderer.placementLocked = placementLocked
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

    // Stream hardware ambient light sensor (Lux) into the AR lighting fusion engine (zero permissions required)
    DisposableEffect(context, arRenderer) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        val sensorListener = if (lightSensor != null) {
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val lux = event?.values?.firstOrNull()
                    if (lux != null && lux.isFinite() && lux >= 0f) {
                        arRenderer.ambientLux = lux
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
        } else {
            null
        }
        if (sensorManager != null && lightSensor != null && sensorListener != null) {
            try {
                sensorManager.registerListener(sensorListener, lightSensor, SensorManager.SENSOR_DELAY_UI)
            } catch (_: Throwable) {
                // Ignore devices that disallow background light sensor registration
            }
        }
        onDispose {
            if (sensorManager != null && sensorListener != null) {
                sensorManager.unregisterListener(sensorListener)
            }
            arRenderer.ambientLux = null
        }
    }

    var arSession by remember { mutableStateOf<Session?>(null) }

    LaunchedEffect(hasCameraPermission, arReadiness, userRequestedInstall) {
        if (hasCameraPermission && arReadiness == ArCoreReadiness.SUPPORTED_INSTALLED && arSession == null) {
            try {
                val created = Session(context).apply {
                    val baseConfig = Config(this).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                        if (isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                        }
                    }
                    try {
                        configure(baseConfig)
                    } catch (_: Throwable) {
                        // Graceful fallback for devices without Environmental HDR light estimation
                        baseConfig.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                        configure(baseConfig)
                    }
                }
                created.resume()
                val hwInfo = readBackCameraHardwareInfo(context)
                arRenderer.cameraSensorOrientationDeg = hwInfo.sensorOrientationDeg
                arRenderer.fallbackApertureFNumber = hwInfo.defaultApertureFNumber
                arRenderer.displayRotation = context.readCurrentDisplayRotation()
                arRenderer.session = created
                arSession = created
                arStatusError = null
            } catch (e: UnavailableException) {
                arStatusError = e.localizedMessage ?: "Google Play Services for AR is unavailable on this device."
            } catch (e: Throwable) {
                arStatusError = e.localizedMessage ?: "ARCore session unavailable on this device."
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (arRenderer.isRecordingVideo) {
                arRenderer.stopVideoRecording { file, _, _ ->
                    file?.delete()
                }
            }
            if (vrModeEnabled) {
                restoreOrientationAfterVr(context.findActivity(), preVrOrientation)
            }
            arSession?.pause()
            arSession?.close()
            arRenderer.session = null
            glSurfaceViewRef = null
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
        } else if (arReadiness != ArCoreReadiness.SUPPORTED_INSTALLED || arStatusError != null) {
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
                        text = when {
                            arReadiness == ArCoreReadiness.CHECKING -> "Checking ARCore Availability…"
                            arReadiness == ArCoreReadiness.NEEDS_INSTALL_WITH_PLAY_STORE ->
                                "Google Play Services for AR Required"
                            else -> "ARCore Unavailable on This Device"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = arStatusError ?: when (arReadiness) {
                            ArCoreReadiness.CHECKING -> "Verifying ARCore services…"
                            ArCoreReadiness.NEEDS_INSTALL_WITH_PLAY_STORE ->
                                "Install or update Google Play Services for AR to project ${document.title} in real-world AR."
                            else ->
                                "Google Play Services for AR is not installed or Play Store services are unavailable on this device/emulator. You can still configure the Move Tool tracking dot in 3D."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    if (arReadiness == ArCoreReadiness.NEEDS_INSTALL_WITH_PLAY_STORE && arStatusError == null) {
                        Button(
                            onClick = {
                                val act = context.findActivity()
                                if (act != null && ArCoreAvailabilityHelper.hasPlayStoreInstallService(context)) {
                                    try {
                                        val status = ArCoreApk.getInstance().requestInstall(act, !userRequestedInstall)
                                        userRequestedInstall = true
                                        if (status == ArCoreApk.InstallStatus.INSTALLED) {
                                            arReadiness = ArCoreAvailabilityHelper.evaluateReadiness(context)
                                        }
                                    } catch (t: Throwable) {
                                        arStatusError = t.localizedMessage
                                            ?: "Unable to launch Google Play Services for AR installer."
                                    }
                                } else {
                                    arReadiness = ArCoreReadiness.UNAVAILABLE_NO_AR_SERVICES
                                }
                            },
                            modifier = Modifier.testTag("ar_install_arcore_button")
                        ) {
                            Text("Install ARCore")
                        }
                        Spacer(Modifier.height(10.dp))
                    } else if (arReadiness == ArCoreReadiness.UNAVAILABLE_NO_AR_SERVICES) {
                        FilledTonalButton(
                            onClick = { show3dDotPicker = true },
                            modifier = Modifier.testTag("ar_unavailable_dot_picker_button")
                        ) {
                            Icon(Icons.Default.AdsClick, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Place Tracking Dot in 3D")
                        }
                        Spacer(Modifier.height(10.dp))
                    }
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
                    val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat().coerceAtLeast(18f)
                    var downX = 0f
                    var downY = 0f
                    var downTimeMs = 0L
                    var hadMultiTouch = false
                    GLSurfaceView(ctx).apply {
                        glSurfaceViewRef = this
                        preserveEGLContextOnPause = true
                        setEGLContextClientVersion(3)
                        setEGLConfigChooser(RecordableEglConfigChooser())
                        setRenderer(arRenderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        setOnTouchListener { _, ev ->
                            when (ev.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    downX = ev.x
                                    downY = ev.y
                                    downTimeMs = ev.eventTime
                                    hadMultiTouch = false
                                }
                                MotionEvent.ACTION_POINTER_DOWN -> {
                                    hadMultiTouch = true
                                }
                                MotionEvent.ACTION_UP -> {
                                    val movedDist = hypot(ev.x - downX, ev.y - downY)
                                    val pressDuration = ev.eventTime - downTimeMs
                                    val isIntentionalSingleTap = !hadMultiTouch &&
                                        movedDist <= touchSlop &&
                                        pressDuration in 0L..450L
                                    if (isIntentionalSingleTap) {
                                        val isPickingDot = arRenderer.tapActionMode == ArTapActionMode.PICK_DOT_ON_MODEL
                                        if (arRenderer.isModelPlaced && arRenderer.placementLocked && !isPickingDot) {
                                            lockFeedbackHint = "Position locked · Unlock in Mode tab to move"
                                        } else if (arRenderer.vrModeEnabled) {
                                            arRenderer.requestPlaceAtCenter()
                                        } else {
                                            arRenderer.requestPlaceAt(ev.x, ev.y)
                                        }
                                    }
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
                trackingDot = trackingDot,
                tapActionMode = tapActionMode,
                alwaysLevel = alwaysLevel,
                showFoundation = showFoundation,
                showTextures = showTextures,
                realisticRenderEnabled = realisticRenderEnabled,
                lightSettings = lightSettings,
                placementLocked = placementLocked,
                lockFeedbackHint = lockFeedbackHint,
                hasFoundationBelowAxis = hasFoundationBelowAxis,
                trackingMode = trackingMode,
                targetWidthMeters = targetWidthMeters,
                captureFeedback = captureFeedback,
                vrModeEnabled = vrModeEnabled,
                vrIpdMm = vrIpdMm,
                vrLensFit = vrLensFit,
                captureMode = captureMode,
                isRecordingVideo = isRecordingVideo,
                recordingElapsedMs = recordingElapsedMs,
                isCapturingPhoto = isCapturingPhoto,
                shutterFlashVisible = shutterFlashVisible,
                lastCapturedMedia = lastCapturedMedia,
                recordStatusMessage = recordStatusMessage,
                onBack = {
                    if (isRecordingVideo) {
                        stopArVideoRecording()
                    }
                    onBack()
                },
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
                    lockFeedbackHint = null
                    placementLocked = false
                    arRenderer.placementLocked = false
                    tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                    arRenderer.tapActionMode = ArTapActionMode.MOVE_IN_WORLD
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
                    arRenderer.resetWorldAnchorShift()
                },
                onSelectTrackingDot = { newDot ->
                    trackingDot = newDot
                    anchorOrigin = ArAnchorOrigin.MODEL_DOT
                    tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                    arRenderer.trackingDot = newDot
                    arRenderer.anchorOrigin = ArAnchorOrigin.MODEL_DOT
                    arRenderer.tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                    arRenderer.resetWorldAnchorShift()
                    onUpdateTrackingDot(newDot)
                },
                onSelectDotPreset = { preset ->
                    val resolved = ArTrackingDotMath.resolvePresetDot(document, effectiveBounds, preset)
                    trackingDot = resolved
                    anchorOrigin = ArAnchorOrigin.MODEL_DOT
                    tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                    arRenderer.trackingDot = resolved
                    arRenderer.anchorOrigin = ArAnchorOrigin.MODEL_DOT
                    arRenderer.tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                    arRenderer.resetWorldAnchorShift()
                    onUpdateTrackingDot(resolved)
                },
                onSelectTapActionMode = { mode ->
                    tapActionMode = mode
                    arRenderer.tapActionMode = mode
                    if (mode == ArTapActionMode.PICK_DOT_ON_MODEL && !trackingStatus.isPlaced) {
                        show3dDotPicker = true
                    } else if (mode == ArTapActionMode.MOVE_IN_WORLD && trackingStatus.isPlaced) {
                        placementLocked = false
                        arRenderer.placementLocked = false
                        lockFeedbackHint = "Move unlocked · Tap a spot to move model"
                    }
                },
                onOpen3dDotPicker = {
                    show3dDotPicker = true
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
                onToggleRealisticRender = { enabled ->
                    realisticRenderEnabled = enabled
                    arRenderer.realisticRenderEnabled = enabled
                },
                onUpdateLightSettings = { next ->
                    lightSettings = next
                    arRenderer.lightSettings = next
                },
                onTogglePlacementLock = { locked ->
                    placementLocked = locked
                    arRenderer.placementLocked = locked
                    lockFeedbackHint = if (locked) {
                        "Position locked · Walk around freely"
                    } else {
                        "Move unlocked · Tap a new spot to place"
                    }
                },
                onSelectTrackingMode = { mode ->
                    trackingMode = mode
                    arRenderer.trackingMode = mode
                    captureFeedback = null
                    lockFeedbackHint = null
                    placementLocked = false
                    arRenderer.placementLocked = false
                    arRenderer.clearAnchor()
                },
                onSelectTargetWidth = { widthMeters ->
                    targetWidthMeters = widthMeters
                },
                onPinAtCenter = {
                    captureFeedback = null
                    tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                    arRenderer.tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                    arRenderer.requestPlaceAtCenter()
                },
                onShowTrackingDotGizmoChanged = { show ->
                    arRenderer.showTrackingDotGizmo = show
                },
                onCaptureObjectTarget = {
                    captureFeedback = "Capturing target..."
                    arRenderer.requestCaptureObjectTarget(targetWidthMeters) { _, message ->
                        captureFeedback = message
                    }
                },
                onSelectCaptureMode = { mode ->
                    if (!isRecordingVideo) {
                        captureMode = mode
                    }
                },
                onTakePhoto = takeArPhoto,
                onToggleVideoRecording = {
                    if (isRecordingVideo) {
                        stopArVideoRecording()
                    } else {
                        startArVideoRecording()
                    }
                },
                onViewCapturedMedia = { media ->
                    ArMediaStoreHelper.openCapturedMedia(context, media)
                },
                onShareCapturedMedia = { media ->
                    ArMediaStoreHelper.shareCapturedMedia(context, media)
                }
            )
        }
    }

    if (show3dDotPicker) {
        ArModelDotPickerDialog(
            document = document,
            effectiveBounds = effectiveBounds,
            initialDot = trackingDot,
            unitSystem = unitSystem,
            precisionDecimals = precisionDecimals,
            onConfirmDot = { picked ->
                trackingDot = picked
                anchorOrigin = ArAnchorOrigin.MODEL_DOT
                tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                arRenderer.trackingDot = picked
                arRenderer.anchorOrigin = ArAnchorOrigin.MODEL_DOT
                arRenderer.tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                arRenderer.resetWorldAnchorShift()
                onUpdateTrackingDot(picked)
                captureFeedback = "Tracking dot placed on ${picked.label} · Tap real world to place model"
                show3dDotPicker = false
            },
            onDismiss = {
                show3dDotPicker = false
                if (!trackingStatus.isPlaced) {
                    tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                    arRenderer.tapActionMode = ArTapActionMode.MOVE_IN_WORLD
                }
            }
        )
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
    trackingDot: ArModelTrackingDot = ArTrackingDotMath.defaultTrackingDot(document),
    tapActionMode: ArTapActionMode = ArTapActionMode.MOVE_IN_WORLD,
    alwaysLevel: Boolean = true,
    showFoundation: Boolean = false,
    showTextures: Boolean = true,
    realisticRenderEnabled: Boolean = false,
    lightSettings: ArLightSettings = ArLightSettings(),
    placementLocked: Boolean = trackingStatus.isPlacementLocked,
    lockFeedbackHint: String? = null,
    hasFoundationBelowAxis: Boolean = ArGeometryClipper.hasFoundationBelowAxis(document.bounds),
    trackingMode: ArTrackingMode,
    targetWidthMeters: Float,
    captureFeedback: String?,
    vrModeEnabled: Boolean = false,
    vrIpdMm: Int = 64,
    vrLensFit: Boolean = false,
    captureMode: ArCaptureMode = ArCaptureMode.PHOTO,
    isRecordingVideo: Boolean = false,
    recordingElapsedMs: Long = 0L,
    isCapturingPhoto: Boolean = false,
    shutterFlashVisible: Boolean = false,
    lastCapturedMedia: ArCapturedMedia? = null,
    recordStatusMessage: String? = null,
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
    onSelectTrackingDot: (ArModelTrackingDot) -> Unit = {},
    onSelectDotPreset: (ArTrackingDotPreset) -> Unit = {},
    onSelectTapActionMode: (ArTapActionMode) -> Unit = {},
    onOpen3dDotPicker: () -> Unit = {},
    onToggleAlwaysLevel: (Boolean) -> Unit = {},
    onToggleShowFoundation: (Boolean) -> Unit = {},
    onToggleShowTextures: (Boolean) -> Unit = {},
    onToggleRealisticRender: (Boolean) -> Unit = {},
    onUpdateLightSettings: (ArLightSettings) -> Unit = {},
    onTogglePlacementLock: (Boolean) -> Unit = {},
    onSelectTrackingMode: (ArTrackingMode) -> Unit,
    onSelectTargetWidth: (Float) -> Unit,
    onPinAtCenter: () -> Unit,
    onShowTrackingDotGizmoChanged: (Boolean) -> Unit = {},
    onCaptureObjectTarget: () -> Unit,
    onSelectCaptureMode: (ArCaptureMode) -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onToggleVideoRecording: () -> Unit = {},
    onViewCapturedMedia: (ArCapturedMedia) -> Unit = {},
    onShareCapturedMedia: (ArCapturedMedia) -> Unit = {}
) {
    // Starts minimized so the AR camera view stays unobstructed; tapping any section tab expands ONLY that section.
    var activeTab by remember { mutableStateOf<ArControlTab?>(null) }
    var showArMoreMenu by remember { mutableStateOf(false) }

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
            realisticRenderEnabled = realisticRenderEnabled,
            hasFoundationBelowAxis = hasFoundationBelowAxis,
            onExitVr = { onToggleVrMode(false) },
            onPinAtCenter = onPinAtCenter,
            onCaptureObjectTarget = onCaptureObjectTarget,
            onCycleIpd = onCycleVrIpd,
            onToggleLensFit = onToggleVrLensFit,
            onToggleFoundation = { onToggleShowFoundation(!showFoundation) },
            onToggleRealisticRender = { onToggleRealisticRender(!realisticRenderEnabled) }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        val density = LocalDensity.current
        val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val amberAccent = if (isDarkTheme) Color(0xFFFBBF24) else Color(0xFFD97706)
        val amberBadgeText = if (isDarkTheme) Color(0xFFFDE68A) else Color(0xFFB45309)

        // Live 2D projected Tracking Dot marker on the 3D model in AR view.
        // Hidden during normal AR viewing so the model stays clean; shown only when editing/picking the dot.
        val dotScreenPos = trackingStatus.trackingDotScreenPos
        val activeDotForOverlay = trackingStatus.trackingDot ?: trackingDot
        val showMoveDotOverlay = activeTab == ArControlTab.DOT ||
            tapActionMode == ArTapActionMode.PICK_DOT_ON_MODEL
        LaunchedEffect(showMoveDotOverlay) {
            onShowTrackingDotGizmoChanged(showMoveDotOverlay)
        }
        if (trackingStatus.isPlaced && dotScreenPos != null && showMoveDotOverlay) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIngeTrazoMoveDotMarker(
                    center = Offset(dotScreenPos.x.toFloat(), dotScreenPos.y.toFloat()),
                    snapType = activeDotForOverlay.snapType
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFFE65100).copy(alpha = 0.94f),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (dotScreenPos.x + 14 * density.density).roundToInt(),
                            y = (dotScreenPos.y - 26 * density.density).roundToInt()
                        )
                    }
                    .testTag("ar_model_tracking_dot_badge")
            ) {
                Text(
                    text = "Move Dot · ${activeDotForOverlay.label}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

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
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = if (isLandscape) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("ar_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to 3D Viewer",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AR · $scaleLabel",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
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
                    Box {
                        IconButton(
                            onClick = { showArMoreMenu = true },
                            modifier = Modifier.testTag("ar_more_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "AR Menu",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        DropdownMenu(
                            expanded = showArMoreMenu,
                            onDismissRequest = { showArMoreMenu = false },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (realisticRenderEnabled) {
                                            "Realistic Render: ON"
                                        } else {
                                            "Realistic Render: OFF"
                                        },
                                        fontWeight = if (realisticRenderEnabled) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (realisticRenderEnabled) amberAccent else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = if (realisticRenderEnabled) {
                                            amberAccent
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                },
                                onClick = {
                                    showArMoreMenu = false
                                    onToggleRealisticRender(!realisticRenderEnabled)
                                },
                                modifier = Modifier
                                    .semantics { selected = realisticRenderEnabled }
                                    .testTag("ar_realistic_render_top_toggle")
                            )
                            DropdownMenuItem(
                                text = { Text("Units (${unitSystem.symbol})") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Straighten,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showArMoreMenu = false
                                    onOpenUnitDialog()
                                },
                                modifier = Modifier.testTag("ar_units_button")
                            )
                            DropdownMenuItem(
                                text = { Text("VR Box Mode") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Vrpano,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showArMoreMenu = false
                                    onToggleVrMode(true)
                                },
                                modifier = Modifier.testTag("ar_vr_mode_button")
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
                                    showArMoreMenu = false
                                    onToggleLandscape(isLandscape)
                                },
                                modifier = Modifier.testTag("ar_landscape_toggle")
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear Anchor") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showArMoreMenu = false
                                    onClearAnchor()
                                },
                                modifier = Modifier.testTag("ar_clear_anchor_button")
                            )
                        }
                    }
                }
            }

            // Live Physical Scale & Object Status Banner
            val bannerContentColor = when (trackingStatus.phase) {
                ObjectTrackingPhase.STICKING_TO_OBJECT -> MaterialTheme.colorScheme.onTertiaryContainer
                ObjectTrackingPhase.QUALITY_ERROR -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (trackingStatus.phase) {
                    ObjectTrackingPhase.STICKING_TO_OBJECT -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f)
                    ObjectTrackingPhase.QUALITY_ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f)
                    else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
                },
                contentColor = bannerContentColor,
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = recordStatusMessage ?: lockFeedbackHint ?: captureFeedback ?: "Projected size: $projectedSizeText",
                        style = CadNumericStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = bannerContentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (realisticRenderEnabled) {
                        Surface(
                            onClick = { activeTab = if (activeTab == ArControlTab.LIGHT) null else ArControlTab.LIGHT },
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFFF59E0B).copy(alpha = 0.22f),
                            contentColor = amberBadgeText,
                            border = BorderStroke(1.dp, amberAccent.copy(alpha = 0.55f)),
                            modifier = Modifier.testTag("ar_light_status_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WbSunny,
                                    contentDescription = "Open Light controls",
                                    tint = amberAccent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = trackingStatus.lightModeLabel ?: ArLightModeLabel.DEFAULT_STUDIO.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = amberBadgeText,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Text(
                        text = if (selectedScale >= 0.995f) "TRUE 1:1" else scaleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Live Recording status pill (stays accessible even when bottom dock is minimized)
            if (isRecordingVideo) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xE6B91C1C),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                    modifier = Modifier.testTag("ar_recording_live_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(Color.White, CircleShape)
                        )
                        Text(
                            text = "REC · ${ArVideoSizeCalculator.formatElapsedDuration(recordingElapsedMs)}",
                            style = CadNumericStyle,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        TextButton(
                            onClick = onTakePhoto,
                            enabled = !isCapturingPhoto,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("ar_recording_live_snap_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Take snapshot while recording",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Surface(
                            onClick = onToggleVideoRecording,
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White,
                            modifier = Modifier.testTag("ar_recording_live_stop_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop recording",
                                    tint = Color(0xFFB91C1C),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Stop",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB91C1C)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Crisp camera shutter flash feedback overlay when taking an AR photo
        AnimatedVisibility(
            visible = shutterFlashVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.55f))
            )
        }

        // Compact Tabbed Control Dock: Side dock in Landscape vs Slim Bottom Tab Bar in Portrait.
        // Only the selected section opens (bounded height) so the live AR viewport stays unobstructed.
        val isSectionOpen = activeTab != null
        val dockModifier = if (isLandscape) {
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom + WindowInsetsSides.Top)
                )
                .padding(top = 64.dp, end = 12.dp, bottom = 10.dp)
                .width(if (isSectionOpen) 310.dp else 284.dp)
                .testTag("ar_controls_panel")
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .fillMaxWidth()
                .testTag("ar_controls_panel")
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            modifier = dockModifier
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Active Section Content Drawer (Compact & Scrollable; only 1 section shown at a time)
                AnimatedVisibility(visible = isSectionOpen) {
                    val currentTab = activeTab
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (isLandscape) 230.dp else 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (currentTab) {
                            ArControlTab.MODE -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
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
                                        label = { Text("Pin Object") },
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
                                        label = { Text("Track Target") },
                                        modifier = Modifier.testTag("ar_mode_tracker")
                                    )
                                    if (trackingStatus.isPlaced && trackingMode == ArTrackingMode.OBJECT_SURFACE) {
                                        FilterChip(
                                            selected = placementLocked,
                                            onClick = { onTogglePlacementLock(!placementLocked) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (placementLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text(if (placementLocked) "Locked" else "Move Unlocked") },
                                            modifier = Modifier.testTag("ar_mode_lock_toggle")
                                        )
                                    }
                                }

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
                            }

                            ArControlTab.STYLE -> {
                                Text(
                                    text = "Rendering & Surface Appearance",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterChip(
                                        selected = realisticRenderEnabled,
                                        onClick = { onToggleRealisticRender(!realisticRenderEnabled) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = {
                                            Text(if (realisticRenderEnabled) "Realistic: ON" else "Realistic")
                                        },
                                        modifier = Modifier.testTag("ar_realistic_render_chip")
                                    )
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
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                            }

                            ArControlTab.RECORD -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ar_record_section"),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isRecordingVideo) {
                                                "Recording AR · ${ArVideoSizeCalculator.formatElapsedDuration(recordingElapsedMs)}"
                                            } else {
                                                "AR Camera · Photo & Video"
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isRecordingVideo) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Clean AR View",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FilterChip(
                                            selected = captureMode == ArCaptureMode.PHOTO,
                                            onClick = { onSelectCaptureMode(ArCaptureMode.PHOTO) },
                                            enabled = !isRecordingVideo,
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.PhotoCamera,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text("Photo") },
                                            modifier = Modifier.testTag("ar_record_mode_photo")
                                        )
                                        FilterChip(
                                            selected = captureMode == ArCaptureMode.VIDEO,
                                            onClick = { onSelectCaptureMode(ArCaptureMode.VIDEO) },
                                            enabled = !isRecordingVideo,
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Videocam,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text("Video") },
                                            modifier = Modifier.testTag("ar_record_mode_video")
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val isVideoMode = captureMode == ArCaptureMode.VIDEO
                                        Button(
                                            onClick = {
                                                if (isVideoMode) {
                                                    onToggleVideoRecording()
                                                } else {
                                                    onTakePhoto()
                                                }
                                            },
                                            enabled = if (isVideoMode) true else !isCapturingPhoto,
                                            colors = if (isVideoMode) {
                                                ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFFDC2626),
                                                    contentColor = Color.White
                                                )
                                            } else {
                                                ButtonDefaults.buttonColors()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("ar_record_action_button")
                                        ) {
                                            Icon(
                                                imageVector = when {
                                                    !isVideoMode -> Icons.Default.PhotoCamera
                                                    isRecordingVideo -> Icons.Default.Stop
                                                    else -> Icons.Default.FiberManualRecord
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = when {
                                                    !isVideoMode && isCapturingPhoto -> "Saving Photo…"
                                                    !isVideoMode -> "Take Photo"
                                                    isRecordingVideo -> "Stop · ${ArVideoSizeCalculator.formatElapsedDuration(recordingElapsedMs)}"
                                                    else -> "Start Recording"
                                                },
                                                maxLines = 1
                                            )
                                        }

                                        if (isRecordingVideo) {
                                            FilledTonalButton(
                                                onClick = onTakePhoto,
                                                enabled = !isCapturingPhoto,
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag("ar_record_snap_during_video_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PhotoCamera,
                                                    contentDescription = "Snap Photo",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("Snap")
                                            }
                                        }
                                    }

                                    if (lastCapturedMedia != null) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("ar_record_last_capture_row")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (lastCapturedMedia.isVideo) {
                                                        Icons.Default.Videocam
                                                    } else {
                                                        Icons.Default.PhotoCamera
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = if (lastCapturedMedia.isVideo) {
                                                        "Saved Video · ${ArVideoSizeCalculator.formatElapsedDuration(lastCapturedMedia.durationMs)}"
                                                    } else {
                                                        "Saved Photo · Gallery"
                                                    },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                TextButton(
                                                    onClick = { onViewCapturedMedia(lastCapturedMedia) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier
                                                        .heightIn(min = 36.dp)
                                                        .testTag("ar_record_view_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.OpenInNew,
                                                        contentDescription = "View capture",
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(Modifier.width(3.dp))
                                                    Text("View", style = MaterialTheme.typography.labelMedium)
                                                }
                                                TextButton(
                                                    onClick = { onShareCapturedMedia(lastCapturedMedia) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier
                                                        .heightIn(min = 36.dp)
                                                        .testTag("ar_record_share_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Share,
                                                        contentDescription = "Share capture",
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(Modifier.width(3.dp))
                                                    Text("Share", style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            ArControlTab.LIGHT -> {
                                val displayedAz = if (lightSettings.autoDetectLight) {
                                    trackingStatus.detectedLightAzimuthDeg
                                } else {
                                    lightSettings.manualAzimuthDeg
                                }
                                val displayedEl = if (lightSettings.autoDetectLight) {
                                    trackingStatus.detectedLightElevationDeg
                                } else {
                                    lightSettings.manualElevationDeg
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ar_light_section"),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${trackingStatus.lightModeLabel ?: "Scene Light"} · Az ${displayedAz.roundToInt()}° · El ${displayedEl.roundToInt()}°",
                                            style = CadNumericStyle,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { onUpdateLightSettings(ArLightSettings()) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier
                                                .heightIn(min = 32.dp)
                                                .testTag("ar_light_reset_button")
                                        ) {
                                            Text("Reset", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }

                                    val envCond = trackingStatus.environmentConditionLabel
                                    val camSummary = trackingStatus.cameraTelemetrySummary
                                    if (envCond != null || camSummary != null) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("ar_light_telemetry_readout")
                                        ) {
                                            Text(
                                                text = listOfNotNull(envCond, camSummary).joinToString(" · "),
                                                style = CadNumericStyle,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!realisticRenderEnabled) {
                                            FilterChip(
                                                selected = false,
                                                onClick = { onToggleRealisticRender(true) },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.AutoAwesome,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                },
                                                label = { Text("Enable Realistic") },
                                                modifier = Modifier.testTag("ar_light_enable_realistic_chip")
                                            )
                                        }
                                        FilterChip(
                                            selected = lightSettings.autoDetectLight,
                                            onClick = {
                                                onUpdateLightSettings(lightSettings.copy(autoDetectLight = true))
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.WbSunny,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text("Auto Scene") },
                                            modifier = Modifier.testTag("ar_light_auto_chip")
                                        )
                                        FilterChip(
                                            selected = !lightSettings.autoDetectLight,
                                            onClick = {
                                                onUpdateLightSettings(
                                                    lightSettings.copy(
                                                        autoDetectLight = false,
                                                        manualAzimuthDeg = displayedAz,
                                                        manualElevationDeg = displayedEl.coerceIn(15f, 85f)
                                                    )
                                                )
                                            },
                                            label = { Text("Manual Angle") },
                                            modifier = Modifier.testTag("ar_light_manual_chip")
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Bright ${(lightSettings.exposureMultiplier * 100f).roundToInt()}%",
                                            style = CadNumericStyle,
                                            modifier = Modifier.width(86.dp),
                                            maxLines = 1
                                        )
                                        Slider(
                                            value = lightSettings.exposureMultiplier,
                                            onValueChange = { v ->
                                                onUpdateLightSettings(lightSettings.copy(exposureMultiplier = v))
                                            },
                                            valueRange = 0.45f..1.65f,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("ar_light_exposure_slider")
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Shadow ${(lightSettings.shadowStrengthScale * 100f).roundToInt()}%",
                                            style = CadNumericStyle,
                                            modifier = Modifier.width(86.dp),
                                            maxLines = 1
                                        )
                                        Slider(
                                            value = lightSettings.shadowStrengthScale,
                                            onValueChange = { v ->
                                                onUpdateLightSettings(lightSettings.copy(shadowStrengthScale = v))
                                            },
                                            valueRange = 0f..1.55f,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("ar_light_shadow_slider")
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Angle ${displayedAz.roundToInt()}°",
                                            style = CadNumericStyle,
                                            modifier = Modifier.width(86.dp),
                                            maxLines = 1
                                        )
                                        Slider(
                                            value = displayedAz.coerceIn(0f, 360f),
                                            onValueChange = { deg ->
                                                onUpdateLightSettings(
                                                    lightSettings.copy(
                                                        autoDetectLight = false,
                                                        manualAzimuthDeg = deg,
                                                        manualElevationDeg = displayedEl.coerceIn(15f, 85f)
                                                    )
                                                )
                                            },
                                            valueRange = 0f..360f,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("ar_light_azimuth_slider")
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Height ${displayedEl.roundToInt()}°",
                                            style = CadNumericStyle,
                                            modifier = Modifier.width(86.dp),
                                            maxLines = 1
                                        )
                                        Slider(
                                            value = displayedEl.coerceIn(15f, 85f),
                                            onValueChange = { deg ->
                                                onUpdateLightSettings(
                                                    lightSettings.copy(
                                                        autoDetectLight = false,
                                                        manualAzimuthDeg = displayedAz,
                                                        manualElevationDeg = deg
                                                    )
                                                )
                                            },
                                            valueRange = 15f..85f,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("ar_light_elevation_slider")
                                        )
                                    }
                                }
                            }

                            ArControlTab.SCALE -> {
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
                            }

                            ArControlTab.DOT -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ar_tracking_dot_section"),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenWith,
                                                contentDescription = null,
                                                tint = Color(0xFFFF5722),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Move Tool Tracking Dot",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = onOpen3dDotPicker,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .heightIn(min = 34.dp)
                                                .testTag("ar_open_3d_dot_picker_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AdsClick,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Place Dot in 3D", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }

                                    val activeDotCoords = ArTrackingDotMath.formatDotCoordinates(
                                        point = ArTrackingDotMath.resolveAlignmentPoint(anchorOrigin, effectiveBounds, trackingDot),
                                        unit = unitSystem,
                                        decimals = precisionDecimals
                                    )
                                    val activeDotTitle = if (anchorOrigin == ArAnchorOrigin.MODEL_DOT) {
                                        trackingDot.label
                                    } else {
                                        anchorOrigin.label
                                    }
                                    Text(
                                        text = "Dot: $activeDotTitle $activeDotCoords",
                                        style = CadNumericStyle,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("ar_tracking_dot_readout")
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FilterChip(
                                            selected = tapActionMode == ArTapActionMode.PICK_DOT_ON_MODEL,
                                            onClick = { onSelectTapActionMode(ArTapActionMode.PICK_DOT_ON_MODEL) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.AdsClick,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = {
                                                Text(if (isLandscape) "1. Pick Dot" else "1. Pick Dot on Model")
                                            },
                                            modifier = Modifier.testTag("ar_tap_mode_pick_dot")
                                        )
                                        FilterChip(
                                            selected = tapActionMode == ArTapActionMode.MOVE_IN_WORLD,
                                            onClick = { onSelectTapActionMode(ArTapActionMode.MOVE_IN_WORLD) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.OpenWith,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = {
                                                Text(if (isLandscape) "2. Move World" else "2. Move in World")
                                            },
                                            modifier = Modifier.testTag("ar_tap_mode_move_world")
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Snap:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        ArTrackingDotPreset.entries.forEach { preset ->
                                            val isPresetSelected = anchorOrigin == ArAnchorOrigin.MODEL_DOT &&
                                                trackingDot.label == preset.label
                                            FilterChip(
                                                selected = isPresetSelected,
                                                onClick = { onSelectDotPreset(preset) },
                                                label = { Text(preset.label) },
                                                modifier = Modifier.testTag("ar_dot_preset_${preset.name.lowercase()}")
                                            )
                                        }
                                    }

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
                                }
                            }

                            ArControlTab.ADJUST -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Rotation & Elevation",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    TextButton(
                                        onClick = {
                                            onChangeRotation(0f)
                                            onChangeVerticalOffset(0f)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier
                                            .heightIn(min = 32.dp)
                                            .testTag("ar_adjust_reset_button")
                                    ) {
                                        Text("Reset", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
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

                            null -> Unit
                        }
                    }
                }

                if (isSectionOpen) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }

                // Always-visible Slim Section Tab Strip (~48dp) — tap any tab to open ONLY that section,
                // tap active tab again or chevron to minimize back to the strip.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArControlTab.entries.forEach { tab ->
                            val isSelected = activeTab == tab
                            val tabIcon: ImageVector = when (tab) {
                                ArControlTab.MODE -> if (trackingMode == ArTrackingMode.VISUAL_OBJECT_TRACKER) {
                                    Icons.Default.CenterFocusStrong
                                } else {
                                    Icons.Default.MyLocation
                                }
                                ArControlTab.STYLE -> Icons.Default.AutoAwesome
                                ArControlTab.RECORD -> if (isRecordingVideo) {
                                    Icons.Default.FiberManualRecord
                                } else {
                                    Icons.Default.Videocam
                                }
                                ArControlTab.LIGHT -> Icons.Default.WbSunny
                                ArControlTab.SCALE -> Icons.Default.AspectRatio
                                ArControlTab.DOT -> Icons.Default.OpenWith
                                ArControlTab.ADJUST -> Icons.Default.Tune
                            }
                            val tabBadgeActive = when (tab) {
                                ArControlTab.STYLE -> realisticRenderEnabled
                                ArControlTab.RECORD -> isRecordingVideo
                                ArControlTab.LIGHT -> !lightSettings.autoDetectLight
                                else -> false
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    activeTab = if (isSelected) null else tab
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = tabIcon,
                                        contentDescription = null,
                                         tint = when {
                                             tab == ArControlTab.RECORD && isRecordingVideo -> MaterialTheme.colorScheme.error
                                             tabBadgeActive && !isSelected -> amberAccent
                                             else -> MaterialTheme.colorScheme.onSurfaceVariant
                                         },
                                         modifier = Modifier.size(16.dp)
                                     )
                                 },
                                 label = {
                                     Text(
                                         text = tab.label,
                                         maxLines = 1,
                                         style = MaterialTheme.typography.labelMedium
                                     )
                                 },
                                 modifier = Modifier
                                     .heightIn(min = 48.dp)
                                     .testTag(tab.tag)
                             )
                         }
                     }

                     IconButton(
                         onClick = {
                             activeTab = if (activeTab != null) null else ArControlTab.MODE
                         },
                         modifier = Modifier.testTag("ar_toggle_panel")
                     ) {
                         Icon(
                             imageVector = if (isSectionOpen) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                             contentDescription = if (isSectionOpen) "Minimize AR menu" else "Open AR menu",
                             tint = MaterialTheme.colorScheme.onSurface
                         )
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
    realisticRenderEnabled: Boolean,
    hasFoundationBelowAxis: Boolean,
    onExitVr: () -> Unit,
    onPinAtCenter: () -> Unit,
    onCaptureObjectTarget: () -> Unit,
    onCycleIpd: () -> Unit,
    onToggleLensFit: () -> Unit,
    onToggleFoundation: () -> Unit,
    onToggleRealisticRender: () -> Unit
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

                TextButton(
                    onClick = onToggleRealisticRender,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ar_vr_realistic_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (realisticRenderEnabled) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (realisticRenderEnabled) "Real: ON" else "Real: OFF",
                        color = if (realisticRenderEnabled) Color(0xFFFBBF24) else Color.White,
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

private data class BackCameraHardwareInfo(
    val sensorOrientationDeg: Int = 90,
    val defaultApertureFNumber: Float = 1.8f
)

private fun readBackCameraHardwareInfo(context: Context): BackCameraHardwareInfo {
    return try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return BackCameraHardwareInfo()
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val orient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val aperture = apertures?.firstOrNull { it.isFinite() && it in 0.7f..22f } ?: 1.8f
                return BackCameraHardwareInfo(
                    sensorOrientationDeg = orient,
                    defaultApertureFNumber = aperture
                )
            }
        }
        BackCameraHardwareInfo()
    } catch (_: Throwable) {
        BackCameraHardwareInfo()
    }
}
