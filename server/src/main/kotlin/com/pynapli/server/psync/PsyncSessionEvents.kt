package com.pynapli.server.psync

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import java.time.Clock
import java.util.UUID

internal class PsyncSessionEvents(
    private val activityService: ActivityService?,
    private val notificationPublisher: NotificationPublisher?,
    private val broadcaster: PsyncBroadcaster?,
    private val clock: Clock,
    private val matchConversationOpener: com.pynapli.server.messaging.MatchConversationOpener? = null,
) {
    /** Fan out a convenience realtime delta to [recipients] (no-op without a broadcaster). */
    suspend fun emitDelta(
        sessionId: UUID,
        type: String,
        recipients: List<UUID>,
    ) {
        val b = broadcaster ?: return
        val now = clock.instant()
        recipients.forEach { recipient ->
            b.emit(PsyncRealtimeDelta(recipientUserId = recipient, sessionId = sessionId, type = type, occurredAt = now))
        }
    }

    /** Record a Psync session card (started/recap/expired) for both partners, actor = the other. */
    suspend fun recordForBoth(
        session: PsyncSession,
        eventType: String,
    ) {
        val activity = activityService ?: return
        val ids = session.participants.map { it.userId }
        for (recipient in ids) {
            val other = ids.firstOrNull { it != recipient } ?: continue
            activity.recordPsyncSessionEvent(recipient, other, eventType, session.id)
        }
    }

    suspend fun recordExpired(expired: List<PsyncExpiredSession>) {
        val activity = activityService ?: return
        for (session in expired) {
            for (recipient in session.participantUserIds) {
                val other = session.participantUserIds.firstOrNull { it != recipient } ?: continue
                activity.recordPsyncSessionEvent(recipient, other, "psync_expired", session.sessionId)
            }
        }
    }

    /**
     * Push the invite to the invited partner (the Activity card is recorded separately). Follows
     * their "partner invites" notification setting, like a Playing Tonight invite. Idempotent per
     * session and recipient.
     */
    suspend fun notifyInvite(
        sessionId: UUID,
        inviterUserId: UUID,
        inviteeUserId: UUID,
    ) {
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = inviteeUserId,
                actorUserId = inviterUserId,
                kind = NotificationKind.PSYNC_INVITE,
                subjectId = sessionId,
                idempotencyKey = "psync_invite:$sessionId:$inviteeUserId",
                payload =
                    mapOf(
                        "target" to "psync",
                        "sessionId" to sessionId.toString(),
                    ),
            ),
        )
    }

    /**
     * Surface delivered Psync likes in each target's Likes You. A like only one partner made reads
     * as that person's ordinary like. When both partners liked the same person, each like is tagged
     * with the other partner so the target sees one "liked you together" row (PSYNC_SPEC pair
     * presentation); both remain real likes the target can answer. Passes are never delivered.
     */
    suspend fun emitDeliveredLikes(
        likes: List<PsyncDeliveredLike>,
        sessionId: UUID? = null,
    ) {
        val activity = activityService ?: return
        val likersByTarget = likes.groupBy { it.targetUserId }.mapValues { (_, byTarget) -> byTarget.map { it.actorUserId }.distinct() }
        for (like in likes) {
            val eventType = if (like.action == "pineapple_like") "pineapple_like" else "swipe_like"
            val partner = likersByTarget[like.targetUserId].orEmpty().singleOrNull { it != like.actorUserId }
            activity.recordSwipeInterest(
                actorUserId = like.actorUserId,
                targetUserId = like.targetUserId,
                eventType = eventType,
                pairUserId = partner?.takeIf { sessionId != null },
                pairSessionId = sessionId.takeIf { partner != null },
            )
        }
    }

    /**
     * Replay resolved matches into the existing match pipeline post-commit. Psync never streams
     * individual swipes — only the resolved match outcome surfaces.
     */
    suspend fun emitMatchActivity(matches: List<PsyncResolvedMatch>) {
        val activity = activityService ?: return
        for (match in matches) {
            // Each partner's match is their own conversation (no silent partner adds, PSYNC_SPEC).
            matchConversationOpener?.openForMatch(match.partnerUserId, match.targetUserId)
            activity.recordMatch(match.partnerUserId, match.targetUserId, match.matchId)
            notificationPublisher?.enqueue(
                NotificationEventDraft(
                    recipientUserId = match.targetUserId,
                    actorUserId = match.partnerUserId,
                    kind = NotificationKind.MATCH,
                    subjectId = match.matchId,
                    idempotencyKey = "match:${match.matchId}:${match.targetUserId}",
                    payload =
                        mapOf(
                            // Matches live in the Messages new-matches rail, not an Activity tab.
                            "target" to "messages",
                            "activityEventType" to "match",
                            "matchId" to match.matchId.toString(),
                            "matchedUserId" to match.partnerUserId.toString(),
                        ),
                ),
            )
        }
    }
}
