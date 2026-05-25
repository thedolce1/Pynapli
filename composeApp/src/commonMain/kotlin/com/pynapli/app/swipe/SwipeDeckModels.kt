package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.SwipeCardDto

@Immutable
data class SwipeDeckUi(
    val deckSessionId: String? = null,
    val cards: List<SwipeCardDto>,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val advancedFiltersApplied: Boolean = false
)

@Immutable
data class SwipeRewindUi(
    val deckSessionId: String,
    val card: SwipeCardDto
)

@Immutable
data class QueuedSwipeAction(
    val actionId: String,
    val deckSessionId: String?,
    val card: SwipeCardDto,
    val action: String,
    val noteText: String? = null
)
