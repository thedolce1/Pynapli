package com.pynapli.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Both schemes derive from the token objects so PynapliColors is the single source of truth:
// editing a token updates the Material scheme (and every MaterialTheme.colorScheme call site)
// without re-hardcoding hexes. `onError` is set explicitly so destructive buttons render light
// text on the danger red rather than Material's default dark maroon.
private val PynapliDarkColors: ColorScheme =
    darkColorScheme(
        primary = PynapliColors.Accent,
        onPrimary = PynapliColors.TextInverse,
        background = PynapliColors.Black,
        onBackground = PynapliColors.TextPrimary,
        surface = PynapliColors.Surface,
        onSurface = PynapliColors.TextPrimary,
        surfaceVariant = PynapliColors.SurfaceRaised,
        onSurfaceVariant = PynapliColors.TextSecondary,
        outline = PynapliColors.Border,
        error = PynapliColors.Danger,
        onError = PynapliColors.TextPrimary,
    )

private val PynapliLightColors: ColorScheme =
    lightColorScheme(
        primary = PynapliColorsLight.Primary,
        onPrimary = PynapliColorsLight.OnPrimary,
        background = PynapliColorsLight.Background,
        onBackground = PynapliColorsLight.OnBackground,
        surface = PynapliColorsLight.Surface,
        onSurface = PynapliColorsLight.OnSurface,
        surfaceVariant = PynapliColorsLight.SurfaceVariant,
        onSurfaceVariant = PynapliColorsLight.OnSurfaceVariant,
        outline = PynapliColorsLight.Outline,
        error = PynapliColors.Danger,
        onError = PynapliColorsLight.OnError,
    )

private val PynapliTypography =
    Typography(
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                lineHeight = 22.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 19.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 15.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 13.sp,
            ),
    )

@Composable
fun PynapliTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            "light" -> false
            "system" -> isSystemInDarkTheme()
            else -> true
        }

    MaterialTheme(
        colorScheme = if (darkTheme) PynapliDarkColors else PynapliLightColors,
        typography = PynapliTypography,
        content = content,
    )
}
