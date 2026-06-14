package com.pynapli.server.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord =
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
}
