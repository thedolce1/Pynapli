package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentTonightSessionDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.SetTonightAvailableRequestDto
import com.pynapli.shared.network.dto.TonightAvailabilityDto
import com.pynapli.shared.network.dto.TonightFeedResponseDto
import com.pynapli.shared.network.dto.TonightSessionDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [TonightApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorTonightApi(
    private val support: KtorApiSupport,
) : TonightApi {
    override suspend fun getTonightAvailability(): AppResult<TonightAvailabilityDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/me/availability/tonight")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun setTonightAvailability(request: SetTonightAvailableRequestDto): AppResult<TonightAvailabilityDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/me/availability/tonight")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun clearTonightAvailability(): AppResult<TonightAvailabilityDto> =
        support.authenticated { token ->
            support.httpClient
                .delete(support.endpoint("/v1/me/availability/tonight")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun getTonightFeed(
        radiusMeters: Int,
        intent: String?,
    ): AppResult<TonightFeedResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/tonight/feed")) {
                    bearerAuth(token)
                    parameter("radiusMeters", radiusMeters)
                    intent?.let { parameter("intent", it) }
                }.toAppResult(support.json)
        }

    override suspend fun getCurrentTonightSession(): AppResult<CurrentTonightSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/me/tonight/session")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun proposeTonightSession(request: ProposeTonightSessionRequestDto): AppResult<TonightSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/tonight/sessions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun approveTonightSession(sessionId: String): AppResult<TonightSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/tonight/sessions/$sessionId/approve")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun declineTonightSession(sessionId: String): AppResult<TonightSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/tonight/sessions/$sessionId/decline")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun endTonightSession(sessionId: String): AppResult<TonightSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .delete(support.endpoint("/v1/tonight/sessions/$sessionId")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
