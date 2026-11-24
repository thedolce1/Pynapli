package com.pynapli.server.contactblocks

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The blocker's own rows. Every statement here touches `contact_blocks` only, keyed by the blocker:
 * nothing on the add/remove/count path reads `users` or anyone else's rows, so none of it can depend
 * on whether an address belongs to a member (ContactBlockRequestPathTest records the statements).
 *
 * Only rows with `released_at` NULL are the blocker's addresses (V120). Removing one runs ONE
 * statement whatever it is: a pending row is deleted, a settled one (bound or exempt) becomes a
 * tombstone (`released_at`), so nothing the blocker did before the removal ever reaches the person it
 * pointed at (an exempt tombstone points at nobody's lookup and changes nothing).
 */
interface ContactBlockRepository {
    /** One statement: inserts unless the blocker already has the address or [maxRows] addresses. */
    suspend fun add(
        id: UUID,
        blockerUserId: UUID,
        emailToken: String,
        createdAt: Instant,
        maxRows: Int,
    ): ContactBlockAddOutcome

    /** One statement whether or not the address was ever added, pending or settled. */
    suspend fun remove(
        blockerUserId: UUID,
        emailToken: String,
        releasedAt: Instant,
    )

    suspend fun removeAll(
        blockerUserId: UUID,
        releasedAt: Instant,
    )

    /** The blocker's addresses (tombstones never count). */
    suspend fun count(blockerUserId: UUID): Int
}

enum class ContactBlockAddOutcome {
    ADDED,
    ALREADY_ADDED,
    LIMIT_REACHED,
}

class JdbcContactBlockRepository(
    private val dataSource: DataSource,
) : ContactBlockRepository {
    override suspend fun add(
        id: UUID,
        blockerUserId: UUID,
        emailToken: String,
        createdAt: Instant,
        maxRows: Int,
    ): ContactBlockAddOutcome =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                // Serialises one blocker's adds so two at once can't pass the cap together.
                prepareStatement(LOCK_BLOCKER_SQL).use { statement ->
                    statement.setString(1, "contact-blocks:$blockerUserId")
                    statement.executeQuery().close()
                }
                prepareStatement(ADD_SQL).use { statement ->
                    statement.setString(1, emailToken) // existing.present
                    statement.setObject(2, blockerUserId) // existing.total
                    statement.setObject(3, id)
                    statement.setObject(4, blockerUserId)
                    statement.setString(5, emailToken)
                    statement.setTimestamp(6, Timestamp.from(createdAt))
                    statement.setInt(7, maxRows)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        when {
                            rs.getInt("inserted") > 0 -> ContactBlockAddOutcome.ADDED
                            rs.getBoolean("present") -> ContactBlockAddOutcome.ALREADY_ADDED
                            else -> ContactBlockAddOutcome.LIMIT_REACHED
                        }
                    }
                }
            }
        }

    override suspend fun remove(
        blockerUserId: UUID,
        emailToken: String,
        releasedAt: Instant,
    ) {
        dataSource.withConnection { connection ->
            connection.prepareStatement(REMOVE_SQL).use { statement ->
                statement.setTimestamp(1, Timestamp.from(releasedAt))
                statement.setObject(2, blockerUserId)
                statement.setString(3, emailToken)
                statement.setObject(4, blockerUserId)
                statement.setString(5, emailToken)
                statement.executeQuery().close()
            }
        }
    }

    override suspend fun removeAll(
        blockerUserId: UUID,
        releasedAt: Instant,
    ) {
        dataSource.withConnection { connection ->
            connection.prepareStatement(REMOVE_ALL_SQL).use { statement ->
                statement.setTimestamp(1, Timestamp.from(releasedAt))
                statement.setObject(2, blockerUserId)
                statement.setObject(3, blockerUserId)
                statement.executeQuery().close()
            }
        }
    }

    override suspend fun count(blockerUserId: UUID): Int =
        dataSource.withConnection { connection ->
            connection.prepareStatement(COUNT_SQL).use { statement ->
                statement.setObject(1, blockerUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    internal companion object {
        const val LOCK_BLOCKER_SQL = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"

        val ADD_SQL =
            """
            WITH existing AS (
                SELECT COUNT(*) AS total,
                       COALESCE(BOOL_OR(email_token = ?::text), false) AS present
                FROM contact_blocks
                WHERE blocker_user_id = ?
                  AND released_at IS NULL
            ),
            inserted AS (
                INSERT INTO contact_blocks (id, blocker_user_id, email_token, created_at)
                SELECT ?::uuid, ?::uuid, ?::text, ?::timestamptz
                FROM existing
                WHERE NOT existing.present
                  AND existing.total < ?
                ON CONFLICT (blocker_user_id, email_token) WHERE released_at IS NULL DO NOTHING
                RETURNING 1
            )
            SELECT (SELECT COUNT(*) FROM inserted) AS inserted,
                   existing.present AS present
            FROM existing
            """.trimIndent()

        val REMOVE_SQL =
            """
            WITH released AS (
                UPDATE contact_blocks
                SET released_at = ?::timestamptz
                WHERE blocker_user_id = ?
                  AND email_token = ?
                  AND released_at IS NULL
                  AND target_token IS NOT NULL
                RETURNING id, blocker_user_id, target_token
            ),
            pruned AS (
                -- Older tombstones of the same pair hide acts before an earlier time (subsumed); beyond the newest few they go.
                DELETE FROM contact_blocks old
                USING released
                WHERE old.blocker_user_id = released.blocker_user_id
                  AND old.target_token = released.target_token
                  AND old.released_at IS NOT NULL
                  AND old.id <> released.id
                  -- Keep the newest few: their windows still tell 'a match X was never shown' apart (Interaction).
                  AND old.id NOT IN (
                      SELECT keep.id FROM contact_blocks keep
                      WHERE keep.blocker_user_id = old.blocker_user_id AND keep.target_token = old.target_token AND keep.released_at IS NOT NULL
                      ORDER BY keep.released_at DESC
                      LIMIT $TOMBSTONES_KEPT_BEFORE_NEW
                  )
                RETURNING 1
            ),
            deleted AS (
                DELETE FROM contact_blocks
                WHERE blocker_user_id = ?
                  AND email_token = ?
                  AND released_at IS NULL
                  AND target_token IS NULL
                RETURNING 1
            )
            SELECT (SELECT COUNT(*) FROM released) + (SELECT COUNT(*) FROM deleted) AS removed
            """.trimIndent()

        val REMOVE_ALL_SQL =
            """
            WITH released AS (
                UPDATE contact_blocks
                SET released_at = ?::timestamptz
                WHERE blocker_user_id = ?
                  AND released_at IS NULL
                  AND target_token IS NOT NULL
                RETURNING id, blocker_user_id, target_token
            ),
            pruned AS (
                -- Older tombstones of the same pair hide acts before an earlier time (subsumed); beyond the newest few they go.
                DELETE FROM contact_blocks old
                USING released
                WHERE old.blocker_user_id = released.blocker_user_id
                  AND old.target_token = released.target_token
                  AND old.released_at IS NOT NULL
                  AND old.id <> released.id
                  -- Keep the newest few: their windows still tell 'a match X was never shown' apart (Interaction).
                  AND old.id NOT IN (
                      SELECT keep.id FROM contact_blocks keep
                      WHERE keep.blocker_user_id = old.blocker_user_id AND keep.target_token = old.target_token AND keep.released_at IS NOT NULL
                      ORDER BY keep.released_at DESC
                      LIMIT $TOMBSTONES_KEPT_BEFORE_NEW
                  )
                RETURNING 1
            ),
            deleted AS (
                DELETE FROM contact_blocks
                WHERE blocker_user_id = ?
                  AND released_at IS NULL
                  AND target_token IS NULL
                RETURNING 1
            )
            SELECT (SELECT COUNT(*) FROM released) + (SELECT COUNT(*) FROM deleted) AS removed
            """.trimIndent()

        val COUNT_SQL = "SELECT COUNT(*) FROM contact_blocks WHERE blocker_user_id = ? AND released_at IS NULL"
    }
}
