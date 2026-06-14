package com.pynapli.server.safety

import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

internal fun ResultSet.toAppealRecord(): AppealRecord =
    AppealRecord(
        id = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        actionType = getString("action_type"),
        targetType = getString("target_type"),
        targetId = getString("target_id"),
        relatedReportId = getObject("related_report_id", UUID::class.java),
        status = getString("status"),
        userReason = getString("user_reason"),
        adminNote = getString("admin_note"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        reviewedAt = getTimestamp("reviewed_at")?.toInstant(),
    )

internal fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

internal fun java.sql.PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) {
    value?.let { setString(index, it) } ?: setNull(index, Types.VARCHAR)
}
