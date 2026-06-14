package com.pynapli.server.moderation

import java.sql.Connection
import java.util.UUID

internal fun Connection.markRiskReviewedWithAudit(
    userId: UUID,
    actorId: String,
    reason: String,
): AdminRiskActionRecord? {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        val current =
            lockRiskState(userId) ?: run {
                rollback()
                return null
            }
        val state =
            updateRiskReviewedAt(userId) ?: run {
                rollback()
                return null
            }
        insertRiskEvent(
            userId = userId,
            source = ADMIN_RISK_SOURCE_REVIEW,
            reason = "admin_review.reviewed",
            delta = 0,
            state = state,
            metadataJson =
                """
                {
                  "adminActorId":"${actorId.jsonEscapedForAdminMetadata()}",
                  "reason":"${reason.jsonEscapedForAdminMetadata()}",
                  "action":"mark_reviewed"
                }
                """.trimIndent(),
        )
        insertAdminAuditLog(
            actorId = actorId,
            action = "risk.reviewed",
            targetType = "user",
            targetId = userId,
            reason = reason,
            metadataJson =
                """
                {
                  "previousScore":${current.score},
                  "score":${state.score},
                  "tier":"${state.tier}"
                }
                """.trimIndent(),
        )
        commit()
        return state
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}
