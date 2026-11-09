package com.pynapli.server.admin

import com.pynapli.server.db.withConnection
import com.pynapli.server.moderation.insertAdminAuditLog
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAdminUserLookupRepository(
    private val dataSource: DataSource,
) : AdminUserLookupRepository {
    override suspend fun moderationFacts(userId: UUID): AdminUserModerationFacts =
        dataSource.withConnection { connection ->
            val risk = connection.riskState(userId)
            AdminUserModerationFacts(
                riskTier = risk?.first ?: "clean",
                riskScore = risk?.second ?: 0,
                lastReviewedAt = risk?.third,
                recentRiskReasons = connection.recentRiskReasons(userId),
                reportsAgainstCount = connection.count("safety_reports", "reported_user_id", userId),
                reportsByCount = connection.count("safety_reports", "reporter_user_id", userId),
                blocksAgainstCount = connection.count("user_blocks", "blocked_user_id", userId),
                blocksByCount = connection.count("user_blocks", "blocker_user_id", userId),
                activeSessionCount = connection.activeSessionCount(userId),
                activeDeviceCount = connection.activeDeviceCount(userId),
                recentModerationActions = connection.recentModerationActions(userId),
            )
        }

    override suspend fun recordLookupAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    ) {
        dataSource.withConnection { connection ->
            connection.insertAdminAuditLog(
                actorId = actorId,
                action = "user_lookup",
                targetType = "user",
                targetId = userId,
                reason = reason,
                metadataJson = "{}",
            )
        }
    }

    private fun Connection.riskState(userId: UUID): Triple<String, Int, Instant?>? =
        prepareStatement(
            "SELECT tier, score, last_reviewed_at FROM account_risk_states WHERE user_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    Triple(rs.getString("tier"), rs.getInt("score"), rs.getTimestamp("last_reviewed_at")?.toInstant())
                } else {
                    null
                }
            }
        }

    private fun Connection.recentRiskReasons(userId: UUID): List<AdminUserRiskReason> =
        prepareStatement(
            """
            SELECT source, reason, delta, created_at
            FROM account_risk_events
            WHERE user_id = ?
            ORDER BY created_at DESC
            LIMIT 5
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AdminUserRiskReason(
                                source = rs.getString("source"),
                                reason = rs.getString("reason"),
                                delta = rs.getInt("delta"),
                                occurredAt = rs.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.recentModerationActions(userId: UUID): List<AdminUserModerationAction> =
        prepareStatement(
            """
            SELECT action, target_type, reason, admin_actor_id, created_at
            FROM admin_audit_log
            WHERE target_id = ?
            ORDER BY created_at DESC
            LIMIT 10
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AdminUserModerationAction(
                                action = rs.getString("action"),
                                targetType = rs.getString("target_type"),
                                reason = rs.getString("reason"),
                                actorId = rs.getString("admin_actor_id"),
                                occurredAt = rs.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.activeSessionCount(userId: UUID): Int =
        prepareStatement(
            """
            SELECT count(*) FROM user_sessions
            WHERE user_id = ? AND revoked_at IS NULL AND refresh_expires_at > now()
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.singleInt()
        }

    private fun Connection.activeDeviceCount(userId: UUID): Int =
        prepareStatement(
            "SELECT count(*) FROM push_devices WHERE user_id = ? AND disabled_at IS NULL",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.singleInt()
        }

    private fun Connection.count(
        table: String,
        column: String,
        userId: UUID,
    ): Int =
        // table/column are compile-time constants from this file only — never request input.
        prepareStatement("SELECT count(*) FROM $table WHERE $column = ?").use { statement ->
            statement.setObject(1, userId)
            statement.singleInt()
        }

    private fun java.sql.PreparedStatement.singleInt(): Int =
        executeQuery().use { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
}
