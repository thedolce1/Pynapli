package com.pynapli.server.activity

import com.pynapli.server.media.MediaService
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import java.util.UUID

internal class ActivityManagementActions(
    private val repository: ActivityRepository,
    private val mediaService: MediaService?,
) {
    suspend fun respondToAlbumAccessRequest(
        ownerUserId: UUID,
        eventId: UUID,
        approve: Boolean,
    ): RespondAlbumAccessResponseDto {
        val event =
            repository.getAlbumRequestEvent(ownerUserId, eventId)
                ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
        val status =
            mediaService?.decidePrivateAlbumAccessRequest(
                ownerUserId = event.ownerUserId,
                requesterUserId = event.requesterUserId,
                approved = approve,
            ) ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
        return RespondAlbumAccessResponseDto(
            eventId = event.eventId.toString(),
            status = status,
        )
    }

    suspend fun dismissActivityEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): DismissActivityEventResponseDto {
        if (!repository.dismissActivityEvent(recipientUserId, eventId)) {
            throw ActivityException("ACTIVITY_EVENT_NOT_FOUND")
        }
        return DismissActivityEventResponseDto(eventId = eventId.toString(), dismissed = true)
    }

    suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryDto {
        val summary = repository.getActivitySummary(recipientUserId)
        return ActivitySummaryDto(
            badgeCount = summary.badgeCount,
            profileViewCount = summary.profileViewCount,
            flirtCount = summary.flirtCount,
            likeCount = summary.likeCount,
            matchCount = summary.matchCount,
            albumRequestCount = summary.albumRequestCount,
            partnerInviteCount = summary.partnerInviteCount,
            latestBadgeOccurredAt = summary.latestBadgeOccurredAt?.toString(),
        )
    }

    suspend fun markActivityEventsSeen(
        recipientUserId: UUID,
        eventIdTexts: List<String>,
    ): MarkActivitySeenResponseDto {
        if (eventIdTexts.size > ACTIVITY_MAX_MARK_SEEN_IDS) {
            throw ActivityException("TOO_MANY_ACTIVITY_EVENTS")
        }
        val eventIds =
            eventIdTexts
                .map { value ->
                    runCatching { UUID.fromString(value) }
                        .getOrElse { throw ActivityException("INVALID_ACTIVITY_EVENT") }
                }.toSet()
        if (eventIds.isEmpty()) return MarkActivitySeenResponseDto(markedCount = 0)

        return MarkActivitySeenResponseDto(
            markedCount = repository.markActivityEventsSeen(recipientUserId, eventIds),
        )
    }
}
