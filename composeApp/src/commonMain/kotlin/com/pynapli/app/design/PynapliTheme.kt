package com.pynapli.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val PynapliDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFC928),
    onPrimary = Color(0xFF080808),
    background = Color(0xFF050505),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF2A2A2A),
    error = Color(0xFFFF4D5E)
)

private val PynapliLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF6E4B00),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F6F1),
    onBackground = Color(0xFF171510),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171510),
    surfaceVariant = Color(0xFFEAE4D8),
    onSurfaceVariant = Color(0xFF5C564D),
    outline = Color(0xFFD1C8B8),
    error = Color(0xFFB00020)
)

private val PynapliTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp
    )
)

@Composable
fun PynapliTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) PynapliDarkColors else PynapliLightColors,
        typography = PynapliTypography,
        content = content
    )
}
