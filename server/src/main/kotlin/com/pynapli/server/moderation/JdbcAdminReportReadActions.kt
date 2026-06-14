package com.pynapli.server.moderation

import java.sql.Connection

internal fun Connection.listReportsWithAudit(
    status: String,
    limit: Int,
    actorId: String,
    reason: String,
): List<AdminReportRecord> {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        insertAdminAuditLog(
            actorId = actorId,
            action = "reports.list",
            targetType = "safety_reports",
            targetId = null,
            reason = reason,
            metadataJson = """{"status":"$status","limit":$limit}""",
        )
        val reports = selectReports(status, limit)
        commit()
        return reports
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}

private fun Connection.selectReports(
    status: String,
    limit: Int,
): List<AdminReportRecord> =
    prepareStatement(
        """
        SELECT id,
               reporter_user_id,
               reported_user_id,
               target_type,
               target_id,
               category,
               note,
               source,
               status,
               created_at,
               updated_at,
               reviewed_at
        FROM safety_reports
        WHERE (? = 'all' OR status = ?)
        ORDER BY
            CASE status
                WHEN 'open' THEN 0
                WHEN 'under_review' THEN 1
                ELSE 2
            END ASC,
            created_at ASC,
            id ASC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, status)
        statement.setString(2, status)
        statement.setInt(3, limit)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.toAdminReportRecord())
            }
        }
    }
