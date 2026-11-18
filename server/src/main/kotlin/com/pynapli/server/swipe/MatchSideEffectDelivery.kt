package com.pynapli.server.swipe

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.messaging.MatchConversationOpener
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.profiling.profile
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Performs [MatchSideEffect]s. Every step is idempotent (Activity rows are unique per pair and type,
 * the match conversation is get-or-create, the push carries an idempotency key), so the drainer may
 * repeat a delivery the request had already half done.
 */
class MatchSideEffectDelivery(
    private val activityService: ActivityService?,
    private val notificationPublisher: NotificationPublisher?,
    private val matchConversationOpener: MatchConversationOpener?,
) {
    private val logger = LoggerFactory.getLogger(MatchSideEffectDelivery::class.java)

    /** Delivers one effect; throws when a step fails, so the outbox keeps it for a retry. */
    suspend fun deliver(effect: MatchSideEffect) {
        when (effect.kind) {
            MatchSideEffectKind.SWIPE_INTEREST ->
                profile("swipe.action.activity") {
                    activityService?.recordSwipeInterest(
                        actorUserId = effect.actorUserId,
                        targetUserId = effect.targetUserId,
                        eventType = requireNotNull(effect.eventType) { "swipe_interest side effect without an event type" },
                        pairUserId = effect.pairUserId,
                        pairSessionId = effect.pairSessionId,
                    )
                }
            MatchSideEffectKind.MATCH -> deliverMatch(effect, requireNotNull(effect.matchId) { "match side effect without a match" })
        }
    }

    private suspend fun deliverMatch(
        effect: MatchSideEffect,
        matchId: UUID,
    ) {
        profile("swipe.action.activity") {
            activityService?.recordMatch(effect.actorUserId, effect.targetUserId, matchId)
        }
        // The match belongs in the actor's Messages (new-matches rail) straight away. For a Psync
        // match the actor is the partner who matched, never both partners together (PSYNC_SPEC).
        profile("swipe.action.matchConversation") {
            matchConversationOpener?.openForMatch(effect.actorUserId, effect.targetUserId)
        }
        profile("swipe.action.notify") {
            // The strict enqueue: a failed push write throws, so this effect stays in the outbox and
            // the drainer retries it instead of the push being silently lost.
            notificationPublisher?.enqueueOrThrow(
                NotificationEventDraft(
                    recipientUserId = effect.targetUserId,
                    actorUserId = effect.actorUserId,
                    kind = NotificationKind.MATCH,
                    subjectId = matchId,
                    idempotencyKey = "match:$matchId:${effect.targetUserId}",
                    payload =
                        mapOf(
                            // Matches live in the Messages new-matches rail, not an Activity tab.
                            "target" to "messages",
                            "activityEventType" to "match",
                            "matchId" to matchId.toString(),
                            "matchedUserId" to effect.actorUserId.toString(),
                        ),
                ),
            )
        }
    }

    /**
     * The fast path: delivers [effects] in order straight after the commit that recorded them, then
     * reports the delivered ones to [markDelivered]. A failure is logged and left in the outbox for
     * the drainer; it never fails the request, whose like or match already stands.
     */
    suspend fun deliverNow(
        effects: List<MatchSideEffect>,
        markDelivered: suspend (List<UUID>) -> Unit,
    ) {
        if (effects.isEmpty()) return
        val delivered = mutableListOf<UUID>()
        for (effect in effects) {
            try {
                deliver(effect)
                delivered += effect.id
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn("Match side effect {} ({}) left for the drainer: {}", effect.id, effect.kind, error.javaClass.simpleName)
            }
        }
        if (delivered.isEmpty()) return
        try {
            markDelivered(delivered)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Harmless: the drainer will redeliver them, and delivery is idempotent.
            logger.warn("Could not mark {} match side effect(s) delivered: {}", delivered.size, error.javaClass.simpleName)
        }
    }
}
