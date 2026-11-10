package com.pynapli.server.psync

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
): List<PsyncResolvedMatch> {
    val initiator = session.initiatorUserId
    val partner =
        session.participants.firstOrNull { it.userId != initiator }?.userId
            ?: return emptyList() // a session always has two participants; nothing to resolve otherwise
    val targets = deckTargetIds(session.id)
    val likedByInitiator = likedTargetsByActor(session.id, initiator)
    val likedByPartner = likedTargetsByActor(session.id, partner)

    return buildList {
        for (target in targets) {
            val aLiked = target in likedByInitiator
            val bLiked = target in likedByPartner
            if (!aLiked && !bLiked) continue
            // Blocks override all match state.
            if (blockedBetween(target, initiator) || blockedBetween(target, partner)) continue

            val tLikesA = targetLikesPartner(target, initiator)
            val tLikesB = targetLikesPartner(target, partner)

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
}

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
): Set<UUID> =
    prepareStatement(
        """
        SELECT target_user_id
        FROM psync_session_actions
        WHERE session_id = ? AND actor_user_id = ? AND action IN ('like', 'pineapple_like')
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.setObject(2, actorUserId)
        statement.executeQuery().use { rs ->
            buildSet { while (rs.next()) add(rs.getObject("target_user_id", UUID::class.java)) }
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
