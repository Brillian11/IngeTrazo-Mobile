package com.example.ui.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    onLongPressRow: (String) -> Unit = {},
    onToggleCheck: (String) -> Unit = {},
    onToggleSelectAll: () -> Unit = {},
    onExitSelectionMode: () -> Unit = {},
    onExplodeChange: (Float) -> Unit,
    onOpenUnitDialog: () -> Unit,
    onSetTool: (CadTool) -> Unit,
    onExportPdf: () -> Unit,
    onSharePdf: (CutListPdfExportResult) -> Unit = {},
    onDismissPdfResult: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val checkedCount = cutListState.checkedGroupIds.size
    val checkedPieces = remember(cutListState.entries, cutListState.checkedGroupIds) {
        cutListState.entries
            .filter { it.groupId in cutListState.checkedGroupIds }
            .sumOf { it.quantity }
    }
    val allChecked = cutListState.entries.isNotEmpty() && checkedCount == cutListState.entries.size

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
        // Header: Title + Selection status + Export PDF (only enabled when parts are selected) + Optional Close
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
                    text = if (checkedCount > 0) {
                        "$checkedCount of ${cutListState.entries.size} selected · $checkedPieces pcs"
                    } else {
                        "Select parts to export · ${cutListState.entries.size} types"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checkedCount > 0) {
                        CutHighlightOrange
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val canExport = !cutListState.isExportingPdf &&
                !cutListState.isLoading &&
                checkedCount > 0

            Button(
                onClick = onExportPdf,
                enabled = canExport,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CutHighlightOrange,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
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
                    text = when {
                        cutListState.isExportingPdf -> "Saving…"
                        checkedCount > 0 -> "Export ($checkedCount)"
                        else -> "Export PDF"
                    },
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

        // Always-available Selection Controls Bar (only selected items can be exported)
        if (cutListState.entries.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (checkedCount > 0) {
                    CutHighlightOrange.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                border = BorderStroke(
                    1.dp,
                    if (checkedCount > 0) CutHighlightOrange.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .testTag("cutlist_selection_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckBox,
                        contentDescription = null,
                        tint = if (checkedCount > 0) CutHighlightOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (checkedCount > 0) {
                            "$checkedCount selected for PDF"
                        } else {
                            "Check parts to export"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (checkedCount > 0) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (checkedCount > 0) CutHighlightOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onToggleSelectAll,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                            .testTag("cutlist_select_all_button")
                    ) {
                        Text(
                            text = if (allChecked) "Clear All" else "Select All",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (checkedCount > 0) {
                        TextButton(
                            onClick = onExitSelectionMode,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                                .testTag("cutlist_exit_selection_button")
                        ) {
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
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

        // Error notice if extraction or export fails

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
                            isChecked = entry.groupId in cutListState.checkedGroupIds,
                            onClick = { onSelectRow(entry.groupId) },
                            onLongClick = { onLongPressRow(entry.groupId) },
                            onCheckedChange = { onToggleCheck(entry.groupId) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CutListRowItem(
    entry: CutListEntry,
    unitSystem: UnitSystem,
    precisionDecimals: Int,
    isSelected: Boolean,
    isChecked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val isActiveHighlight = isChecked || isSelected
    val swatchColor = Color(
        red = entry.primaryColor.r.coerceIn(0f, 1f),
        green = entry.primaryColor.g.coerceIn(0f, 1f),
        blue = entry.primaryColor.b.coerceIn(0f, 1f),
        alpha = 1f
    )
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isActiveHighlight) {
            CutHighlightOrange.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = if (isActiveHighlight) {
            BorderStroke(1.5.dp, CutHighlightOrange)
        } else {
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .semantics { selected = isActiveHighlight }
            .testTag("cutlist_row_${entry.groupId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onCheckedChange() },
                colors = CheckboxDefaults.colors(
                    checkedColor = CutHighlightOrange,
                    checkmarkColor = Color.White
                ),
                modifier = Modifier
                    .size(24.dp)
                    .testTag("cutlist_checkbox_${entry.groupId}")
            )
            Spacer(Modifier.width(8.dp))

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
                        fontWeight = if (isActiveHighlight) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "#${entry.partNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActiveHighlight) CutHighlightOrange else MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = if (isActiveHighlight) CutHighlightOrange else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "×${entry.quantity}",
                        style = CadNumericStyle,
                        fontWeight = FontWeight.Bold,
                        color = if (isActiveHighlight) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PdfExportToastBanner(
    exported: CutListPdfExportResult,
    onOpenPdf: (CutListPdfExportResult) -> Unit,
    onSharePdf: (CutListPdfExportResult) -> Unit,
    onDismiss: () -> Unit,
    autoDismissMillis: Int = 5000,
    modifier: Modifier = Modifier
) {
    val remainingProgress = remember(exported.fileName, exported.shareUri) { Animatable(1f) }

    LaunchedEffect(exported.fileName, exported.shareUri, autoDismissMillis) {
        remainingProgress.snapTo(1f)
        remainingProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = autoDismissMillis, easing = LinearEasing)
        )
        onDismiss()
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, CutHighlightOrange.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("cutlist_pdf_ready_banner")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = CutHighlightOrange,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PDF saved",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = exported.savedLocationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    onClick = { onOpenPdf(exported) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 1.dp, minHeight = 36.dp)
                        .heightIn(min = 38.dp)
                        .testTag("cutlist_open_pdf_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Open", maxLines = 1, softWrap = false)
                }
                IconButton(
                    onClick = { onSharePdf(exported) },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("cutlist_share_pdf_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share PDF",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("cutlist_dismiss_pdf_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss PDF notice",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            LinearProgressIndicator(
                progress = { remainingProgress.value.coerceIn(0f, 1f) },
                color = CutHighlightOrange,
                trackColor = CutHighlightOrange.copy(alpha = 0.16f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .testTag("cutlist_pdf_countdown_bar")
            )
        }
    }
}

fun launchOpenPdf(context: Context, exported: CutListPdfExportResult): Boolean {
    val targetUri = exported.shareUri
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(targetUri, "application/pdf")
        clipData = ClipData.newRawUri("Cut List PDF", targetUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        val chooser = Intent.createChooser(viewIntent, "Open Cut List PDF").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    } catch (_: Throwable) {
        false
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
