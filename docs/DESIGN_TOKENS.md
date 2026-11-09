# Pynapli Design Tokens

Last updated: 2026-04-24

## Purpose

This document defines the initial design tokens for Pynapli's Compose Multiplatform UI.

Core rule:

> Components consume tokens. Feature screens should not hardcode visual values.

Use these tokens to implement the visual direction defined in [FRONTEND_DESIGN_DIRECTION_SPEC.md](FRONTEND_DESIGN_DIRECTION_SPEC.md).

## Tone

Pynapli should feel:

- Dark-first
- Social
- Polished
- Dense
- Private
- Warm without being nightclub-like

Avoid:

- One-note purple/blue gradients
- Beige/brown lifestyle cliché palettes
- Decorative gradient blobs
- Cheesy pineapple visual overload

## Color Tokens

Dark palette (refined 2026-05-31 to match the design handoff `Pynapli-Handoff/components/primitives.jsx`: lifted, warmer-dark surfaces + warm off-white text; accent stays pineapple gold). This block is the source of truth and is mirrored in `composeApp/.../design/PynapliColors.kt`.

```kotlin
object PynapliColors {
    val Black = Color(0xFF0B0B0E)       // app background ("bg")
    val Surface = Color(0xFF121216)
    val SurfaceRaised = Color(0xFF16161C)
    val SurfacePressed = Color(0xFF1E1E26)

    val TextPrimary = Color(0xFFF5F2EC) // warm off-white
    val TextSecondary = Color(0xFFA8A29A)
    val TextTertiary = Color(0xFF6B6660)
    val TextInverse = Color(0xFF0A0A0C)

    val OnPhotoPrimary = Color(0xFFEAE6DE)   // warm near-white text over photos (swipe/hero/FOMO)
    val OnPhotoSecondary = Color(0xFFCCC7BE) // dimmer warm meta text over photos

    val Accent = Color(0xFFFFC928)      // restrained pineapple gold (founder-locked)
    val AccentDark = Color(0xFFB88700)
    val Success = Color(0xFF18D77A)     // online dot
    val Danger = Color(0xFFFF4D5E)
    val Warning = Color(0xFFFFA23A)
    val Info = Color(0xFF4DA3FF)

    val Border = Color(0xFF23232C)
    val BorderSoft = Color(0xFF1B1B22)  // hairline dividers / sticky-bar underlines
    val OverlayScrim = Color(0x99000000)
    val PhotoGradientBottom = Color(0xCC000000)

    // Inline success/error banner tints (muted dark fill + readable border on the dark surface).
    val DangerContainer = Color(0xFF351317)
    val SuccessContainer = Color(0xFF172A1F)
    val SuccessBorder = Color(0xFF275C3B)

    // Linked-partner ring tones (categorical; the Sun tone reuses Accent).
    val PartnerViolet = Color(0xFF8B5CF6)
    val PartnerGold = Color(0xFFC39A49)
    val PartnerPink = Color(0xFFE85D75)
    val PartnerTeal = Color(0xFF4DB6AC)

    // Decorative warm brown: the "Visiting" travel pill + warm avatar gradients.
    val WarmBrown = Color(0xFF4E342E)
}
```

Light mode can exist, but dark mode should be the default product feel. Light surface values are named in `PynapliColorsLight` (dark `PynapliColors` stays the handoff-canonical default).

**Single source of truth:** the Material `ColorScheme` (`PynapliTheme.kt`) is *derived from* these token objects — it must not re-hardcode hexes. Editing a token therefore updates every `MaterialTheme.colorScheme` call site. Feature code routes brand-fixed values through `PynapliColors`/`PynapliSemantic` and reads theme-driven values via `MaterialTheme.colorScheme`; do not introduce raw hex literals that duplicate a token value (e.g. the online green is `PynapliColors.Success`, never an inline `0xFF…`).

## Semantic Tokens

```kotlin
object PynapliSemantic {
    val Online = PynapliColors.Success
    val Verified = PynapliColors.Info
    val Premium = PynapliColors.Accent
    val Risk = PynapliColors.Warning
    val Error = PynapliColors.Danger
}
```

## Typography

Recommended font:

- Inter, or platform-native fallback if bundled font is delayed.

Rules:

- No negative letter spacing.
- No viewport-scaled type.
- Use `sp`.
- Keep grid text compact and high-contrast.

Initial scale:

```kotlin
object PynapliType {
    val Display = 32.sp
    val Title = 22.sp
    val Section = 18.sp
    val Body = 16.sp
    val BodySmall = 14.sp
    val Label = 13.sp
    val Caption = 12.sp
    val Micro = 11.sp
}
```

## Spacing

Spacing is deliberately **not** on a fixed grid (settled 2026-07-23): the design handoff hand-tunes gaps, and the app matches it (8dp and 12dp dominate). Use the shared values below where a common rhythm genuinely applies, hand-tune inside components, and don't snap existing screens to a scale.

```kotlin
object PynapliSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}
```

Layout:

```kotlin
object PynapliLayout {
    val ScreenPadding = 16.dp
    val GridGap = 2.dp
    val HeaderHeight = 64.dp
    val BottomNavHeight = 64.dp
    val MinTouchTarget = 48.dp
}
```

## Shape

```kotlin
object PynapliShape {
    val None = 0.dp
    val Small = 4.dp
    val Card = 8.dp
    val Sheet = 16.dp
    val Pill = 100.dp
}
```

Rules:

- Repeated cards max 8dp.
- Grid tiles should feel tight and photo-first.
- Sheets can have larger top corners.
- Icon buttons should be square/circular with stable size.

## Grid Tile Tokens

```kotlin
object GridTileTokens {
    val AspectRatio = 1f
    val Gap = 2.dp
    val NameTextSize = 15.sp
    val MetaTextSize = 12.sp
    val OnlineDotSize = 10.dp
    val PartnerIconSize = 22.dp
    val BottomGradientHeight = 44.dp
}
```

Grid tile visible data:

- Photo
- Name
- Online dot
- Distance
- Linked partner mini icon

No chips/tags.

## Swipe Card Tokens

```kotlin
object SwipeCardTokens {
    val Radius = 8.dp
    val PhotoAspectRatio = 0.78f
    val StackScaleStep = 0.035f
    val DragThresholdFraction = 0.28f
    val RotationMaxDegrees = 12f
    val ActionButtonSize = 56.dp
}
```

## Motion Tokens

```kotlin
object PynapliMotion {
    val Fast = 120
    val Standard = 220
    val Slow = 360
    val GridPlacement = 220
    val SwipeFlyOut = 240
    val SwipeSpringBack = 260
}
```

Rules:

- No tab-switch animation.
- Avoid continuous decorative motion.
- Playing Tonight pulse must be subtle.
- Respect reduce-motion settings if available.

## Icons

Use lucide-style icons where available.

Recommended icons:

- Grid: layout-grid
- Swipe: cards / flame-like only if tasteful
- Activity: bell or activity
- Messages: message-square
- Store: badge / gem / crown, but avoid gaudy VIP feel
- Settings: sliders
- Partner link: link
- Album: image-lock
- Incognito: eye-off
- Boost: zap

## Component Sizing

Buttons:

- Primary height: 48dp
- Secondary height: 44dp
- Icon button: 44-48dp

Filter pills:

- Height: 40dp
- Horizontal padding: 14dp

Bottom nav:

- Stable 5 items
- Badge max text: `99+`

## Accessibility Tokens

Requirements:

- Touch target: 48dp minimum.
- Contrast: WCAG AA minimum.
- Text wraps or truncates intentionally.
- Dynamic font sizes tested.
- Content descriptions for meaningful icons.

## Do Not Implement

- Do not hardcode hex values in feature screens.
- Do not use negative letter spacing.
- Do not use giant rounded cards everywhere.
- Do not decorate with gradient orbs/blobs.
- Do not make pineapple gold dominate the whole UI.
- Do not put text into tiny controls that cannot scale.
