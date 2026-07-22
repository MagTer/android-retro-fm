package com.retrofm.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Single brand scheme regardless of the system light/dark setting: the station identity is
// dark navy, and following the system (e.g. a car UI in day mode) produced a white screen
// that matches neither the brand nor the artwork.
private val BrandColorScheme = darkColorScheme(
    primary = BrandLight,
    secondary = BrandGray,
    tertiary = BrandGray,
    background = BrandDarkBlue,
    surface = BrandDarkBlue,
    onPrimary = BrandDarkBlue,
    onSecondary = BrandLight,
    onBackground = BrandLight,
    onSurface = BrandLight
)

@Composable
fun RetroFMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BrandColorScheme,
        typography = Typography,
        content = content
    )
}
