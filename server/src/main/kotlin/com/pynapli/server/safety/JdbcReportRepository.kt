package com.pynapli.server.safety

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
}
