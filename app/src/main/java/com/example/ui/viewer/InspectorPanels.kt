package com.example.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AngleMeasurementEntity
import com.example.data.local.CountedAngleEntity
import com.example.data.local.LengthMeasurementEntity
import com.example.data.local.UserPreferences
import com.example.domain.math.AngleBucketSummary
import com.example.domain.math.Vec3
import com.example.domain.model.CadDocument
import com.example.domain.model.UnitSystem
import com.example.ui.theme.CadNumericStyle
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HierarchyPanelContent(
    document: CadDocument,
    hiddenLayers: Set<String>,
    hiddenNodes: Set<String>,
    selectedNodeIds: Set<String>,
    onToggleLayer: (String) -> Unit,
    onToggleNode: (String) -> Unit,
    onSelectNode: (String) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tabIdx by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Scene Hierarchy", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onShowAll, modifier = Modifier.testTag("hierarchy_show_all")) {
                Text("Show All")
            }
        }
        PrimaryTabRow(selectedTabIndex = tabIdx) {
            Tab(
                selected = tabIdx == 0,
                onClick = { tabIdx = 0 },
                text = { Text("Layers (${document.layers.size})") },
                modifier = Modifier.testTag("tab_layers")
            )
            Tab(
                selected = tabIdx == 1,
                onClick = { tabIdx = 1 },
                text = { Text("Groups (${document.nodes.size})") },
                modifier = Modifier.testTag("tab_groups")
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (tabIdx == 0) {
                items(document.layers, key = { it.name }) { layer ->
                    val visible = layer.name !in hiddenLayers
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(layer.name, style = MaterialTheme.typography.titleMedium)
                                if (!layer.folder.isNullOrEmpty()) {
                                    Text(
                                        text = "Folder: ${layer.folder}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onToggleLayer(layer.name) },
                                modifier = Modifier.testTag("layer_eye_${layer.name}")
                            ) {
                                Icon(
                                    imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (visible) "Hide layer ${layer.name}" else "Show layer ${layer.name}"
                                )
                            }
                        }
                    }
                }
            } else {
                items(document.nodes, key = { it.id }) { node ->
                    val visible = node.id !in hiddenNodes
                    val selected = node.id in selectedNodeIds
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectNode(node.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = if (node.isComponent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = node.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${if (node.isComponent) "Component" else "Group"} · ${node.faceCount}f · ${node.edgeCount}e · ${node.layer}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onToggleNode(node.id) },
                                modifier = Modifier.testTag("node_eye_${node.id}")
                            ) {
                                Icon(
                                    imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (visible) "Hide ${node.name}" else "Show ${node.name}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MeasurementsAndAnglesPanel(
    preferences: UserPreferences,
    lengths: List<LengthMeasurementEntity>,
    angles: List<AngleMeasurementEntity>,
    countedAngles: List<CountedAngleEntity>,
    angleBuckets: List<AngleBucketSummary>,
    onChangeUnits: (UnitSystem) -> Unit,
    onChangeDecimals: (Int) -> Unit,
    onChangeTolerance: (Double) -> Unit,
    onFocusPoint: (Vec3) -> Unit,
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
    modifier: Modifier = Modifier
) {
    var tabIdx by remember { mutableIntStateOf(2) } // Default or switchable between Lengths / Angles / Counter
    var editingLabelTarget by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Measurements & Angles", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(
                onClick = onExportCsv,
                modifier = Modifier.testTag("export_csv_button")
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("CSV")
            }
        }

        // Unit & Precision Strip
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            Text(
                text = "Display Units & Precision",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                UnitSystem.entries.forEach { u ->
                    FilterChip(
                        selected = preferences.unitSystem == u,
                        onClick = { onChangeUnits(u) },
                        label = { Text(u.symbol, style = CadNumericStyle) },
                        modifier = Modifier.testTag("unit_chip_${u.symbol}")
                    )
                }
                listOf(0, 1, 2, 3).forEach { dec ->
                    val label = if (dec == 0) "0" else ".${"0".repeat(dec)}"
                    FilterChip(
                        selected = preferences.precisionDecimals == dec,
                        onClick = { onChangeDecimals(dec) },
                        label = { Text(label, style = CadNumericStyle) },
                        modifier = Modifier.testTag("precision_chip_$dec")
                    )
                }
            }
        }

        PrimaryTabRow(selectedTabIndex = tabIdx) {
            Tab(
                selected = tabIdx == 0,
                onClick = { tabIdx = 0 },
                text = { Text("Lengths (${lengths.size})") },
                modifier = Modifier.testTag("tab_lengths")
            )
            Tab(
                selected = tabIdx == 1,
                onClick = { tabIdx = 1 },
                text = { Text("Angles (${angles.size})") },
                modifier = Modifier.testTag("tab_angles")
            )
            Tab(
                selected = tabIdx == 2,
                onClick = { tabIdx = 2 },
                text = { Text("Counter (${countedAngles.size})") },
                modifier = Modifier.testTag("tab_counter")
            )
        }
        Spacer(Modifier.height(10.dp))

        when (tabIdx) {
            0 -> {
                if (lengths.isEmpty()) {
                    EmptyMeasurementHint("Use the Tape tool to snap 2 points on vertices, edges, or faces.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(lengths, key = { it.id }) { item ->
                            val dx = abs(item.bx - item.ax)
                            val dy = abs(item.by - item.ay)
                            val dz = abs(item.bz - item.az)
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${item.label} · ${preferences.unitSystem.formatMeters(item.lengthMeters, preferences.precisionDecimals)}",
                                            style = CadNumericStyle,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "ΔX ${preferences.unitSystem.formatMeters(dx, preferences.precisionDecimals)}  ΔY ${preferences.unitSystem.formatMeters(dy, preferences.precisionDecimals)}  ΔZ ${preferences.unitSystem.formatMeters(dz, preferences.precisionDecimals)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = {
                                        onFocusPoint(Vec3((item.ax + item.bx) * 0.5, (item.ay + item.by) * 0.5, (item.az + item.bz) * 0.5))
                                    }) {
                                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Focus measurement")
                                    }
                                    IconButton(onClick = {
                                        editingLabelTarget = item.label to { newLbl -> onRenameLength(item, newLbl) }
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename measurement")
                                    }
                                    IconButton(onClick = { onDeleteLength(item.id) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete measurement")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                if (angles.isEmpty()) {
                    EmptyMeasurementHint("Use the Protractor tool (3 Points, 2 Edges, or 2 Faces) to measure an angle.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(angles, key = { it.id }) { item ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = String.format(Locale.US, "%s · %.2f°", item.label, item.angleDegrees),
                                            style = CadNumericStyle,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Mode: ${item.mode} · Apex (${String.format(Locale.US, "%.2f, %.2f, %.2f", item.vx, item.vy, item.vz)})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onMarkAngleIntoCounter(item) },
                                        modifier = Modifier.testTag("mark_angle_${item.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.AddCircleOutline,
                                            contentDescription = "Add to Angle Counter",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { onFocusPoint(Vec3(item.vx, item.vy, item.vz)) }) {
                                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Focus angle")
                                    }
                                    IconButton(onClick = {
                                        editingLabelTarget = item.label to { newLbl -> onRenameAngle(item, newLbl) }
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename angle")
                                    }
                                    IconButton(onClick = { onDeleteAngle(item.id) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete angle")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                // Angle Counting Summary + Auto-Count + Table
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Total Angles", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${countedAngles.size}",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.testTag("total_angle_count")
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Distinct Values", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${angleBuckets.size}",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.testTag("distinct_angle_count")
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onAutoCountSelection,
                        modifier = Modifier.weight(1f).testTag("auto_count_angles_button")
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Auto-Count Selection")
                    }
                    if (countedAngles.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearCountedAngles,
                            modifier = Modifier.testTag("clear_counted_angles_button")
                        ) {
                            Text("Clear")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Tolerance:", style = MaterialTheme.typography.bodyMedium)
                    listOf(0.1, 0.5, 1.0).forEach { tol ->
                        FilterChip(
                            selected = abs(preferences.angleToleranceDeg - tol) < 0.01,
                            onClick = { onChangeTolerance(tol) },
                            label = { Text("±$tol°", style = CadNumericStyle) }
                        )
                    }
                }

                if (angleBuckets.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        angleBuckets.forEach { b ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = "${b.bucketKey} × ${b.count}",
                                    style = CadNumericStyle,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                if (countedAngles.isEmpty()) {
                    EmptyMeasurementHint("Tap Auto-Count Selection or mark measured angles from the Angles tab.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(countedAngles, key = { it.id }) { item ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "%.2f°", item.angleDegrees),
                                            style = CadNumericStyle,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.locationLabel,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.source}${if (item.notes.isNotBlank()) " · ${item.notes}" else ""}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { onFocusPoint(Vec3(item.vx, item.vy, item.vz)) }) {
                                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Focus counted angle")
                                    }
                                    IconButton(onClick = {
                                        editingLabelTarget = item.notes to { note -> onUpdateCountedNote(item, note) }
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit note")
                                    }
                                    IconButton(onClick = { onDeleteCountedAngle(item.id) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete counted angle")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingLabelTarget?.let { (initial, onSave) ->
        var text by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { editingLabelTarget = null },
            title = { Text("Edit Label / Notes") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Label") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(text.trim())
                    editingLabelTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingLabelTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EmptyMeasurementHint(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}
