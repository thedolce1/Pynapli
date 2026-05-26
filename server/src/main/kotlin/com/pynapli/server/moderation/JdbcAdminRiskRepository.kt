package com.pynapli.server.moderation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class JdbcAdminRiskRepository(
    private val dataSource: DataSource
) : AdminRiskRepository {
    override suspend fun listRiskStates(
        tierFilter: String,
        limit: Int,
        actorId: String,
        reason: String
    ): List<AdminRiskStateRecord> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.insertAdminAuditLog(
                    actorId = actorId,
                    action = "risk_states.list",
                    targetType = "account_risk_states",
                    targetId = null,
                    reason = reason,
                    metadataJson = """{"tierFilter":"$tierFilter","limit":$limit}"""
                )
                val states = connection.selectRiskStates(tierFilter, limit)
                connection.commit()
                states
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun listRiskEvents(
        userId: UUID,
        limit: Int,
        actorId: String,
        reason: String
    ): List<AdminRiskEventRecord> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.insertAdminAuditLog(
                    actorId = actorId,
                    action = "risk_events.list",
                    targetType = "user",
                    targetId = userId,
                    reason = reason,
                    metadataJson = """{"limit":$limit}"""
                )
                val events = connection.selectRiskEvents(userId, limit)
                connection.commit()
                events
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun setRiskTier(
        userId: UUID,
        tier: String,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.setRiskTierWithAudit(userId, tier, actorId, reason)
        }
    }

    override suspend fun markRiskReviewed(
        userId: UUID,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.markRiskReviewedWithAudit(userId, actorId, reason)
        }
    }

    override suspend fun lockAccount(
        userId: UUID,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.enforceAccountStatusWithAudit(
                userId = userId,
                nextStatus = "suspended",
                action = "lock",
                actorId = actorId,
                reason = reason
            )
        }
    }

    override suspend fun banAccount(
        userId: UUID,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.enforceAccountStatusWithAudit(
                userId = userId,
                nextStatus = "banned",
                action = "ban",
                actorId = actorId,
                reason = reason
            )
        }
    }

    private fun Connection.setRiskTierWithAudit(
        userId: UUID,
        tier: String,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? {
        val previousAutoCommit = autoCommit
        autoCommit = false
        try {
            if (!activeOrPausedUserExists(userId)) {
                rollback()
                return null
            }
            val previousScore = lockRiskScore(userId) ?: 0
            val nextScore = tier.defaultScore()
            val state = upsertRiskState(userId, score = nextScore, tier = tier, reviewed = true)
            insertRiskEvent(
                userId = userId,
                source = SOURCE_ADMIN_REVIEW,
                reason = "admin_review.set_$tier",
                delta = nextScore - previousScore,
                state = state,
                metadataJson = """
                    {
                      "adminActorId":"${actorId.jsonEscaped()}",
                      "reason":"${reason.jsonEscaped()}",
                      "action":"set_tier"
                    }
                """.trimIndent()
            )
            insertAdminAuditLog(
                actorId = actorId,
                action = "risk.set_$tier",
                targetType = "user",
                targetId = userId,
                reason = reason,
                metadataJson = """
                    {
                      "previousScore":$previousScore,
                      "score":${state.score},
                      "tier":"${state.tier}"
                    }
                """.trimIndent()
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

    private fun Connection.markRiskReviewedWithAudit(
        userId: UUID,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? {
        val previousAutoCommit = autoCommit
        autoCommit = false
        try {
            val current = lockRiskState(userId) ?: run {
                rollback()
                return null
            }
            val state = updateRiskReviewedAt(userId) ?: run {
                rollback()
                return null
            }
            insertRiskEvent(
                userId = userId,
                source = SOURCE_ADMIN_REVIEW,
                reason = "admin_review.reviewed",
                delta = 0,
                state = state,
                metadataJson = """
                    {
                      "adminActorId":"${actorId.jsonEscaped()}",
                      "reason":"${reason.jsonEscaped()}",
                      "action":"mark_reviewed"
                    }
                """.trimIndent()
            )
            insertAdminAuditLog(
                actorId = actorId,
                action = "risk.reviewed",
                targetType = "user",
                targetId = userId,
                reason = reason,
                metadataJson = """
                    {
                      "previousScore":${current.score},
                      "score":${state.score},
                      "tier":"${state.tier}"
                    }
                """.trimIndent()
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

    private fun Connection.enforceAccountStatusWithAudit(
        userId: UUID,
        nextStatus: String,
        action: String,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? {
        val previousAutoCommit = autoCommit
        autoCommit = false
        try {
            val previousStatus = lockUserStatus(userId) ?: run {
                rollback()
                return null
            }
            val previousScore = lockRiskScore(userId) ?: 0
            val revokedSessionCount = revokeUserSessions(userId)
            updateUserStatus(userId, nextStatus)
            val state = upsertRiskState(userId, score = "terminate".defaultScore(), tier = "terminate", reviewed = true)
                .copy(accountStatus = nextStatus)
            insertRiskEvent(
                userId = userId,
                source = SOURCE_ADMIN_ENFORCEMENT,
                reason = "admin_enforcement.$action",
                delta = state.score - previousScore,
                state = state,
                metadataJson = """
                    {
                      "adminActorId":"${actorId.jsonEscaped()}",
                      "reason":"${reason.jsonEscaped()}",
                      "action":"$action",
                      "previousStatus":"$previousStatus",
                      "status":"$nextStatus",
                      "revokedSessionCount":$revokedSessionCount
                    }
                """.trimIndent()
            )
            insertAdminAuditLog(
                actorId = actorId,
                action = "account.$action",
                targetType = "user",
                targetId = userId,
                reason = reason,
                metadataJson = """
                    {
                      "previousStatus":"$previousStatus",
                      "status":"$nextStatus",
                      "previousScore":$previousScore,
                      "score":${state.score},
                      "tier":"${state.tier}",
                      "revokedSessionCount":$revokedSessionCount
                    }
                """.trimIndent()
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

    private fun String.defaultScore(): Int = when (this) {
        "terminate" -> 90
        "quarantine" -> 70
        "friction" -> 50
        "watch" -> 30
        else -> 0
    }

    private fun String.jsonEscaped(): String =
        buildString(length) {
            this@jsonEscaped.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

    companion object {
        private const val SOURCE_ADMIN_REVIEW = "admin_review"
        private const val SOURCE_ADMIN_ENFORCEMENT = "admin_enforcement"
    }
}
