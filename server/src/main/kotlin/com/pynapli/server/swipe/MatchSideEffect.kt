package com.pynapli.server.swipe

import com.pynapli.shared.network.dto.SwipeAction
import java.util.UUID

/**
 * Follow-up work a committed like or match owes (SWIPE_IMPLEMENTATION_SPEC "Match side effects").
 * It is planned and written to `match_side_effect_outbox` in the same transaction as the like or
 * match, delivered by the request straight after commit, and redelivered by
 * [MatchSideEffectDrainer] if the request never got that far. Delivery is idempotent.
 */
data class MatchSideEffect(
    val id: UUID,
    val kind: MatchSideEffectKind,
    val actorUserId: UUID,
    val targetUserId: UUID,
    /** [MatchSideEffectKind.SWIPE_INTEREST]: `swipe_like` or `pineapple_like`. */
    val eventType: String? = null,
    /** [MatchSideEffectKind.MATCH]: the match being announced. */
    val matchId: UUID? = null,
    /** A Psync like made together with the linked partner, shown as one row in Likes You. */
    val pairUserId: UUID? = null,
    val pairSessionId: UUID? = null,
)

enum class MatchSideEffectKind(
    val storageValue: String,
) {
    /** The actor's like in the target's Likes You. */
    SWIPE_INTEREST("swipe_interest"),

    /**
     * A match: the Activity match row for both people, the actor's conversation with the target in
     * Messages, and the match push to the target.
     */
    MATCH("match"),
    ;

    companion object {
        fun fromStorage(value: String): MatchSideEffectKind = entries.single { it.storageValue == value }
    }
}

internal object MatchSideEffectPlans {
    /**
     * What a newly recorded Swipe decision owes: the like in the target's Likes You (likes only,
     * never passes) and, when it made a match, the match announcement.
     */
    fun forSwipe(
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        match: MatchRecord?,
        likeNewlyRecorded: Boolean = true,
    ): List<MatchSideEffect> =
        buildList {
            val eventType =
                when (action) {
                    SwipeAction.LIKE -> "swipe_like"
                    SwipeAction.PINEAPPLE_LIKE -> "pineapple_like"
                    else -> null
                }
            if (likeNewlyRecorded && eventType != null) {
                add(
                    MatchSideEffect(
                        id = UUID.randomUUID(),
                        kind = MatchSideEffectKind.SWIPE_INTEREST,
                        actorUserId = actorUserId,
                        targetUserId = targetUserId,
                        eventType = eventType,
                    ),
                )
            }
            if (match != null) add(matchAnnouncement(actorUserId, targetUserId, match.matchId))
        }

    fun matchAnnouncement(
        actorUserId: UUID,
        targetUserId: UUID,
        matchId: UUID,
    ): MatchSideEffect =
        MatchSideEffect(
            id = UUID.randomUUID(),
            kind = MatchSideEffectKind.MATCH,
            actorUserId = actorUserId,
            targetUserId = targetUserId,
            matchId = matchId,
        )
}
