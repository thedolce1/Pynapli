package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentTonightSessionDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.SetTonightAvailableRequestDto
import com.pynapli.shared.network.dto.TonightAvailabilityDto
import com.pynapli.shared.network.dto.TonightFeedResponseDto
import com.pynapli.shared.network.dto.TonightSessionDto

/**
 * Playing-Tonight slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers tonight availability, the zone feed,
 * and session propose/approve/decline/end. Signatures are unchanged.
 */
interface TonightApi {
    suspend fun getTonightAvailability(): AppResult<TonightAvailabilityDto>

    suspend fun setTonightAvailability(
        request: SetTonightAvailableRequestDto = SetTonightAvailableRequestDto(),
    ): AppResult<TonightAvailabilityDto>

    suspend fun clearTonightAvailability(): AppResult<TonightAvailabilityDto>

    suspend fun getTonightFeed(
        radiusMeters: Int = 50_000,
        intent: String? = null,
    ): AppResult<TonightFeedResponseDto>

    suspend fun getCurrentTonightSession(): AppResult<CurrentTonightSessionDto>

    suspend fun proposeTonightSession(request: ProposeTonightSessionRequestDto): AppResult<TonightSessionDto>

    suspend fun approveTonightSession(sessionId: String): AppResult<TonightSessionDto>

    suspend fun declineTonightSession(sessionId: String): AppResult<TonightSessionDto>

    suspend fun endTonightSession(sessionId: String): AppResult<TonightSessionDto>
}
