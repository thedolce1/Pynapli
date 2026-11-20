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
 * on whether an address belongs to a member (ContactBlockRequestPathTest pins the SQL).
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

    /** One DELETE whether or not the row exists. */
    suspend fun remove(
        blockerUserId: UUID,
        emailToken: String,
    )

    suspend fun removeAll(blockerUserId: UUID)

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
    ) {
        dataSource.withConnection { connection ->
            connection.prepareStatement(REMOVE_SQL).use { statement ->
                statement.setObject(1, blockerUserId)
                statement.setString(2, emailToken)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun removeAll(blockerUserId: UUID) {
        dataSource.withConnection { connection ->
            connection.prepareStatement(REMOVE_ALL_SQL).use { statement ->
                statement.setObject(1, blockerUserId)
                statement.executeUpdate()
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
            ),
            inserted AS (
                INSERT INTO contact_blocks (id, blocker_user_id, email_token, created_at)
                SELECT ?::uuid, ?::uuid, ?::text, ?::timestamptz
                FROM existing
                WHERE NOT existing.present
                  AND existing.total < ?
                ON CONFLICT (blocker_user_id, email_token) DO NOTHING
                RETURNING 1
            )
            SELECT (SELECT COUNT(*) FROM inserted) AS inserted,
                   existing.present AS present
            FROM existing
            """.trimIndent()

        val REMOVE_SQL = "DELETE FROM contact_blocks WHERE blocker_user_id = ? AND email_token = ?"

        val REMOVE_ALL_SQL = "DELETE FROM contact_blocks WHERE blocker_user_id = ?"

        val COUNT_SQL = "SELECT COUNT(*) FROM contact_blocks WHERE blocker_user_id = ?"

        /** Every statement the blocker's own requests run (pinned by a test: none reads `users`). */
        val REQUEST_PATH_SQL: List<String> = listOf(LOCK_BLOCKER_SQL, ADD_SQL, REMOVE_SQL, REMOVE_ALL_SQL, COUNT_SQL)
    }
}
