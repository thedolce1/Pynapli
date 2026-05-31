package com.pynapli.app.design

import androidx.compose.ui.graphics.Color

/**
 * Canonical Pynapli brand color tokens, mirroring docs/DESIGN_TOKENS.md.
 *
 * Dark-first by design: these are the product's default surface/text/accent values. Feature
 * screens and design primitives should consume these (or [PynapliSemantic]) instead of hardcoding
 * hex literals. Theme-aware widgets may still read `MaterialTheme.colorScheme`; these tokens cover
 * the brand-fixed values the Material scheme does not express.
 */
object PynapliColors {
    val Black = Color(0xFF050505)
    val Surface = Color(0xFF101010)
    val SurfaceRaised = Color(0xFF1A1A1A)
    val SurfacePressed = Color(0xFF242424)

    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFB8B8B8)
    val TextTertiary = Color(0xFF7A7A7A)
    val TextInverse = Color(0xFF080808)

    val Accent = Color(0xFFFFC928) // restrained pineapple gold
    val AccentDark = Color(0xFFB88700)
    val Success = Color(0xFF18D77A) // online dot
    val Danger = Color(0xFFFF4D5E)
    val Warning = Color(0xFFFFA23A)
    val Info = Color(0xFF4DA3FF)

    val Border = Color(0xFF2A2A2A)
    val OverlayScrim = Color(0x99000000)
    val PhotoGradientBottom = Color(0xCC000000)
}

/**
 * Semantic aliases over [PynapliColors] so call sites express intent (online/verified/premium)
 * rather than a raw palette name. Mirrors docs/DESIGN_TOKENS.md.
 */
object PynapliSemantic {
    val Online = PynapliColors.Success
    val Verified = PynapliColors.Info
    val Premium = PynapliColors.Accent
    val Risk = PynapliColors.Warning
    val Error = PynapliColors.Danger
}
