package com.pynapli.server.swipe

import java.time.Clock
import java.util.UUID

/**
 * Fast path for a recorded swipe's side effects: delivers what the record transaction wrote to the
 * match side-effect outbox and marks it delivered, so the user sees Likes You, the match
 * conversation and the push with no added latency. Anything that fails here stays in the outbox
 * for [MatchSideEffectDrainer].
 */
internal class SwipeActionSideEffects(
    private val repository: SwipeRepository,
    private val delivery: MatchSideEffectDelivery,
    private val clock: Clock,
) {
    suspend fun deliver(
        result: SwipeActionResult,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
    ) {
        val effects =
            result.sideEffects
                // A repository without an outbox: plan the same effects and deliver them in-process.
                ?: if (result.newlyRecorded) {
                    MatchSideEffectPlans.forSwipe(actorUserId, targetUserId, action, result.match)
                } else {
                    listOfNotNull(result.match?.let { MatchSideEffectPlans.matchAnnouncement(actorUserId, targetUserId, it.matchId) })
                }
        delivery.deliverNow(effects) { ids -> repository.markSideEffectsDelivered(ids, clock.instant()) }
    }
}
