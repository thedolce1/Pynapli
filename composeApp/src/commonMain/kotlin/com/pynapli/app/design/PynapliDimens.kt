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
    val NameTextSize = 15.sp
    val MetaTextSize = 12.sp
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
