package com.pynapli.app.design

import androidx.compose.ui.graphics.Color

/**
 * Canonical Pynapli brand color tokens.
 *
 * Source of truth is the design handoff (`Pynapli-Handoff/components/primitives.jsx`), which
 * refines the original docs/DESIGN_SPEC.md neutrals toward slightly lifted, warmer-dark
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

    // Tertiary lifted from the handoff's #6B6660 (~3.4:1 on the #0B0B0E background, which fails
    // WCAG AA for normal text) to #8A847C (~5:1, passes AA) — a deliberate a11y deviation from the
    // handoff so the dimmest meta text stays legible. Same warm hue, one step lighter.
    val TextTertiary = Color(0xFF8A847C)
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
    val PhotoScrimStrong = Color(0xE6000000) // stronger bottom stop under the swipe-card footer text
    val ModalScrim = Color(0xCC000000) // full-screen dialog/overlay backdrop (e.g. the match overlay)

    // Translucent neutrals layered over photos (swipe/grid cards): chip fills/strokes, carousel
    // indicators, and the linked-partner badge fill/stroke. Named here so on-photo overlays stay
    // consistent and feature screens don't hardcode ARGB literals. On-photo *text/icons* use
    // [OnPhotoPrimary]/[OnPhotoSecondary]; these are the chrome behind them.
    val OnPhotoChipFill = Color(0x33FFFFFF)
    val OnPhotoChipStroke = Color(0x66FFFFFF)
    val OnPhotoStroke = Color(0xCCFFFFFF) // bright hairline: active carousel dot, badge ring
    val OnPhotoIndicatorInactive = Color(0x40FFFFFF)
    val OnPhotoBadgeFill = Color(0xCC1B1B1B) // dark translucent disc behind on-photo badges
    val OnPhotoHoverTint = Color(0x0FFFFFFF) // pointer hover brighten on photo tiles (web/desktop)

    // Inline banner/container tints: muted dark fills + readable borders for success/error message
    // bands (e.g. the Profile-hub message band) that must sit on the dark surface without using the
    // raw, retina-searing semantic red/green at full strength.
    val DangerContainer = Color(0xFF351317)
    val SuccessContainer = Color(0xFF172A1F)
    val SuccessBorder = Color(0xFF275C3B)

    // Linked-partner ring tones — a small categorical palette so a partner reads as a distinct
    // person on a card without competing with the brand accent. Mapped per LinkedPartnerTone
    // (GridCard); the Sun tone reuses [Accent].
    val PartnerViolet = Color(0xFF8B5CF6)
    val PartnerGold = Color(0xFFC39A49)
    val PartnerPink = Color(0xFFE85D75)
    val PartnerTeal = Color(0xFF4DB6AC)

    // Decorative warm brown: the "Visiting" travel pill backdrop (Grid + Swipe) and warm avatar
    // gradients — a quiet "context, not a tier" tone rather than a loud badge color.
    val WarmBrown = Color(0xFF4E342E)

    // Warm-dark placeholder fill/gradient for avatars and private-album tiles before media loads.
    val WarmPlaceholderDark = Color(0xFF2B2622)
    val WarmPlaceholderLight = Color(0xFF6D5D4F)

    // Playing Tonight privacy-first zone map: a warm near-opaque scrim behind the map error toast.
    val TonightMapScrim = Color(0xF2201216)

    // Offline presence — a warm muted grey (the dimmed counterpart to the gold accent / green online
    // dot). Replaces the cool slate/grey literals that clashed with the warm palette on the Tonight
    // map dots + halos; aliased as [PynapliSemantic.Offline].
    val Offline = Color(0xFF8A8580)

    // "Met in person" badge (members confirmed meeting them; not checked by Pynapli). A muted sage so
    // it never reads as the blue verified tick or the gold premium accent.
    val MetInPerson = Color(0xFF8FBF9F)
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
 * rather than a raw palette name. Mirrors docs/DESIGN_SPEC.md.
 */
object PynapliSemantic {
    val Online = PynapliColors.Success
    val Offline = PynapliColors.Offline
    val Verified = PynapliColors.Info
    val Premium = PynapliColors.Accent
    val Risk = PynapliColors.Warning
    val Error = PynapliColors.Danger
}
