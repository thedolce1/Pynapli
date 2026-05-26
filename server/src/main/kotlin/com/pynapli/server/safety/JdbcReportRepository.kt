package com.pynapli.server.safety

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcReportRepository(
    private val dataSource: DataSource
) : ReportRepository {
    override suspend fun createReport(
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        targetId: String?,
        category: String,
        note: String?,
        reportIdFactory: () -> UUID
    ): ReportRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO safety_reports (
                    id,
                    reporter_user_id,
                    reported_user_id,
                    target_type,
                    target_id,
                    category,
                    note
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id,
                          reporter_user_id,
                          reported_user_id,
                          target_type,
                          target_id,
                          category,
                          note,
                          status,
                          created_at
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, reportIdFactory())
                statement.setObject(2, reporterUserId)
                statement.setObject(3, reportedUserId)
                statement.setString(4, targetType)
                statement.setString(5, targetId)
                statement.setString(6, category)
                statement.setString(7, note)
                statement.executeQuery().use { rs ->
                    rs.next()
                    ReportRecord(
                        id = rs.getObject("id", UUID::class.java),
                        reporterUserId = rs.getObject("reporter_user_id", UUID::class.java),
                        reportedUserId = rs.getObject("reported_user_id", UUID::class.java),
                        targetType = rs.getString("target_type"),
                        targetId = rs.getString("target_id"),
                        category = rs.getString("category"),
                        note = rs.getString("note"),
                        status = rs.getString("status"),
                        createdAt = rs.getTimestamp("created_at").toInstant()
                    )
                }
            }
        }
    }

    override suspend fun listReportsByReporter(
        reporterUserId: UUID,
        limit: Int
    ): List<ReportHistoryRecord> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT sr.id,
                       p.display_name AS reported_display_name,
                       sr.target_type,
                       sr.category,
                       sr.status,
                       sr.created_at
                FROM safety_reports sr
                LEFT JOIN profiles p ON p.user_id = sr.reported_user_id
                WHERE sr.reporter_user_id = ?
                ORDER BY sr.created_at DESC, sr.id DESC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, reporterUserId)
                statement.setInt(2, limit.coerceIn(1, MAX_HISTORY_LIMIT))
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toReportHistoryRecord())
                    }
                }
            }
        }
    }

    private fun ResultSet.toReportHistoryRecord(): ReportHistoryRecord =
        ReportHistoryRecord(
            id = getObject("id", UUID::class.java),
            reportedDisplayName = getString("reported_display_name"),
            targetType = getString("target_type"),
            category = getString("category"),
            status = getString("status"),
            createdAt = getTimestamp("created_at").toInstant()
        )

    private companion object {
        const val MAX_HISTORY_LIMIT = 50
    }
}
