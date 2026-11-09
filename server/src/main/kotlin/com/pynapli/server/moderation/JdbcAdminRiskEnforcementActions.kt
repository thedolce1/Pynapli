package com.pynapli.server.moderation

import com.pynapli.server.db.inTransaction
import java.sql.Connection
import java.util.UUID

internal fun Connection.enforceAccountStatusWithAudit(
    userId: UUID,
    nextStatus: String,
    action: String,
    actorId: String,
    reason: String,
): AdminRiskActionRecord? {
    inTransaction {
        val previousStatus =
            lockUserStatus(userId) ?: run {
                rollback()
                return null
            }
        val previousScore = lockRiskScore(userId) ?: 0
        val revokedSessionCount = revokeUserSessions(userId)
        updateUserStatus(userId, nextStatus)
        val state =
            upsertRiskState(
                userId = userId,
                score = "terminate".adminRiskDefaultScore(),
                tier = "terminate",
                reviewed = true,
            ).copy(accountStatus = nextStatus)
        insertRiskEvent(
            userId = userId,
            source = ADMIN_RISK_SOURCE_ENFORCEMENT,
            reason = "admin_enforcement.$action",
            delta = state.score - previousScore,
            state = state,
            metadataJson =
                """
                {
                  "adminActorId":"${actorId.jsonEscapedForAdminMetadata()}",
                  "reason":"${reason.jsonEscapedForAdminMetadata()}",
                  "action":"$action",
                  "previousStatus":"$previousStatus",
                  "status":"$nextStatus",
                  "revokedSessionCount":$revokedSessionCount
                }
                """.trimIndent(),
        )
        insertAdminAuditLog(
            actorId = actorId,
            action = "account.$action",
            targetType = "user",
            targetId = userId,
            reason = reason,
            metadataJson =
                """
                {
                  "previousStatus":"$previousStatus",
                  "status":"$nextStatus",
                  "previousScore":$previousScore,
                  "score":${state.score},
                  "tier":"${state.tier}",
                  "revokedSessionCount":$revokedSessionCount
                }
                """.trimIndent(),
        )
        commit()
        return state
    }
}
