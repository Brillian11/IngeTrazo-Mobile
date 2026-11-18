package com.example

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.MainViewModel
import com.example.ui.ar.ArProjectionScreen
import com.example.ui.components.findActivity
import com.example.ui.components.togglePreviewLandscapeOrientation
import com.example.ui.hub.ProjectHubScreen
import com.example.ui.theme.IngeTrazoTheme
import com.example.ui.viewer.CadViewerScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable

@Serializable
data object HubRoute

@Serializable
data object ViewerRoute

@Serializable
data object ArRoute

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUri = if (intent?.action == Intent.ACTION_VIEW) intent.data else null

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()

            IngeTrazoTheme(darkCadMode = prefs.darkCadMode) {
                IngeTrazoNavApp(
                    viewModel = viewModel,
                    initialExternalUri = initialUri
                )
            }
        }
    }
}

@Composable
fun IngeTrazoNavApp(
    viewModel: MainViewModel,
    initialExternalUri: android.net.Uri? = null
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val activeDocument by viewModel.activeDocument.collectAsStateWithLifecycle()
    val viewerState by viewModel.viewerState.collectAsStateWithLifecycle()
    val cutListState by viewModel.cutListState.collectAsStateWithLifecycle()
    val lengths by viewModel.lengths.collectAsStateWithLifecycle()
    val angles by viewModel.angles.collectAsStateWithLifecycle()
    val countedAngles by viewModel.countedAngles.collectAsStateWithLifecycle()
    val angleBuckets by viewModel.angleBuckets.collectAsStateWithLifecycle()

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.openUri(uri) {
                navController.navigate(ViewerRoute) {
                    launchSingleTop = true
                }
            }
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportCsvToUri(uri, context.contentResolver)
        }
    }

    LaunchedEffect(initialExternalUri) {
        if (initialExternalUri != null) {
            viewModel.openUri(initialExternalUri) {
                navController.navigate(ViewerRoute) {
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = HubRoute) {
        composable<HubRoute> {
            LaunchedEffect(Unit) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            ProjectHubScreen(
                samples = viewModel.bundledSamples,
                recentFiles = recentFiles,
                preferences = prefs,
                loadState = loadState,
                onOpenFilePicker = { openDocumentLauncher.launch(arrayOf("*/*")) },
                onOpenSample = { sample ->
                    viewModel.openSample(sample) {
                        navController.navigate(ViewerRoute) { launchSingleTop = true }
                    }
                },
                onOpenRecent = { recent ->
                    viewModel.openRecent(recent) {
                        navController.navigate(ViewerRoute) { launchSingleTop = true }
                    }
                },
                onRemoveRecent = viewModel::removeRecent,
                onToggleTheme = viewModel::setDarkCadMode,
                onChangeUnits = viewModel::setUnitSystem,
                onChangeDecimals = viewModel::setPrecisionDecimals,
                onDismissError = viewModel::dismissError
            )
        }

        composable<ViewerRoute> {
            val doc = activeDocument
            if (doc == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                CadViewerScreen(
                    document = doc,
                    viewerState = viewerState,
                    preferences = prefs,
                    lengths = lengths,
                    angles = angles,
                    countedAngles = countedAngles,
                    angleBuckets = angleBuckets,
                    onBack = { navController.popBackStack() },
                    onOpenArMode = { navController.navigate(ArRoute) { launchSingleTop = true } },
                    onSetTool = viewModel::setTool,
                    onSetAngleInputMode = viewModel::setAngleInputMode,
                    onSetDisplayStyle = viewModel::setDisplayStyle,
                    onToggleTheme = viewModel::setDarkCadMode,
                    onToggleOrthographic = viewModel::toggleOrthographic,
                    onApplyStandardView = viewModel::applyStandardView,
                    onApplySavedView = viewModel::applySavedView,
                    onTapViewport = viewModel::onViewportTap,
                    onSnapHover = viewModel::onSnapHover,
                    onUndoPick = viewModel::undoActivePick,
                    onClearPicks = viewModel::clearActivePicks,
                    onClearSelection = viewModel::clearSelection,
                    onToggleLayer = viewModel::toggleLayerVisibility,
                    onToggleNode = viewModel::toggleNodeVisibility,
                    onSelectNode = viewModel::selectNode,
                    onShowAllHierarchy = viewModel::showAllHierarchy,
                    onChangeUnits = viewModel::setUnitSystem,
                    onChangeDecimals = viewModel::setPrecisionDecimals,
                    onChangeTolerance = viewModel::setAngleToleranceDeg,
                    onFocusPoint = viewModel::focusOnPoint,
                    onRenameLength = viewModel::renameLength,
                    onDeleteLength = viewModel::deleteLength,
                    onRenameAngle = viewModel::renameAngle,
                    onDeleteAngle = viewModel::deleteAngle,
                    onMarkAngleIntoCounter = viewModel::markMeasuredAngleIntoCounter,
                    onAutoCountSelection = viewModel::autoCountSelectionAngles,
                    onUpdateCountedNote = { item, note -> viewModel.updateCountedAngle(item.copy(notes = note)) },
                    onDeleteCountedAngle = viewModel::deleteCountedAngle,
                    onClearCountedAngles = viewModel::clearCountedAngles,
                    onExportCsv = {
                        val safeName = doc.title.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_")
                        exportCsvLauncher.launch("${safeName}_measurements.csv")
                    },
                    onDismissBanner = viewModel::clearStatusBanner,
                    onToggleLandscape = { currentlyLandscape ->
                        togglePreviewLandscapeOrientation(activity, currentlyLandscape)
                    },
                    cutListState = cutListState,
                    onOpenCutList = viewModel::ensureCutListExtracted,
                    onSelectCutListRow = viewModel::onSelectCutListRow,
                    onSetExplodeFraction = viewModel::setExplodeFraction,
                    onExportCutListPdf = { captureSnapshot ->
                        viewModel.exportCutListPdf(context, captureSnapshot)
                    },
                    onDismissExportedPdf = viewModel::dismissExportedPdfBanner
                )
            }
        }

        composable<ArRoute> {
            val doc = activeDocument
            if (doc == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                ArProjectionScreen(
                    document = doc,
                    unitSystem = prefs.unitSystem,
                    precisionDecimals = prefs.precisionDecimals,
                    onChangeUnits = viewModel::setUnitSystem,
                    onChangeDecimals = viewModel::setPrecisionDecimals,
                    onToggleLandscape = { currentlyLandscape ->
                        togglePreviewLandscapeOrientation(activity, currentlyLandscape)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
