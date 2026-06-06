package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncNudgeResponseDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [PsyncApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorPsyncApi(
    private val support: KtorApiSupport,
) : PsyncApi {
    override suspend fun createPsyncSession(request: CreatePsyncSessionRequestDto): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun getCurrentPsyncSession(): AppResult<CurrentPsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/me/psync/session")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun getPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/psync/sessions/$sessionId")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun acceptPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/accept")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun denyPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/deny")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun suggestPsyncFilters(
        sessionId: String,
        request: SuggestPsyncFiltersRequestDto,
    ): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/suggest-filters")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun leavePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/leave")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun startPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/start")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun getPsyncDeck(sessionId: String): AppResult<PsyncDeckResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/psync/sessions/$sessionId/deck")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun submitPsyncAction(
        sessionId: String,
        request: SubmitPsyncActionRequestDto,
    ): AppResult<PsyncActionResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/actions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun pausePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/pause")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun resumePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/resume")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun completePsyncSession(sessionId: String): AppResult<PsyncRecapDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/complete")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun getPsyncRecap(sessionId: String): AppResult<PsyncRecapDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/psync/sessions/$sessionId/recap")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun nudgePsyncSession(sessionId: String): AppResult<PsyncNudgeResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/psync/sessions/$sessionId/nudge")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
