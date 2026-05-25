package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto

interface SwipeRepository {
    suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi>

    suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?>

    suspend fun rewind(): AppResult<SwipeRewindUi?>
}
