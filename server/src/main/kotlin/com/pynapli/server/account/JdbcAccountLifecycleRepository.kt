package com.pynapli.server.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcAccountLifecycleRepository(
    private val dataSource: DataSource,
) : AccountLifecycleRepository {
    override suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, user_id, status, requested_at, completed_at, expires_at, download_url, error_message
                        FROM account_data_export_requests
                        WHERE user_id = ?
                        ORDER BY requested_at DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) return@withContext null
                            rs.toAccountDataExportRecord()
                        }
                    }
            }
        }

    override suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO account_data_export_requests (id, user_id, status)
                        VALUES (?, ?, 'requested')
                        RETURNING id, user_id, status, requested_at, completed_at, expires_at, download_url, error_message
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, requestId)
                        statement.setObject(2, userId)
                        statement.executeQuery().use { rs ->
                            check(rs.next()) { "Data export request insert returned no row." }
                            rs.toAccountDataExportRecord()
                        }
                    }
            }
        }

    override suspend fun deleteAccount(userId: UUID): AccountDeletionResult? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val deleted = markUserDeleted(connection, userId)
                    if (!deleted) {
                        connection.rollback()
                        return@withContext null
                    }

                    hideProfile(connection, userId)
                    removeFromDiscovery(connection, userId)
                    clearActiveTravelLocation(connection, userId)
                    val revokedSessionCount = revokeSessions(connection, userId)
                    recordDeletionEvent(connection, userId, revokedSessionCount)

                    connection.commit()
                    AccountDeletionResult(
                        userId = userId,
                        revokedSessionCount = revokedSessionCount,
                    )
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    private fun markUserDeleted(
        connection: Connection,
        userId: UUID,
    ): Boolean {
        connection
            .prepareStatement(
                """
                UPDATE users
                SET status = 'deleted',
                    deleted_at = COALESCE(deleted_at, now()),
                    updated_at = now()
                WHERE id = ?
                  AND deleted_at IS NULL
                  AND status <> 'deleted'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                return statement.executeUpdate() > 0
            }
    }

    private fun hideProfile(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE profiles
                SET visibility_status = 'hidden',
                    updated_at = now()
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun removeFromDiscovery(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE discovery_settings
                SET grid_visible = false,
                    swipe_visible = false,
                    events_visible = false,
                    show_in_viewed_lists = false,
                    allow_grid_messages = false,
                    allow_flirts = false,
                    allow_intro_notes = false,
                    allow_album_requests = false,
                    visible_while_traveling = false,
                    updated_at = now()
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun clearActiveTravelLocation(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE travel_locations
                SET status = 'cleared',
                    active_until = now()
                WHERE user_id = ?
                  AND status = 'active'
                  AND active_until IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun revokeSessions(
        connection: Connection,
        userId: UUID,
    ): Int {
        connection
            .prepareStatement(
                """
                UPDATE user_sessions
                SET revoked_at = now()
                WHERE user_id = ?
                  AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                return statement.executeUpdate()
            }
    }

    private fun recordDeletionEvent(
        connection: Connection,
        userId: UUID,
        revokedSessionCount: Int,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO account_deletion_events (
                    id,
                    user_id,
                    revoked_session_count,
                    retention_note
                )
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setInt(3, revokedSessionCount)
                statement.setString(4, ACCOUNT_DELETION_RETENTION_NOTE)
                statement.executeUpdate()
            }
    }

    private fun ResultSet.toAccountDataExportRecord(): AccountDataExportRecord =
        AccountDataExportRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            status = getString("status"),
            requestedAt = getTimestamp("requested_at").toInstant(),
            completedAt = getTimestamp("completed_at")?.toInstant(),
            expiresAt = getTimestamp("expires_at")?.toInstant(),
            downloadUrl = getString("download_url"),
            errorMessage = getString("error_message"),
        )

    companion object {
        private const val ACCOUNT_DELETION_RETENTION_NOTE =
            "Account removed from discovery and sessions revoked; safety and legal retention records preserved."
    }
}
