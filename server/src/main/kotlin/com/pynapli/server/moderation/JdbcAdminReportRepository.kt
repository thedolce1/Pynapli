package com.pynapli.server.moderation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
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
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.insertAdminAuditLog(
                        actorId = actorId,
                        action = "reports.list",
                        targetType = "safety_reports",
                        targetId = null,
                        reason = reason,
                        metadataJson = """{"status":"$status","limit":$limit}""",
                    )
                    val reports = connection.selectReports(status, limit)
                    connection.commit()
                    reports
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
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

    private fun Connection.updateReportStatusWithAudit(
        reportId: UUID,
        status: String,
        actorId: String,
        reason: String,
    ): AdminReportActionRecord? {
        val previousAutoCommit = autoCommit
        autoCommit = false
        try {
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
        } catch (error: Throwable) {
            rollback()
            throw error
        } finally {
            autoCommit = previousAutoCommit
        }
    }

    private fun Connection.resolveReportAndRemoveMediaWithAudit(
        reportId: UUID,
        actorId: String,
        reason: String,
    ): AdminReportActionRecord? {
        val previousAutoCommit = autoCommit
        autoCommit = false
        try {
            val report =
                lockActionableReportTarget(reportId) ?: run {
                    rollback()
                    return null
                }
            if (report.targetType != "media" || report.targetId.isNullOrBlank()) {
                rollback()
                return null
            }
            val mediaAssetId =
                runCatching { UUID.fromString(report.targetId) }.getOrNull()
                    ?: run {
                        rollback()
                        return null
                    }
            val previousMediaStatus =
                lockMediaAssetModerationStatus(
                    ownerUserId = report.reportedUserId,
                    mediaAssetId = mediaAssetId,
                ) ?: run {
                    rollback()
                    return null
                }
            removeMediaAsset(
                ownerUserId = report.reportedUserId,
                mediaAssetId = mediaAssetId,
            )
            val action =
                updateReportStatus(reportId, "resolved", reason) ?: run {
                    rollback()
                    return null
                }
            insertAdminAuditLog(
                actorId = actorId,
                action = "report.resolve_remove_media",
                targetType = "safety_report",
                targetId = reportId,
                reason = reason,
                metadataJson =
                    """
                    {
                      "previousStatus":"${report.status}",
                      "status":"resolved",
                      "mediaAssetId":"$mediaAssetId",
                      "previousMediaModerationStatus":"${previousMediaStatus.moderationStatus}",
                      "previousMediaProcessingStatus":"${previousMediaStatus.processingStatus}",
                      "mediaModerationStatus":"removed"
                    }
                    """.trimIndent(),
            )
            commit()
            return action
        } catch (error: Throwable) {
            rollback()
            throw error
        } finally {
            autoCommit = previousAutoCommit
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

    private fun Connection.lockActionableReportTarget(reportId: UUID): LockedReportTarget? =
        prepareStatement(
            """
            SELECT id,
                   reported_user_id,
                   target_type,
                   target_id,
                   status
            FROM safety_reports
            WHERE id = ?
              AND status IN ('open', 'under_review')
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, reportId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    LockedReportTarget(
                        reportId = rs.getObject("id", UUID::class.java),
                        reportedUserId = rs.getObject("reported_user_id", UUID::class.java),
                        targetType = rs.getString("target_type"),
                        targetId = rs.getString("target_id"),
                        status = rs.getString("status"),
                    )
                } else {
                    null
                }
            }
        }

    private fun Connection.lockMediaAssetModerationStatus(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): LockedMediaStatus? =
        prepareStatement(
            """
            SELECT moderation_status,
                   processing_status
            FROM media_assets
            WHERE id = ?
              AND owner_user_id = ?
              AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, mediaAssetId)
            statement.setObject(2, ownerUserId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    LockedMediaStatus(
                        moderationStatus = rs.getString("moderation_status"),
                        processingStatus = rs.getString("processing_status"),
                    )
                } else {
                    null
                }
            }
        }

    private fun Connection.removeMediaAsset(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ) {
        prepareStatement(
            """
            UPDATE media_assets
            SET processing_status = 'ready',
                moderation_status = 'removed',
                updated_at = now()
            WHERE id = ?
              AND owner_user_id = ?
              AND deleted_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, mediaAssetId)
            statement.setObject(2, ownerUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.updateReportStatus(
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

    private fun ResultSet.toAdminReportRecord(): AdminReportRecord =
        AdminReportRecord(
            reportId = getObject("id", UUID::class.java),
            reporterUserId = getObject("reporter_user_id", UUID::class.java),
            reportedUserId = getObject("reported_user_id", UUID::class.java),
            targetType = getString("target_type"),
            targetId = getString("target_id"),
            category = getString("category"),
            note = getString("note"),
            source = getString("source"),
            status = getString("status"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            reviewedAt = getTimestamp("reviewed_at")?.toInstant(),
        )

    private data class LockedReportTarget(
        val reportId: UUID,
        val reportedUserId: UUID,
        val targetType: String,
        val targetId: String?,
        val status: String,
    )

    private data class LockedMediaStatus(
        val moderationStatus: String,
        val processingStatus: String,
    )
}
