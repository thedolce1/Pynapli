package com.pynapli.server.contactblocks

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A pending row together with the verified member whose address it is. */
data class PendingContactBlockMatch(
    val rowId: UUID,
    val blockerUserId: UUID,
    val createdAt: Instant,
    val targetUserId: UUID,
)

/**
 * Off-request storage for the resolver: finding pending rows that now point at a verified member,
 * the prior-interaction check, and binding. Never called from the blocker's own requests.
 */
interface ContactBlockResolverRepository {
    /** Pending rows whose email token equals a live, verified member's `users.contact_token`. */
    suspend fun findPendingMatches(limit: Int): List<PendingContactBlockMatch>

    /** Pending rows of one address, matched to [targetUserId] (the member who just proved it). */
    suspend fun findPendingForAddress(
        emailToken: String,
        targetUserId: UUID,
    ): List<PendingContactBlockMatch>

    /**
     * Stores [contactToken] on a live, verified account. With [onlyIfUnset] it changes nothing when a
     * token is already there. True when the row changed.
     */
    suspend fun recordContactToken(
        userId: UUID,
        contactToken: String,
        onlyIfUnset: Boolean,
    ): Boolean

    /** Any recorded interaction that exempts the pair ([ContactBlockInteractionSql]). */
    suspend fun hadInteraction(
        blockerUserId: UUID,
        targetUserId: UUID,
        blockCreatedAt: Instant,
    ): Boolean

    /**
     * Sets the row's target token while it is still pending and the target is still the live,
     * verified owner of the address (the target's user row is locked first, so a concurrent account
     * deletion either runs before, and this binds nothing, or after, and clears what this set).
     */
    suspend fun bind(
        rowId: UUID,
        targetUserId: UUID,
        targetToken: String,
    ): Boolean
}

class JdbcContactBlockResolverRepository(
    private val dataSource: DataSource,
) : ContactBlockResolverRepository {
    override suspend fun findPendingMatches(limit: Int): List<PendingContactBlockMatch> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(FIND_PENDING_MATCHES_SQL).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PendingContactBlockMatch(
                                    rowId = rs.getObject("id", UUID::class.java),
                                    blockerUserId = rs.getObject("blocker_user_id", UUID::class.java),
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                    targetUserId = rs.getObject("target_user_id", UUID::class.java),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override suspend fun findPendingForAddress(
        emailToken: String,
        targetUserId: UUID,
    ): List<PendingContactBlockMatch> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(FIND_PENDING_FOR_ADDRESS_SQL).use { statement ->
                statement.setString(1, emailToken)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PendingContactBlockMatch(
                                    rowId = rs.getObject("id", UUID::class.java),
                                    blockerUserId = rs.getObject("blocker_user_id", UUID::class.java),
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                    targetUserId = targetUserId,
                                ),
                            )
                        }
                    }
                }
            }
        }

    override suspend fun recordContactToken(
        userId: UUID,
        contactToken: String,
        onlyIfUnset: Boolean,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.prepareStatement(if (onlyIfUnset) RECORD_TOKEN_IF_UNSET_SQL else RECORD_TOKEN_SQL).use { statement ->
                statement.setString(1, contactToken)
                statement.setObject(2, userId)
                statement.executeUpdate() > 0
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
        targetToken: String,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.inTransaction { bindLocked(rowId, targetUserId, targetToken) }
        }

    private fun Connection.bindLocked(
        rowId: UUID,
        targetUserId: UUID,
        targetToken: String,
    ): Boolean {
        val ownerToken =
            prepareStatement(LOCK_TARGET_SQL).use { statement ->
                statement.setObject(1, targetUserId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getString("contact_token") else null }
            } ?: return false
        return prepareStatement(BIND_SQL).use { statement ->
            statement.setString(1, targetToken)
            statement.setObject(2, rowId)
            statement.setString(3, ownerToken)
            statement.executeUpdate() > 0
        }
    }

    private companion object {
        val FIND_PENDING_MATCHES_SQL =
            """
            SELECT cb.id, cb.blocker_user_id, cb.created_at, u.id AS target_user_id
            FROM contact_blocks cb
            JOIN users u
              ON u.contact_token = cb.email_token
             AND u.email_verified_at IS NOT NULL
             AND u.deleted_at IS NULL
             AND u.status <> 'deleted'
            WHERE cb.target_token IS NULL
            ORDER BY cb.created_at ASC, cb.id ASC
            LIMIT ?
            """.trimIndent()

        val FIND_PENDING_FOR_ADDRESS_SQL =
            """
            SELECT id, blocker_user_id, created_at
            FROM contact_blocks
            WHERE email_token = ?
              AND target_token IS NULL
            ORDER BY created_at ASC, id ASC
            """.trimIndent()

        val RECORD_TOKEN_SQL =
            """
            UPDATE users
            SET contact_token = ?
            WHERE id = ?
              AND email_verified_at IS NOT NULL
              AND deleted_at IS NULL
            """.trimIndent()

        val RECORD_TOKEN_IF_UNSET_SQL =
            """
            UPDATE users
            SET contact_token = ?
            WHERE id = ?
              AND email_verified_at IS NOT NULL
              AND deleted_at IS NULL
              AND contact_token IS NULL
            """.trimIndent()

        val LOCK_TARGET_SQL =
            """
            SELECT contact_token
            FROM users
            WHERE id = ?
              AND email_verified_at IS NOT NULL
              AND deleted_at IS NULL
              AND status <> 'deleted'
              AND contact_token IS NOT NULL
            FOR SHARE
            """.trimIndent()

        val BIND_SQL =
            """
            UPDATE contact_blocks
            SET target_token = ?
            WHERE id = ?
              AND target_token IS NULL
              AND email_token = ?
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
