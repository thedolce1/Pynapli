package com.pynapli.server.safety

import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

internal fun Connection.createAppealInTransaction(
    userId: UUID,
    actionType: String,
    targetType: String,
    targetId: String?,
    relatedReportId: UUID?,
    reason: String,
    appealIdFactory: () -> UUID,
): CreateAppealResult {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        if (relatedReportId != null && !relatedReportBelongsToUser(relatedReportId, userId)) {
            rollback()
            return CreateAppealResult.RelatedReportNotFound
        }
        if (!appealableTargetExists(userId, actionType, targetType, targetId)) {
            rollback()
            return CreateAppealResult.TargetNotAppealable
        }
        val appeal =
            insertAppeal(
                appealId = appealIdFactory(),
                userId = userId,
                actionType = actionType,
                targetType = targetType,
                targetId = targetId,
                relatedReportId = relatedReportId,
                reason = reason,
            )
        commit()
        return CreateAppealResult.Created(appeal)
    } catch (error: SQLException) {
        rollback()
        if (error.sqlState == APPEAL_UNIQUE_VIOLATION_SQLSTATE) {
            return CreateAppealResult.DuplicateOpenAppeal
        }
        throw error
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
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
