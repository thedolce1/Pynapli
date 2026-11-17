package com.pynapli.server.psync

import com.pynapli.server.db.lockPairsForTransaction
import com.pynapli.server.swipe.SWIPE_PAIR_LOCK_NAMESPACE
import java.sql.Connection
import java.util.UUID

/*
 * Match-resolution JDBC operations for JdbcPsyncSessionRepository, extracted to keep the
 * repository class focused on the PsyncSessionRepository interface methods. These `Connection`
 * extensions turn a completed deck into together/solo/no-match outcomes and persist them; they hold
 * no reference to the repository's `json` instance or status constants. Behavior matches the
 * original private members verbatim.
 */

/**
 * Resolve every deck target into a together / solo / no-match outcome and persist it.
 * Together: both partners liked + target is together-open + target reciprocated (liked >=1
 * partner). Otherwise each partner who liked a target that liked them back gets a solo
 * passthrough match. Blocks (either direction, either partner) skip the target entirely.
 */
internal fun Connection.resolveMatches(
    session: PsyncSession,
    matchIdFactory: () -> UUID,
): PsyncResolution {
    val initiator = session.initiatorUserId
    val partner =
        session.participants.firstOrNull { it.userId != initiator }?.userId
            // a session always has two participants; nothing to resolve otherwise
            ?: return PsyncResolution(emptyList(), emptyList())
    val targets = deckTargetIds(session.id)
    val likedByInitiator = likedTargetsByActor(session.id, initiator)
    val likedByPartner = likedTargetsByActor(session.id, partner)
    // The target may be liking a partner from Swipe right now. Take the same per-pair lock Swipe
    // takes before any check or insert below, or each side could miss the other's like. All of
    // them up front and in sorted order, so two overlapping resolves can't deadlock.
    lockPairsForTransaction(
        SWIPE_PAIR_LOCK_NAMESPACE,
        likedByInitiator.keys.map { initiator to it } + likedByPartner.keys.map { partner to it },
    )
    val delivered = mutableListOf<PsyncDeliveredLike>()

    val matches =
        buildList {
            for (target in targets) {
                val aLike = likedByInitiator[target]
                val bLike = likedByPartner[target]
                val aLiked = aLike != null
                val bLiked = bLike != null
                if (!aLiked && !bLiked) continue
                // Blocks override all match state, and a blocked like is never delivered.
                if (blockedBetween(target, initiator) || blockedBetween(target, partner)) continue

                val tLikesA = targetLikesPartner(target, initiator)
                val tLikesB = targetLikesPartner(target, partner)

                // Deliver each partner's like as a normal swipe like so the target can see it and
                // like back later. A like the target has not answered yet goes to their Likes You.
                if (aLike != null && deliverPsyncLike(session.id, session.travelLocationId, initiator, target, aLike) && !tLikesA) {
                    delivered += PsyncDeliveredLike(initiator, target, aLike.action)
                }
                if (bLike != null && deliverPsyncLike(session.id, session.travelLocationId, partner, target, bLike) && !tLikesB) {
                    delivered += PsyncDeliveredLike(partner, target, bLike.action)
                }

                if (aLiked && bLiked && togetherEnabled(target) && (tLikesA || tLikesB)) {
                    val matchA = upsertMatch(initiator, target, matchIdFactory)
                    val matchB = upsertMatch(partner, target, matchIdFactory)
                    insertSessionMatch(session.id, target, kind = "together", ownerUserId = null, matchId = matchB)
                    add(PsyncResolvedMatch(initiator, target, matchA, "together"))
                    add(PsyncResolvedMatch(partner, target, matchB, "together"))
                    continue
                }
                // Solo passthrough(s): each liking partner the target liked back.
                if (aLiked && tLikesA) {
                    val matchId = upsertMatch(initiator, target, matchIdFactory)
                    insertSessionMatch(session.id, target, kind = "solo", ownerUserId = initiator, matchId = matchId)
                    add(PsyncResolvedMatch(initiator, target, matchId, "solo"))
                }
                if (bLiked && tLikesB) {
                    val matchId = upsertMatch(partner, target, matchIdFactory)
                    insertSessionMatch(session.id, target, kind = "solo", ownerUserId = partner, matchId = matchId)
                    add(PsyncResolvedMatch(partner, target, matchId, "solo"))
                }
            }
        }
    // A like that resolved into a match (a together match can include a partner the target never
    // liked individually) is not also announced as a pending like.
    val matchedPairs = matches.map { it.partnerUserId to it.targetUserId }.toSet()
    return PsyncResolution(matches, delivered.filterNot { (it.actorUserId to it.targetUserId) in matchedPairs })
}

/**
 * Write a partner's Psync like into `swipe_actions`, tagged with the session it came from. Returns
 * false when the partner already has a live decision on this target from the ordinary Swipe deck;
 * that earlier decision stands and nothing new is delivered.
 */
internal fun Connection.deliverPsyncLike(
    sessionId: UUID,
    // The travel destination the session was dealt at, so the like follows the same 24-hour
    // after-leaving rule as a like sent from Travel mode in Swipe.
    travelLocationId: UUID?,
    actorUserId: UUID,
    targetUserId: UUID,
    like: PsyncLike,
): Boolean =
    prepareStatement(
        """
        INSERT INTO swipe_actions (
            id, actor_user_id, target_user_id, action, psync_session_id, consumed_inventory_id, travel_location_id
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, actorUserId)
        statement.setObject(3, targetUserId)
        statement.setString(4, like.action)
        statement.setObject(5, sessionId)
        // Carried over so a later Swipe rewind of this Pineapple Like refunds the row that paid.
        statement.setObject(6, like.consumedInventoryId)
        statement.setObject(7, travelLocationId)
        statement.executeUpdate() > 0
    }

/** A partner's like on a deck target: `like` or `pineapple_like`, plus the inventory row it spent. */
internal data class PsyncLike(
    val action: String,
    val consumedInventoryId: UUID?,
)

internal fun Connection.participantIdsForSessions(sessionIds: List<UUID>): Map<UUID, List<UUID>> =
    prepareStatement(
        "SELECT session_id, user_id FROM psync_session_participants WHERE session_id = ANY(?)",
    ).use { statement ->
        statement.setArray(1, createArrayOf("uuid", sessionIds.toTypedArray()))
        statement.executeQuery().use { rs ->
            buildMap<UUID, MutableList<UUID>> {
                while (rs.next()) {
                    val sessionId = rs.getObject("session_id", UUID::class.java)
                    val userId = rs.getObject("user_id", UUID::class.java)
                    getOrPut(sessionId) { mutableListOf() }.add(userId)
                }
            }
        }
    }

internal fun Connection.deckTargetIds(sessionId: UUID): List<UUID> =
    prepareStatement(
        "SELECT target_user_id FROM psync_session_cards WHERE session_id = ? ORDER BY position ASC",
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.getObject("target_user_id", UUID::class.java)) }
        }
    }

internal fun Connection.likedTargetsByActor(
    sessionId: UUID,
    actorUserId: UUID,
): Map<UUID, PsyncLike> =
    prepareStatement(
        """
        SELECT target_user_id, action, consumed_inventory_id
        FROM psync_session_actions
        WHERE session_id = ? AND actor_user_id = ? AND action IN ('like', 'pineapple_like')
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.setObject(2, actorUserId)
        statement.executeQuery().use { rs ->
            buildMap {
                while (rs.next()) {
                    put(
                        rs.getObject("target_user_id", UUID::class.java),
                        PsyncLike(rs.getString("action"), rs.getObject("consumed_inventory_id", UUID::class.java)),
                    )
                }
            }
        }
    }

internal fun Connection.togetherEnabled(userId: UUID): Boolean =
    prepareStatement(
        "SELECT COALESCE(together_enabled, false) FROM discovery_settings WHERE user_id = ?",
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs -> if (!rs.next()) false else rs.getBoolean(1) }
    }

internal fun Connection.targetLikesPartner(
    targetUserId: UUID,
    partnerUserId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1 FROM swipe_actions
            WHERE actor_user_id = ? AND target_user_id = ?
              -- Same definition of a live like as the Swipe match query (JdbcSwipeActionSql):
              -- a Pineapple Like counts, a rewound like does not.
              AND action IN ('like', 'pineapple_like')
              AND rewound_at IS NULL
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, targetUserId)
        statement.setObject(2, partnerUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

internal fun Connection.blockedBetween(
    userA: UUID,
    userB: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1 FROM user_blocks
            WHERE (blocker_user_id = ? AND blocked_user_id = ?)
               OR (blocker_user_id = ? AND blocked_user_id = ?)
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userA)
        statement.setObject(2, userB)
        statement.setObject(3, userB)
        statement.setObject(4, userA)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

internal fun Connection.upsertMatch(
    actorUserId: UUID,
    targetUserId: UUID,
    matchIdFactory: () -> UUID,
): UUID {
    val (userA, userB) =
        if (actorUserId.toString() < targetUserId.toString()) {
            actorUserId to targetUserId
        } else {
            targetUserId to actorUserId
        }
    return prepareStatement(
        """
        INSERT INTO matches (id, user_a, user_b)
        VALUES (?, ?, ?)
        ON CONFLICT (user_a, user_b) DO UPDATE SET user_a = EXCLUDED.user_a
        RETURNING id
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, matchIdFactory())
        statement.setObject(2, userA)
        statement.setObject(3, userB)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getObject("id", UUID::class.java)
        }
    }
}

internal fun Connection.insertSessionMatch(
    sessionId: UUID,
    targetUserId: UUID,
    kind: String,
    ownerUserId: UUID?,
    matchId: UUID,
) {
    prepareStatement(
        """
        INSERT INTO psync_session_matches (id, session_id, target_user_id, kind, owner_user_id, match_id)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, sessionId)
        statement.setObject(3, targetUserId)
        statement.setString(4, kind)
        statement.setObject(5, ownerUserId)
        statement.setObject(6, matchId)
        statement.executeUpdate()
    }
}
