package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeCardDto

@Immutable
data class SwipeUiState(
    val deckSessionId: String? = null,
    val cards: List<SwipeCardDto> = emptyList(),
    val topPicksMode: Boolean = false,
    val filters: List<SwipeFilterUi> = defaultSwipeFilters,
    val swipeFilters: SwipeFilters = SwipeFilters.defaults,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val isRewinding: Boolean = false,
    val isPaging: Boolean = false,
    val nextCursor: String? = null,
    val pendingActionCount: Int = 0,
    val pendingActionLimit: Int = MAX_PENDING_ACTIONS,
    val paywallMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val pendingMatch: MatchDto? = null,
) {
    val topCard: SwipeCardDto? get() = cards.firstOrNull()
    val hasMore: Boolean get() = cards.isNotEmpty() || nextCursor != null
    val actionQueueFull: Boolean get() = pendingActionCount >= pendingActionLimit
}
