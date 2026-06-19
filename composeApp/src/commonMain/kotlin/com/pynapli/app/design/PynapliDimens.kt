package com.pynapli.app.design

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

/** Corner radii. Repeated cards stay at or below [Card]; sheets may use [Sheet]. */
object PynapliShape {
    val None = 0.dp
    val Small = 4.dp
    val Card = 8.dp
    val Sheet = 16.dp
    val Pill = 100.dp

    val SmallShape = RoundedCornerShape(Small)
    val CardShape = RoundedCornerShape(Card)
    val SheetShape = RoundedCornerShape(topStart = Sheet, topEnd = Sheet)
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
    val Radius = 8.dp
    const val PHOTO_ASPECT_RATIO = 0.78f
    const val STACK_SCALE_STEP = 0.035f
    const val DRAG_THRESHOLD_FRACTION = 0.28f
    const val ROTATION_MAX_DEGREES = 12f
    val ActionButtonSize = 56.dp
}
