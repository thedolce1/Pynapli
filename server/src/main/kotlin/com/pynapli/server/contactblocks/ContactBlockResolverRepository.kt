package com.pynapli.server.contactblocks

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A row still pointing at nobody (target_token IS NULL). */
data class PendingContactBlockRow(
    val rowId: UUID,
    val blockerUserId: UUID,
    val emailToken: String,
    val createdAt: Instant,
)

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

    /** Any recorded interaction that exempts the pair ([ContactBlockInteractionSql]). */
    suspend fun hadInteraction(
        blockerUserId: UUID,
        targetUserId: UUID,
        blockCreatedAt: Instant,
    ): Boolean

    /**
     * Sets the row's target token while it is still pending and [targetUserId] is still the live,
     * verified member holding [memberToken]. The member's user row is locked first (FOR SHARE), so a
     * concurrent account deletion (which updates that row first) either runs before, and this binds
     * nothing, or after, and clears what this set.
     */
    suspend fun bind(
        rowId: UUID,
        targetUserId: UUID,
        memberToken: String,
        targetToken: String,
    ): Boolean
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
            connection.prepareStatement(RECORD_TOKEN_SQL).use { statement ->
                statement.setString(1, memberToken)
                statement.setObject(2, userId)
                statement.setString(3, memberToken)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun hadInteraction(
        blockerUserId: UUID,
        targetUserId: UUID,
        blockCreatedAt: Instant,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.prepareStatement(ContactBlockInteractionSql.HAD_INTERACTION_SQL).use { statement ->
                statement.setObject(1, blockerUserId)
                statement.setObject(2, targetUserId)
                statement.setTimestamp(3, Timestamp.from(blockCreatedAt))
                statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }

    override suspend fun bind(
        rowId: UUID,
        targetUserId: UUID,
        memberToken: String,
        targetToken: String,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.inTransaction { bindLocked(rowId, targetUserId, memberToken, targetToken) }
        }

    private fun Connection.bindLocked(
        rowId: UUID,
        targetUserId: UUID,
        memberToken: String,
        targetToken: String,
    ): Boolean {
        val stillOwner =
            prepareStatement(LOCK_TARGET_SQL).use { statement ->
                statement.setObject(1, targetUserId)
                statement.setString(2, memberToken)
                statement.executeQuery().use { rs -> rs.next() }
            }
        if (!stillOwner) return false
        return prepareStatement(BIND_SQL).use { statement ->
            statement.setString(1, targetToken)
            statement.setObject(2, rowId)
            statement.executeUpdate() > 0
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
        val LIST_PENDING_SQL =
            """
            SELECT id, blocker_user_id, email_token, created_at
            FROM contact_blocks
            WHERE target_token IS NULL
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
            """.trimIndent()
    }
}

/**
 * "Interaction" between a blocker A and the member X who owns the address (TRUST_AND_SAFETY_SPEC,
 * "Contact blocks", Interaction). The pair is exempt, and left completely alone, when:
 *
 *  1. any of these rows exists between them, in either direction, from BEFORE the block was added
 *     (`contact_blocks.created_at`): a profile view or any other Activity row (flirt, like, Pineapple
 *     Like, match, album request or grant, partner chat invite, Psync and Playing Tonight rows: all
 *     live in activity_events), a like or Pineapple Like (a pass produces no visible signal and is
 *     ignored), a shared conversation, a favourite, a partner link or link invite, an ordinary block,
 *     a private album request or grant, a Psync choice; or
 *  2. at ANY time up to resolution, X did something A can see: the same rows initiated by X (or a
 *     match, or a conversation X started or wrote in). Otherwise binding would stop something A was
 *     already seeing from X ("they stopped viewing me"), which would confirm the block.
 *
 * Rows A created toward X after adding the address don't exempt the pair: A's own actions after the
 * add are exactly what the feature hides.
 *
 * Parameters: blocker id, target id, block created_at.
 */
internal object ContactBlockInteractionSql {
    val HAD_INTERACTION_SQL =
        """
        WITH p AS (SELECT ?::uuid AS a, ?::uuid AS x, ?::timestamptz AS t)
        SELECT (
            EXISTS (
                SELECT 1 FROM activity_events ae
                WHERE (ae.actor_user_id = p.a AND ae.recipient_user_id = p.x AND ae.first_occurred_at < p.t)
                   OR (ae.actor_user_id = p.x AND ae.recipient_user_id = p.a)
            )
            OR EXISTS (
                SELECT 1 FROM swipe_actions sa
                WHERE sa.action <> 'pass'
                  AND (
                      (sa.actor_user_id = p.a AND sa.target_user_id = p.x AND sa.created_at < p.t)
                      OR (sa.actor_user_id = p.x AND sa.target_user_id = p.a)
                  )
            )
            OR EXISTS (
                SELECT 1 FROM matches m
                WHERE m.user_a = LEAST(p.a, p.x) AND m.user_b = GREATEST(p.a, p.x)
            )
            OR EXISTS (
                SELECT 1
                FROM conversation_participants cpa
                JOIN conversation_participants cpx
                  ON cpx.conversation_id = cpa.conversation_id
                 AND cpx.user_id = p.x
                JOIN conversations c ON c.id = cpa.conversation_id
                WHERE cpa.user_id = p.a
                  AND (
                      c.created_at < p.t
                      OR c.created_by_user_id = p.x
                      OR EXISTS (
                          SELECT 1 FROM messages msg
                          WHERE msg.conversation_id = c.id
                            AND msg.sender_user_id = p.x
                      )
                  )
            )
            OR EXISTS (
                SELECT 1 FROM favorite_profiles f
                WHERE (f.user_id = p.a AND f.favorite_user_id = p.x AND f.created_at < p.t)
                   OR (f.user_id = p.x AND f.favorite_user_id = p.a)
            )
            OR EXISTS (
                SELECT 1 FROM user_profile_links l
                WHERE (l.user_id = p.a AND l.linked_user_id = p.x AND l.created_at < p.t)
                   OR (l.user_id = p.x AND l.linked_user_id = p.a)
            )
            OR EXISTS (
                SELECT 1 FROM user_blocks b
                WHERE (b.blocker_user_id = p.a AND b.blocked_user_id = p.x AND b.created_at < p.t)
                   OR (b.blocker_user_id = p.x AND b.blocked_user_id = p.a)
            )
            OR EXISTS (
                SELECT 1 FROM media_album_access_requests r
                WHERE (r.requester_user_id = p.a AND r.owner_user_id = p.x AND r.requested_at < p.t)
                   OR (r.requester_user_id = p.x AND r.owner_user_id = p.a)
            )
            OR EXISTS (
                SELECT 1 FROM media_album_access_grants g
                WHERE (g.owner_user_id = p.a AND g.grantee_user_id = p.x AND g.granted_at < p.t)
                   OR (g.owner_user_id = p.x AND g.grantee_user_id = p.a)
            )
            OR EXISTS (
                SELECT 1 FROM psync_session_actions pa
                WHERE (pa.actor_user_id = p.a AND pa.target_user_id = p.x AND pa.created_at < p.t)
                   OR (pa.actor_user_id = p.x AND pa.target_user_id = p.a)
            )
        ) AS interacted
        FROM p
        """.trimIndent()
}
