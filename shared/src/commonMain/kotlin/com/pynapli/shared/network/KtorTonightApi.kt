package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentTonightSessionDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.TonightFeedFiltersDto
import com.pynapli.shared.network.dto.TonightFeedQueryRequestDto
import com.pynapli.shared.network.dto.TonightFeedResponseDto
import com.pynapli.shared.network.dto.TonightSessionDto
import io.ktor.http.HttpMethod

/** Ktor implementation of [TonightApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorTonightApi(
    private val support: KtorApiSupport,
) : TonightApi {
    override suspend fun getTonightFeed(
        radiusMeters: Int,
        intent: String?,
    ): AppResult<TonightFeedResponseDto> = getTonightFeed(radiusMeters, intent, TonightFeedFiltersDto())

    override suspend fun getTonightFeed(
        radiusMeters: Int,
        intent: String?,
        filters: TonightFeedFiltersDto,
    ): AppResult<TonightFeedResponseDto> =
        support.authSend(
            HttpMethod.Post,
            "/v1/tonight/feed/query",
            TonightFeedQueryRequestDto(radiusMeters = radiusMeters, intent = intent, filters = filters),
        )

    override suspend fun getCurrentTonightSession(): AppResult<CurrentTonightSessionDto> = support.authGet("/v1/me/tonight/session")

    override suspend fun proposeTonightSession(request: ProposeTonightSessionRequestDto): AppResult<TonightSessionDto> = support.authSend(HttpMethod.Post, "/v1/tonight/sessions", request)

    override suspend fun approveTonightSession(sessionId: String): AppResult<TonightSessionDto> = support.authPost("/v1/tonight/sessions/$sessionId/approve")

    override suspend fun declineTonightSession(sessionId: String): AppResult<TonightSessionDto> = support.authPost("/v1/tonight/sessions/$sessionId/decline")

    override suspend fun endTonightSession(sessionId: String): AppResult<TonightSessionDto> = support.authDelete("/v1/tonight/sessions/$sessionId")
}
