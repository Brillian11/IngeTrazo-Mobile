package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    fun `measurements panel unit chips switch between cm and inch`() {
        var changedUnit: UnitSystem? = null
        var changedDecimals: Int? = null

        composeRule.setContent {
            IngeTrazoTheme(darkCadMode = false) {
                MeasurementsAndAnglesPanel(
                    preferences = UserPreferences(unitSystem = UnitSystem.CENTIMETERS, precisionDecimals = 1),
                    lengths = emptyList(),
                    angles = emptyList(),
                    countedAngles = emptyList(),
                    angleBuckets = emptyList(),
                    onChangeUnits = { changedUnit = it },
                    onChangeDecimals = { changedDecimals = it },
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

        composeRule.onNodeWithTag("unit_chip_in").performClick()
        composeRule.waitForIdle()
        assertEquals(UnitSystem.INCHES, changedUnit)

        composeRule.onNodeWithTag("unit_chip_cm").performClick()
        composeRule.waitForIdle()
        assertEquals(UnitSystem.CENTIMETERS, changedUnit)

        composeRule.onNodeWithTag("precision_chip_0").performClick()
        composeRule.waitForIdle()
        assertEquals(0, changedDecimals)
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

        // Tap 1:1 preset
        composeRule.onNodeWithTag("ar_scale_1_1").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1.0f, selectedScale, 1e-6f)
        composeRule.onNodeWithText("Projected size: 200.0 × 100.0 × 100.0 cm").assertIsDisplayed()
        composeRule.onNodeWithText("TRUE 1:1").assertIsDisplayed()

        // Switch to Object Tracker mode and trigger capture
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
        composeRule.onNodeWithTag("ar_origin_axis").assertIsSelected()
        composeRule.onNodeWithTag("ar_level_toggle").assertIsSelected()
        composeRule.onNodeWithTag("ar_foundation_toggle").assertIsNotSelected()
        composeRule.onNodeWithTag("ar_textures_toggle").assertIsSelected()

        // Toggle Show Foundation ON -> height includes Z:-1..0 footing -> 3.00 m
        composeRule.onNodeWithTag("ar_foundation_toggle").performClick()
        composeRule.waitForIdle()
        assertTrue(showFoundation)
        composeRule.onNodeWithText("Projected size: 1.00 × 0.00 × 3.00 m").assertIsDisplayed()

        // Toggle Level OFF -> Surface Tilt
        composeRule.onNodeWithTag("ar_level_toggle").performClick()
        composeRule.waitForIdle()
        assertEquals(false, alwaysLevel)

        // Toggle Textures OFF
        composeRule.onNodeWithTag("ar_textures_toggle").performClick()
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

        // Enter VR Box mode
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
        composeRule.onNodeWithTag("ar_vr_mode_button").assertIsDisplayed()
    }
}
