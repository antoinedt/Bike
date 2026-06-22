package com.bike.trainer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BikeColors = darkColorScheme(
    primary = BikeOrange,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = BikeOrangeDark,
    secondary = BikeGreen,
    background = BikeBackground,
    onBackground = BikeOnSurface,
    surface = BikeSurface,
    onSurface = BikeOnSurface,
    surfaceVariant = BikeSurfaceVariant,
    onSurfaceVariant = BikeMuted,
    error = BikeRed,
)

@Composable
fun BikeTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = BikeColors,
        typography = Typography(),
        content = content,
    )
}
