package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto

class ApiSwipeRepository(
    private val apiClient: PynapliApiClient,
) : SwipeRepository {
    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> =
        when (val result = apiClient.querySwipeDeck(request)) {
            is AppResult.Success ->
                AppResult.Success(
                    SwipeDeckUi(
                        deckSessionId = result.value.deckSessionId,
                        cards = result.value.items,
                        nextCursor = result.value.nextCursor,
                        adSupported = result.value.adSupported,
                        advancedFiltersApplied = result.value.advancedFiltersApplied,
                    ),
                )
            is AppResult.Failure -> result
        }

    override suspend fun loadTopPicks(): AppResult<SwipeDeckUi> =
        when (val result = apiClient.getTopPicks()) {
            is AppResult.Success ->
                AppResult.Success(
                    SwipeDeckUi(
                        deckSessionId = result.value.deckSessionId.takeIf { it.isNotBlank() },
                        cards = result.value.picks.map { it.card },
                        nextCursor = null,
                        adSupported = false,
                        advancedFiltersApplied = false,
                    ),
                )
            is AppResult.Failure -> result
        }

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> =
        when (
            val result =
                apiClient.submitSwipeAction(
                    SwipeActionRequestDto(
                        actionId = action.actionId,
                        deckSessionId = action.deckSessionId.orEmpty(),
                        cardToken = action.card.rankToken,
                        targetUserId = action.card.userId,
                        action = action.action,
                        noteText = action.noteText,
                    ),
                )
        ) {
            is AppResult.Success -> AppResult.Success(result.value.match)
            is AppResult.Failure -> result
        }

    override suspend fun rewind(): AppResult<SwipeRewindUi?> =
        when (val result = apiClient.rewindSwipeAction()) {
            is AppResult.Success -> AppResult.Success(result.value.toUi())
            is AppResult.Failure -> result
        }
}
