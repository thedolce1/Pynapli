package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto

/**
 * Activity slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers the activity feed/summary,
 * seen/dismiss, flirts, and album-access requests/responses. Signatures are unchanged.
 */
interface ActivityApi {
    suspend fun listActivity(
        limit: Int? = null,
        tab: String? = null,
        cursor: String? = null,
    ): AppResult<ActivityFeedResponseDto>

    suspend fun getActivitySummary(): AppResult<ActivitySummaryDto>

    suspend fun markActivitySeen(request: MarkActivitySeenRequestDto): AppResult<MarkActivitySeenResponseDto>

    suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto>

    suspend fun sendFlirt(request: SendFlirtRequestDto): AppResult<SendFlirtResponseDto>

    suspend fun requestAlbumAccess(request: RequestAlbumAccessRequestDto): AppResult<RequestAlbumAccessResponseDto>

    suspend fun respondToAlbumAccessRequest(
        eventId: String,
        request: RespondAlbumAccessRequestDto,
    ): AppResult<RespondAlbumAccessResponseDto>
}
