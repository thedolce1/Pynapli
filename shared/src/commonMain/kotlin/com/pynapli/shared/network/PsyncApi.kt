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

/**
 * Psync (synchronized partner swipe) slice of the API surface, split out of the monolithic
 * [PynapliApiClient] (which now composes the per-feature interfaces). Server is the product
 * authority; the client just negotiates, swipes the shared deck privately, and reads the recap.
 * Signatures are unchanged.
 */
interface PsyncApi {
    suspend fun createPsyncSession(
        request: CreatePsyncSessionRequestDto = CreatePsyncSessionRequestDto(),
    ): AppResult<PsyncSessionDto>

    suspend fun getCurrentPsyncSession(): AppResult<CurrentPsyncSessionDto>

    suspend fun getPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun acceptPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun denyPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun suggestPsyncFilters(
        sessionId: String,
        request: SuggestPsyncFiltersRequestDto,
    ): AppResult<PsyncSessionDto>

    suspend fun leavePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun startPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun getPsyncDeck(sessionId: String): AppResult<PsyncDeckResponseDto>

    suspend fun submitPsyncAction(
        sessionId: String,
        request: SubmitPsyncActionRequestDto,
    ): AppResult<PsyncActionResponseDto>

    suspend fun pausePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun resumePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun completePsyncSession(sessionId: String): AppResult<PsyncRecapDto>

    suspend fun getPsyncRecap(sessionId: String): AppResult<PsyncRecapDto>

    suspend fun nudgePsyncSession(sessionId: String): AppResult<PsyncNudgeResponseDto>
}
