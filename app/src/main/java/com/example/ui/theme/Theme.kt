package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DolbyDarkColorScheme = darkColorScheme(
    primary = DolbyAccentLightBlue,
    onPrimary = DolbyObsidianBg,
    primaryContainer = DolbySliderDeepBlue,
    onPrimaryContainer = DolbyTextPrimary,
    secondary = DolbySectionHeaderBlue,
    onSecondary = DolbyObsidianBg,
    tertiary = DolbyAuraCyan,
    background = DolbyObsidianBg,
    onBackground = DolbyTextPrimary,
    surface = DolbyObsidianBg,
    onSurface = DolbyTextPrimary,
    surfaceVariant = DolbySurfaceElevated,
    onSurfaceVariant = DolbyTextSecondary,
    outline = DolbyDivider
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DolbyDarkColorScheme,
        typography = Typography,
        content = content
    )
}
