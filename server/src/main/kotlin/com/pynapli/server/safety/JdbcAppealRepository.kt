package com.pynapli.server.safety

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

class JdbcAppealRepository(
    private val dataSource: DataSource,
) : AppealRepository {
    override suspend fun createAppeal(
        userId: UUID,
        actionType: String,
        targetType: String,
        targetId: String?,
        relatedReportId: UUID?,
        reason: String,
        appealIdFactory: () -> UUID,
    ): CreateAppealResult =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    if (relatedReportId != null && !connection.relatedReportBelongsToUser(relatedReportId, userId)) {
                        connection.rollback()
                        return@withContext CreateAppealResult.RelatedReportNotFound
                    }
                    if (!connection.appealableTargetExists(userId, actionType, targetType, targetId)) {
                        connection.rollback()
                        return@withContext CreateAppealResult.TargetNotAppealable
                    }
                    val appeal =
                        connection.insertAppeal(
                            appealId = appealIdFactory(),
                            userId = userId,
                            actionType = actionType,
                            targetType = targetType,
                            targetId = targetId,
                            relatedReportId = relatedReportId,
                            reason = reason,
                        )
                    connection.commit()
                    CreateAppealResult.Created(appeal)
                } catch (error: SQLException) {
                    connection.rollback()
                    if (error.sqlState == UNIQUE_VIOLATION_SQLSTATE) {
                        CreateAppealResult.DuplicateOpenAppeal
                    } else {
                        throw error
                    }
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    override suspend fun listAppeals(
        userId: UUID,
        limit: Int,
    ): List<AppealRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.selectAppealsByUser(userId, limit)
            }
        }

    override suspend fun withdrawAppeal(
        userId: UUID,
        appealId: UUID,
    ): AppealRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.withdrawAppealByUser(userId, appealId)
            }
        }

    private fun Connection.relatedReportBelongsToUser(
        reportId: UUID,
        userId: UUID,
    ): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM safety_reports
                WHERE id = ?
                  AND (reporter_user_id = ? OR reported_user_id = ?)
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, reportId)
            statement.setObject(2, userId)
            statement.setObject(3, userId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.appealableTargetExists(
        userId: UUID,
        actionType: String,
        targetType: String,
        targetId: String?,
    ): Boolean =
        when (actionType) {
            "account_suspension" -> accountStatusAppealable(userId, "suspended")
            "account_ban" -> accountStatusAppealable(userId, "banned")
            "risk_restriction" -> riskRestrictionAppealable(userId)
            "media_removal" -> mediaRemovalAppealable(userId, targetId)
            "message_removal" -> messageRemovalAppealable(userId, targetId)
            "other" -> targetType == "other" || genericTargetBelongsToUser(userId, targetType, targetId)
            else -> false
        }

    private fun Connection.accountStatusAppealable(
        userId: UUID,
        status: String,
    ): Boolean =
        exists(
            """
            SELECT EXISTS (
                SELECT 1
                FROM users
                WHERE id = ?
                  AND status = ?
                  AND deleted_at IS NULL
                  AND updated_at >= now() - INTERVAL '30 days'
            )
            """.trimIndent(),
            userId,
            status,
        )

    private fun Connection.riskRestrictionAppealable(userId: UUID): Boolean =
        exists(
            """
            SELECT EXISTS (
                SELECT 1
                FROM account_risk_states
                WHERE user_id = ?
                  AND tier IN ('friction', 'quarantine', 'terminate')
                  AND updated_at >= now() - INTERVAL '30 days'
            )
            """.trimIndent(),
            userId,
        )

    private fun Connection.mediaRemovalAppealable(
        userId: UUID,
        targetId: String?,
    ): Boolean =
        targetId
            ?.toUuidOrNull()
            ?.let { mediaAssetId ->
                exists(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM media_assets
                        WHERE id = ?
                          AND owner_user_id = ?
                          AND moderation_status = 'removed'
                          AND deleted_at IS NULL
                          AND updated_at >= now() - INTERVAL '30 days'
                    )
                    """.trimIndent(),
                    mediaAssetId,
                    userId,
                )
            } == true

    private fun Connection.messageRemovalAppealable(
        userId: UUID,
        targetId: String?,
    ): Boolean =
        targetId
            ?.toUuidOrNull()
            ?.let { messageId ->
                exists(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM messages
                        WHERE id = ?
                          AND sender_user_id = ?
                          AND moderation_status = 'removed'
                          AND created_at >= now() - INTERVAL '30 days'
                    )
                    """.trimIndent(),
                    messageId,
                    userId,
                )
            } == true

    private fun Connection.genericTargetBelongsToUser(
        userId: UUID,
        targetType: String,
        targetId: String?,
    ): Boolean =
        when (targetType) {
            "account" -> exists("SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND deleted_at IS NULL)", userId)
            "media" -> mediaRemovalAppealable(userId, targetId)
            "message" -> messageRemovalAppealable(userId, targetId)
            "profile" -> targetId?.toUuidOrNull() == userId
            else -> false
        }

    private fun Connection.insertAppeal(
        appealId: UUID,
        userId: UUID,
        actionType: String,
        targetType: String,
        targetId: String?,
        relatedReportId: UUID?,
        reason: String,
    ): AppealRecord =
        prepareStatement(
            """
            INSERT INTO moderation_appeals (
                id,
                user_id,
                action_type,
                target_type,
                target_id,
                related_report_id,
                user_reason
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id,
                      user_id,
                      action_type,
                      target_type,
                      target_id,
                      related_report_id,
                      status,
                      user_reason,
                      admin_note,
                      created_at,
                      updated_at,
                      reviewed_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, appealId)
            statement.setObject(2, userId)
            statement.setString(3, actionType)
            statement.setString(4, targetType)
            statement.setNullableString(5, targetId)
            relatedReportId?.let { statement.setObject(6, it) } ?: statement.setNull(6, Types.OTHER)
            statement.setString(7, reason)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.toAppealRecord()
            }
        }

    private fun Connection.selectAppealsByUser(
        userId: UUID,
        limit: Int,
    ): List<AppealRecord> =
        prepareStatement(
            """
            SELECT id,
                   user_id,
                   action_type,
                   target_type,
                   target_id,
                   related_report_id,
                   status,
                   user_reason,
                   admin_note,
                   created_at,
                   updated_at,
                   reviewed_at
            FROM moderation_appeals
            WHERE user_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toAppealRecord())
                }
            }
        }

    private fun Connection.withdrawAppealByUser(
        userId: UUID,
        appealId: UUID,
    ): AppealRecord? =
        prepareStatement(
            """
            UPDATE moderation_appeals
            SET status = 'withdrawn',
                updated_at = now()
            WHERE id = ?
              AND user_id = ?
              AND status IN ('open', 'under_review')
            RETURNING id,
                      user_id,
                      action_type,
                      target_type,
                      target_id,
                      related_report_id,
                      status,
                      user_reason,
                      admin_note,
                      created_at,
                      updated_at,
                      reviewed_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, appealId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toAppealRecord() else null
            }
        }

    private fun Connection.exists(
        sql: String,
        vararg values: Any,
    ): Boolean =
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value ->
                statement.setObject(index + 1, value)
            }
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun ResultSet.toAppealRecord(): AppealRecord =
        AppealRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            actionType = getString("action_type"),
            targetType = getString("target_type"),
            targetId = getString("target_id"),
            relatedReportId = getObject("related_report_id", UUID::class.java),
            status = getString("status"),
            userReason = getString("user_reason"),
            adminNote = getString("admin_note"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            reviewedAt = getTimestamp("reviewed_at")?.toInstant(),
        )

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private fun java.sql.PreparedStatement.setNullableString(
        index: Int,
        value: String?,
    ) {
        value?.let { setString(index, it) } ?: setNull(index, Types.VARCHAR)
    }

    companion object {
        private const val UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
