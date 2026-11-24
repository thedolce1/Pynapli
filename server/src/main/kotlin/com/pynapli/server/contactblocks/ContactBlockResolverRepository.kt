package com.pynapli.server.contactblocks

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.lockPairForTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.swipe.SWIPE_PAIR_LOCK_NAMESPACE
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A row still pointing at nobody (target_token IS NULL, not released). */
data class PendingContactBlockRow(
    val rowId: UUID,
    val blockerUserId: UUID,
    val emailToken: String,
    val createdAt: Instant,
)

enum class ContactBlockSettleOutcome {
    BOUND,
    EXEMPT,

    /** Nothing changed: the row isn't pending any more, or the member no longer holds the token. */
    UNCHANGED,
}

/**
 * Off-request storage for the resolver: pending rows, verified members by their contact token, the
 * prior-interaction check, and binding. Never called from the blocker's own requests.
 */
interface ContactBlockResolverRepository {
    /**
     * Pending rows created at or after [createdSince], in (created_at, id) order, after [after] when
     * given (keyset paging).
     */
    suspend fun listPendingRows(
        createdSince: Instant,
        after: PendingContactBlockRow?,
        limit: Int,
    ): List<PendingContactBlockRow>

    /** Pending rows of one address. */
    suspend fun listPendingForAddress(emailToken: String): List<PendingContactBlockRow>

    /** Live, verified members whose `users.contact_token` is one of [memberTokens]: token -> member id. */
    suspend fun findVerifiedMembers(memberTokens: Collection<String>): Map<String, UUID>

    /** Stores [memberToken] as the contact token of a live, verified account (no write when it's already there). */
    suspend fun recordContactToken(
        userId: UUID,
        memberToken: String,
    )

    /**
     * In ONE transaction, with `lock_timeout` 2 s: locks the member's user row (FOR SHARE: a concurrent
     * account deletion either runs first and nothing binds, or after and clears what this set), takes
     * the pair advisory lock ([SWIPE_PAIR_LOCK_NAMESPACE]) that [ContactBlockVisibility.noteActToward]
     * also takes (and Swipe likes take around their own write), checks [ContactBlockInteractionSql]
     * under it, and writes [exemptToken] or [targetToken]. An act by the member toward the blocker that
     * committed before the check is seen here (exempt); one that commits after it is followed by
     * noteActToward, which waits for this transaction and then finds the bound row and flips it.
     */
    suspend fun settle(
        row: PendingContactBlockRow,
        targetUserId: UUID,
        memberToken: String,
        targetToken: String,
        exemptToken: String,
    ): ContactBlockSettleOutcome

    /**
     * Claims the full sweep: true (and records an attempt at [now]) when the last SUCCESS was before
     * [now] minus [every] and no attempt was made in the last [retryAfter]. One statement, so two
     * replicas can't both claim it. A failed sweep is retried after [retryAfter], not a day later.
     */
    suspend fun claimFullSweep(
        now: Instant,
        every: java.time.Duration,
        retryAfter: java.time.Duration = java.time.Duration.ofHours(1),
    ): Boolean

    /** Records a completed full sweep. */
    suspend fun recordFullSweepSuccess(now: Instant)
}

class JdbcContactBlockResolverRepository(
    private val dataSource: DataSource,
) : ContactBlockResolverRepository {
    override suspend fun listPendingRows(
        createdSince: Instant,
        after: PendingContactBlockRow?,
        limit: Int,
    ): List<PendingContactBlockRow> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(LIST_PENDING_SQL).use { statement ->
                val afterCreated = after?.createdAt?.let(Timestamp::from)
                statement.setTimestamp(1, Timestamp.from(createdSince))
                statement.setTimestamp(2, afterCreated)
                statement.setTimestamp(3, afterCreated)
                statement.setObject(4, after?.rowId)
                statement.setInt(5, limit)
                statement.executeQuery().use { rs -> rs.pendingRows() }
            }
        }

    override suspend fun listPendingForAddress(emailToken: String): List<PendingContactBlockRow> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(LIST_PENDING_FOR_ADDRESS_SQL).use { statement ->
                statement.setString(1, emailToken)
                statement.executeQuery().use { rs -> rs.pendingRows() }
            }
        }

    override suspend fun findVerifiedMembers(memberTokens: Collection<String>): Map<String, UUID> {
        if (memberTokens.isEmpty()) return emptyMap()
        return dataSource.withConnection { connection ->
            connection.prepareStatement(FIND_MEMBERS_SQL).use { statement ->
                statement.setArray(1, connection.createArrayOf("text", memberTokens.distinct().toTypedArray()))
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString("contact_token"), rs.getObject("id", UUID::class.java))
                    }
                }
            }
        }
    }

    override suspend fun recordContactToken(
        userId: UUID,
        memberToken: String,
    ) {
        dataSource.withConnection { connection ->
            connection.inTransaction {
                // Runs inside sign-in and verify too: a held user row fails fast instead of stalling.
                createStatement().use { it.execute("SET LOCAL lock_timeout = '$SETTLE_LOCK_TIMEOUT'") }
                prepareStatement(RECORD_TOKEN_SQL).use { statement ->
                    statement.setString(1, memberToken)
                    statement.setObject(2, userId)
                    statement.setString(3, memberToken)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun settle(
        row: PendingContactBlockRow,
        targetUserId: UUID,
        memberToken: String,
        targetToken: String,
        exemptToken: String,
    ): ContactBlockSettleOutcome =
        dataSource.withConnection { connection ->
            connection.inTransaction { settleLocked(row, targetUserId, memberToken, targetToken, exemptToken) }
        }

    private fun Connection.settleLocked(
        row: PendingContactBlockRow,
        targetUserId: UUID,
        memberToken: String,
        targetToken: String,
        exemptToken: String,
    ): ContactBlockSettleOutcome {
        // Bounded: this runs inside sign-in and verify, so a held row or pair lock must fail fast (an
        // SQL error ContactBlockSettling turns into a worker retry), never stall the request.
        createStatement().use { it.execute("SET LOCAL lock_timeout = '$SETTLE_LOCK_TIMEOUT'") }
        val stillOwner =
            prepareStatement(LOCK_TARGET_SQL).use { statement ->
                statement.setObject(1, targetUserId)
                statement.setString(2, memberToken)
                statement.executeQuery().use { rs -> rs.next() }
            }
        if (!stillOwner) return ContactBlockSettleOutcome.UNCHANGED
        val exempt =
            if (row.blockerUserId == targetUserId) {
                true
            } else {
                lockPairForTransaction(SWIPE_PAIR_LOCK_NAMESPACE, row.blockerUserId, targetUserId)
                prepareStatement(ContactBlockInteractionSql.HAD_INTERACTION_SQL).use { statement ->
                    statement.setObject(1, row.blockerUserId)
                    statement.setObject(2, targetUserId)
                    statement.setTimestamp(3, Timestamp.from(row.createdAt))
                    statement.setString(4, targetToken)
                    statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
            }
        val written =
            prepareStatement(BIND_SQL).use { statement ->
                statement.setString(1, if (exempt) exemptToken else targetToken)
                statement.setObject(2, row.rowId)
                statement.executeUpdate() > 0
            }
        return when {
            !written -> ContactBlockSettleOutcome.UNCHANGED
            exempt -> ContactBlockSettleOutcome.EXEMPT
            else -> ContactBlockSettleOutcome.BOUND
        }
    }

    override suspend fun claimFullSweep(
        now: Instant,
        every: java.time.Duration,
        retryAfter: java.time.Duration,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.prepareStatement(CLAIM_FULL_SWEEP_SQL).use { statement ->
                statement.setString(1, FULL_SWEEP_JOB)
                statement.setTimestamp(2, Timestamp.from(now))
                statement.setTimestamp(3, Timestamp.from(now.minus(every)))
                statement.setTimestamp(4, Timestamp.from(now.minus(retryAfter)))
                statement.executeQuery().use { rs -> rs.next() }
            }
        }

    override suspend fun recordFullSweepSuccess(now: Instant) {
        dataSource.withConnection { connection ->
            connection.prepareStatement(RECORD_SWEEP_SUCCESS_SQL).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setString(2, FULL_SWEEP_JOB)
                statement.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.pendingRows(): List<PendingContactBlockRow> =
        buildList {
            while (next()) {
                add(
                    PendingContactBlockRow(
                        rowId = getObject("id", UUID::class.java),
                        blockerUserId = getObject("blocker_user_id", UUID::class.java),
                        emailToken = getString("email_token"),
                        createdAt = getTimestamp("created_at").toInstant(),
                    ),
                )
            }
        }

    private companion object {
        const val FULL_SWEEP_JOB = "contact-block-full-sweep"
        const val SETTLE_LOCK_TIMEOUT = "2s"

        val LIST_PENDING_SQL =
            """
            SELECT id, blocker_user_id, email_token, created_at
            FROM contact_blocks
            WHERE target_token IS NULL
              AND released_at IS NULL
              AND created_at >= ?::timestamptz
              AND (?::timestamptz IS NULL OR (created_at, id) > (?::timestamptz, ?::uuid))
            ORDER BY created_at ASC, id ASC
            LIMIT ?
            """.trimIndent()

        val LIST_PENDING_FOR_ADDRESS_SQL =
            """
            SELECT id, blocker_user_id, email_token, created_at
            FROM contact_blocks
            WHERE email_token = ?
              AND target_token IS NULL
              AND released_at IS NULL
            ORDER BY created_at ASC, id ASC
            """.trimIndent()

        val FIND_MEMBERS_SQL =
            """
            SELECT id, contact_token
            FROM users
            WHERE contact_token = ANY (?)
              AND email_verified_at IS NOT NULL
              AND deleted_at IS NULL
              AND status <> 'deleted'
            """.trimIndent()

        val RECORD_TOKEN_SQL =
            """
            UPDATE users
            SET contact_token = ?
            WHERE id = ?
              AND email_verified_at IS NOT NULL
              AND deleted_at IS NULL
              AND contact_token IS DISTINCT FROM ?
            """.trimIndent()

        val LOCK_TARGET_SQL =
            """
            SELECT 1
            FROM users
            WHERE id = ?
              AND contact_token = ?
              AND email_verified_at IS NOT NULL
              AND deleted_at IS NULL
              AND status <> 'deleted'
            FOR SHARE
            """.trimIndent()

        val BIND_SQL =
            """
            UPDATE contact_blocks
            SET target_token = ?
            WHERE id = ?
              AND target_token IS NULL
              AND released_at IS NULL
            """.trimIndent()

        val CLAIM_FULL_SWEEP_SQL =
            """
            INSERT INTO contact_block_job_state AS s (job_name, last_full_sweep_at)
            VALUES (?, ?::timestamptz)
            ON CONFLICT (job_name) DO UPDATE
            SET last_full_sweep_at = EXCLUDED.last_full_sweep_at
            WHERE COALESCE(s.last_success_at, '-infinity'::timestamptz) < ?::timestamptz
              AND s.last_full_sweep_at < ?::timestamptz
            RETURNING 1
            """.trimIndent()

        val RECORD_SWEEP_SUCCESS_SQL =
            "UPDATE contact_block_job_state SET last_success_at = ?::timestamptz WHERE job_name = ?"
    }
}

/**
 * "Interaction" between a blocker A and the member X who owns the address (TRUST_AND_SAFETY_SPEC,
 * "Contact Blocks", Interaction). The pair is exempt, and left completely alone, only when X did
 * something A could already observe, at any time up to resolution:
 *
 *  - an Activity row X caused for A (a profile view A can see, a flirt, a like, a Pineapple Like, an
 *    album request or grant, a partner chat invite, a Psync or Playing Tonight row);
 *  - a like or Pineapple Like by X on A (a pass produces no visible signal);
 *  - a match between them that X could have seen (not one made while A was hiding from X, which X was
 *    never shown: a Psync together match, or a raced like, during an earlier bound period);
 *  - a conversation holding both of them in which X has ever written or read: durable, so the
 *    retention purge of disappearing messages can't erase it (V121 `first_written_at`, set by a
 *    trigger on every visible message; `last_read_at` moved past X's joining);
 *  - a partner link invite from X, a private album request from X, an album grant by X;
 *  - a Psync like by X on A.
 *
 * Nothing A did toward X, before or after adding the address, ever exempts the pair: if X never did
 * anything A could see, binding changes nothing A observes, so there is nothing to protect. What A
 * did that X already saw disappears from X's views once bound, like everything else from A.
 * Favourites are private to the person who stars, so X's favourite of A doesn't count, and an empty
 * conversation X opened is invisible to A until X writes in it.
 *
 * Parameters: blocker id, target id, block created_at (unused), the target's own target token.
 */
internal object ContactBlockInteractionSql {
    val HAD_INTERACTION_SQL =
        """
        WITH p AS (SELECT ?::uuid AS a, ?::uuid AS x, ?::timestamptz AS t, ?::text AS xt)
        SELECT (
            EXISTS (
                SELECT 1 FROM activity_events ae
                WHERE ae.actor_user_id = p.x AND ae.recipient_user_id = p.a
            )
            OR EXISTS (
                SELECT 1 FROM swipe_actions sa
                WHERE sa.actor_user_id = p.x AND sa.target_user_id = p.a AND sa.action <> 'pass'
            )
            OR EXISTS (
                SELECT 1 FROM matches m
                WHERE m.user_a = LEAST(p.a, p.x) AND m.user_b = GREATEST(p.a, p.x)
                  AND NOT EXISTS (
                      SELECT 1 FROM contact_blocks earlier
                      WHERE earlier.target_token = p.xt
                        AND earlier.blocker_user_id = p.a
                        AND earlier.released_at IS NOT NULL
                        AND m.created_at >= earlier.created_at
                        AND m.created_at < earlier.released_at
                  )
            )
            OR EXISTS (
                SELECT 1
                FROM conversation_participants cpa
                JOIN conversation_participants cpx
                  ON cpx.conversation_id = cpa.conversation_id
                 AND cpx.user_id = p.x
                WHERE cpa.user_id = p.a
                  AND (
                      cpx.first_written_at IS NOT NULL
                      OR cpx.last_read_at > cpx.joined_at
                      OR EXISTS (
                          SELECT 1 FROM messages msg
                          WHERE msg.conversation_id = cpa.conversation_id
                            AND msg.sender_user_id = p.x
                      )
                  )
            )
            OR EXISTS (
                SELECT 1 FROM user_profile_links l
                WHERE l.user_id = p.x AND l.linked_user_id = p.a
            )
            OR EXISTS (
                SELECT 1 FROM media_album_access_requests r
                WHERE r.requester_user_id = p.x AND r.owner_user_id = p.a
            )
            OR EXISTS (
                SELECT 1 FROM media_album_access_grants g
                WHERE g.owner_user_id = p.x AND g.grantee_user_id = p.a
            )
            OR EXISTS (
                SELECT 1 FROM psync_session_actions pa
                WHERE pa.actor_user_id = p.x AND pa.target_user_id = p.a AND pa.action <> 'pass'
            )
        ) AS interacted
        FROM p
        """.trimIndent()
}
