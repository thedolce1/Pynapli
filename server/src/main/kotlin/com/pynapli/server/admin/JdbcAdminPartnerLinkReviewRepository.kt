package com.pynapli.server.admin

import com.pynapli.server.db.withConnection
import com.pynapli.server.moderation.insertAdminAuditLog
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAdminPartnerLinkReviewRepository(
    private val dataSource: DataSource,
) : AdminPartnerLinkReviewRepository {
    override suspend fun review(userId: UUID): AdminPartnerLinkReviewFacts? =
        dataSource.withConnection { connection ->
            if (!connection.userExists(userId)) return@withConnection null

            val links = connection.links(userId)
            val currentPartnerId = links.firstOrNull { it.status == "active" && it.unlinkedAt == null }?.otherUserId
            AdminPartnerLinkReviewFacts(
                partnerVisibility = connection.partnerVisibility(userId),
                links = links,
                reportsAgainstUserCount = connection.reportsAgainst(userId),
                reportsAgainstCurrentPartnerCount = currentPartnerId?.let { connection.reportsAgainst(it) } ?: 0,
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
                action = "partner_link_review",
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

    private fun Connection.partnerVisibility(userId: UUID): String =
        prepareStatement("SELECT partner_visibility FROM discovery_settings WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getString("partner_visibility") else "public"
            }
        }

    private fun Connection.links(userId: UUID): List<AdminPartnerLinkFact> =
        prepareStatement(
            """
            SELECT id, user_id, linked_user_id, status, created_at, accepted_at, unlinked_at
            FROM user_profile_links
            WHERE user_id = ? OR linked_user_id = ?
            ORDER BY created_at DESC
            LIMIT 50
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val initiator = rs.getObject("user_id", UUID::class.java)
                        val invitee = rs.getObject("linked_user_id", UUID::class.java)
                        val isInitiator = initiator == userId
                        add(
                            AdminPartnerLinkFact(
                                linkId = rs.getObject("id", UUID::class.java),
                                otherUserId = if (isInitiator) invitee else initiator,
                                status = rs.getString("status"),
                                direction = if (isInitiator) "initiated" else "received",
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                acceptedAt = rs.getTimestamp("accepted_at")?.toInstant(),
                                unlinkedAt = rs.getTimestamp("unlinked_at")?.toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.reportsAgainst(userId: UUID): Int =
        prepareStatement("SELECT count(*) FROM safety_reports WHERE reported_user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
}
