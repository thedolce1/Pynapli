package com.pynapli.app.activity

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto

class ApiActivityFeedRepository(
    private val apiClient: PynapliApiClient
) : ActivityFeedRepository {
    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int
    ): AppResult<ActivityFeedUi> =
        when (val result = apiClient.listActivity(limit = limit, tab = tab.apiValue, cursor = cursor)) {
            is AppResult.Success -> AppResult.Success(
                ActivityFeedUi(
                    items = result.value.items,
                    nextCursor = result.value.nextCursor,
                    adSupported = result.value.adSupported,
                    entitlementTier = result.value.entitlementTier,
                    revealLimit = result.value.revealLimit,
                    gatedCount = result.value.gatedCount
                )
            )
            is AppResult.Failure -> result
        }

    override suspend fun loadSummary(): AppResult<ActivitySummaryUi> =
        when (val result = apiClient.getActivitySummary()) {
            is AppResult.Success -> AppResult.Success(
                ActivitySummaryUi(
                    badgeCount = result.value.badgeCount,
                    profileViewCount = result.value.profileViewCount,
                    flirtCount = result.value.flirtCount,
                    likeCount = result.value.likeCount,
                    matchCount = result.value.matchCount,
                    albumRequestCount = result.value.albumRequestCount,
                    partnerInviteCount = result.value.partnerInviteCount,
                    latestBadgeOccurredAt = result.value.latestBadgeOccurredAt
                )
            )

            is AppResult.Failure -> result
        }

    override suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto> =
        apiClient.markActivitySeen(MarkActivitySeenRequestDto(eventIds = eventIds))

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> =
        apiClient.dismissActivityEvent(eventId)

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean
    ): AppResult<RespondAlbumAccessResponseDto> =
        apiClient.respondToAlbumAccessRequest(
            eventId = eventId,
            request = RespondAlbumAccessRequestDto(approve = approve)
        )
}
