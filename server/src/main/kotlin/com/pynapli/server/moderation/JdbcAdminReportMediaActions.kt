package com.pynapli.server.moderation

import java.sql.Connection
import java.util.UUID

internal fun Connection.resolveReportAndRemoveMediaWithAudit(
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
