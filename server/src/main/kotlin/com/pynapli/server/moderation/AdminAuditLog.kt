package com.pynapli.server.moderation

import java.sql.Connection
import java.sql.Types
import java.util.UUID

internal fun Connection.insertAdminAuditLog(
    actorId: String,
    action: String,
    targetType: String,
    targetId: UUID?,
    reason: String?,
    metadataJson: String,
) {
    prepareStatement(
        """
        INSERT INTO admin_audit_log (
            id,
            admin_actor_id,
            admin_role,
            action,
            target_type,
            target_id,
            reason,
            metadata
        )
        VALUES (?, ?, 'bootstrap_admin', ?, ?, ?, ?, ?::jsonb)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setString(2, actorId)
        statement.setString(3, action)
        statement.setString(4, targetType)
        targetId?.let { statement.setObject(5, it) } ?: statement.setNull(5, Types.OTHER)
        statement.setString(6, reason)
        statement.setString(7, metadataJson)
        statement.executeUpdate()
    }
}
