package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentTonightSessionDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.TonightFeedFiltersDto
import com.pynapli.shared.network.dto.TonightFeedResponseDto
import com.pynapli.shared.network.dto.TonightSessionDto

/**
 * Playing-Tonight slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers the zone feed and session
 * propose/approve/decline/end. Signatures are unchanged.
 */
interface TonightApi {
    suspend fun getTonightFeed(
        radiusMeters: Int = 50_000,
        intent: String? = null,
    ): AppResult<TonightFeedResponseDto>

    /** Filtered feed; the default ignores filters so test fakes need not implement it. */
    suspend fun getTonightFeed(
        radiusMeters: Int,
        intent: String?,
        filters: TonightFeedFiltersDto,
    ): AppResult<TonightFeedResponseDto> = getTonightFeed(radiusMeters, intent)

    suspend fun getCurrentTonightSession(): AppResult<CurrentTonightSessionDto>

    suspend fun proposeTonightSession(request: ProposeTonightSessionRequestDto): AppResult<TonightSessionDto>

    suspend fun approveTonightSession(sessionId: String): AppResult<TonightSessionDto>

    suspend fun declineTonightSession(sessionId: String): AppResult<TonightSessionDto>

    suspend fun endTonightSession(sessionId: String): AppResult<TonightSessionDto>
}
