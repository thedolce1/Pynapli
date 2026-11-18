package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MarkMatchesCelebratedRequestDto
import com.pynapli.shared.network.dto.MarkMatchesCelebratedResponseDto
import com.pynapli.shared.network.dto.MatchCelebrationsResponseDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import io.ktor.client.request.get
import io.ktor.http.HttpMethod

/** Ktor implementation of [SwipeApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorSwipeApi(
    private val support: KtorApiSupport,
) : SwipeApi {
    override suspend fun querySwipeDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckResponseDto> = support.authSend(HttpMethod.Post, "/v1/swipe/deck/query", request)

    override suspend fun submitSwipeAction(request: SwipeActionRequestDto): AppResult<SwipeActionResponseDto> = support.authSend(HttpMethod.Post, "/v1/swipe/actions", request)

    override suspend fun rewindSwipeAction(): AppResult<SwipeRewindResponseDto> = support.authPost("/v1/swipe/rewind")

    override suspend fun likeBack(targetUserId: String): AppResult<SwipeActionResponseDto> =
        support.authSend(
            HttpMethod.Post,
            "/v1/swipe/like-back",
            com.pynapli.shared.network.dto
                .LikeBackRequestDto(targetUserId),
        )

    override suspend fun getMatchCelebrations(): AppResult<MatchCelebrationsResponseDto> = support.authGet("/v1/matches/celebrations")

    override suspend fun markMatchesCelebrated(matchIds: List<String>): AppResult<MarkMatchesCelebratedResponseDto> = support.authSend(HttpMethod.Post, "/v1/matches/celebrations/seen", MarkMatchesCelebratedRequestDto(matchIds))
}
