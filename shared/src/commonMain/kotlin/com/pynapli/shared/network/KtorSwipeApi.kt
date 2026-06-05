package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MatchesListResponseDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [SwipeApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorSwipeApi(
    private val support: KtorApiSupport,
) : SwipeApi {
    override suspend fun querySwipeDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/swipe/deck/query")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun submitSwipeAction(request: SwipeActionRequestDto): AppResult<SwipeActionResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/swipe/actions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun rewindSwipeAction(): AppResult<SwipeRewindResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/swipe/rewind")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun listMatches(): AppResult<MatchesListResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/matches")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
