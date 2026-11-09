package com.pynapli.server.moderation

import com.pynapli.server.db.inTransaction
import java.sql.Connection
import java.util.UUID

internal fun Connection.updateReportStatusWithAudit(
    reportId: UUID,
    status: String,
    actorId: String,
    reason: String,
): AdminReportActionRecord? {
    inTransaction {
        val previousStatus =
            lockActionableReport(reportId) ?: run {
                rollback()
                return null
            }
        val action =
            updateReportStatus(reportId, status, reason) ?: run {
                rollback()
                return null
            }
        insertAdminAuditLog(
            actorId = actorId,
            action = "report.$status",
            targetType = "safety_report",
            targetId = reportId,
            reason = reason,
            metadataJson =
                """
                {
                  "previousStatus":"$previousStatus",
                  "status":"$status"
                }
                """.trimIndent(),
        )
        commit()
        return action
    }
}

internal fun Connection.updateReportStatus(
    reportId: UUID,
    status: String,
    reason: String,
): AdminReportActionRecord? =
    prepareStatement(
        """
        UPDATE safety_reports
        SET status = ?,
            review_note = ?,
            updated_at = now(),
            reviewed_at = CASE
                WHEN ? IN ('resolved', 'dismissed') THEN now()
                ELSE reviewed_at
            END
        WHERE id = ?
          AND status IN ('open', 'under_review')
        RETURNING id, status, updated_at
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, status)
        statement.setString(2, reason)
        statement.setString(3, status)
        statement.setObject(4, reportId)
        statement.executeQuery().use { rs ->
            if (rs.next()) {
                AdminReportActionRecord(
                    reportId = rs.getObject("id", UUID::class.java),
                    status = rs.getString("status"),
                    actedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            } else {
                null
            }
        }
    }

private fun Connection.lockActionableReport(reportId: UUID): String? =
    prepareStatement(
        """
        SELECT status
        FROM safety_reports
        WHERE id = ?
          AND status IN ('open', 'under_review')
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, reportId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.getString("status") else null
        }
    }
