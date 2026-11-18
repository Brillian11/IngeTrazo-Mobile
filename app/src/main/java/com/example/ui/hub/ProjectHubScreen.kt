package com.example.ui.hub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.local.RecentFileEntity
import com.example.data.local.UserPreferences
import com.example.data.repository.SampleModelInfo
import com.example.domain.model.UnitSystem
import com.example.ui.DocumentLoadState
import com.example.ui.components.UnitSettingsDialog
import com.example.ui.theme.CadNumericStyle
import java.text.DateFormat
import java.util.Date

@Composable
fun ProjectHubScreen(
    samples: List<SampleModelInfo>,
    recentFiles: List<RecentFileEntity>,
    preferences: UserPreferences,
    loadState: DocumentLoadState,
    onOpenFilePicker: () -> Unit,
    onOpenSample: (SampleModelInfo) -> Unit,
    onOpenRecent: (RecentFileEntity) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onChangeUnits: (UnitSystem) -> Unit = {},
    onChangeDecimals: (Int) -> Unit = {},
    onDismissError: () -> Unit
) {
    var showUnitsDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                val step = 32.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(gridLineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(gridLineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
            }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .widthIn(max = 920.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Architecture,
                                contentDescription = "IngeTrazo Mobile",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "IngeTrazo Mobile",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.testTag("hub_overflow_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (preferences.darkCadMode) "Dark Theme" else "Light Theme")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (preferences.darkCadMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                        contentDescription = null
                                    )
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = preferences.darkCadMode,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = {
                                    onToggleTheme(!preferences.darkCadMode)
                                    showOverflowMenu = false
                                },
                                modifier = Modifier.testTag("hub_theme_toggle")
                            )
                            DropdownMenuItem(
                                text = { Text("Unit Preferences") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Straighten,
                                        contentDescription = null
                                    )
                                },
                                trailingIcon = {
                                    Text(
                                        text = "${preferences.unitSystem.symbol} · ${preferences.precisionDecimals} dec",
                                        style = CadNumericStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showUnitsDialog = true
                                },
                                modifier = Modifier.testTag("hub_menu_units")
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("About the App") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showAboutDialog = true
                                },
                                modifier = Modifier.testTag("hub_menu_about")
                            )
                        }
                    }
                }
            }

            // Hero Open File Action Card
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ViewInAr,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Open Project or 3D Model",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onOpenFilePicker,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("open_file_button")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Browse Device Files")
                        }
                    }
                }
            }

            // Sample IngeTrazo Repository Projects
            item {
                Column {
                    Text(
                        text = "Official IngeTrazo Sample Models",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(samples, key = { it.assetPath }) { sample ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .width(240.dp)
                                    .testTag("sample_card_${sample.assetPath.substringAfterLast('/')}")
                                    .clickable { onOpenSample(sample) }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = sample.formatTag,
                                            style = CadNumericStyle,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = sample.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = sample.subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        minLines = 2,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Recent Files Section
            item {
                Text(
                    text = "Recent Projects",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (recentFiles.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "No recent files yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Tap any official IngeTrazo sample above or browse your device for .igz, .skp, .obj, or .gltf/.glb models.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(recentFiles, key = { it.documentKey }) { recent ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recent_file_${recent.displayName}")
                            .clickable { onOpenRecent(recent) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = recent.formatName,
                                    style = CadNumericStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = recent.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${recent.faceCount} faces · ${recent.edgeCount} edges · ${formatDate(recent.lastOpenedAt)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onRemoveRecent(recent.documentKey) }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Remove from recent"
                                )
                            }
                        }
                    }
                }
            }
        }

        if (loadState is DocumentLoadState.Loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(loadState.title, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (loadState is DocumentLoadState.Error) {
            AlertDialog(
                onDismissRequest = onDismissError,
                title = { Text(loadState.title) },
                text = { Text(loadState.details ?: "Could not parse the selected 3D model file.") },
                confirmButton = {
                    TextButton(onClick = onDismissError) { Text("OK") }
                }
            )
        }

        if (showUnitsDialog) {
            UnitSettingsDialog(
                currentUnit = preferences.unitSystem,
                currentDecimals = preferences.precisionDecimals,
                onSelectUnit = onChangeUnits,
                onSelectDecimals = onChangeDecimals,
                onDismiss = { showUnitsDialog = false }
            )
        }

        if (showAboutDialog) {
            val uriHandler = LocalUriHandler.current
            val appVersion = BuildConfig.VERSION_NAME
            val creatorUrl = "https://github.com/Brillian11"
            val ingeTrazoUrl = "https://github.com/ingelibre/ingetrazo"

            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Architecture,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("About IngeTrazo Mobile")
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.testTag("about_version_badge")
                        ) {
                            Text(
                                text = "Version: $appVersion",
                                style = CadNumericStyle,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Mobile 3D CAD viewer, automatic 3D Cut List (BOM) & PDF generator, tape & protractor measurement suite, angle counter, and AR/VR projection.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Creator Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { runCatching { uriHandler.openUri(creatorUrl) } }
                                .testTag("about_creator_link")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Created by Brillian11",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = creatorUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open Creator GitHub",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Special Thanks Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { runCatching { uriHandler.openUri(ingeTrazoUrl) } }
                                .testTag("about_ingetrazo_link")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Special Thanks: IngeTrazo Project",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = ingeTrazoUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open IngeTrazo Project GitHub",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Text(
                            text = "Supported formats: IngeTrazo (.igz v1–v3), SketchUp (.skp VFF 2021+), Wavefront (.obj), and glTF 2.0 (.gltf/.glb). Format specifications and bundled samples originate from ingelibre/ingetrazo (GPL-3.0).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showAboutDialog = false },
                        modifier = Modifier.testTag("hub_about_close")
                    ) {
                        Text("Close")
                    }
                },
                modifier = Modifier.testTag("hub_about_dialog")
            )
        }
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
