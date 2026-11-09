package com.pynapli.server.moderation

import com.pynapli.server.db.inTransaction
import java.sql.Connection
import java.util.UUID

internal fun Connection.listRiskStatesWithAudit(
    tierFilter: String,
    limit: Int,
    actorId: String,
    reason: String,
): List<AdminRiskStateRecord> {
    inTransaction {
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
    }
}

internal fun Connection.listRiskEventsWithAudit(
    userId: UUID,
    limit: Int,
    actorId: String,
    reason: String,
): List<AdminRiskEventRecord> {
    inTransaction {
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
    }
}
