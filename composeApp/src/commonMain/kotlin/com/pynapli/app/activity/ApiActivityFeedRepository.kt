package com.pynapli.app.activity

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.map
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto

class ApiActivityFeedRepository(
    private val apiClient: PynapliApiClient,
) : ActivityFeedRepository {
    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int,
    ): AppResult<ActivityFeedUi> =
        apiClient.listActivity(limit = limit, tab = tab.apiValue, cursor = cursor).map { value ->
            ActivityFeedUi(
                items = value.items,
                nextCursor = value.nextCursor,
                entitlementTier = value.entitlementTier,
                revealLimit = value.revealLimit,
                gatedCount = value.gatedCount,
            )
        }

    override suspend fun loadSummary(): AppResult<ActivitySummaryUi> =
        apiClient.getActivitySummary().map { value ->
            ActivitySummaryUi(
                badgeCount = value.badgeCount,
                profileViewCount = value.profileViewCount,
                flirtCount = value.flirtCount,
                likeCount = value.likeCount,
                matchCount = value.matchCount,
                albumRequestCount = value.albumRequestCount,
                partnerInviteCount = value.partnerInviteCount,
                latestBadgeOccurredAt = value.latestBadgeOccurredAt,
            )
        }

    override suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto> = apiClient.markActivitySeen(MarkActivitySeenRequestDto(eventIds = eventIds))

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> = apiClient.dismissActivityEvent(eventId)

    override suspend fun likeBack(userId: String) = apiClient.likeBack(userId)

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean,
    ): AppResult<RespondAlbumAccessResponseDto> =
        apiClient.respondToAlbumAccessRequest(
            eventId = eventId,
            request = RespondAlbumAccessRequestDto(approve = approve),
        )
}
