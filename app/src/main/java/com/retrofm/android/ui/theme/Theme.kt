package com.retrofm.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
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

private val LightColorScheme = lightColorScheme(
    primary = BrandDarkBlue,
    secondary = BrandGray,
    tertiary = BrandGray,
    background = BrandLight,
    surface = BrandLight,
    onPrimary = BrandLight,
    onSecondary = BrandDarkBlue,
    onBackground = BrandDarkBlue,
    onSurface = BrandDarkBlue
)

@Composable
fun RetroFMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
