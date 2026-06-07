package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto

interface SwipeRepository {
    suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi>

    /**
     * Loads the daily curated Top Picks as a swipeable deck. Picks are backed by a real
     * swipe deck session (SWIPE_IMPLEMENTATION_SPEC: "Picks are a shortcut into the swipe
     * engine"), so the returned cards are acted on through the normal swipe action path.
     * There is no pagination — picks are a fixed daily set, so `nextCursor` is always null.
     */
    suspend fun loadTopPicks(): AppResult<SwipeDeckUi>

    suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?>

    suspend fun rewind(): AppResult<SwipeRewindUi?>
}
