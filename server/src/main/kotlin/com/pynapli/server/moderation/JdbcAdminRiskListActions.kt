package com.pynapli.server.moderation

import java.sql.Connection
import java.util.UUID

internal fun Connection.listRiskStatesWithAudit(
    tierFilter: String,
    limit: Int,
    actorId: String,
    reason: String,
): List<AdminRiskStateRecord> {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        insertAdminAuditLog(
            actorId = actorId,
            action = "risk_states.list",
            targetType = "account_risk_states",
            targetId = null,
            reason = reason,
            metadataJson = """{"tierFilter":"$tierFilter","limit":$limit}""",
        )
        val states = selectRiskStates(tierFilter, limit)
        commit()
        return states
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}

internal fun Connection.listRiskEventsWithAudit(
    userId: UUID,
    limit: Int,
    actorId: String,
    reason: String,
): List<AdminRiskEventRecord> {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        insertAdminAuditLog(
            actorId = actorId,
            action = "risk_events.list",
            targetType = "user",
            targetId = userId,
            reason = reason,
            metadataJson = """{"limit":$limit}""",
        )
        val events = selectRiskEvents(userId, limit)
        commit()
        return events
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}
