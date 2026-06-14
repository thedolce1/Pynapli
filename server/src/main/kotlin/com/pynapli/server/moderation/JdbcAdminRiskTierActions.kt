package com.pynapli.server.moderation

import java.sql.Connection
import java.util.UUID

internal fun Connection.setRiskTierWithAudit(
    userId: UUID,
    tier: String,
    actorId: String,
    reason: String,
): AdminRiskActionRecord? {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        if (!activeOrPausedUserExists(userId)) {
            rollback()
            return null
        }
        val previousScore = lockRiskScore(userId) ?: 0
        val nextScore = tier.adminRiskDefaultScore()
        val state = upsertRiskState(userId, score = nextScore, tier = tier, reviewed = true)
        insertRiskEvent(
            userId = userId,
            source = ADMIN_RISK_SOURCE_REVIEW,
            reason = "admin_review.set_$tier",
            delta = nextScore - previousScore,
            state = state,
            metadataJson =
                """
                {
                  "adminActorId":"${actorId.jsonEscapedForAdminMetadata()}",
                  "reason":"${reason.jsonEscapedForAdminMetadata()}",
                  "action":"set_tier"
                }
                """.trimIndent(),
        )
        insertAdminAuditLog(
            actorId = actorId,
            action = "risk.set_$tier",
            targetType = "user",
            targetId = userId,
            reason = reason,
            metadataJson =
                """
                {
                  "previousScore":$previousScore,
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
