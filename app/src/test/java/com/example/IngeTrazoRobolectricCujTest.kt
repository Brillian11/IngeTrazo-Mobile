package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AngleMeasurementEntity
import com.example.data.local.AppDatabase
import com.example.data.local.CountedAngleEntity
import com.example.data.local.UserPreferences
import com.example.data.parser.CadParseResult
import com.example.data.parser.IgzParser
import com.example.data.repository.SampleModelInfo
import com.example.domain.math.AngleMath
import com.example.domain.model.UnitSystem
import com.example.ui.DocumentLoadState
import com.example.ui.hub.ProjectHubScreen
import com.example.ui.theme.IngeTrazoTheme
import com.example.ui.viewer.HierarchyPanelContent
import com.example.ui.viewer.MeasurementsAndAnglesPanel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IngeTrazoRobolectricCujTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase

    @Before
    fun setupDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDownDb() {
        db.close()
    }

    @Test
    fun `project hub renders samples and triggers theme toggle and sample click`() {
        var darkToggled = false
        var openedSample: SampleModelInfo? = null
        val samples = listOf(
            SampleModelInfo("samples/pestanas-prueba.igz", "Pestañas & Escenas", "Framing test", "IGZ v1")
        )

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                ProjectHubScreen(
                    samples = samples,
                    recentFiles = emptyList(),
                    preferences = UserPreferences(),
                    loadState = DocumentLoadState.Idle,
                    onOpenFilePicker = {},
                    onOpenSample = { openedSample = it },
                    onOpenRecent = {},
                    onRemoveRecent = {},
                    onToggleTheme = { darkToggled = it },
                    onDismissError = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("IngeTrazo Mobile").assertIsDisplayed()
        composeRule.onNodeWithText("3D CAD Viewer · Tape & Protractor · Angle Counter").assertDoesNotExist()
        composeRule.onNodeWithTag("open_file_button").assertIsDisplayed()

        // Open 3-dot overflow menu -> toggle dark/light theme
        composeRule.onNodeWithTag("hub_overflow_menu_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_theme_toggle").performClick()
        composeRule.waitForIdle()
        assertTrue(darkToggled)

        // Open 3-dot overflow menu -> About the App
        assertEquals("beta 0.1", BuildConfig.VERSION_NAME)
        composeRule.onNodeWithTag("hub_overflow_menu_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_menu_about").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_about_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Version: beta 0.1").assertIsDisplayed()
        composeRule.onNodeWithText("Created by Brillian11").assertIsDisplayed()
        composeRule.onNodeWithText("https://github.com/Brillian11").assertIsDisplayed()
        composeRule.onNodeWithText("Special Thanks: IngeTrazo Project").assertIsDisplayed()
        composeRule.onNodeWithText("https://github.com/ingelibre/ingetrazo").assertIsDisplayed()
        composeRule.onNodeWithTag("about_creator_link").assertIsDisplayed()
        composeRule.onNodeWithTag("about_ingetrazo_link").assertIsDisplayed()
        composeRule.onNodeWithTag("hub_about_close").performClick()
        composeRule.waitForIdle()

        // Open 3-dot overflow menu -> Unit Preferences
        composeRule.onNodeWithTag("hub_overflow_menu_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_menu_units").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("unit_dialog_done").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("sample_card_pestanas-prueba.igz").performClick()
        composeRule.waitForIdle()
        assertEquals("samples/pestanas-prueba.igz", openedSample?.assetPath)
    }

    @Test
    fun `measurements panel supports angle counting, marking, and csv export`() {
        var autoCountClicked = false
        var markedAngleId = -1L
        var exportClicked = false
        val angles = listOf(
            AngleMeasurementEntity(10L, "doc", "A1", 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 90.0, "3-Point")
        )
        val counted = listOf(
            CountedAngleEntity(1L, "doc", 90.0, "Corner 1", "Auto-Selection", "f1", 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            CountedAngleEntity(2L, "doc", 45.0, "Brace 1", "Manual", "e1+e2", 1.0, 0.0, 0.0, 2.0, 0.0, 0.0, 1.0, 1.0, 0.0)
        )
        val buckets = AngleMath.groupAnglesByTolerance(counted.map { it.angleDegrees }, 0.5)

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                MeasurementsAndAnglesPanel(
                    preferences = UserPreferences(),
                    lengths = emptyList(),
                    angles = angles,
                    countedAngles = counted,
                    angleBuckets = buckets,
                    onChangeUnits = {},
                    onChangeDecimals = {},
                    onChangeTolerance = {},
                    onFocusPoint = {},
                    onRenameLength = { _, _ -> },
                    onDeleteLength = {},
                    onRenameAngle = { _, _ -> },
                    onDeleteAngle = {},
                    onMarkAngleIntoCounter = { markedAngleId = it.id },
                    onAutoCountSelection = { autoCountClicked = true },
                    onUpdateCountedNote = { _, _ -> },
                    onDeleteCountedAngle = {},
                    onClearCountedAngles = {},
                    onExportCsv = { exportClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("total_angle_count").assertTextEquals("2")
        composeRule.onNodeWithTag("distinct_angle_count").assertTextEquals("2")

        composeRule.onNodeWithTag("auto_count_angles_button").performClick()
        composeRule.waitForIdle()
        assertTrue(autoCountClicked)

        composeRule.onNodeWithTag("export_csv_button").performClick()
        composeRule.waitForIdle()
        assertTrue(exportClicked)

        composeRule.onNodeWithTag("tab_angles").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mark_angle_10").performClick()
        composeRule.waitForIdle()
        assertEquals(10L, markedAngleId)
    }

    @Test
    fun `hierarchy panel toggles layers and persists angle counts in Room`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = context.assets.open("samples/capas-prueba.igz").use { it.readBytes() }
        val res = IgzParser().parse(bytes, "asset:capas", "Capas", null) as CadParseResult.Success
        val doc = res.document

        var toggledLayer: String? = null
        composeRule.setContent {
            IngeTrazoTheme {
                HierarchyPanelContent(
                    document = doc,
                    hiddenLayers = emptySet(),
                    hiddenNodes = emptySet(),
                    selectedNodeIds = emptySet(),
                    onToggleLayer = { toggledLayer = it },
                    onToggleNode = {},
                    onSelectNode = {},
                    onShowAll = {}
                )
            }
        }
        composeRule.waitForIdle()

        val firstLayer = doc.layers.first().name
        composeRule.onNodeWithTag("layer_eye_$firstLayer").performClick()
        composeRule.waitForIdle()
        assertEquals(firstLayer, toggledLayer)

        // Verify Room persistence for counted angles
        val dao = db.measurementDao()
        dao.insertCountedAngle(
            CountedAngleEntity(
                documentKey = doc.documentKey,
                angleDegrees = 90.0,
                locationLabel = "Corner A",
                source = "Auto-Model",
                entityRef = "f_0",
                vx = 0.0, vy = 0.0, vz = 0.0,
                ax = 1.0, ay = 0.0, az = 0.0,
                bx = 0.0, by = 1.0, bz = 0.0
            )
        )
        val stored = dao.observeCountedAngles(doc.documentKey).first()
        assertEquals(1, stored.size)
        assertEquals(90.0, stored[0].angleDegrees, 1e-4)
    }

    @Test
    fun `project hub overflow unit menu opens UnitSettingsDialog to set cm and inch`() {
        var prefsState by mutableStateOf(UserPreferences())

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                ProjectHubScreen(
                    samples = emptyList(),
                    recentFiles = emptyList(),
                    preferences = prefsState,
                    loadState = DocumentLoadState.Idle,
                    onOpenFilePicker = {},
                    onOpenSample = {},
                    onOpenRecent = {},
                    onRemoveRecent = {},
                    onToggleTheme = {},
                    onChangeUnits = { prefsState = prefsState.copy(unitSystem = it) },
                    onChangeDecimals = { prefsState = prefsState.copy(precisionDecimals = it) },
                    onDismissError = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("hub_units_button").assertDoesNotExist()
        composeRule.onNodeWithTag("hub_overflow_menu_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_menu_units").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("unit_option_cm").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(UnitSystem.CENTIMETERS, prefsState.unitSystem)

        composeRule.onNodeWithTag("unit_option_in").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(UnitSystem.INCHES, prefsState.unitSystem)

        composeRule.onNodeWithTag("precision_option_1").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, prefsState.precisionDecimals)

        composeRule.onNodeWithTag("unit_dialog_done").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_overflow_menu_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("in · 1 dec").assertIsDisplayed()
    }

    @Test
    fun `measurements panel omits duplicate display units and precision strip`() {
        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                MeasurementsAndAnglesPanel(
                    preferences = UserPreferences(unitSystem = UnitSystem.CENTIMETERS, precisionDecimals = 1),
                    lengths = emptyList(),
                    angles = emptyList(),
                    countedAngles = emptyList(),
                    angleBuckets = emptyList(),
                    onChangeTolerance = {},
                    onFocusPoint = {},
                    onRenameLength = { _, _ -> },
                    onDeleteLength = {},
                    onRenameAngle = { _, _ -> },
                    onDeleteAngle = {},
                    onMarkAngleIntoCounter = {},
                    onAutoCountSelection = {},
                    onUpdateCountedNote = { _, _ -> },
                    onDeleteCountedAngle = {},
                    onClearCountedAngles = {},
                    onExportCsv = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Measurements & Angles").assertIsDisplayed()
        composeRule.onNodeWithText("Display Units & Precision").assertDoesNotExist()
        composeRule.onNodeWithTag("unit_chip_in").assertDoesNotExist()
        composeRule.onNodeWithTag("unit_chip_cm").assertDoesNotExist()
        composeRule.onNodeWithTag("precision_chip_0").assertDoesNotExist()
    }

    @Test
    fun `ar hud overlay supports true 1_1 scale, unit dimensions, and object tracker mode`() {
        val doc = com.example.domain.model.CadDocument(
            documentKey = "ar_test_doc",
            title = "Gazebo",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3.ZERO,
                com.example.domain.math.Vec3(2.0, 1.0, 1.0)
            ),
            stats = com.example.domain.model.ModelStats(1, 0, 0, 0, 0, 0, 0, 0)
        )
        var selectedScale by mutableStateOf(0.1f)
        var trackingMode by mutableStateOf(com.example.ui.ar.ArTrackingMode.OBJECT_SURFACE)
        var unitSystem by mutableStateOf(UnitSystem.CENTIMETERS)
        var capturedTarget = false

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.ar.ArHudOverlay(
                    document = doc,
                    unitSystem = unitSystem,
                    precisionDecimals = 1,
                    trackingStatus = com.example.ui.ar.ArTrackingStatus(),
                    selectedScale = selectedScale,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = com.example.ui.ar.ArAnchorOrigin.BASE,
                    trackingMode = trackingMode,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onClearAnchor = {},
                    onSelectScale = { selectedScale = it },
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = {},
                    onSelectTrackingMode = { trackingMode = it },
                    onSelectTargetWidth = {},
                    onPinAtCenter = {},
                    onCaptureObjectTarget = { capturedTarget = true }
                )
            }
        }
        composeRule.waitForIdle()

        // Initial 1:10 scale in centimeters
        composeRule.onNodeWithText("Projected size: 20.0 × 10.0 × 10.0 cm").assertIsDisplayed()

        // Open Scale tab and tap 1:1 preset
        composeRule.onNodeWithTag("ar_tab_scale").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_scale_1_1").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1.0f, selectedScale, 1e-6f)
        composeRule.onNodeWithText("Projected size: 200.0 × 100.0 × 100.0 cm").assertIsDisplayed()
        composeRule.onNodeWithText("TRUE 1:1").assertIsDisplayed()

        // Open Mode tab, switch to Object Tracker mode, and trigger capture
        composeRule.onNodeWithTag("ar_tab_mode").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_mode_tracker").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_capture_target_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(capturedTarget)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h400dp-land")
    fun `ar hud overlay adapts to landscape side dock and triggers landscape toggle`() {
        val doc = com.example.domain.model.CadDocument(
            documentKey = "ar_land_doc",
            title = "Arch",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3.ZERO,
                com.example.domain.math.Vec3(1.5, 1.0, 2.0)
            ),
            stats = com.example.domain.model.ModelStats(1, 0, 0, 0, 0, 0, 0, 0)
        )
        var toggledFromLandscape: Boolean? = null

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.ar.ArHudOverlay(
                    document = doc,
                    unitSystem = UnitSystem.METERS,
                    precisionDecimals = 2,
                    trackingStatus = com.example.ui.ar.ArTrackingStatus(),
                    selectedScale = 1.0f,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = com.example.ui.ar.ArAnchorOrigin.BASE,
                    trackingMode = com.example.ui.ar.ArTrackingMode.OBJECT_SURFACE,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onToggleLandscape = { toggledFromLandscape = it },
                    onClearAnchor = {},
                    onSelectScale = {},
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = {},
                    onSelectTrackingMode = {},
                    onSelectTargetWidth = {},
                    onPinAtCenter = {},
                    onCaptureObjectTarget = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ar_controls_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_more_menu_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_landscape_toggle").performClick()
        composeRule.waitForIdle()
        assertEquals(true, toggledFromLandscape)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h400dp-land")
    fun `cad viewer shows vertical tool rail and landscape toggle in compact landscape`() {
        val doc = com.example.domain.model.CadDocument(
            documentKey = "viewer_land_doc",
            title = "Gazebo",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3.ZERO,
                com.example.domain.math.Vec3(3.0, 2.0, 2.5)
            ),
            stats = com.example.domain.model.ModelStats(1, 0, 0, 0, 0, 0, 0, 0)
        )
        var toggledFromLandscape: Boolean? = null

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.viewer.CadViewerScreen(
                    document = doc,
                    viewerState = com.example.ui.ViewerUiState(),
                    preferences = UserPreferences(),
                    lengths = emptyList(),
                    angles = emptyList(),
                    countedAngles = emptyList(),
                    angleBuckets = emptyList(),
                    onBack = {},
                    onOpenArMode = {},
                    onSetTool = {},
                    onSetAngleInputMode = {},
                    onSetDisplayStyle = {},
                    onToggleTheme = {},
                    onToggleOrthographic = {},
                    onApplyStandardView = { _, _ -> },
                    onApplySavedView = { _, _ -> },
                    onTapViewport = { _, _, _ -> },
                    onSnapHover = { _, _, _ -> },
                    onUndoPick = {},
                    onClearPicks = {},
                    onClearSelection = {},
                    onToggleLayer = {},
                    onToggleNode = {},
                    onSelectNode = {},
                    onShowAllHierarchy = {},
                    onChangeUnits = {},
                    onChangeDecimals = {},
                    onChangeTolerance = {},
                    onFocusPoint = { _, _ -> },
                    onRenameLength = { _, _ -> },
                    onDeleteLength = {},
                    onRenameAngle = { _, _ -> },
                    onDeleteAngle = {},
                    onMarkAngleIntoCounter = {},
                    onAutoCountSelection = {},
                    onUpdateCountedNote = { _, _ -> },
                    onDeleteCountedAngle = {},
                    onClearCountedAngles = {},
                    onExportCsv = {},
                    onDismissBanner = {},
                    onToggleLandscape = { toggledFromLandscape = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("viewer_landscape_tool_rail").assertIsDisplayed()
        composeRule.onNodeWithTag("tool_ar_dot").assertDoesNotExist()
        composeRule.onNodeWithTag("viewer_more_menu_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewer_landscape_toggle").performClick()
        composeRule.waitForIdle()
        assertEquals(true, toggledFromLandscape)
    }

    @Test
    fun `cad viewer tool dock toggles Select Orbit Pan exclusively`() {
        val doc = com.example.domain.model.CadDocument(
            documentKey = "tools_doc",
            title = "Gazebo",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3.ZERO,
                com.example.domain.math.Vec3(3.0, 2.0, 2.5)
            ),
            stats = com.example.domain.model.ModelStats(1, 0, 0, 0, 0, 0, 0, 0)
        )
        var viewerState by mutableStateOf(com.example.ui.ViewerUiState(activeTool = com.example.domain.model.CadTool.SELECT))

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.viewer.CadViewerScreen(
                    document = doc,
                    viewerState = viewerState,
                    preferences = UserPreferences(),
                    lengths = emptyList(),
                    angles = emptyList(),
                    countedAngles = emptyList(),
                    angleBuckets = emptyList(),
                    onBack = {},
                    onOpenArMode = {},
                    onSetTool = { viewerState = viewerState.copy(activeTool = it) },
                    onSetAngleInputMode = {},
                    onSetDisplayStyle = {},
                    onToggleTheme = {},
                    onToggleOrthographic = {},
                    onApplyStandardView = { _, _ -> },
                    onApplySavedView = { _, _ -> },
                    onTapViewport = { _, _, _ -> },
                    onSnapHover = { _, _, _ -> },
                    onUndoPick = {},
                    onClearPicks = {},
                    onClearSelection = {},
                    onToggleLayer = {},
                    onToggleNode = {},
                    onSelectNode = {},
                    onShowAllHierarchy = {},
                    onChangeUnits = {},
                    onChangeDecimals = {},
                    onChangeTolerance = {},
                    onFocusPoint = { _, _ -> },
                    onRenameLength = { _, _ -> },
                    onDeleteLength = {},
                    onRenameAngle = { _, _ -> },
                    onDeleteAngle = {},
                    onMarkAngleIntoCounter = {},
                    onAutoCountSelection = {},
                    onUpdateCountedNote = { _, _ -> },
                    onDeleteCountedAngle = {},
                    onClearCountedAngles = {},
                    onExportCsv = {},
                    onDismissBanner = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tool_select").assertIsSelected()
        composeRule.onNodeWithTag("tool_orbit").assertIsNotSelected()
        composeRule.onNodeWithTag("tool_pan").assertIsNotSelected()

        composeRule.onNodeWithTag("tool_orbit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_select").assertIsNotSelected()
        composeRule.onNodeWithTag("tool_orbit").assertIsSelected()
        composeRule.onNodeWithTag("tool_pan").assertIsNotSelected()

        composeRule.onNodeWithTag("tool_pan").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_select").assertIsNotSelected()
        composeRule.onNodeWithTag("tool_orbit").assertIsNotSelected()
        composeRule.onNodeWithTag("tool_pan").assertIsSelected()
    }

    @Test
    fun `ar math normalizes scale consistently, levels tilted poses, and clips Z_lt_0 foundations`() {
        // 1. Scale consistency across vastly different model sizes
        val smallPart = com.example.domain.math.BoundingBox3D(
            com.example.domain.math.Vec3.ZERO,
            com.example.domain.math.Vec3(0.4, 0.3, 0.25)
        )
        val mediumArch = com.example.domain.math.BoundingBox3D(
            com.example.domain.math.Vec3(-6.0, -1.0, 0.0),
            com.example.domain.math.Vec3(6.0, 1.0, 5.0)
        )
        val giantMmObj = com.example.domain.math.BoundingBox3D(
            com.example.domain.math.Vec3.ZERO,
            com.example.domain.math.Vec3(1200.0, 800.0, 500.0)
        )
        assertEquals(1.0f, com.example.ui.ar.ArScaleMath.recommendedInitialScale(smallPart), 1e-6f)
        val archScale = com.example.ui.ar.ArScaleMath.recommendedInitialScale(mediumArch)
        assertTrue(archScale in 0.04f..0.11f)
        assertEquals(0.001f, com.example.ui.ar.ArScaleMath.recommendedInitialScale(giantMmObj), 1e-6f)

        // 2. Gravity leveling strips 35 deg surface tilt while keeping position and yaw
        val tiltedMat = FloatArray(16)
        android.opengl.Matrix.setIdentityM(tiltedMat, 0)
        android.opengl.Matrix.translateM(tiltedMat, 0, 1.25f, -0.4f, -2.5f)
        android.opengl.Matrix.rotateM(tiltedMat, 0, 45f, 0f, 1f, 0f)
        android.opengl.Matrix.rotateM(tiltedMat, 0, 35f, 1f, 0f, 0f)

        val leveled = com.example.ui.ar.ArTransformMath.buildPoseMatrix(tiltedMat, alwaysLevel = true)
        assertEquals(0f, leveled[4], 1e-5f)
        assertEquals(1f, leveled[5], 1e-5f)
        assertEquals(0f, leveled[6], 1e-5f)
        assertEquals(0f, leveled[1], 1e-5f)
        assertEquals(0f, leveled[9], 1e-5f)
        assertEquals(1.25f, leveled[12], 1e-5f)
        assertEquals(-0.4f, leveled[13], 1e-5f)
        assertEquals(-2.5f, leveled[14], 1e-5f)

        // 3. Z < 0 foundation clipping with UV interpolation
        val clipped = com.example.ui.ar.ArGeometryClipper.clipTriangleAboveGround(
            com.example.domain.math.Vec3(0.0, 0.0, -1.0),
            com.example.domain.math.Vec3(1.0, 0.0, -1.0),
            com.example.domain.math.Vec3(0.5, 0.0, 1.0),
            com.example.domain.math.Vec2(0.0, 0.0),
            com.example.domain.math.Vec2(1.0, 0.0),
            com.example.domain.math.Vec2(0.5, 1.0)
        )
        assertEquals(1, clipped.size)
        val c = clipped[0]
        assertTrue(c.v0.pos.z >= -1e-4 && c.v1.pos.z >= -1e-4 && c.v2.pos.z >= -1e-4)
        assertEquals(0.5, c.v0.uv.y, 1e-4)
    }

    @Test
    fun `ar hud toggles foundation visibility, gravity level, textures, and model axis origin`() {
        val columnFaces = listOf(
            com.example.domain.model.CadFace(
                id = "f_col",
                nodeId = "root",
                layer = "Layer 0",
                outerLoop = listOf(
                    com.example.domain.math.Vec3(-0.5, 0.0, -1.0),
                    com.example.domain.math.Vec3(0.5, 0.0, -1.0),
                    com.example.domain.math.Vec3(0.5, 0.0, 2.0),
                    com.example.domain.math.Vec3(-0.5, 0.0, 2.0)
                ),
                normal = com.example.domain.math.Vec3(0.0, -1.0, 0.0),
                triangleVertices = listOf(
                    com.example.domain.math.Vec3(-0.5, 0.0, -1.0),
                    com.example.domain.math.Vec3(0.5, 0.0, -1.0),
                    com.example.domain.math.Vec3(0.5, 0.0, 2.0),
                    com.example.domain.math.Vec3(-0.5, 0.0, -1.0),
                    com.example.domain.math.Vec3(0.5, 0.0, 2.0),
                    com.example.domain.math.Vec3(-0.5, 0.0, 2.0)
                ),
                triangleUvs = List(6) { com.example.domain.math.Vec2(0.0, 0.0) },
                color = com.example.domain.model.CadColor.DEFAULT_FRONT
            )
        )
        val doc = com.example.domain.model.CadDocument(
            documentKey = "ar_foundation_doc",
            title = "Luminaria",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = columnFaces,
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3(-0.5, 0.0, -1.0),
                com.example.domain.math.Vec3(0.5, 0.0, 2.0)
            ),
            stats = com.example.domain.model.ModelStats(1, 1, 2, 0, 4, 1, 0, 0)
        )
        var alwaysLevel by mutableStateOf(true)
        var showFoundation by mutableStateOf(false)
        var showTextures by mutableStateOf(true)
        var anchorOrigin by mutableStateOf(com.example.ui.ar.ArAnchorOrigin.AXIS)

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.ar.ArHudOverlay(
                    document = doc,
                    unitSystem = UnitSystem.METERS,
                    precisionDecimals = 2,
                    trackingStatus = com.example.ui.ar.ArTrackingStatus(),
                    selectedScale = 1.0f,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = anchorOrigin,
                    alwaysLevel = alwaysLevel,
                    showFoundation = showFoundation,
                    showTextures = showTextures,
                    hasFoundationBelowAxis = true,
                    trackingMode = com.example.ui.ar.ArTrackingMode.OBJECT_SURFACE,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onClearAnchor = {},
                    onSelectScale = {},
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = { anchorOrigin = it },
                    onToggleAlwaysLevel = { alwaysLevel = it },
                    onToggleShowFoundation = { showFoundation = it },
                    onToggleShowTextures = { showTextures = it },
                    onSelectTrackingMode = {},
                    onSelectTargetWidth = {},
                    onPinAtCenter = {},
                    onCaptureObjectTarget = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Foundation buried by default -> height above axis is 2.00 m (Z: 0..2)
        composeRule.onNodeWithText("Projected size: 1.00 × 0.00 × 2.00 m").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_tab_dot").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_origin_axis").performScrollTo().assertIsSelected()

        // Open Render tab for Level, Foundation, and Textures toggles
        composeRule.onNodeWithTag("ar_tab_style").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_level_toggle").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("ar_foundation_toggle").performScrollTo().assertIsNotSelected()
        composeRule.onNodeWithTag("ar_textures_toggle").performScrollTo().assertIsSelected()

        // Toggle Show Foundation ON -> height includes Z:-1..0 footing -> 3.00 m
        composeRule.onNodeWithTag("ar_foundation_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(showFoundation)
        composeRule.onNodeWithText("Projected size: 1.00 × 0.00 × 3.00 m").assertIsDisplayed()

        // Toggle Level OFF -> Surface Tilt
        composeRule.onNodeWithTag("ar_level_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(false, alwaysLevel)

        // Toggle Textures OFF
        composeRule.onNodeWithTag("ar_textures_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(false, showTextures)
    }

    @Test
    fun `vr stereo math computes IPD eye shift, aspect projection, and cropped background UVs`() {
        val monoView = FloatArray(16)
        android.opengl.Matrix.setIdentityM(monoView, 0)
        android.opengl.Matrix.translateM(monoView, 0, 0f, 0f, -2.0f)

        val leftView = com.example.ui.ar.ArTransformMath.buildVrEyeViewMatrix(
            monoViewMat = monoView,
            eyeSign = -1f,
            ipdMeters = 0.064f
        )
        val rightView = com.example.ui.ar.ArTransformMath.buildVrEyeViewMatrix(
            monoViewMat = monoView,
            eyeSign = 1f,
            ipdMeters = 0.064f
        )
        assertEquals(0.032f, leftView[12], 1e-6f)
        assertEquals(-0.032f, rightView[12], 1e-6f)
        assertEquals(-2.0f, leftView[14], 1e-6f)

        val monoProj = FloatArray(16)
        android.opengl.Matrix.perspectiveM(monoProj, 0, 60f, 2.0f, 0.1f, 100f)
        val eyeProj = com.example.ui.ar.ArTransformMath.buildVrEyeProjMatrix(
            monoProjMat = monoProj,
            eyeWidthRatio = 0.5f,
            zoomFactor = 1.0f
        )
        // Half-width eye viewport doubles horizontal focal coefficient while keeping vertical FOV identical
        assertEquals(monoProj[0] * 2f, eyeProj[0], 1e-5f)
        assertEquals(monoProj[5], eyeProj[5], 1e-5f)

        // Full quad BL(0,1), BR(1,1), TL(0,0), TR(1,0) center-cropped for 0.5 width -> U in [0.25, 0.75]
        val fullUvs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val croppedUvs = com.example.ui.ar.ArTransformMath.computeVrEyeBackgroundTexCoords(
            fullTexCoords = fullUvs,
            eyeWidthRatio = 0.5f,
            eyeSign = 0f,
            stereoBgShiftFraction = 0f
        )
        assertEquals(0.25f, croppedUvs[0], 1e-6f)
        assertEquals(1.0f, croppedUvs[1], 1e-6f)
        assertEquals(0.75f, croppedUvs[2], 1e-6f)
        assertEquals(1.0f, croppedUvs[3], 1e-6f)
        assertEquals(0.25f, croppedUvs[4], 1e-6f)
        assertEquals(0.0f, croppedUvs[5], 1e-6f)
        assertEquals(0.75f, croppedUvs[6], 1e-6f)
        assertEquals(0.0f, croppedUvs[7], 1e-6f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h400dp-land")
    fun `ar hud overlay enters and exits VR Box mode with IPD, Lens Fit, and Pin controls`() {
        val doc = com.example.domain.model.CadDocument(
            documentKey = "ar_vr_doc",
            title = "Pergola",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3(-1.0, -1.0, -0.5),
                com.example.domain.math.Vec3(1.0, 1.0, 2.0)
            ),
            stats = com.example.domain.model.ModelStats(1, 0, 0, 0, 0, 0, 0, 0)
        )
        var vrMode by mutableStateOf(false)
        var ipdMm by mutableStateOf(64)
        var lensFit by mutableStateOf(false)
        var showFoundation by mutableStateOf(false)
        var pinnedCenter = false

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.ar.ArHudOverlay(
                    document = doc,
                    unitSystem = UnitSystem.METERS,
                    precisionDecimals = 2,
                    trackingStatus = com.example.ui.ar.ArTrackingStatus(),
                    selectedScale = 1.0f,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = com.example.ui.ar.ArAnchorOrigin.AXIS,
                    showFoundation = showFoundation,
                    hasFoundationBelowAxis = true,
                    trackingMode = com.example.ui.ar.ArTrackingMode.OBJECT_SURFACE,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    vrModeEnabled = vrMode,
                    vrIpdMm = ipdMm,
                    vrLensFit = lensFit,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onToggleVrMode = { vrMode = it },
                    onCycleVrIpd = {
                        val presets = com.example.ui.ar.ArTransformMath.IPD_PRESETS_MM
                        ipdMm = presets[(presets.indexOf(ipdMm) + 1) % presets.size]
                    },
                    onToggleVrLensFit = { lensFit = !lensFit },
                    onClearAnchor = {},
                    onSelectScale = {},
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = {},
                    onToggleShowFoundation = { showFoundation = it },
                    onSelectTrackingMode = {},
                    onSelectTargetWidth = {},
                    onPinAtCenter = { pinnedCenter = true },
                    onCaptureObjectTarget = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Enter VR Box mode from AR three-dot overflow menu
        composeRule.onNodeWithTag("ar_more_menu_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_vr_mode_button").performClick()
        composeRule.waitForIdle()
        assertTrue(vrMode)
        composeRule.onNodeWithTag("ar_vr_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_vr_status_pill").assertIsDisplayed()

        // Cycle IPD from 64mm -> 68mm
        composeRule.onNodeWithText("IPD 64mm").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_vr_ipd_button").performClick()
        composeRule.waitForIdle()
        assertEquals(68, ipdMm)
        composeRule.onNodeWithText("IPD 68mm").assertIsDisplayed()

        // Toggle Lens Full -> Fit
        composeRule.onNodeWithText("Lens: Full").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_vr_lens_button").performClick()
        composeRule.waitForIdle()
        assertTrue(lensFit)
        composeRule.onNodeWithText("Lens: Fit").assertIsDisplayed()

        // Toggle Foundation inside VR HUD
        composeRule.onNodeWithTag("ar_vr_foundation_button").performClick()
        composeRule.waitForIdle()
        assertTrue(showFoundation)

        // Pin Center inside VR HUD
        composeRule.onNodeWithTag("ar_vr_pin_button").performClick()
        composeRule.waitForIdle()
        assertTrue(pinnedCenter)

        // Exit VR back to regular AR HUD
        composeRule.onNodeWithTag("ar_vr_exit_button").performClick()
        composeRule.waitForIdle()
        assertEquals(false, vrMode)
        composeRule.onNodeWithTag("ar_more_menu_button").assertIsDisplayed()
    }

    @Test
    fun `ar move tool tracking dot math resolves vertex presets, picks model points via MVP, and preserves world placement`() {
        val v0 = com.example.domain.math.Vec3(0.0, 0.0, 0.0)
        val v1 = com.example.domain.math.Vec3(2.0, 0.0, 0.0)
        val v2 = com.example.domain.math.Vec3(2.0, 1.0, 0.0)
        val v3 = com.example.domain.math.Vec3(0.0, 1.0, 0.0)
        val face = com.example.domain.model.CadFace(
            id = "f_base",
            nodeId = "root",
            layer = "Layer 0",
            outerLoop = listOf(v0, v1, v2, v3),
            normal = com.example.domain.math.Vec3(0.0, 0.0, 1.0),
            triangleVertices = listOf(v0, v1, v2, v0, v2, v3),
            triangleUvs = List(6) { com.example.domain.math.Vec2(0.0, 0.0) },
            color = com.example.domain.model.CadColor.DEFAULT_FRONT
        )
        val edge = com.example.domain.model.CadEdge(
            id = "e_front",
            nodeId = "root",
            layer = "Layer 0",
            a = com.example.domain.math.Vec3(0.05, 0.04, 0.0),
            b = v1
        )
        val doc = com.example.domain.model.CadDocument(
            documentKey = "ar_dot_math_doc",
            title = "Bench",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = listOf(face),
            edges = listOf(edge),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3(0.0, 0.0, 0.0),
                com.example.domain.math.Vec3(2.0, 1.0, 0.8)
            ),
            stats = com.example.domain.model.ModelStats(1, 1, 1, 0, 4, 1, 0, 0)
        )

        // 1. Preset snaps to actual nearby vertex edge.a (0.05, 0.04, 0.0)
        val flPreset = com.example.ui.ar.ArTrackingDotMath.resolvePresetDot(
            document = doc,
            bounds = doc.bounds,
            preset = com.example.ui.ar.ArTrackingDotPreset.FRONT_LEFT_BASE
        )
        assertEquals(0.05, flPreset.point.x, 1e-6)
        assertEquals(0.04, flPreset.point.y, 1e-6)
        assertEquals(com.example.domain.model.SnapType.VERTEX, flPreset.snapType)

        // 2. Project & pick dot directly on rendered AR model via MVP matrix
        val viewMat = FloatArray(16)
        val projMat = FloatArray(16)
        val worldMat = FloatArray(16)
        val mvMat = FloatArray(16)
        val mvpMat = FloatArray(16)
        android.opengl.Matrix.setLookAtM(viewMat, 0, 1.0f, -3.0f, 1.5f, 1.0f, 0.5f, 0.0f, 0f, 0f, 1f)
        android.opengl.Matrix.perspectiveM(projMat, 0, 50f, 1000f / 800f, 0.1f, 50f)
        android.opengl.Matrix.setIdentityM(worldMat, 0)
        android.opengl.Matrix.multiplyMM(mvMat, 0, viewMat, 0, worldMat, 0)
        android.opengl.Matrix.multiplyMM(mvpMat, 0, projMat, 0, mvMat, 0)

        val spV1 = com.example.ui.ar.ArTrackingDotMath.projectModelPointToScreen(v1, mvpMat, 1000, 800)!!
        val picked = com.example.ui.ar.ArTrackingDotMath.pickDotOnRenderedArModel(
            document = doc,
            mvpMatrix = mvpMat,
            screenX = spV1.x.toFloat(),
            screenY = spV1.y.toFloat(),
            viewportWidth = 1000,
            viewportHeight = 800
        )!!
        assertEquals(2.0, picked.point.x, 1e-3)
        assertEquals(0.0, picked.point.y, 1e-3)
        assertEquals(0.0, picked.point.z, 1e-3)

        // 3. Stationary world shift keeps model geometry fixed in world space when switching dot
        val oldAlign = v0
        val newAlign = v1
        val currentWorld = FloatArray(16)
        android.opengl.Matrix.setIdentityM(currentWorld, 0)
        android.opengl.Matrix.translateM(currentWorld, 0, 1.0f, 0.5f, -2.0f)
        android.opengl.Matrix.rotateM(currentWorld, 0, 30f, 0f, 1f, 0f)
        android.opengl.Matrix.rotateM(currentWorld, 0, -90f, 1f, 0f, 0f)
        android.opengl.Matrix.translateM(currentWorld, 0, -oldAlign.x.toFloat(), -oldAlign.y.toFloat(), -oldAlign.z.toFloat())

        val shift = com.example.ui.ar.ArTrackingDotMath.computeStationaryWorldShiftForNewDot(
            oldAlign = oldAlign,
            newAlign = newAlign,
            currentWorldMatrix = currentWorld,
            existingShift = floatArrayOf(0f, 0f, 0f)
        )
        val recomputedWorld = FloatArray(16)
        android.opengl.Matrix.setIdentityM(recomputedWorld, 0)
        android.opengl.Matrix.translateM(recomputedWorld, 0, 1.0f + shift[0], 0.5f + shift[1], -2.0f + shift[2])
        android.opengl.Matrix.rotateM(recomputedWorld, 0, 30f, 0f, 1f, 0f)
        android.opengl.Matrix.rotateM(recomputedWorld, 0, -90f, 1f, 0f, 0f)
        android.opengl.Matrix.translateM(recomputedWorld, 0, -newAlign.x.toFloat(), -newAlign.y.toFloat(), -newAlign.z.toFloat())

        val beforePt = com.example.ui.ar.ArTrackingDotMath.transformModelPointToWorld(v2, currentWorld)
        val afterPt = com.example.ui.ar.ArTrackingDotMath.transformModelPointToWorld(v2, recomputedWorld)
        assertEquals(beforePt[0], afterPt[0], 1e-4f)
        assertEquals(beforePt[1], afterPt[1], 1e-4f)
        assertEquals(beforePt[2], afterPt[2], 1e-4f)
    }

    @Test
    fun `ar hud overlay switches Move Tool tap mode, applies dot presets, and opens 3D dot picker`() {
        val doc = com.example.domain.model.CadDocument(
            documentKey = "ar_dot_hud_doc",
            title = "Table",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3(0.0, 0.0, 0.0),
                com.example.domain.math.Vec3(1.5, 0.8, 0.75)
            ),
            stats = com.example.domain.model.ModelStats(1, 0, 0, 0, 0, 0, 0, 0)
        )
        var trackingDot by mutableStateOf(com.example.ui.ar.ArTrackingDotMath.defaultTrackingDot(doc))
        var anchorOrigin by mutableStateOf(com.example.ui.ar.ArAnchorOrigin.MODEL_DOT)
        var tapActionMode by mutableStateOf(com.example.ui.ar.ArTapActionMode.MOVE_IN_WORLD)
        var opened3dPicker = false

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.ar.ArHudOverlay(
                    document = doc,
                    unitSystem = UnitSystem.METERS,
                    precisionDecimals = 2,
                    trackingStatus = com.example.ui.ar.ArTrackingStatus(
                        isPlaced = true,
                        trackingDot = trackingDot,
                        trackingDotScreenPos = com.example.domain.math.Vec2(240.0, 320.0),
                        tapActionMode = tapActionMode
                    ),
                    selectedScale = 1.0f,
                    rotationDeg = 0f,
                    verticalOffsetMeters = 0f,
                    anchorOrigin = anchorOrigin,
                    trackingDot = trackingDot,
                    tapActionMode = tapActionMode,
                    showFoundation = true,
                    hasFoundationBelowAxis = false,
                    trackingMode = com.example.ui.ar.ArTrackingMode.OBJECT_SURFACE,
                    targetWidthMeters = 0.2f,
                    captureFeedback = null,
                    onBack = {},
                    onOpenUnitDialog = {},
                    onClearAnchor = {},
                    onSelectScale = {},
                    onChangeRotation = {},
                    onChangeVerticalOffset = {},
                    onSelectAnchorOrigin = { anchorOrigin = it },
                    onSelectTapActionMode = { tapActionMode = it },
                    onSelectDotPreset = { preset ->
                        trackingDot = com.example.ui.ar.ArTrackingDotMath.resolvePresetDot(doc, doc.bounds, preset)
                        anchorOrigin = com.example.ui.ar.ArAnchorOrigin.MODEL_DOT
                    },
                    onOpen3dDotPicker = { opened3dPicker = true },
                    onSelectTrackingMode = {},
                    onSelectTargetWidth = {},
                    onPinAtCenter = {},
                    onCaptureObjectTarget = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Tracking dot badge stays hidden during normal AR viewing and appears only when Dot tab is opened
        composeRule.onNodeWithTag("ar_model_tracking_dot_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("ar_tab_dot").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ar_model_tracking_dot_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_tracking_dot_section").assertIsDisplayed()
        composeRule.onNodeWithTag("ar_tracking_dot_readout").assertIsDisplayed()

        // Switch tap mode to "1. Pick Dot on Model"
        composeRule.onNodeWithTag("ar_tap_mode_pick_dot").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(com.example.ui.ar.ArTapActionMode.PICK_DOT_ON_MODEL, tapActionMode)

        // Select Front-Left Base dot preset
        composeRule.onNodeWithTag("ar_dot_preset_front_left_base").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(com.example.ui.ar.ArTrackingDotPreset.FRONT_LEFT_BASE.label, trackingDot.label)
        assertEquals(0.0, trackingDot.point.x, 1e-6)

        // Open 3D Dot Picker modal button
        composeRule.onNodeWithTag("ar_open_3d_dot_picker_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(opened3dPicker)
    }

    @Test
    fun `tape measurement dot drag hit-tests endpoints, snaps or slides on plane, and recomputes live length`() {
        val cam = com.example.ui.viewer.gl.CadCamera().apply {
            target = com.example.domain.math.Vec3(1.0, 0.5, 0.0)
            distance = 4.0
            yawRad = -Math.PI / 4.0
            pitchRad = Math.PI / 6.0
        }
        cam.updateMatrices(1000, 800)

        val pA = com.example.domain.math.Vec3(0.0, 0.0, 0.0)
        val pB = com.example.domain.math.Vec3(2.0, 0.0, 0.0)
        val pTargetVertex = com.example.domain.math.Vec3(2.0, 1.0, 0.0)

        val saved = listOf(
            com.example.data.local.LengthMeasurementEntity(
                id = 7L,
                documentKey = "tape_drag_doc",
                label = "L1",
                ax = pA.x, ay = pA.y, az = pA.z,
                bx = pB.x, by = pB.y, bz = pB.z,
                lengthMeters = pA.distanceTo(pB)
            )
        )

        // 1. Hit-test endpoint B from screen touch
        val spB = cam.projectToScreen(pB)!!
        val hitB = com.example.ui.viewer.gl.LengthDotDragMath.findHitLengthDot(
            screenX = (spB.x + 8.0).toFloat(),
            screenY = (spB.y - 6.0).toFloat(),
            savedLengths = saved,
            activeLengthPicks = emptyList(),
            projectToScreen = cam::projectToScreen,
            hitRadiusPx = 64.0
        )!!
        assertEquals(com.example.ui.viewer.gl.LengthDotTarget.SavedEndpoint(7L, isStartA = false), hitB.target)

        // Far tap misses all dots
        val miss = com.example.ui.viewer.gl.LengthDotDragMath.findHitLengthDot(
            screenX = 5f,
            screenY = 5f,
            savedLengths = saved,
            activeLengthPicks = emptyList(),
            projectToScreen = cam::projectToScreen,
            hitRadiusPx = 24.0
        )
        assertEquals(null, miss)

        // 2. Drag resolves snap to vertex when near geometry, and falls back to plane in empty space
        val doc = com.example.domain.model.CadDocument(
            documentKey = "tape_drag_doc",
            title = "Slab",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = listOf(
                com.example.domain.model.CadFace(
                    id = "f1",
                    nodeId = "root",
                    layer = "Layer 0",
                    outerLoop = listOf(pA, pB, pTargetVertex, com.example.domain.math.Vec3(0.0, 1.0, 0.0)),
                    normal = com.example.domain.math.Vec3(0.0, 0.0, 1.0),
                    triangleVertices = listOf(pA, pB, pTargetVertex),
                    triangleUvs = List(3) { com.example.domain.math.Vec2(0.0, 0.0) },
                    color = com.example.domain.model.CadColor.DEFAULT_FRONT
                )
            ),
            edges = listOf(
                com.example.domain.model.CadEdge("e1", "root", "Layer 0", pB, pTargetVertex)
            ),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(pA, pTargetVertex),
            stats = com.example.domain.model.ModelStats(1, 1, 1, 0, 3, 1, 0, 0)
        )

        val spV = cam.projectToScreen(pTargetVertex)!!
        val (snappedPt, snapHit) = com.example.ui.viewer.gl.LengthDotDragMath.resolveDraggedPoint(
            doc = doc,
            hiddenLayers = emptySet(),
            hiddenNodes = emptySet(),
            screenX = spV.x.toFloat(),
            screenY = spV.y.toFloat(),
            viewportWidth = cam.viewportWidth,
            viewportHeight = cam.viewportHeight,
            projectToScreen = cam::projectToScreen,
            rayFromScreen = cam::rayFromScreen,
            cameraForward = cam.forwardVector(),
            planeOrigin = pB
        )!!
        assertEquals(com.example.domain.model.SnapType.VERTEX, snapHit?.type)
        assertEquals(pTargetVertex.x, snappedPt.x, 1e-4)
        assertEquals(pTargetVertex.y, snappedPt.y, 1e-4)

        val (freePt, freeSnap) = com.example.ui.viewer.gl.LengthDotDragMath.resolveDraggedPoint(
            doc = doc,
            hiddenLayers = emptySet(),
            hiddenNodes = emptySet(),
            screenX = 60f,
            screenY = 60f,
            viewportWidth = cam.viewportWidth,
            viewportHeight = cam.viewportHeight,
            projectToScreen = cam::projectToScreen,
            rayFromScreen = cam::rayFromScreen,
            cameraForward = cam.forwardVector(),
            planeOrigin = pB
        )!!
        assertEquals(null, freeSnap)
        assertTrue(freePt.distanceTo(pB) > 0.1)

        // 3. Live effective length updates distance = hypot(2, 1) = sqrt(5)
        val drag = com.example.ui.viewer.gl.ActiveLengthDotDrag(
            target = com.example.ui.viewer.gl.LengthDotTarget.SavedEndpoint(7L, isStartA = false),
            originalPoint = pB,
            currentPoint = pTargetVertex,
            snapType = com.example.domain.model.SnapType.VERTEX
        )
        val updated = com.example.ui.viewer.gl.LengthDotDragMath.effectiveSavedLengths(saved, drag)
        assertEquals(kotlin.math.sqrt(5.0), updated.first().lengthMeters, 1e-6)
        assertEquals(pTargetVertex.y, updated.first().by, 1e-6)

        // 4. Active pick dot dragging updates effectiveActivePicks
        val pickDrag = com.example.ui.viewer.gl.ActiveLengthDotDrag(
            target = com.example.ui.viewer.gl.LengthDotTarget.ActivePick(0),
            originalPoint = pA,
            currentPoint = pTargetVertex
        )
        val updatedPicks = com.example.ui.viewer.gl.LengthDotDragMath.effectiveActivePicks(listOf(pA), pickDrag)
        assertEquals(pTargetVertex, updatedPicks[0])

        // 5. Gesture policy permits dot dragging only in Tape / Select tools
        assertTrue(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsLengthDotDrag(com.example.domain.model.CadTool.MEASURE_LENGTH))
        assertTrue(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsLengthDotDrag(com.example.domain.model.CadTool.SELECT))
        assertEquals(false, com.example.ui.viewer.gl.CadToolGesturePolicy.allowsLengthDotDrag(com.example.domain.model.CadTool.ORBIT))
        assertEquals(false, com.example.ui.viewer.gl.CadToolGesturePolicy.allowsLengthDotDrag(com.example.domain.model.CadTool.PAN))
    }

    @Test
    fun `arcore availability helper returns unavailable cleanly without binding Play Store when ARCore is not installed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(false, com.example.ui.ar.ArCoreAvailabilityHelper.isArCorePackageInstalled(context))
        assertEquals(false, com.example.ui.ar.ArCoreAvailabilityHelper.hasPlayStoreInstallService(context))
        assertEquals(
            com.example.ui.ar.ArCoreReadiness.UNAVAILABLE_NO_AR_SERVICES,
            com.example.ui.ar.ArCoreAvailabilityHelper.evaluateReadiness(context)
        )
    }

    @Test
    fun `cad viewer tape and angle contextual bars show undo redo and hide unit and drag hint`() {
        val doc = com.example.domain.model.CadDocument(
            documentKey = "undo_redo_ui_doc",
            title = "Pergola",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3.ZERO,
                com.example.domain.math.Vec3(3.0, 2.0, 2.5)
            ),
            stats = com.example.domain.model.ModelStats(1, 0, 0, 0, 0, 0, 0, 0)
        )
        val savedLengths = listOf(
            com.example.data.local.LengthMeasurementEntity(
                id = 1L,
                documentKey = "undo_redo_ui_doc",
                label = "L1",
                ax = 0.0, ay = 0.0, az = 0.0,
                bx = 1.0, by = 0.0, bz = 0.0,
                lengthMeters = 1.0
            )
        )
        var viewerState by mutableStateOf(
            com.example.ui.ViewerUiState(
                activeTool = com.example.domain.model.CadTool.MEASURE_LENGTH,
                canUndoTape = false,
                canRedoTape = false
            )
        )
        var undoClicks = 0
        var redoClicks = 0

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.viewer.CadViewerScreen(
                    document = doc,
                    viewerState = viewerState,
                    preferences = UserPreferences(),
                    lengths = savedLengths,
                    angles = emptyList(),
                    countedAngles = emptyList(),
                    angleBuckets = emptyList(),
                    onBack = {},
                    onOpenArMode = {},
                    onSetTool = { viewerState = viewerState.copy(activeTool = it) },
                    onSetAngleInputMode = { viewerState = viewerState.copy(angleInputMode = it) },
                    onSetDisplayStyle = {},
                    onToggleTheme = {},
                    onToggleOrthographic = {},
                    onApplyStandardView = { _, _ -> },
                    onApplySavedView = { _, _ -> },
                    onTapViewport = { _, _, _ -> },
                    onSnapHover = { _, _, _ -> },
                    onUndoPick = { undoClicks++ },
                    onRedoPick = { redoClicks++ },
                    onClearPicks = {},
                    onClearSelection = {},
                    onToggleLayer = {},
                    onToggleNode = {},
                    onSelectNode = {},
                    onShowAllHierarchy = {},
                    onChangeUnits = {},
                    onChangeDecimals = {},
                    onChangeTolerance = {},
                    onFocusPoint = { _, _ -> },
                    onRenameLength = { _, _ -> },
                    onDeleteLength = {},
                    onRenameAngle = { _, _ -> },
                    onDeleteAngle = {},
                    onMarkAngleIntoCounter = {},
                    onAutoCountSelection = {},
                    onUpdateCountedNote = { _, _ -> },
                    onDeleteCountedAngle = {},
                    onClearCountedAngles = {},
                    onExportCsv = {},
                    onDismissBanner = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Tape contextual toolbar must NOT show Unit or Drag dot hint chips, and shows Undo & Redo
        composeRule.onNodeWithTag("viewer_contextual_unit_chip").assertDoesNotExist()
        composeRule.onNodeWithTag("viewer_drag_dot_hint_chip").assertDoesNotExist()
        composeRule.onNodeWithTag("viewer_measure_undo_button").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("viewer_measure_redo_button").assertIsDisplayed().assertIsNotEnabled()

        viewerState = viewerState.copy(canUndoTape = true, canRedoTape = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewer_measure_undo_button").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("viewer_measure_redo_button").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(1, undoClicks)
        assertEquals(1, redoClicks)

        // Angle contextual toolbar preserves mode chips and also shows Undo & Redo
        viewerState = viewerState.copy(
            activeTool = com.example.domain.model.CadTool.MEASURE_ANGLE,
            canUndoAngle = true,
            canRedoAngle = false
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 Points").assertIsDisplayed()
        composeRule.onNodeWithText("2 Edges").assertIsDisplayed()
        composeRule.onNodeWithText("2 Faces").assertIsDisplayed()
        composeRule.onNodeWithTag("viewer_measure_undo_button").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("viewer_measure_redo_button").assertIsDisplayed().assertIsNotEnabled()
        composeRule.waitForIdle()
        assertEquals(2, undoClicks)

        viewerState = viewerState.copy(canRedoAngle = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewer_measure_redo_button").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(2, redoClicks)
    }

    @Test
    fun `main viewmodel undo and redo restore tape picks, lengths, and angle picks`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repo = com.example.data.repository.CadRepository(
                context = context,
                parserRegistry = com.example.data.parser.DocumentParserRegistry(listOf(IgzParser())),
                recentFileDao = db.recentFileDao(),
                measurementDao = db.measurementDao()
            )
            val dsFile = java.io.File(context.cacheDir, "undo_redo_test_prefs_${System.nanoTime()}.preferences_pb")
            val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { dsFile }
            val prefsRepo = com.example.data.local.PreferencesRepository(dataStore)
            val vm = com.example.ui.MainViewModel(repo, prefsRepo)

            var opened = false
            vm.openSample(repo.bundledSamples.last()) { opened = true }
            val deadline = System.currentTimeMillis() + 5000L
            while (!opened && System.currentTimeMillis() < deadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(15)
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(opened)
            val doc = vm.activeDocument.value!!
            assertTrue(doc.faces.isNotEmpty())

            val camera = com.example.ui.viewer.gl.CadCamera().apply {
                zoomExtents(doc.bounds)
                updateMatrices(1000, 800)
            }
            val firstVertex = doc.faces.first().outerLoop.first()
            val secondVertex = doc.faces.first().outerLoop[1]
            val thirdVertex = doc.faces.first().outerLoop[2]
            val sp1 = camera.projectToScreen(firstVertex)!!
            val sp2 = camera.projectToScreen(secondVertex)!!
            val sp3 = camera.projectToScreen(thirdVertex)!!

            // Tape pick -> undo -> redo -> complete -> undo -> redo
            vm.setTool(com.example.domain.model.CadTool.MEASURE_LENGTH)
            assertFalse(vm.viewerState.value.canUndoTape)
            assertFalse(vm.viewerState.value.canRedoTape)

            vm.onViewportTap(sp1.x.toFloat(), sp1.y.toFloat(), camera)
            assertEquals(1, vm.viewerState.value.activeLengthPicks.size)
            assertTrue(vm.viewerState.value.canUndoTape)
            assertFalse(vm.viewerState.value.canRedoTape)

            vm.undoActivePick()
            assertEquals(0, vm.viewerState.value.activeLengthPicks.size)
            assertFalse(vm.viewerState.value.canUndoTape)
            assertTrue(vm.viewerState.value.canRedoTape)

            vm.redoActivePick()
            assertEquals(1, vm.viewerState.value.activeLengthPicks.size)
            assertTrue(vm.viewerState.value.canUndoTape)
            assertFalse(vm.viewerState.value.canRedoTape)

            vm.onViewportTap(sp2.x.toFloat(), sp2.y.toFloat(), camera)
            repeat(10) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (db.measurementDao().observeLengths(doc.documentKey).first().isEmpty()) Thread.sleep(15)
            }
            assertEquals(1, db.measurementDao().observeLengths(doc.documentKey).first().size)
            assertTrue(vm.viewerState.value.canUndoTape)

            vm.undoActivePick()
            repeat(10) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (db.measurementDao().observeLengths(doc.documentKey).first().isNotEmpty()) Thread.sleep(15)
            }
            assertEquals(0, db.measurementDao().observeLengths(doc.documentKey).first().size)
            assertTrue(vm.viewerState.value.canRedoTape)

            vm.redoActivePick()
            repeat(10) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (db.measurementDao().observeLengths(doc.documentKey).first().isEmpty()) Thread.sleep(15)
            }
            assertEquals(1, db.measurementDao().observeLengths(doc.documentKey).first().size)

            // Angle 3-Point picks -> undo -> redo -> complete -> undo -> redo
            vm.setTool(com.example.domain.model.CadTool.MEASURE_ANGLE)
            vm.onViewportTap(sp1.x.toFloat(), sp1.y.toFloat(), camera)
            vm.onViewportTap(sp2.x.toFloat(), sp2.y.toFloat(), camera)
            assertEquals(2, vm.viewerState.value.activeAnglePicks.size)
            assertTrue(vm.viewerState.value.canUndoAngle)

            vm.undoActivePick()
            assertEquals(1, vm.viewerState.value.activeAnglePicks.size)
            assertTrue(vm.viewerState.value.canRedoAngle)

            vm.redoActivePick()
            assertEquals(2, vm.viewerState.value.activeAnglePicks.size)

            vm.onViewportTap(sp3.x.toFloat(), sp3.y.toFloat(), camera)
            repeat(10) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (db.measurementDao().observeAngles(doc.documentKey).first().isEmpty()) Thread.sleep(15)
            }
            assertEquals(1, db.measurementDao().observeAngles(doc.documentKey).first().size)
            assertTrue(vm.viewerState.value.canUndoAngle)

            vm.undoActivePick()
            repeat(10) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (db.measurementDao().observeAngles(doc.documentKey).first().isNotEmpty()) Thread.sleep(15)
            }
            assertEquals(0, db.measurementDao().observeAngles(doc.documentKey).first().size)
            assertTrue(vm.viewerState.value.canRedoAngle)

            vm.redoActivePick()
            repeat(10) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (db.measurementDao().observeAngles(doc.documentKey).first().isEmpty()) Thread.sleep(15)
            }
            assertEquals(1, db.measurementDao().observeAngles(doc.documentKey).first().size)
            dsFile.delete()
        }
    }

    @Test
    fun `camera orbit and pan immediately refresh screen projection so movable dots stay synced with lines`() {
        val cam = com.example.ui.viewer.gl.CadCamera().apply {
            target = com.example.domain.math.Vec3(1.0, 1.0, 0.5)
            distance = 5.0
            yawRad = -Math.PI / 4.0
            pitchRad = Math.PI / 6.0
            updateMatrices(1000, 800)
        }
        val dotPoint = com.example.domain.math.Vec3(1.5, 0.5, 0.5)
        val initialPos = cam.projectToScreen(dotPoint)!!

        // Orbit without calling updateMatrices externally -> projection must update immediately
        cam.orbit(120f, 45f)
        val afterOrbitPos = cam.projectToScreen(dotPoint)!!
        assertTrue(
            "Dot screen coordinates must move immediately on orbit",
            kotlin.math.hypot(afterOrbitPos.x - initialPos.x, afterOrbitPos.y - initialPos.y) > 15.0
        )

        // Pan without calling updateMatrices externally -> projection must update immediately
        cam.pan(80f, -60f)
        val afterPanPos = cam.projectToScreen(dotPoint)!!
        assertTrue(
            "Dot screen coordinates must move immediately on pan",
            kotlin.math.hypot(afterPanPos.x - afterOrbitPos.x, afterPanPos.y - afterOrbitPos.y) > 15.0
        )
    }

    @Test
    fun `angle dot drag hit-tests vertex and arms, recomputes live degrees, and measurement hit math detects lines`() {
        val cam = com.example.ui.viewer.gl.CadCamera().apply {
            target = com.example.domain.math.Vec3(0.5, 0.5, 0.0)
            distance = 4.0
            yawRad = -Math.PI / 4.0
            pitchRad = Math.PI / 4.0
            updateMatrices(1000, 800)
        }
        val v = com.example.domain.math.Vec3(0.0, 0.0, 0.0)
        val a = com.example.domain.math.Vec3(1.0, 0.0, 0.0)
        val b = com.example.domain.math.Vec3(0.0, 1.0, 0.0)
        val b45 = com.example.domain.math.Vec3(1.0, 1.0, 0.0)

        val savedAngle = AngleMeasurementEntity(
            id = 12L,
            documentKey = "angle_drag_doc",
            label = "A1",
            vx = v.x, vy = v.y, vz = v.z,
            ax = a.x, ay = a.y, az = a.z,
            bx = b.x, by = b.y, bz = b.z,
            angleDegrees = 90.0,
            mode = "3-Point"
        )
        val savedLength = com.example.data.local.LengthMeasurementEntity(
            id = 5L,
            documentKey = "angle_drag_doc",
            label = "L1",
            ax = 0.0, ay = 0.0, az = 1.0,
            bx = 1.0, by = 0.0, bz = 1.0,
            lengthMeters = 1.0
        )

        // 1. Hit-test angle arm B dot and vertex dot
        val spB = cam.projectToScreen(b)!!
        val hitB = com.example.ui.viewer.gl.AngleDotDragMath.findHitAngleDot(
            screenX = (spB.x + 4.0).toFloat(),
            screenY = (spB.y - 3.0).toFloat(),
            savedAngles = listOf(savedAngle),
            activeAnglePicks = emptyList(),
            projectToScreen = cam::projectToScreen,
            hitRadiusPx = 64.0
        )!!
        assertEquals(
            com.example.ui.viewer.gl.AngleDotTarget.SavedPoint(12L, com.example.ui.viewer.gl.AnglePointRole.RAY_B),
            hitB.target
        )

        val spV = cam.projectToScreen(v)!!
        val hitV = com.example.ui.viewer.gl.AngleDotDragMath.findHitAngleDot(
            screenX = spV.x.toFloat(),
            screenY = spV.y.toFloat(),
            savedAngles = listOf(savedAngle),
            activeAnglePicks = emptyList(),
            projectToScreen = cam::projectToScreen,
            hitRadiusPx = 64.0
        )!!
        assertEquals(
            com.example.ui.viewer.gl.AngleDotTarget.SavedPoint(12L, com.example.ui.viewer.gl.AnglePointRole.VERTEX),
            hitV.target
        )

        // 2. Dragging arm B to (1,1,0) recomputes angle from 90 deg to 45 deg
        val drag = com.example.ui.viewer.gl.ActiveAngleDotDrag(
            target = com.example.ui.viewer.gl.AngleDotTarget.SavedPoint(12L, com.example.ui.viewer.gl.AnglePointRole.RAY_B),
            originalPoint = b,
            currentPoint = b45
        )
        val updatedAngles = com.example.ui.viewer.gl.AngleDotDragMath.effectiveSavedAngles(listOf(savedAngle), drag)
        assertEquals(45.0, updatedAngles.first().angleDegrees, 1e-4)

        // 3. Long-press hit-testing finds individual tape line midpoint and angle arm
        val spLenMid = cam.projectToScreen(com.example.domain.math.Vec3(0.5, 0.0, 1.0))!!
        val hitLine = com.example.ui.viewer.gl.MeasurementHitMath.findHitSavedMeasurement(
            screenX = spLenMid.x.toFloat(),
            screenY = spLenMid.y.toFloat(),
            activeTool = com.example.domain.model.CadTool.MEASURE_LENGTH,
            savedLengths = listOf(savedLength),
            savedAngles = listOf(savedAngle),
            projectToScreen = cam::projectToScreen,
            hitRadiusPx = 60.0
        )!!
        assertTrue(hitLine.target is com.example.ui.viewer.gl.MeasurementContextTarget.Length)
        assertEquals(5L, (hitLine.target as com.example.ui.viewer.gl.MeasurementContextTarget.Length).entity.id)

        val spAngArm = cam.projectToScreen(com.example.domain.math.Vec3(0.0, 0.5, 0.0))!!
        val hitAng = com.example.ui.viewer.gl.MeasurementHitMath.findHitSavedMeasurement(
            screenX = spAngArm.x.toFloat(),
            screenY = spAngArm.y.toFloat(),
            activeTool = com.example.domain.model.CadTool.MEASURE_ANGLE,
            savedLengths = listOf(savedLength),
            savedAngles = listOf(savedAngle),
            projectToScreen = cam::projectToScreen,
            hitRadiusPx = 60.0
        )!!
        assertTrue(hitAng.target is com.example.ui.viewer.gl.MeasurementContextTarget.Angle)
        assertEquals(12L, (hitAng.target as com.example.ui.viewer.gl.MeasurementContextTarget.Angle).entity.id)

        // 4. Gesture policy checks
        assertTrue(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsAngleDotDrag(com.example.domain.model.CadTool.MEASURE_ANGLE))
        assertTrue(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsAngleDotDrag(com.example.domain.model.CadTool.SELECT))
        assertFalse(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsAngleDotDrag(com.example.domain.model.CadTool.ORBIT))
        assertFalse(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsAngleDotDrag(com.example.domain.model.CadTool.PAN))
        assertTrue(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsMeasurementLongPress(com.example.domain.model.CadTool.MEASURE_LENGTH))
        assertTrue(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsMeasurementLongPress(com.example.domain.model.CadTool.MEASURE_ANGLE))
        assertFalse(com.example.ui.viewer.gl.CadToolGesturePolicy.allowsMeasurementLongPress(com.example.domain.model.CadTool.ORBIT))
    }

    @Test
    fun `long pressing tape or angle measurement opens context menu and deletes individual line`() {
        val doc = com.example.domain.model.CadDocument(
            documentKey = "ctx_menu_doc",
            title = "Frame",
            format = com.example.domain.model.ModelFormat.IGZ,
            formatVersion = "v2",
            nodes = emptyList(),
            faces = emptyList(),
            edges = emptyList(),
            layers = emptyList(),
            materials = emptyMap(),
            textures = emptyMap(),
            savedViews = emptyList(),
            bounds = com.example.domain.math.BoundingBox3D(
                com.example.domain.math.Vec3(-1.0, -1.0, 0.0),
                com.example.domain.math.Vec3(1.0, 1.0, 1.0)
            ),
            stats = com.example.domain.model.ModelStats(1, 0, 0, 0, 0, 0, 0, 0)
        )
        val savedLengths = listOf(
            com.example.data.local.LengthMeasurementEntity(
                id = 101L,
                documentKey = "ctx_menu_doc",
                label = "L1",
                ax = -0.5, ay = 0.0, az = 0.5,
                bx = 0.5, by = 0.0, bz = 0.5,
                lengthMeters = 1.0
            )
        )
        val savedAngles = listOf(
            AngleMeasurementEntity(
                id = 202L,
                documentKey = "ctx_menu_doc",
                label = "A1",
                vx = 0.0, vy = 0.0, vz = 0.5,
                ax = 0.5, ay = 0.0, az = 0.5,
                bx = 0.0, by = 0.5, bz = 0.5,
                angleDegrees = 90.0,
                mode = "3-Point"
            )
        )
        var viewerState by mutableStateOf(
            com.example.ui.ViewerUiState(activeTool = com.example.domain.model.CadTool.MEASURE_LENGTH)
        )
        var deletedLengthId: Long? = null
        var deletedAngleId: Long? = null

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                com.example.ui.viewer.CadViewerScreen(
                    document = doc,
                    viewerState = viewerState,
                    preferences = UserPreferences(),
                    lengths = savedLengths,
                    angles = savedAngles,
                    countedAngles = emptyList(),
                    angleBuckets = emptyList(),
                    onBack = {},
                    onOpenArMode = {},
                    onSetTool = { viewerState = viewerState.copy(activeTool = it) },
                    onSetAngleInputMode = {},
                    onSetDisplayStyle = {},
                    onToggleTheme = {},
                    onToggleOrthographic = {},
                    onApplyStandardView = { _, _ -> },
                    onApplySavedView = { _, _ -> },
                    onTapViewport = { _, _, _ -> },
                    onSnapHover = { _, _, _ -> },
                    onUndoPick = {},
                    onClearPicks = {},
                    onClearSelection = {},
                    onToggleLayer = {},
                    onToggleNode = {},
                    onSelectNode = {},
                    onShowAllHierarchy = {},
                    onChangeUnits = {},
                    onChangeDecimals = {},
                    onChangeTolerance = {},
                    onFocusPoint = { _, _ -> },
                    onRenameLength = { _, _ -> },
                    onDeleteLength = { deletedLengthId = it },
                    onRenameAngle = { _, _ -> },
                    onDeleteAngle = { deletedAngleId = it },
                    onMarkAngleIntoCounter = {},
                    onAutoCountSelection = {},
                    onUpdateCountedNote = { _, _ -> },
                    onDeleteCountedAngle = {},
                    onClearCountedAngles = {},
                    onExportCsv = {},
                    onDismissBanner = {}
                )
            }
        }
        composeRule.waitForIdle()

        // 1. Long-press Tape measurement badge -> opens context menu -> deletes length 101
        composeRule.onNodeWithTag("viewport_length_badge_101")
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("measurement_context_menu").assertIsDisplayed()
        composeRule.onNodeWithText("Delete line").assertIsDisplayed()
        composeRule.onNodeWithTag("measurement_context_delete_button").performClick()
        composeRule.waitForIdle()
        assertEquals(101L, deletedLengthId)

        // 2. Switch to Angle tool -> long-press Angle badge -> opens context menu -> deletes angle 202
        viewerState = viewerState.copy(activeTool = com.example.domain.model.CadTool.MEASURE_ANGLE)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewport_angle_badge_202")
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("measurement_context_menu").assertIsDisplayed()
        composeRule.onNodeWithText("Delete angle").assertIsDisplayed()
        composeRule.onNodeWithTag("measurement_context_delete_button").performClick()
        composeRule.waitForIdle()
        assertEquals(202L, deletedAngleId)
    }
}
