package com.pynapli.server.activity

import java.time.Instant
import java.util.UUID

interface ActivityRepository {
    suspend fun recordProfileView(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): Boolean

    suspend fun recordFlirt(
        senderUserId: UUID,
        targetUserId: UUID,
        source: String? = null,
    ): Boolean

    suspend fun recordAlbumRequest(
        requesterUserId: UUID,
        targetUserId: UUID,
    ): Boolean

    suspend fun recordSwipeInterest(
        actorUserId: UUID,
        targetUserId: UUID,
        eventType: String,
        metadataJson: String = "{}",
    ): Boolean

    suspend fun recordMatch(
        recipientUserId: UUID,
        actorUserId: UUID,
        matchId: UUID,
    ): Boolean

    suspend fun recordPartnerChatInvite(
        inviterUserId: UUID,
        inviteeUserId: UUID,
        conversationId: UUID,
    ): Boolean

    /** The owner granted the requester access to their private album. True if a card was written. */
    suspend fun recordAlbumGranted(
        ownerUserId: UUID,
        requesterUserId: UUID,
    ): Boolean = false

    /**
     * Record a Psync session-lifecycle card (psync_invite/started/recap/expired) for [recipientUserId].
     * Idempotent per (recipient, eventType, sessionId). Returns true if a new row was inserted.
     */
    suspend fun recordPsyncSessionEvent(
        recipientUserId: UUID,
        actorUserId: UUID,
        eventType: String,
        sessionId: UUID,
    ): Boolean = false

    suspend fun getAlbumRequestEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): AlbumRequestEventRecord?

    suspend fun listActivity(query: ActivityFeedQuery): List<ActivityEventRecord>

    suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryRecord

    suspend fun markActivityEventsSeen(
        recipientUserId: UUID,
        eventIds: Set<UUID>,
    ): Int

    suspend fun dismissActivityEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): Boolean
}

data class ActivityFeedQuery(
    val recipientUserId: UUID,
    val eventTypes: Set<String>,
    val limit: Int,
    val cursor: ActivityCursorAnchor?,
)

data class ActivityCursorAnchor(
    val occurredAt: Instant,
    val eventId: UUID,
    val seenCount: Int,
)

data class ActivityEventRecord(
    val eventId: UUID,
    val type: String,
    val actorUserId: UUID,
    val actorDisplayName: String,
    val actorProfilePhotoMediaAssetId: UUID?,
    val count: Int,
    val occurredAt: Instant,
    val seen: Boolean,
    val showDistance: Boolean,
    val distanceMeters: Double?,
    val albumRequestStatus: String?,
    val matchId: String?,
    val conversationId: String?,
    val source: String? = null,
    // The Psync session a psync_* item is about, so the client can open that session.
    val psyncSessionId: String? = null,
    // A like made together in Psync: the actor's linked partner, who liked the recipient too.
    val pairUserId: UUID? = null,
    val pairDisplayName: String? = null,
    val pairSessionId: String? = null,
    // pineapple_like: the note the sender added, if any.
    val noteText: String? = null,
)

fun ActivityEventRecord.toCursorAnchor(seenCount: Int): ActivityCursorAnchor =
    ActivityCursorAnchor(
        occurredAt = occurredAt,
        eventId = eventId,
        seenCount = seenCount,
    )

data class AlbumRequestEventRecord(
    val eventId: UUID,
    val ownerUserId: UUID,
    val requesterUserId: UUID,
)

data class ActivitySummaryRecord(
    val profileViewCount: Int,
    val flirtCount: Int,
    val likeCount: Int,
    val albumRequestCount: Int,
    val partnerInviteCount: Int,
    val latestBadgeOccurredAt: Instant?,
) {
    // Matches are not in the badge: Activity has no Matches tab (they live in the Messages
    // new-matches rail), so no client loads a match row to mark it seen and it would never clear.
    val badgeCount: Int
        get() = flirtCount + likeCount + albumRequestCount + partnerInviteCount
}
