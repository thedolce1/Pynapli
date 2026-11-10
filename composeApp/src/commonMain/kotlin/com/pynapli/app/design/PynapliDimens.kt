package com.pynapli.app.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 8dp-based spacing scale (docs/DESIGN_TOKENS.md). */
object PynapliSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/** Layout constants shared across screens (docs/DESIGN_TOKENS.md). */
object PynapliLayout {
    val ScreenPadding = 16.dp
    val GridGap = 2.dp
    val HeaderHeight = 64.dp
    val BottomNavHeight = 64.dp
    val MinTouchTarget = 48.dp
}

/**
 * Upper bounds that keep single-column surfaces (auth, onboarding, store, activity, profile hub,
 * media albums, conversations) reading as an intentional centered column on wide windows instead
 * of a phone layout stretched edge-to-edge. Consumed via `PynapliMaxWidthColumn` /
 * `PynapliMaxWidthContainer` (design/PynapliWindowSize.kt) as `widthIn(max = …)`, so on phones —
 * where the window is narrower than the bound — they are no-ops and the dense Compact layout is
 * byte-for-byte unchanged. The Grid stays deliberately edge-to-edge (density is its identity) and
 * Swipe carries its own centered deck stage, so those are excluded on purpose.
 */
object PynapliContentWidth {
    val Form = 440.dp // auth + onboarding + narrow single-purpose forms
    val Stage = 520.dp // a centered card deck (Swipe, Psync); a stretched hero card is the stretched-phone tell
    val Conversation = 720.dp // a readable chat column (bubbles + composer) in a wide Messages pane
    val Feed = 760.dp // single-column card lists (Store plans, profile hub, media albums)
    val WideFeed = 900.dp // multi-column card grids (Activity FOMO)
}

/**
 * Screen-header tokens. Every top-of-screen header (Grid, Swipe, Activity, Messages, Store)
 * is built from the `PynapliScreenHeader` scaffold (design/PynapliScreenHeader.kt) so insets,
 * padding, avatar size, and action-button treatment match across surfaces. One canonical frame;
 * each surface fills the leading/center/action slots with its own content.
 */
object PynapliHeaderTokens {
    val HorizontalPadding = 16.dp // aligns header edges with PynapliLayout.ScreenPadding
    val VerticalPadding = 10.dp
    val ItemSpacing = 12.dp
    val AvatarSize = 42.dp
    val ActionButtonSize = 44.dp
    val ActionIconSize = 20.dp
}

/**
 * Corner radii, mirroring the handoff's radius vocabulary (`Pynapli-Handoff/components`).
 *
 * The handoff uses four values with distinct jobs, and the distinction between [Photo] and [Card]
 * is the one that matters: its `Photo` primitive defaults to 8, while cards and banners are styled
 * at 12 (its single most common radius). Collapsing those two into one 8dp "card" token is what let
 * every bordered container in the app drift a step tighter than the design language, so they are
 * separate tokens here. Pick by the element's *role*, not by what looks close:
 *
 * - [Small] — grid tiles and tiny indicators (pips, progress ticks).
 * - [Photo] — photo and thumbnail corners: album tiles, avatars, media previews.
 * - [Card] — cards, banners, message bands, list rows, inputs. The default for a bordered container.
 * - [Sheet] — bottom sheets and the wide-window dialog cards they become.
 * - [Pill] — fully rounded chips and buttons.
 */
object PynapliShape {
    val None = 0.dp
    val Small = 4.dp
    val Photo = 8.dp
    val Card = 12.dp
    val Sheet = 16.dp
    val Pill = 100.dp

    val SmallShape = RoundedCornerShape(Small)
    val PhotoShape = RoundedCornerShape(Photo)
    val CardShape = RoundedCornerShape(Card)
    val SheetShape = RoundedCornerShape(topStart = Sheet, topEnd = Sheet)
    val DialogShape = RoundedCornerShape(Sheet) // wide-window sheet-as-dialog cards (all corners)
    val PillShape = RoundedCornerShape(Pill)
}

/** Type sizes (sp). Pair with weights at the call site / Material typography. */
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

/**
 * Bottom-sheet layout tokens. Every modal sheet consumes these via the `PynapliBottomSheet`
 * scaffold (design/PynapliSheet.kt) so padding, section rhythm, and tall-sheet height match
 * across Grid filters, Swipe filters, Travel, Start Tonight, Conversation, and the profile peek.
 */
object PynapliSheetTokens {
    val ContentHorizontalPadding = 20.dp
    val ContentTopPadding = 12.dp
    val ContentBottomPadding = 32.dp
    val SectionSpacing = 16.dp // canonical inter-section rhythm inside a sheet body

    /**
     * Breathing room below the last element of a [PynapliSheetScaffold] body, *inside* the scroll
     * region. Without it the content ends flush against the pinned footer: the last card is sliced
     * off at the footer edge, and a body that only just overflows gets a few pixels of scroll travel
     * that rubber-bands straight back — the profile peek "bounce".
     */
    val ScaffoldBodyBottomPadding = 20.dp
    const val TALL_HEIGHT_FRACTION = 0.94f // profile peek + conversation cover most of the screen
}

/** Animation durations in milliseconds (docs/DESIGN_TOKENS.md). */
object PynapliMotion {
    const val FAST = 120
    const val STANDARD = 220
    const val SLOW = 360
    const val GRID_PLACEMENT = 220
    const val SWIPE_FLY_OUT = 240
    const val SWIPE_SPRING_BACK = 260

    // Calm ease-in-out for entrances/exits (decelerate into place).
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // Staggered list/grid reveal tuning.
    const val StaggerStepMillis = 35
    const val MaxStaggerItems = 9

    /** Standard tween for the given value type (e.g. Float). */
    fun <T> standardTween(
        durationMillis: Int = STANDARD,
        delayMillis: Int = 0,
    ): FiniteAnimationSpec<T> = tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = StandardEasing)

    /** Gentle spring for scale/enter accents — a touch of life without an overshooting bounce. */
    fun gentleSpringFloat(): FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Lightly-bouncy spring for "pop" accents — badge appears / count increments. */
    fun popSpringFloat(): FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
}

/** Grid-tile-specific tokens (docs/DESIGN_TOKENS.md). Tiles are photo-first: no chips/tags. */
object GridTileTokens {
    const val ASPECT_RATIO = 1f
    val Gap = 2.dp
    val NameTextSize = 12.5.sp // compact, photo-first caption (handoff Grid)
    val MetaTextSize = 10.sp
    val OnlineDotSize = 10.dp
    val PartnerIconSize = 22.dp
    val BottomGradientHeight = 44.dp
}

/** Swipe-card-specific tokens (docs/DESIGN_TOKENS.md). */
object SwipeCardTokens {
    // Swipe card radius is 16 per the design north-star ("swipe card 16") — the hero card reads as a
    // distinct rounded card, softer than the dense grid tiles (~4) and chrome cards (12/8).
    val Radius = 16.dp
    val Shape = RoundedCornerShape(Radius)
    const val PHOTO_ASPECT_RATIO = 0.78f
    const val STACK_SCALE_STEP = 0.035f
    const val DRAG_THRESHOLD_FRACTION = 0.28f
    const val ROTATION_MAX_DEGREES = 12f
    val ActionButtonSize = 56.dp
}
