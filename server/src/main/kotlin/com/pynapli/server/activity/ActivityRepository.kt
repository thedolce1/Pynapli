package com.pynapli.server.activity

import java.time.Instant
import java.util.UUID

interface ActivityRepository {
    suspend fun recordProfileView(viewerUserId: UUID, targetUserId: UUID): Boolean

    suspend fun recordFlirt(senderUserId: UUID, targetUserId: UUID): Boolean

    suspend fun recordAlbumRequest(requesterUserId: UUID, targetUserId: UUID): Boolean

    suspend fun recordSwipeInterest(actorUserId: UUID, targetUserId: UUID, eventType: String): Boolean

    suspend fun recordMatch(recipientUserId: UUID, actorUserId: UUID, matchId: UUID): Boolean

    suspend fun recordPartnerChatInvite(inviterUserId: UUID, inviteeUserId: UUID, conversationId: UUID): Boolean

    suspend fun getAlbumRequestEvent(recipientUserId: UUID, eventId: UUID): AlbumRequestEventRecord?

    suspend fun listActivity(query: ActivityFeedQuery): List<ActivityEventRecord>

    suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryRecord

    suspend fun markActivityEventsSeen(recipientUserId: UUID, eventIds: Set<UUID>): Int

    suspend fun dismissActivityEvent(recipientUserId: UUID, eventId: UUID): Boolean
}

data class ActivityFeedQuery(
    val recipientUserId: UUID,
    val eventTypes: Set<String>,
    val limit: Int,
    val cursor: ActivityCursorAnchor?
)

data class ActivityCursorAnchor(
    val occurredAt: Instant,
    val eventId: UUID,
    val seenCount: Int
)

data class ActivityEventRecord(
    val eventId: UUID,
    val type: String,
    val actorUserId: UUID,
    val actorDisplayName: String,
    val actorProfilePhotoMediaAssetId: UUID?,
    val count: Int,
    val occurredAt: Instant,
    val showDistance: Boolean,
    val distanceMeters: Double?,
    val albumRequestStatus: String?,
    val matchId: String?,
    val conversationId: String?
)

fun ActivityEventRecord.toCursorAnchor(seenCount: Int): ActivityCursorAnchor =
    ActivityCursorAnchor(
        occurredAt = occurredAt,
        eventId = eventId,
        seenCount = seenCount
    )

data class AlbumRequestEventRecord(
    val eventId: UUID,
    val ownerUserId: UUID,
    val requesterUserId: UUID
)

data class ActivitySummaryRecord(
    val profileViewCount: Int,
    val flirtCount: Int,
    val likeCount: Int,
    val matchCount: Int,
    val albumRequestCount: Int,
    val partnerInviteCount: Int,
    val latestBadgeOccurredAt: Instant?
) {
    val badgeCount: Int
        get() = flirtCount + likeCount + matchCount + albumRequestCount + partnerInviteCount
}
