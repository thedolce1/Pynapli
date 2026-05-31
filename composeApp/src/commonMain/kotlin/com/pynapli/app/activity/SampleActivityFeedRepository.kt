package com.pynapli.app.activity

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto

class SampleActivityFeedRepository : ActivityFeedRepository {
    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int,
    ): AppResult<ActivityFeedUi> {
        val allItems =
            listOf(
                ActivityEventDto(
                    eventId = "sample-1",
                    type = "profile_view",
                    actorUserId = "1",
                    actorDisplayName = "Maya",
                    count = 3,
                    occurredAt = "2026-04-25T18:00:00Z",
                    distanceLabel = "1.2 km",
                    distanceMeters = 1_200,
                ),
                ActivityEventDto(
                    eventId = "sample-2",
                    type = "flirt",
                    actorUserId = "2",
                    actorDisplayName = "Alex",
                    occurredAt = "2026-04-25T17:45:00Z",
                ),
                ActivityEventDto(
                    eventId = "sample-3",
                    type = "album_request",
                    actorUserId = "3",
                    actorDisplayName = "Sam",
                    occurredAt = "2026-04-25T17:10:00Z",
                    albumRequestStatus = "pending",
                ),
                ActivityEventDto(
                    eventId = "sample-4",
                    type = "match",
                    actorUserId = "4",
                    actorDisplayName = "Taylor",
                    occurredAt = "2026-04-25T16:40:00Z",
                    matchId = "sample-match",
                ),
                ActivityEventDto(
                    eventId = "sample-5",
                    type = "partner_chat_invite",
                    actorUserId = "5",
                    actorDisplayName = "Noa",
                    occurredAt = "2026-04-25T16:20:00Z",
                    conversationId = "sample-conversation",
                ),
            )
        val filteredItems = allItems.filter { item -> tab.matches(item.type) }
        return AppResult.Success(
            ActivityFeedUi(
                items = if (cursor == null) filteredItems.take(limit) else emptyList(),
                nextCursor = null,
            ),
        )
    }

    override suspend fun loadSummary(): AppResult<ActivitySummaryUi> =
        AppResult.Success(
            ActivitySummaryUi(
                badgeCount = 4,
                profileViewCount = 3,
                flirtCount = 1,
                likeCount = 0,
                matchCount = 1,
                albumRequestCount = 1,
                partnerInviteCount = 1,
                latestBadgeOccurredAt = "2026-04-25T17:45:00Z",
            ),
        )

    override suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto> = AppResult.Success(MarkActivitySeenResponseDto(markedCount = eventIds.distinct().size))

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> =
        AppResult.Success(
            DismissActivityEventResponseDto(
                eventId = eventId,
                dismissed = true,
            ),
        )

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean,
    ): AppResult<RespondAlbumAccessResponseDto> =
        AppResult.Success(
            RespondAlbumAccessResponseDto(
                eventId = eventId,
                status = if (approve) "approved" else "denied",
            ),
        )
}

private fun ActivityTabUi.matches(type: String): Boolean =
    when (this) {
        ActivityTabUi.All -> true
        ActivityTabUi.Views -> type == "profile_view"
        ActivityTabUi.Flirts -> type == "flirt"
        ActivityTabUi.Likes -> type in setOf("swipe_like", "pineapple_like", "intro_note")
        ActivityTabUi.Matches -> type == "match"
        ActivityTabUi.Albums -> type == "album_request"
        ActivityTabUi.Invites -> type in setOf("partner_chat_invite", "psync_invite", "playing_tonight")
    }
