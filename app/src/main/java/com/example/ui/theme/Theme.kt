package com.example.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
    onTertiary = DraftSurface,
    tertiaryContainer = DraftEmeraldContainer,
    onTertiaryContainer = DraftOnEmeraldContainer,
    background = DraftCanvasBg,
    onBackground = DraftInkPrimary,
    surface = DraftSurface,
    onSurface = DraftInkPrimary,
    surfaceVariant = DraftSurfaceVariant,
    onSurfaceVariant = DraftInkSecondary,
    surfaceContainerLowest = DraftSurface,
    surfaceContainerLow = DraftSurfaceContainerLow,
    surfaceContainer = DraftSurfaceContainer,
    surfaceContainerHigh = DraftSurfaceContainerHigh,
    surfaceContainerHighest = DraftSurfaceContainerHighest,
    outline = DraftOutline,
    outlineVariant = DraftSurfaceVariant,
    error = DraftError,
    onError = DraftSurface,
    errorContainer = DraftErrorContainer,
    onErrorContainer = DraftOnErrorContainer
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
    onTertiary = DarkCadBg,
    tertiaryContainer = DarkCadEmeraldContainer,
    onTertiaryContainer = DarkCadOnEmeraldContainer,
    background = DarkCadBg,
    onBackground = DarkCadInkPrimary,
    surface = DarkCadSurface,
    onSurface = DarkCadInkPrimary,
    surfaceVariant = DarkCadSurfaceVariant,
    onSurfaceVariant = DarkCadInkSecondary,
    surfaceContainerLowest = DarkCadSurfaceContainerLowest,
    surfaceContainerLow = DarkCadSurfaceContainerLow,
    surfaceContainer = DarkCadSurfaceContainer,
    surfaceContainerHigh = DarkCadSurfaceContainerHigh,
    surfaceContainerHighest = DarkCadSurfaceContainerHighest,
    outline = DarkCadOutline,
    outlineVariant = DarkCadSurfaceVariant,
    error = DarkCadError,
    onError = DarkCadBg,
    errorContainer = DarkCadErrorContainer,
    onErrorContainer = DarkCadOnErrorContainer
)

@Composable
fun IngeTrazoTheme(
    darkCadMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkCadMode) DarkCadColorScheme else LightDraftingColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = IngeTrazoTypography
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
            content()
        }
    }
}
