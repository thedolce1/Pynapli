package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto

class SampleSwipeRepository : SwipeRepository {
    private val cards = mutableListOf(
        SwipeCardDto(
            userId = "sample-1",
            displayName = "Maya",
            displayAge = 31,
            distanceLabel = "800 m",
            distanceMeters = 800,
            photoUrl = null,
            linkedPartner = LinkedPartnerMiniDto(
                userId = "sample-linked-1",
                displayName = "Noa"
            ),
            rankToken = "swipe:sample:1"
        ),
        SwipeCardDto(
            userId = "sample-2",
            displayName = "Alex",
            displayAge = 34,
            distanceLabel = "1.2 km",
            distanceMeters = 1_200,
            photoUrl = null,
            rankToken = "swipe:sample:2"
        )
    )

    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> =
        AppResult.Success(
            SwipeDeckUi(
                deckSessionId = "sample-session",
                cards = cards.toList(),
                nextCursor = null,
                adSupported = true,
                advancedFiltersApplied = request.lifestyleTags.isNotEmpty() ||
                    request.logisticsTags.isNotEmpty()
            )
        )

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> {
        cards.removeAll { it.userId == action.card.userId }
        return AppResult.Success(null)
    }

    override suspend fun rewind(): AppResult<SwipeRewindUi?> =
        AppResult.Success(null)
}
