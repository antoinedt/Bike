package com.medialibrary.manager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark-only Cinema theme, matching the desktop app. */
private val DarkColors = darkColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1A18),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = AccentBlue,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = BackgroundDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextDim,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceVariantDark,
    outline = Color(0xFF2E3445),
    error = DangerRed
)

@Composable
fun MediaLibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
