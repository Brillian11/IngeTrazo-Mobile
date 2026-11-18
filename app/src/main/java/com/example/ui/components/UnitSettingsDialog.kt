package com.example.ui.components

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.UnitSystem
import com.example.ui.theme.CadNumericStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitSettingsDialog(
    currentUnit: UnitSystem,
    currentDecimals: Int,
    onSelectUnit: (UnitSystem) -> Unit,
    onSelectDecimals: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Straighten,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Units & Precision") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Display Unit",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                UnitSystem.entries.forEach { unit ->
                    val selected = unit == currentUnit
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("unit_option_${unit.symbol}")
                            .clickable { onSelectUnit(unit) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelectUnit(unit) }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = unit.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = unit.formatMeters(1.0, currentDecimals),
                                style = CadNumericStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Decimal Precision",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(0, 1, 2, 3, 4).forEach { dec ->
                        val label = if (dec == 0) "0" else ".${"0".repeat(dec)}"
                        FilterChip(
                            selected = currentDecimals == dec,
                            onClick = { onSelectDecimals(dec) },
                            label = { Text(label, style = CadNumericStyle) },
                            modifier = Modifier.testTag("precision_option_$dec")
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Example (1.00 m): ${currentUnit.formatMeters(1.0, currentDecimals)}",
                        style = CadNumericStyle,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("unit_dialog_done")
            ) {
                Text("Done")
            }
        }
    )
}
