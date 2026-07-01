package com.pynapli.server.admin

import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAdminSubscriptionSupportRepository(
    private val dataSource: DataSource,
) : AdminSubscriptionSupportRepository {
    override suspend fun facts(userId: UUID): AdminSubscriptionSupportFacts? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                if (!connection.userExists(userId)) return@use null

                AdminSubscriptionSupportFacts(
                    subscriptions = connection.subscriptions(userId),
                    benefitsGranted = connection.benefits(userId, asSubscriber = true),
                    benefitsReceived = connection.benefits(userId, asSubscriber = false),
                )
            }
        }

    override suspend fun recordReviewAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    ) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    // asSubscriber selects the benefits this user grants; otherwise the benefits they receive. The
    // column names are compile-time constants (never user input), so the interpolation is injection-safe.
    private fun Connection.benefits(
        userId: UUID,
        asSubscriber: Boolean,
    ): List<AdminLinkedBenefitFact> {
        val filterColumn = if (asSubscriber) "subscriber_user_id" else "beneficiary_user_id"
        val otherColumn = if (asSubscriber) "beneficiary_user_id" else "subscriber_user_id"
        return prepareStatement(
            """
            SELECT $otherColumn AS other_user_id, tier, status, starts_at, ends_at
            FROM linked_partner_benefits
            WHERE $filterColumn = ?
            ORDER BY starts_at DESC
            LIMIT 50
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AdminLinkedBenefitFact(
                                otherUserId = rs.getObject("other_user_id", UUID::class.java),
                                tier = rs.getString("tier"),
                                status = rs.getString("status"),
                                startsAt = rs.getTimestamp("starts_at").toInstant(),
                                endsAt = rs.getTimestamp("ends_at")?.toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }
}
