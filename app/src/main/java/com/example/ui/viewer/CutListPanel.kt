package com.example.ui.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.export.CutListPdfExportResult
import com.example.domain.bom.CutListEntry
import com.example.domain.model.CadTool
import com.example.domain.model.UnitSystem
import com.example.ui.CutListUiState
import com.example.ui.theme.CadNumericStyle
import kotlin.math.roundToInt

private val CutHighlightOrange = Color(0xFFF97316)

@Composable
fun CutListPanel(
    cutListState: CutListUiState,
    unitSystem: UnitSystem,
    precisionDecimals: Int,
    activeTool: CadTool,
    showCloseButton: Boolean,
    showViewportModeStrip: Boolean,
    onSelectRow: (String) -> Unit,
    onExplodeChange: (Float) -> Unit,
    onOpenUnitDialog: () -> Unit,
    onSetTool: (CadTool) -> Unit,
    onExportPdf: () -> Unit,
    onSharePdf: (CutListPdfExportResult) -> Unit,
    onDismissPdfResult: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(cutListState.selectedGroupId, cutListState.entries) {
        val selectedId = cutListState.selectedGroupId ?: return@LaunchedEffect
        val targetIndex = cutListState.entries.indexOfFirst { it.groupId == selectedId }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("cutlist_panel")
    ) {
        // Header: Title + Count + Unit toggle + Export PDF + Optional Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cut List",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${cutListState.entries.size} types · ${cutListState.totalPieces} pcs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onOpenUnitDialog,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 1.dp, minHeight = 36.dp)
                    .heightIn(min = 40.dp)
                    .testTag("cutlist_unit_button")
            ) {
                Text(unitSystem.symbol, style = CadNumericStyle, maxLines = 1)
            }

            Button(
                onClick = onExportPdf,
                enabled = !cutListState.isExportingPdf && !cutListState.isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CutHighlightOrange,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 1.dp, minHeight = 36.dp)
                    .heightIn(min = 40.dp)
                    .testTag("cutlist_export_pdf_button")
            ) {
                if (cutListState.isExportingPdf) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (cutListState.isExportingPdf) "Saving…" else "Export PDF",
                    maxLines = 1,
                    softWrap = false
                )
            }

            if (showCloseButton) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("cutlist_close_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close Cut List")
                }
            }
        }

        // Quick Select/Orbit/Pan strip when docked over bottom bar on phones
        if (showViewportModeStrip) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "3D:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = activeTool == CadTool.SELECT,
                    onClick = { onSetTool(CadTool.SELECT) },
                    leadingIcon = { Icon(Icons.Default.AdsClick, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    label = { Text("Tap", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.heightIn(min = 32.dp).testTag("cutlist_mode_select")
                )
                FilterChip(
                    selected = activeTool == CadTool.ORBIT,
                    onClick = { onSetTool(CadTool.ORBIT) },
                    leadingIcon = { Icon(Icons.Default._3dRotation, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    label = { Text("Orbit", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.heightIn(min = 32.dp).testTag("cutlist_mode_orbit")
                )
                FilterChip(
                    selected = activeTool == CadTool.PAN,
                    onClick = { onSetTool(CadTool.PAN) },
                    leadingIcon = { Icon(Icons.Default.PanTool, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    label = { Text("Pan", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.heightIn(min = 32.dp).testTag("cutlist_mode_pan")
                )
            }
        }

        // Export Confirmation Banner (Downloads + Share Sheet)
        AnimatedVisibility(visible = cutListState.lastExportedPdf != null) {
            cutListState.lastExportedPdf?.let { exported ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, CutHighlightOrange.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("cutlist_pdf_ready_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Saved: ${exported.savedLocationLabel}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { onSharePdf(exported) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("cutlist_share_pdf_button")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share")
                        }
                        IconButton(
                            onClick = onDismissPdfResult,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss PDF notice", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        cutListState.errorMessage?.let { err ->
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Compact single-row Explode Slider Bar (0% to 100%)
        val explodePct = (cutListState.explodeFraction * 100f).roundToInt()
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = null,
                    tint = if (explodePct > 0) CutHighlightOrange else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Explode",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = cutListState.explodeFraction,
                    onValueChange = onExplodeChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = CutHighlightOrange,
                        activeTrackColor = CutHighlightOrange
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cutlist_explode_slider")
                )
                if (explodePct > 0) {
                    TextButton(
                        onClick = { onExplodeChange(0f) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 1.dp, minHeight = 28.dp)
                            .testTag("cutlist_explode_reset")
                    ) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (explodePct > 0) {
                        CutHighlightOrange.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ) {
                    Text(
                        text = "$explodePct%",
                        style = CadNumericStyle,
                        fontWeight = FontWeight.Bold,
                        color = if (explodePct > 0) CutHighlightOrange else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                            .testTag("cutlist_explode_percent")
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Sticky Table Column Header
        Surface(
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Part",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.36f)
                )
                Text(
                    text = "Dimensions (L × W × H)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.49f)
                )
                Text(
                    text = "Qty",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.15f)
                )
            }
        }
        HorizontalDivider()

        when {
            cutListState.isLoading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = CutHighlightOrange)
                        Text(
                            text = "Analyzing 3D part geometry…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            cutListState.entries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "No separable 3D components or meshes found in this file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("cutlist_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    items(cutListState.entries, key = { it.groupId }) { entry ->
                        CutListRowItem(
                            entry = entry,
                            unitSystem = unitSystem,
                            precisionDecimals = precisionDecimals,
                            isSelected = cutListState.selectedGroupId == entry.groupId,
                            onClick = { onSelectRow(entry.groupId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CutListRowItem(
    entry: CutListEntry,
    unitSystem: UnitSystem,
    precisionDecimals: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val swatchColor = Color(
        red = entry.primaryColor.r.coerceIn(0f, 1f),
        green = entry.primaryColor.g.coerceIn(0f, 1f),
        blue = entry.primaryColor.b.coerceIn(0f, 1f),
        alpha = 1f
    )
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) {
            CutHighlightOrange.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = if (isSelected) {
            BorderStroke(1.5.dp, CutHighlightOrange)
        } else {
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { selected = isSelected }
            .testTag("cutlist_row_${entry.groupId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(0.36f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                )
                Column {
                    Text(
                        text = entry.partName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "#${entry.partNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) CutHighlightOrange else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = entry.formatDimensions(unitSystem, precisionDecimals),
                style = CadNumericStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(0.49f)
                    .padding(horizontal = 4.dp)
            )

            Box(
                modifier = Modifier.weight(0.15f),
                contentAlignment = Alignment.CenterEnd
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) CutHighlightOrange else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "×${entry.quantity}",
                        style = CadNumericStyle,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

fun launchPdfShareSheet(context: Context, exported: CutListPdfExportResult) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, exported.shareUri)
        putExtra(Intent.EXTRA_SUBJECT, exported.fileName)
        clipData = ClipData.newRawUri("Cut List PDF", exported.shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "Share Cut List PDF").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
}
