package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MarkMatchesCelebratedResponseDto
import com.pynapli.shared.network.dto.MatchCelebrationsResponseDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto

/**
 * Swipe slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers the swipe deck, swipe
 * actions, rewind, and like-back. Signatures are unchanged.
 */
interface SwipeApi {
    suspend fun querySwipeDeck(request: SwipeDeckRequestDto = SwipeDeckRequestDto()): AppResult<SwipeDeckResponseDto>

    suspend fun submitSwipeAction(request: SwipeActionRequestDto): AppResult<SwipeActionResponseDto>

    suspend fun rewindSwipeAction(): AppResult<SwipeRewindResponseDto>

    /** Like back [targetUserId], who has liked the viewer. A match comes back when it makes one. */
    suspend fun likeBack(targetUserId: String): AppResult<SwipeActionResponseDto>

    /** Matches the viewer has not seen the "It's a match" moment for yet (server-owned, cross-device). */
    suspend fun getMatchCelebrations(): AppResult<MatchCelebrationsResponseDto> = AppResult.Failure(AppError("UNSUPPORTED", "Not available."))

    /** Idempotent: marks those moments seen for the viewer on every device. */
    suspend fun markMatchesCelebrated(matchIds: List<String>): AppResult<MarkMatchesCelebratedResponseDto> =
        AppResult.Failure(AppError("UNSUPPORTED", "Not available."))
}
