package com.pynapli.server.swipe

import com.pynapli.server.db.inTransaction
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * How long the request that committed a side effect has to deliver it before the drainer may. Long
 * enough that a healthy request always wins (delivery takes milliseconds), short enough that a
 * crashed one is repaired within about a minute.
 */
internal val MATCH_SIDE_EFFECT_FAST_PATH_LEASE: Duration = Duration.ofSeconds(60)

/**
 * Writes [effects] into the outbox inside the caller's open transaction. Each row starts leased to
 * the committing request for [MATCH_SIDE_EFFECT_FAST_PATH_LEASE], so the drainer leaves it alone
 * while that request delivers it.
 */
internal fun Connection.insertMatchSideEffects(
    effects: List<MatchSideEffect>,
    now: Instant,
) {
    if (effects.isEmpty()) return
    prepareStatement(
        """
        INSERT INTO match_side_effect_outbox (
            id, kind, actor_user_id, target_user_id, event_type, match_id,
            pair_user_id, pair_session_id, created_at, next_attempt_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        val leaseUntil = Timestamp.from(now.plus(MATCH_SIDE_EFFECT_FAST_PATH_LEASE))
        for (effect in effects) {
            statement.setObject(1, effect.id)
            statement.setString(2, effect.kind.storageValue)
            statement.setObject(3, effect.actorUserId)
            statement.setObject(4, effect.targetUserId)
            statement.setString(5, effect.eventType)
            statement.setObject(6, effect.matchId)
            statement.setObject(7, effect.pairUserId)
            statement.setObject(8, effect.pairSessionId)
            statement.setTimestamp(9, Timestamp.from(now))
            statement.setTimestamp(10, leaseUntil)
            statement.addBatch()
        }
        statement.executeBatch()
    }
}

internal fun Connection.markMatchSideEffectsDelivered(
    ids: Collection<UUID>,
    now: Instant,
) {
    if (ids.isEmpty()) return
    prepareStatement(
        """
        UPDATE match_side_effect_outbox
        SET delivered_at = ?, last_error = NULL
        WHERE id = ANY(?) AND delivered_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now))
        statement.setArray(2, createArrayOf("uuid", ids.toTypedArray()))
        statement.executeUpdate()
    }
}

/**
 * Claims up to [limit] pending rows whose lease or back-off has passed and that have not used up
 * [maxAttempts]: each claimed row is leased for [lease] and its attempt counted, in one committed
 * statement, so an overlapping drain (or a slow one) never delivers the same row twice at once.
 */
internal fun Connection.claimDueMatchSideEffects(
    now: Instant,
    limit: Int,
    lease: Duration,
    maxAttempts: Int,
): List<Pair<MatchSideEffect, Int>> =
    inTransaction {
        prepareStatement(
            """
            WITH due AS (
                SELECT id
                FROM match_side_effect_outbox
                WHERE delivered_at IS NULL
                  AND next_attempt_at <= ?
                  AND attempts < ?
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE match_side_effect_outbox o
            SET next_attempt_at = ?, attempts = o.attempts + 1
            FROM due
            WHERE o.id = due.id
            RETURNING o.id, o.kind, o.actor_user_id, o.target_user_id, o.event_type, o.match_id,
                      o.pair_user_id, o.pair_session_id, o.created_at, o.attempts
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setInt(2, maxAttempts)
            statement.setInt(3, limit)
            statement.setTimestamp(4, Timestamp.from(now.plus(lease)))
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(ClaimedRow(rs.toMatchSideEffect(), rs.getTimestamp("created_at").toInstant(), rs.getInt("attempts")))
                    }
                }
            }
        }
            // Oldest first, and within one transaction a like before the match it led to, as the
            // fast path delivers them.
            .sortedWith(compareBy({ it.createdAt }, { it.effect.kind.ordinal }))
            .map { it.effect to it.attempts }
    }

private class ClaimedRow(
    val effect: MatchSideEffect,
    val createdAt: Instant,
    val attempts: Int,
)

/** Records a failed attempt: the row is retried at [retryAt]. Only the exception class is kept. */
internal fun Connection.markMatchSideEffectFailed(
    id: UUID,
    errorClass: String,
    retryAt: Instant,
) {
    prepareStatement(
        """
        UPDATE match_side_effect_outbox
        SET next_attempt_at = ?, last_error = ?
        WHERE id = ? AND delivered_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(retryAt))
        statement.setString(2, errorClass.take(200))
        statement.setObject(3, id)
        statement.executeUpdate()
    }
}

/** Deletes delivered rows older than [deliveredBefore]; returns how many went. */
internal fun Connection.pruneDeliveredMatchSideEffects(deliveredBefore: Instant): Int =
    prepareStatement(
        "DELETE FROM match_side_effect_outbox WHERE delivered_at IS NOT NULL AND delivered_at < ?",
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(deliveredBefore))
        statement.executeUpdate()
    }

private fun ResultSet.toMatchSideEffect(): MatchSideEffect =
    MatchSideEffect(
        id = getObject("id", UUID::class.java),
        kind = MatchSideEffectKind.fromStorage(getString("kind")),
        actorUserId = getObject("actor_user_id", UUID::class.java),
        targetUserId = getObject("target_user_id", UUID::class.java),
        eventType = getString("event_type"),
        matchId = getObject("match_id") as UUID?,
        pairUserId = getObject("pair_user_id") as UUID?,
        pairSessionId = getObject("pair_session_id") as UUID?,
    )
