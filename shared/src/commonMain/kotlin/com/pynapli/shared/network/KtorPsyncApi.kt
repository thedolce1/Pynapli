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
import io.ktor.client.request.get
import io.ktor.http.HttpMethod

/** Ktor implementation of [PsyncApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorPsyncApi(
    private val support: KtorApiSupport,
) : PsyncApi {
    override suspend fun createPsyncSession(request: CreatePsyncSessionRequestDto): AppResult<PsyncSessionDto> = support.authSend(HttpMethod.Post, "/v1/psync/sessions", request)

    override suspend fun getCurrentPsyncSession(): AppResult<CurrentPsyncSessionDto> = support.authGet("/v1/me/psync/session")

    override suspend fun getPsyncSession(sessionId: String): AppResult<PsyncSessionDto> = support.authGet("/v1/psync/sessions/$sessionId")

    override suspend fun acceptPsyncSession(sessionId: String): AppResult<PsyncSessionDto> = support.authPost("/v1/psync/sessions/$sessionId/accept")

    override suspend fun denyPsyncSession(sessionId: String): AppResult<PsyncSessionDto> = support.authPost("/v1/psync/sessions/$sessionId/deny")

    override suspend fun suggestPsyncFilters(
        sessionId: String,
        request: SuggestPsyncFiltersRequestDto,
    ): AppResult<PsyncSessionDto> = support.authSend(HttpMethod.Post, "/v1/psync/sessions/$sessionId/suggest-filters", request)

    override suspend fun leavePsyncSession(sessionId: String): AppResult<PsyncSessionDto> = support.authPost("/v1/psync/sessions/$sessionId/leave")

    override suspend fun startPsyncSession(sessionId: String): AppResult<PsyncSessionDto> = support.authPost("/v1/psync/sessions/$sessionId/start")

    override suspend fun getPsyncDeck(sessionId: String): AppResult<PsyncDeckResponseDto> = support.authGet("/v1/psync/sessions/$sessionId/deck")

    override suspend fun submitPsyncAction(
        sessionId: String,
        request: SubmitPsyncActionRequestDto,
    ): AppResult<PsyncActionResponseDto> = support.authSend(HttpMethod.Post, "/v1/psync/sessions/$sessionId/actions", request)

    override suspend fun pausePsyncSession(sessionId: String): AppResult<PsyncSessionDto> = support.authPost("/v1/psync/sessions/$sessionId/pause")

    override suspend fun resumePsyncSession(sessionId: String): AppResult<PsyncSessionDto> = support.authPost("/v1/psync/sessions/$sessionId/resume")

    override suspend fun completePsyncSession(sessionId: String): AppResult<PsyncRecapDto> = support.authPost("/v1/psync/sessions/$sessionId/complete")

    override suspend fun getPsyncRecap(sessionId: String): AppResult<PsyncRecapDto> = support.authGet("/v1/psync/sessions/$sessionId/recap")

    override suspend fun nudgePsyncSession(sessionId: String): AppResult<PsyncNudgeResponseDto> = support.authPost("/v1/psync/sessions/$sessionId/nudge")
}
