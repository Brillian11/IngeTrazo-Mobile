package com.example.ui.ar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.math.BoundingBox3D
import com.example.domain.math.SnapEngine
import com.example.domain.math.SnapHit
import com.example.domain.model.CadDocument
import com.example.domain.model.CadTool
import com.example.domain.model.DisplayStyle
import com.example.domain.model.SnapType
import com.example.domain.model.UnitSystem
import com.example.ui.theme.CadNumericStyle
import com.example.ui.theme.SnapEdgeRed
import com.example.ui.theme.SnapFaceBlue
import com.example.ui.theme.SnapMidpointCyan
import com.example.ui.theme.SnapVertexGreen
import com.example.ui.viewer.gl.CadCamera
import com.example.ui.viewer.gl.CadGlRenderer
import com.example.ui.viewer.gl.CadViewportView
import com.example.ui.viewer.gl.OverlayRenderState
import kotlin.math.roundToInt

private enum class DotPickerInteractionTool {
    PLACE_DOT,
    ORBIT,
    PAN
}

/**
 * Interactive 3D Model Tracking Dot Picker dialog modeled after the IngeTrazo Desktop Move tool.
 * Lets the user orbit/pan the 3D model and tap any vertex, midpoint, edge, or face (or pick a corner preset)
 * to place the Move-tool tracking dot that locks onto the real-world anchor in AR.
 */
@Composable
fun ArModelDotPickerDialog(
    document: CadDocument,
    effectiveBounds: BoundingBox3D = document.bounds,
    initialDot: ArModelTrackingDot,
    unitSystem: UnitSystem,
    precisionDecimals: Int,
    darkCadMode: Boolean = true,
    onConfirmDot: (ArModelTrackingDot) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDot by remember(initialDot) { mutableStateOf(initialDot) }
    var activeSnap by remember { mutableStateOf<SnapHit?>(null) }
    var pickerTool by remember { mutableStateOf(DotPickerInteractionTool.PLACE_DOT) }
    var cameraTick by remember { mutableIntStateOf(0) }

    val camera = remember(document.documentKey) {
        CadCamera().apply { zoomExtents(effectiveBounds) }
    }
    val renderer = remember(document.documentKey) {
        CadGlRenderer(camera) {
            cameraTick++
        }
    }

    val mappedCadTool = when (pickerTool) {
        DotPickerInteractionTool.PLACE_DOT -> CadTool.MEASURE_LENGTH // enables tap + snap hover in CadViewportView
        DotPickerInteractionTool.ORBIT -> CadTool.ORBIT
        DotPickerInteractionTool.PAN -> CadTool.PAN
    }

    val overlayState = remember(activeSnap, darkCadMode) {
        OverlayRenderState(
            displayStyle = DisplayStyle.SHADED_EDGES,
            darkCadMode = darkCadMode,
            activeSnap = activeSnap
        )
    }

    val snapAndSelectAt: (Float, Float) -> Unit = { sx, sy ->
        val hit = SnapEngine.findBestSnap(
            doc = document,
            hiddenLayers = emptySet(),
            hiddenNodes = emptySet(),
            screenX = sx,
            screenY = sy,
            viewportWidth = camera.viewportWidth,
            viewportHeight = camera.viewportHeight,
            projectToScreen = camera::projectToScreen,
            rayFromScreen = camera::rayFromScreen,
            snapRadiusPx = 72.0
        )
        if (hit != null) {
            activeSnap = hit
            selectedDot = ArModelTrackingDot(
                point = hit.point,
                snapType = hit.type,
                label = hit.type.label
            )
            cameraTick++
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 680.dp)
                .padding(vertical = 16.dp)
                .testTag("ar_model_dot_picker_dialog")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenWith,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Place Tracking Dot on Model",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap a corner, midpoint, edge, or face — just like the IngeTrazo Move tool",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("ar_dot_picker_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close dot picker")
                    }
                }

                // 3D Viewport tool bar (Tap Dot / Orbit / Pan / Fit)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = pickerTool == DotPickerInteractionTool.PLACE_DOT,
                        onClick = { pickerTool = DotPickerInteractionTool.PLACE_DOT },
                        leadingIcon = {
                            Icon(Icons.Default.AdsClick, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Tap Dot") },
                        modifier = Modifier.testTag("ar_dot_picker_tool_dot")
                    )
                    FilterChip(
                        selected = pickerTool == DotPickerInteractionTool.ORBIT,
                        onClick = { pickerTool = DotPickerInteractionTool.ORBIT },
                        leadingIcon = {
                            Icon(Icons.Default._3dRotation, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Orbit 3D") },
                        modifier = Modifier.testTag("ar_dot_picker_tool_orbit")
                    )
                    FilterChip(
                        selected = pickerTool == DotPickerInteractionTool.PAN,
                        onClick = { pickerTool = DotPickerInteractionTool.PAN },
                        leadingIcon = {
                            Icon(Icons.Default.PanTool, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Pan") },
                        modifier = Modifier.testTag("ar_dot_picker_tool_pan")
                    )
                    TextButton(
                        onClick = {
                            camera.zoomExtents(effectiveBounds)
                            cameraTick++
                        },
                        modifier = Modifier.testTag("ar_dot_picker_zoom_fit")
                    ) {
                        Icon(Icons.Default.FitScreen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Fit")
                    }
                }

                // Interactive 3D Viewport with Glowing Move Tool Dot Overlay
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CadViewportView(
                            document = document,
                            camera = camera,
                            renderer = renderer,
                            activeTool = mappedCadTool,
                            overlayState = overlayState,
                            unitSystem = unitSystem,
                            decimals = precisionDecimals,
                            cameraRenderSignal = cameraTick.toLong(),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("ar_dot_picker_viewport"),
                            onTapAt = { x, y ->
                                if (pickerTool == DotPickerInteractionTool.PLACE_DOT) {
                                    snapAndSelectAt(x, y)
                                }
                            },
                            onDoubleTap = {
                                camera.zoomExtents(effectiveBounds)
                                cameraTick++
                            },
                            onSnapHover = { x, y ->
                                if (pickerTool == DotPickerInteractionTool.PLACE_DOT) {
                                    snapAndSelectAt(x, y)
                                }
                            }
                        )

                        // Draw the active Tracking Dot marker on the 3D model
                        val _tick = cameraTick
                        val projectedDot = camera.projectToScreen(selectedDot.point)
                        if (projectedDot != null) {
                            val density = LocalDensity.current
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawIngeTrazoMoveDotMarker(
                                    center = Offset(projectedDot.x.toFloat(), projectedDot.y.toFloat()),
                                    snapType = selectedDot.snapType
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xFFE65100).copy(alpha = 0.94f),
                                shadowElevation = 4.dp,
                                modifier = Modifier.offset {
                                    IntOffset(
                                        x = (projectedDot.x + 14 * density.density).roundToInt(),
                                        y = (projectedDot.y - 28 * density.density).roundToInt()
                                    )
                                }
                            ) {
                                Text(
                                    text = "Move Dot · ${selectedDot.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                // Current Dot Coordinates Readout Banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ar_dot_picker_coordinates_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Tracking Dot: ${selectedDot.label}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = ArTrackingDotMath.formatDotCoordinates(
                                point = selectedDot.point,
                                unit = unitSystem,
                                decimals = precisionDecimals
                            ),
                            style = CadNumericStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Quick Reference Point Presets (Corners, Base Center, Axis, Top)
                Text(
                    text = "Quick Snap Reference Points:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ArTrackingDotPreset.entries.forEach { preset ->
                        val isPresetSelected = selectedDot.label == preset.label
                        FilterChip(
                            selected = isPresetSelected,
                            onClick = {
                                selectedDot = ArTrackingDotMath.resolvePresetDot(
                                    document = document,
                                    bounds = effectiveBounds,
                                    preset = preset
                                )
                                cameraTick++
                            },
                            label = { Text(preset.label) },
                            modifier = Modifier.testTag("ar_dot_picker_preset_${preset.name.lowercase()}")
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("ar_dot_picker_cancel_button")
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirmDot(selectedDot) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("ar_dot_picker_confirm_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use This Dot in AR")
                    }
                }
            }
        }
    }
}

/**
 * Shared Canvas helper that draws the distinctive IngeTrazo Desktop Move-tool tracking dot
 * (glowing outer bullseye ring, CAD snap shape, crosshair ticks, and high-contrast center dot).
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIngeTrazoMoveDotMarker(
    center: Offset,
    snapType: SnapType
) {
    val snapColor = when (snapType) {
        SnapType.VERTEX -> SnapVertexGreen
        SnapType.MIDPOINT -> SnapMidpointCyan
        SnapType.EDGE -> SnapEdgeRed
        SnapType.FACE -> SnapFaceBlue
    }
    val moveAccent = Color(0xFFFF5722)

    // Outer halo ring
    drawCircle(
        color = moveAccent.copy(alpha = 0.26f),
        radius = 22.dp.toPx(),
        center = center
    )
    drawCircle(
        color = moveAccent,
        radius = 16.dp.toPx(),
        center = center,
        style = Stroke(width = 2.5.dp.toPx())
    )

    // Crosshair leader ticks
    val tickInner = 17.dp.toPx()
    val tickOuter = 24.dp.toPx()
    val tickStroke = 2.dp.toPx()
    drawLine(Color.White, Offset(center.x - tickOuter, center.y), Offset(center.x - tickInner, center.y), tickStroke)
    drawLine(Color.White, Offset(center.x + tickInner, center.y), Offset(center.x + tickOuter, center.y), tickStroke)
    drawLine(Color.White, Offset(center.x, center.y - tickOuter), Offset(center.x, center.y - tickInner), tickStroke)
    drawLine(Color.White, Offset(center.x, center.y + tickInner), Offset(center.x, center.y + tickOuter), tickStroke)

    // CAD Snap indicator geometry
    val boxHalf = 8.dp.toPx()
    when (snapType) {
        SnapType.VERTEX, SnapType.EDGE -> {
            drawRect(
                color = snapColor,
                topLeft = Offset(center.x - boxHalf, center.y - boxHalf),
                size = Size(boxHalf * 2f, boxHalf * 2f),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
        SnapType.MIDPOINT -> {
            drawCircle(
                color = snapColor,
                radius = 9.dp.toPx(),
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
        SnapType.FACE -> {
            val d = 10.dp.toPx()
            val diamond = Path().apply {
                moveTo(center.x, center.y - d)
                lineTo(center.x + d, center.y)
                lineTo(center.x, center.y + d)
                lineTo(center.x - d, center.y)
                close()
            }
            drawPath(diamond, color = snapColor, style = Stroke(width = 2.5.dp.toPx()))
        }
    }

    // High-contrast center Move Tool dot
    drawCircle(
        color = Color.White,
        radius = 6.dp.toPx(),
        center = center
    )
    drawCircle(
        color = moveAccent,
        radius = 4.2.dp.toPx(),
        center = center
    )
}
