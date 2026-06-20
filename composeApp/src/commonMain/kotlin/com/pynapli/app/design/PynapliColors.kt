package com.pynapli.app.design

import androidx.compose.ui.graphics.Color

/**
 * Canonical Pynapli brand color tokens.
 *
 * Source of truth is the design handoff (`Pynapli-Handoff/components/primitives.jsx`), which
 * refines the original docs/DESIGN_TOKENS.md neutrals toward slightly lifted, warmer-dark
 * surfaces. Dark-first by design: these are the product's default surface/text/accent values.
 * Feature screens and design primitives should consume these (or [PynapliSemantic]) instead of
 * hardcoding hex literals. Theme-aware widgets may still read `MaterialTheme.colorScheme`; these
 * tokens cover the brand-fixed values the Material scheme does not express.
 */
object PynapliColors {
    // Surfaces (handoff: bg #0B0B0E → pressed #1E1E26).
    val Black = Color(0xFF0B0B0E) // app background ("bg")
    val Surface = Color(0xFF121216)
    val SurfaceRaised = Color(0xFF16161C)
    val SurfacePressed = Color(0xFF1E1E26)

    // Text (handoff: warm off-white scale).
    val TextPrimary = Color(0xFFF5F2EC)
    val TextSecondary = Color(0xFFA8A29A)
    val TextTertiary = Color(0xFF6B6660)
    val TextInverse = Color(0xFF0A0A0C) // dark glyph on the gold accent

    // Text laid over photos (swipe card, profile hero, FOMO tiles). Slightly dimmed from
    // [TextPrimary] so it doesn't glare against bright images; one warm pair instead of the
    // per-surface near-whites (#EDEDED/#E5E5E5/#E6E1D8 …) that previously drifted apart.
    val OnPhotoPrimary = Color(0xFFEAE6DE)
    val OnPhotoSecondary = Color(0xFFCCC7BE)

    val Accent = Color(0xFFFFC928) // restrained pineapple gold (founder-locked)
    val AccentDark = Color(0xFFB88700)
    val Success = Color(0xFF18D77A) // online dot
    val Danger = Color(0xFFFF4D5E)
    val Warning = Color(0xFFFFA23A)
    val Info = Color(0xFF4DA3FF)

    val Border = Color(0xFF23232C)
    val BorderSoft = Color(0xFF1B1B22) // hairline dividers / sticky-bar underlines
    val OverlayScrim = Color(0x99000000)
    val OverlayScrimSoft = Color(0x55000000) // mid stop for photo legibility gradients
    val PhotoGradientBottom = Color(0xCC000000)
}

/**
 * Light-mode surface set. The dark [PynapliColors] palette is the handoff-canonical default
 * (Pynapli is dark-first); this is the secondary light variant, named here so the Material light
 * `ColorScheme` derives from tokens instead of inline literals. Brand-fixed semantics (accent,
 * online, danger…) come from [PynapliColors]/[PynapliSemantic] in both themes.
 */
object PynapliColorsLight {
    val Primary = Color(0xFF6E4B00)
    val OnPrimary = Color(0xFFFFFFFF)
    val Background = Color(0xFFF8F6F1)
    val OnBackground = Color(0xFF171510)
    val Surface = Color(0xFFFFFFFF)
    val OnSurface = Color(0xFF171510)
    val SurfaceVariant = Color(0xFFEAE4D8)
    val OnSurfaceVariant = Color(0xFF5C564D)
    val Outline = Color(0xFFD1C8B8)
    val OnError = Color(0xFFFFFFFF)
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
