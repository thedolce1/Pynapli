package com.pynapli.server.admin

import com.pynapli.server.db.withConnection
import com.pynapli.server.moderation.insertAdminAuditLog
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAdminSubscriptionSupportRepository(
    private val dataSource: DataSource,
) : AdminSubscriptionSupportRepository {
    override suspend fun facts(userId: UUID): AdminSubscriptionSupportFacts? =
        dataSource.withConnection { connection ->
            if (!connection.userExists(userId)) return@withConnection null

            AdminSubscriptionSupportFacts(
                subscriptions = connection.subscriptions(userId),
            )
        }

    override suspend fun recordReviewAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    ) {
        dataSource.withConnection { connection ->
            connection.insertAdminAuditLog(
                actorId = actorId,
                action = "subscription_support_view",
                targetType = "user",
                targetId = userId,
                reason = reason,
                metadataJson = "{}",
            )
        }
    }

    private fun Connection.userExists(userId: UUID): Boolean =
        prepareStatement("SELECT 1 FROM users WHERE id = ? AND deleted_at IS NULL").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { it.next() }
        }

    private fun Connection.subscriptions(userId: UUID): List<AdminSubscriptionRecordFact> =
        prepareStatement(
            """
            SELECT platform, tier, status, external_product_id, external_status,
                   acknowledgement_state, current_period_start, current_period_end, last_verified_at
            FROM subscriptions
            WHERE user_id = ?
            ORDER BY current_period_end DESC
            LIMIT 50
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AdminSubscriptionRecordFact(
                                platform = rs.getString("platform"),
                                tier = rs.getString("tier"),
                                status = rs.getString("status"),
                                externalProductId = rs.getString("external_product_id"),
                                externalStatus = rs.getString("external_status"),
                                acknowledgementState = rs.getString("acknowledgement_state"),
                                currentPeriodStart = rs.getTimestamp("current_period_start").toInstant(),
                                currentPeriodEnd = rs.getTimestamp("current_period_end").toInstant(),
                                lastVerifiedAt = rs.getTimestamp("last_verified_at")?.toInstant(),
                            ),
                        )
                    }
                }
            }
        }
}
