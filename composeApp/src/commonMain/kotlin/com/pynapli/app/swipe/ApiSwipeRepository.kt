package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.map
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto

class ApiSwipeRepository(
    private val apiClient: PynapliApiClient,
) : SwipeRepository {
    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> =
        apiClient.querySwipeDeck(request).map { value ->
            SwipeDeckUi(
                deckSessionId = value.deckSessionId,
                cards = value.items,
                nextCursor = value.nextCursor,
                advancedFiltersAllowed = value.advancedFiltersAllowed,
                advancedFiltersApplied = value.advancedFiltersApplied,
            )
        }

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> =
        apiClient
            .submitSwipeAction(
                SwipeActionRequestDto(
                    actionId = action.actionId,
                    deckSessionId = action.deckSessionId.orEmpty(),
                    cardToken = action.card.rankToken,
                    targetUserId = action.card.userId,
                    action = action.action,
                    noteText = action.noteText,
                ),
            ).map { value -> value.match }

    override suspend fun rewind(): AppResult<SwipeRewindUi?> = apiClient.rewindSwipeAction().map { value -> value.toUi() }
}
