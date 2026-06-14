package com.pynapli.server.tonight

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.presence.PresenceService
import java.time.Clock

internal class TonightReminderSender(
    private val repository: TonightSessionRepository,
    private val notificationPublisher: NotificationPublisher?,
    private val clock: Clock,
) {
    suspend fun sendExpiryReminders(): Int {
        val targets = repository.claimSessionsDueForReminder(clock.instant(), TONIGHT_REMINDER_LEAD_WINDOW)
        targets.forEach { target ->
            target.participantUserIds.forEach { participant ->
                notificationPublisher?.enqueue(
                    NotificationEventDraft(
                        recipientUserId = participant,
                        actorUserId = null,
                        kind = NotificationKind.TONIGHT_REMINDER,
                        subjectId = target.sessionId,
                        idempotencyKey = "tonight_reminder:${target.sessionId}:$participant",
                        payload = mapOf("sessionId" to target.sessionId.toString()),
                    ),
                )
            }
        }
        return targets.size
    }
}

internal class TonightPresenceReconciler(
    private val repository: TonightSessionRepository,
    private val presenceService: PresenceService?,
    private val clock: Clock,
) {
    suspend fun reconcilePresence(): TonightReconcileSummary {
        val presence = presenceService ?: return TonightReconcileSummary()
        val now = clock.instant()
        val sessions = repository.listActivePresenceSessions(now)
        if (sessions.isEmpty()) return TonightReconcileSummary()

        val allParticipantIds = sessions.flatMap { it.participantUserIds }.toSet()
        val online = presence.onlineUserIds(allParticipantIds)

        var paused = 0
        var resumed = 0
        var ended = 0
        for (session in sessions) {
            val anyOffline = session.participantUserIds.any { it !in online }
            when {
                !anyOffline && session.pausedAt != null -> {
                    repository.resumeSession(session.sessionId)
                    resumed++
                }
                anyOffline && session.pausedAt == null -> {
                    repository.pauseSession(session.sessionId, now)
                    paused++
                }
                anyOffline &&
                    session.pausedAt != null &&
                    !now.isBefore(session.pausedAt.plus(TONIGHT_PAUSE_GRACE)) -> {
                    repository.systemEndSession(session.sessionId, now)
                    ended++
                }
                // else: still online & running, or paused but within grace -> no change.
            }
        }
        return TonightReconcileSummary(paused = paused, resumed = resumed, ended = ended)
    }
}
