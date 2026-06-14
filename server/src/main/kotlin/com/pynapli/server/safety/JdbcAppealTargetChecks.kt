package com.pynapli.server.safety

import java.sql.Connection
import java.util.UUID

internal fun Connection.relatedReportBelongsToUser(
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

internal fun Connection.appealableTargetExists(
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
