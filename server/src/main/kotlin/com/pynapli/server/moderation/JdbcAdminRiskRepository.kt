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

    private fun Connection.selectRiskStates(tierFilter: String, limit: Int): List<AdminRiskStateRecord> =
        prepareStatement(
            """
            SELECT ars.user_id,
                   NULLIF(p.display_name, '') AS display_name,
                   ars.score,
                   ars.tier,
                   ars.last_event_at,
                   ars.last_reviewed_at,
                   ars.updated_at
            FROM account_risk_states ars
            JOIN users u
              ON u.id = ars.user_id
             AND u.deleted_at IS NULL
            LEFT JOIN profiles p ON p.user_id = ars.user_id
            WHERE (
                ? = 'all'
                OR (? = 'needs_review' AND ars.tier <> 'clean')
                OR ars.tier = ?
            )
            ORDER BY
                CASE ars.tier
                    WHEN 'terminate' THEN 0
                    WHEN 'quarantine' THEN 1
                    WHEN 'friction' THEN 2
                    WHEN 'watch' THEN 3
                    ELSE 4
                END ASC,
                ars.updated_at DESC,
                ars.user_id ASC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, tierFilter)
            statement.setString(2, tierFilter)
            statement.setString(3, tierFilter)
            statement.setInt(4, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toAdminRiskStateRecord())
                }
            }
        }

    private fun Connection.selectRiskEvents(userId: UUID, limit: Int): List<AdminRiskEventRecord> =
        prepareStatement(
            """
            SELECT id,
                   user_id,
                   actor_user_id,
                   source,
                   reason,
                   delta,
                   score_after,
                   tier_after,
                   metadata::text AS metadata_json,
                   created_at
            FROM account_risk_events
            WHERE user_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toAdminRiskEventRecord())
                }
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

    private fun Connection.activeOrPausedUserExists(userId: UUID): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM users
                WHERE id = ?
                  AND deleted_at IS NULL
                  AND status IN ('active', 'paused', 'suspended')
            )
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.lockUserStatus(userId: UUID): String? =
        prepareStatement(
            """
            SELECT status
            FROM users
            WHERE id = ?
              AND deleted_at IS NULL
              AND status <> 'deleted'
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getString("status") else null
            }
        }

    private fun Connection.updateUserStatus(userId: UUID, status: String) {
        prepareStatement(
            """
            UPDATE users
            SET status = ?,
                updated_at = now()
            WHERE id = ?
              AND deleted_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.revokeUserSessions(userId: UUID): Int =
        prepareStatement(
            """
            UPDATE user_sessions
            SET revoked_at = now()
            WHERE user_id = ?
              AND revoked_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }

    private fun Connection.lockRiskScore(userId: UUID): Int? =
        prepareStatement(
            """
            SELECT score
            FROM account_risk_states
            WHERE user_id = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("score") else null
            }
        }

    private fun Connection.lockRiskState(userId: UUID): AdminRiskActionRecord? =
        prepareStatement(
            """
            SELECT user_id,
                   score,
                   tier,
                   last_reviewed_at,
                   updated_at
            FROM account_risk_states
            WHERE user_id = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toAdminRiskActionRecord() else null
            }
        }

    private fun Connection.upsertRiskState(
        userId: UUID,
        score: Int,
        tier: String,
        reviewed: Boolean
    ): AdminRiskActionRecord =
        prepareStatement(
            """
            INSERT INTO account_risk_states (
                user_id,
                score,
                tier,
                last_event_at,
                last_reviewed_at,
                updated_at
            )
            VALUES (
                ?,
                ?,
                ?,
                now(),
                CASE WHEN ? THEN now() ELSE NULL END,
                now()
            )
            ON CONFLICT (user_id) DO UPDATE
            SET score = EXCLUDED.score,
                tier = EXCLUDED.tier,
                last_event_at = now(),
                last_reviewed_at = CASE
                    WHEN ? THEN now()
                    ELSE account_risk_states.last_reviewed_at
                END,
                updated_at = now()
            RETURNING user_id,
                      score,
                      tier,
                      last_reviewed_at,
                      updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setInt(2, score)
            statement.setString(3, tier)
            statement.setBoolean(4, reviewed)
            statement.setBoolean(5, reviewed)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.toAdminRiskActionRecord()
            }
        }

    private fun Connection.updateRiskReviewedAt(userId: UUID): AdminRiskActionRecord? =
        prepareStatement(
            """
            UPDATE account_risk_states
            SET last_reviewed_at = now(),
                updated_at = now()
            WHERE user_id = ?
            RETURNING user_id,
                      score,
                      tier,
                      last_reviewed_at,
                      updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toAdminRiskActionRecord() else null
            }
        }

    private fun Connection.insertRiskEvent(
        userId: UUID,
        source: String,
        reason: String,
        delta: Int,
        state: AdminRiskActionRecord,
        metadataJson: String
    ) {
        prepareStatement(
            """
            INSERT INTO account_risk_events (
                id,
                user_id,
                actor_user_id,
                source,
                reason,
                delta,
                score_after,
                tier_after,
                metadata
            )
            VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, userId)
            statement.setString(3, source)
            statement.setString(4, reason)
            statement.setInt(5, delta)
            statement.setInt(6, state.score)
            statement.setString(7, state.tier)
            statement.setString(8, metadataJson)
            statement.executeUpdate()
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
