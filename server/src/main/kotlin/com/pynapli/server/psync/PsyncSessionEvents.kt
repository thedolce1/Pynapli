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
     * Replay resolved matches into the existing match pipeline post-commit. Psync never streams
     * individual swipes — only the resolved match outcome surfaces.
     */
    suspend fun emitMatchActivity(matches: List<PsyncResolvedMatch>) {
        val activity = activityService ?: return
        for (match in matches) {
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
                            "target" to "activity",
                            "activityTab" to "matches",
                            "activityEventType" to "match",
                            "matchId" to match.matchId.toString(),
                            "matchedUserId" to match.partnerUserId.toString(),
                        ),
                ),
            )
        }
    }
}
