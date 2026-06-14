package com.pynapli.server.safety

import java.sql.Connection
import java.util.UUID

internal fun Connection.selectAppealsByUser(
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

internal fun Connection.withdrawAppealByUser(
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
