package com.pynapli.server.moderation

import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.toAdminReportRecord(): AdminReportRecord =
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

internal data class LockedReportTarget(
    val reportId: UUID,
    val reportedUserId: UUID,
    val targetType: String,
    val targetId: String?,
    val status: String,
)

internal data class LockedMediaStatus(
    val moderationStatus: String,
    val processingStatus: String,
)
