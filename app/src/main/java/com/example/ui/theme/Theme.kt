package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightDraftingColorScheme = lightColorScheme(
    primary = DraftCyanPrimary,
    onPrimary = DraftSurface,
    primaryContainer = DraftCyanContainer,
    onPrimaryContainer = DraftOnCyanContainer,
    secondary = DraftSnapOrange,
    onSecondary = DraftSurface,
    secondaryContainer = DraftSnapOrangeContainer,
    onSecondaryContainer = DraftInkPrimary,
    tertiary = DraftEmerald,
    background = DraftCanvasBg,
    onBackground = DraftInkPrimary,
    surface = DraftSurface,
    onSurface = DraftInkPrimary,
    surfaceVariant = DraftSurfaceVariant,
    onSurfaceVariant = DraftInkSecondary,
    outline = DraftOutline,
    error = DraftError
)

private val DarkCadColorScheme = darkColorScheme(
    primary = DarkCadCyanPrimary,
    onPrimary = DarkCadBg,
    primaryContainer = DarkCadCyanContainer,
    onPrimaryContainer = DarkCadOnCyanContainer,
    secondary = DarkCadSnapAmber,
    onSecondary = DarkCadBg,
    secondaryContainer = DarkCadSnapContainer,
    onSecondaryContainer = DarkCadInkPrimary,
    tertiary = DarkCadEmerald,
    background = DarkCadBg,
    onBackground = DarkCadInkPrimary,
    surface = DarkCadSurface,
    onSurface = DarkCadInkPrimary,
    surfaceVariant = DarkCadSurfaceVariant,
    onSurfaceVariant = DarkCadInkSecondary,
    outline = DarkCadOutline,
    error = DarkCadError
)

@Composable
fun IngeTrazoTheme(
    darkCadMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkCadMode) DarkCadColorScheme else LightDraftingColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = IngeTrazoTypography,
        content = content
    )
}
