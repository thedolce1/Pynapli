package com.pynapli.server.psync

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.swipe.MatchSideEffect
import com.pynapli.server.swipe.MatchSideEffectKind
import com.pynapli.server.swipe.MatchSideEffectPlans
import java.time.Clock
import java.util.UUID

internal class PsyncSessionEvents(
    private val activityService: ActivityService?,
    private val notificationPublisher: NotificationPublisher?,
    private val broadcaster: PsyncBroadcaster?,
    private val clock: Clock,
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
}

/**
 * The side effects a Psync completion owes, written to the match side-effect outbox in the
 * completion transaction (SWIPE_IMPLEMENTATION_SPEC "Match side effects"). Psync never streams
 * individual swipes: only resolved matches and delivered likes surface.
 */
internal object PsyncSideEffectPlans {
    fun forCompletion(
        sessionId: UUID,
        matches: List<PsyncResolvedMatch>,
        deliveredLikes: List<PsyncDeliveredLike>,
    ): List<MatchSideEffect> =
        buildList {
            // Each partner's match is their own conversation (no silent partner adds, PSYNC_SPEC).
            for (match in matches) {
                add(MatchSideEffectPlans.matchAnnouncement(match.partnerUserId, match.targetUserId, match.matchId))
            }
            // Delivered likes go to each target's Likes You. A like only one partner made reads as
            // that person's ordinary like. When both partners liked the same person, each like is
            // tagged with the other partner so the target sees one "liked you together" row
            // (PSYNC_SPEC pair presentation); both remain real likes the target can answer.
            val likersByTarget =
                deliveredLikes.groupBy { it.targetUserId }.mapValues { (_, byTarget) -> byTarget.map { it.actorUserId }.distinct() }
            for (like in deliveredLikes) {
                val partner = likersByTarget[like.targetUserId].orEmpty().singleOrNull { it != like.actorUserId }
                add(
                    MatchSideEffect(
                        id = UUID.randomUUID(),
                        kind = MatchSideEffectKind.SWIPE_INTEREST,
                        actorUserId = like.actorUserId,
                        targetUserId = like.targetUserId,
                        eventType = if (like.action == "pineapple_like") "pineapple_like" else "swipe_like",
                        pairUserId = partner,
                        pairSessionId = sessionId.takeIf { partner != null },
                    ),
                )
            }
        }
}
