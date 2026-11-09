package com.pynapli.server.account

import com.pynapli.server.db.withConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAccountDataExportActions(
    private val dataSource: DataSource,
) {
    suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord? =
        dataSource.withConnection { connection ->
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
                        if (!rs.next()) return@withConnection null
                        rs.toAccountDataExportRecord()
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

    /**
     * Atomically claims the oldest claimable export and moves it to `processing`: a `requested` one, or
     * a `processing` one whose claim is older than [staleAfter] (its worker died). Requests that have
     * used [maxAttempts] are skipped. Null if nothing is claimable.
     */
    suspend fun claimNextPendingExport(
        staleAfter: Duration,
        maxAttempts: Int,
    ): AccountDataExportRecord? =
        querySingle(
            """
            UPDATE account_data_export_requests
            SET status = 'processing', processing_started_at = now(), attempts = attempts + 1
            WHERE id = (
                SELECT id FROM account_data_export_requests
                WHERE attempts < ?
                  AND (
                    status = 'requested'
                    OR (status = 'processing' AND processing_started_at < now() - make_interval(secs => ?))
                  )
                ORDER BY requested_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING $EXPORT_COLUMNS
            """.trimIndent(),
        ) {
            setInt(1, maxAttempts)
            setLong(2, staleAfter.seconds)
        }

    /** Fails stale `processing` exports that have no attempts left. Returns how many were failed. */
    suspend fun failAbandonedExports(
        staleAfter: Duration,
        maxAttempts: Int,
        errorMessage: String,
    ): Int =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE account_data_export_requests
                    SET status = 'failed', error_message = ?
                    WHERE status = 'processing'
                      AND attempts >= ?
                      AND processing_started_at < now() - make_interval(secs => ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, errorMessage)
                    statement.setInt(2, maxAttempts)
                    statement.setLong(3, staleAfter.seconds)
                    statement.executeUpdate()
                }
        }

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
        dataSource.withConnection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.bind()
                statement.executeQuery().use { rs -> if (rs.next()) rs.toAccountDataExportRecord() else null }
            }
        }

    private companion object {
        const val EXPORT_COLUMNS = "id, user_id, status, requested_at, completed_at, expires_at, download_url, error_message"
    }
}
