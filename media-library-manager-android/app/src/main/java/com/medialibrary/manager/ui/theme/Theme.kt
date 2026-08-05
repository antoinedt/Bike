package com.medialibrary.manager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Dark-only for now, mirroring the desktop app's default theme. */
private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceDark,
    error = DangerRed
)

@Composable
fun MediaLibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
