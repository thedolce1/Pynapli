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

// Complete, deliberate Pynapli type scale. Every Material role is defined here (not just body/label)
// so titles/headlines/displays stop inheriting raw Material3 defaults — a compact, dark-first rhythm
// with one font family routed through a single place, ready for a brand-font swap later.
private val PynapliFont = FontFamily.SansSerif

private val PynapliTypography =
    Typography(
        // Display — hero moments (onboarding, the photo-less swipe initial).
        displayLarge =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 50.sp),
        displayMedium =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp),
        displaySmall =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
        // Headline — prominent on-surface/on-photo emphasis (match overlay, swipe name).
        headlineLarge =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineMedium =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        // Title — screen headers and section headers.
        titleLarge =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
        // Body — running text.
        bodyLarge =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 19.sp),
        bodySmall =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
        // Label — chips, captions, buttons.
        labelLarge =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
        labelMedium =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 15.sp),
        labelSmall =
            TextStyle(fontFamily = PynapliFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp),
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
