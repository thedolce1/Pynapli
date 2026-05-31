package com.pynapli.server.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAccountDataExportActions(
    private val dataSource: DataSource,
) {
    suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT $EXPORT_COLUMNS
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

    suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO account_data_export_requests (id, user_id, status)
                        VALUES (?, ?, 'requested')
                        RETURNING $EXPORT_COLUMNS
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

    /** Atomically claims the oldest `requested` export, moving it to `processing`. Null if none pending. */
    suspend fun claimNextPendingExport(): AccountDataExportRecord? =
        querySingle(
            """
            UPDATE account_data_export_requests
            SET status = 'processing'
            WHERE id = (
                SELECT id FROM account_data_export_requests
                WHERE status = 'requested'
                ORDER BY requested_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING $EXPORT_COLUMNS
            """.trimIndent(),
        ) {}

    suspend fun markExportReady(
        requestId: UUID,
        downloadUrl: String,
        completedAt: Instant,
        expiresAt: Instant,
    ): AccountDataExportRecord? =
        querySingle(
            """
            UPDATE account_data_export_requests
            SET status = 'ready', completed_at = ?, expires_at = ?, download_url = ?, error_message = NULL
            WHERE id = ? AND status = 'processing'
            RETURNING $EXPORT_COLUMNS
            """.trimIndent(),
        ) {
            setTimestamp(1, Timestamp.from(completedAt))
            setTimestamp(2, Timestamp.from(expiresAt))
            setString(3, downloadUrl)
            setObject(4, requestId)
        }

    suspend fun markExportFailed(
        requestId: UUID,
        errorMessage: String,
    ): AccountDataExportRecord? =
        querySingle(
            """
            UPDATE account_data_export_requests
            SET status = 'failed', error_message = ?
            WHERE id = ? AND status = 'processing'
            RETURNING $EXPORT_COLUMNS
            """.trimIndent(),
        ) {
            setString(1, errorMessage)
            setObject(2, requestId)
        }

    suspend fun exportRequestById(requestId: UUID): AccountDataExportRecord? =
        querySingle("SELECT $EXPORT_COLUMNS FROM account_data_export_requests WHERE id = ?") {
            setObject(1, requestId)
        }

    private suspend fun querySingle(
        sql: String,
        bind: PreparedStatement.() -> Unit,
    ): AccountDataExportRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.bind()
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toAccountDataExportRecord() else null }
                }
            }
        }

    private companion object {
        const val EXPORT_COLUMNS = "id, user_id, status, requested_at, completed_at, expires_at, download_url, error_message"
    }
}
