package com.pynapli.server.moderation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

class JdbcAdminReportRepository(
    private val dataSource: DataSource,
) : AdminReportRepository {
    override suspend fun listReports(
        status: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminReportRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listReportsWithAudit(
                    status = status,
                    limit = limit,
                    actorId = actorId,
                    reason = reason,
                )
            }
        }

    override suspend fun updateReportStatus(
        reportId: UUID,
        status: String,
        actorId: String,
        reason: String,
    ): AdminReportActionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.updateReportStatusWithAudit(
                    reportId = reportId,
                    status = status,
                    actorId = actorId,
                    reason = reason,
                )
            }
        }

    override suspend fun resolveReportAndRemoveMedia(
        reportId: UUID,
        actorId: String,
        reason: String,
    ): AdminReportActionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.resolveReportAndRemoveMediaWithAudit(
                    reportId = reportId,
                    actorId = actorId,
                    reason = reason,
                )
            }
        }
}
