package com.golftracker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = GolfNeonLime,
    secondary = GolfNeonCyan,
    tertiary = GolfNeonGold,
    background = GolfDarkBg,
    surface = GolfDarkSurface,
    surfaceContainer = GolfDarkCard,
    onPrimary = GolfDarkBg,
    onSecondary = GolfDarkBg,
    onBackground = GolfTextPrimary,
    onSurface = GolfTextPrimary
)

@Composable
fun GolfBallVisualTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
