package com.pynapli.server.moderation

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

class JdbcAdminAppealRepository(
    private val dataSource: DataSource,
) : AdminAppealRepository {
    override suspend fun listAppeals(
        status: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminAppealRecord> =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                connection.insertAdminAuditLog(
                    actorId = actorId,
                    action = "appeals.list",
                    targetType = "moderation_appeals",
                    targetId = null,
                    reason = reason,
                    metadataJson = """{"status":"${status.jsonEscaped()}","limit":$limit}""",
                )
                val appeals = connection.selectAppeals(status, limit)
                connection.commit()
                appeals
            }
        }

    override suspend fun markUnderReview(
        appealId: UUID,
        actorId: String,
        reason: String,
    ): AdminAppealActionRecord? =
        dataSource.withConnection { connection ->
            connection.updateAppealStatusWithAudit(
                appealId = appealId,
                status = "under_review",
                actorId = actorId,
                reason = reason,
                adminNote = null,
            )
        }

    override suspend fun decideAppeal(
        appealId: UUID,
        status: String,
        actorId: String,
        reason: String,
        adminNote: String?,
    ): AdminAppealActionRecord? =
        dataSource.withConnection { connection ->
            connection.updateAppealStatusWithAudit(
                appealId = appealId,
                status = status,
                actorId = actorId,
                reason = reason,
                adminNote = adminNote,
            )
        }

    private fun Connection.selectAppeals(
        status: String,
        limit: Int,
    ): List<AdminAppealRecord> =
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
                   reviewed_at,
                   reviewed_by_admin_id
            FROM moderation_appeals
            WHERE (? = 'all' OR status = ?)
            ORDER BY
                CASE status
                    WHEN 'open' THEN 0
                    WHEN 'under_review' THEN 1
                    ELSE 2
                END ASC,
                created_at ASC,
                id ASC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, status)
            statement.setInt(3, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toAdminAppealRecord())
                }
            }
        }

    private fun Connection.updateAppealStatusWithAudit(
        appealId: UUID,
        status: String,
        actorId: String,
        reason: String,
        adminNote: String?,
    ): AdminAppealActionRecord? {
        inTransaction {
            val locked =
                lockActionableAppeal(appealId) ?: run {
                    rollback()
                    return null
                }
            val action =
                updateAppealStatus(
                    appealId = appealId,
                    status = status,
                    actorId = actorId,
                    reason = reason,
                    adminNote = adminNote,
                ) ?: run {
                    rollback()
                    return null
                }
            insertAdminAuditLog(
                actorId = actorId,
                action = "appeal.$status",
                targetType = "moderation_appeal",
                targetId = appealId,
                reason = reason,
                metadataJson =
                    """
                    {
                      "userId":"${locked.userId}",
                      "previousStatus":"${locked.status}",
                      "status":"$status",
                      "actionType":"${locked.actionType}",
                      "targetType":"${locked.targetType}",
                      "targetId":${locked.targetId.toJsonValue()},
                      "hasAdminNote":${adminNote != null}
                    }
                    """.trimIndent(),
            )
            commit()
            return action
        }
    }

    private fun Connection.lockActionableAppeal(appealId: UUID): AdminAppealRecord? =
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
                   reviewed_at,
                   reviewed_by_admin_id
            FROM moderation_appeals
            WHERE id = ?
              AND status IN ('open', 'under_review')
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, appealId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toAdminAppealRecord() else null
            }
        }

    private fun Connection.updateAppealStatus(
        appealId: UUID,
        status: String,
        actorId: String,
        reason: String,
        adminNote: String?,
    ): AdminAppealActionRecord? =
        prepareStatement(
            """
            UPDATE moderation_appeals
            SET status = ?,
                admin_reason = ?,
                admin_note = ?,
                reviewed_by_admin_id = ?,
                reviewed_at = now(),
                updated_at = now()
            WHERE id = ?
              AND status IN ('open', 'under_review')
            RETURNING id,
                      user_id,
                      status,
                      updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, reason)
            adminNote?.let { statement.setString(3, it) } ?: statement.setNull(3, Types.VARCHAR)
            statement.setString(4, actorId)
            statement.setObject(5, appealId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    AdminAppealActionRecord(
                        appealId = rs.getObject("id", UUID::class.java),
                        userId = rs.getObject("user_id", UUID::class.java),
                        status = rs.getString("status"),
                        actedAt = rs.getTimestamp("updated_at").toInstant(),
                    )
                } else {
                    null
                }
            }
        }

    private fun ResultSet.toAdminAppealRecord(): AdminAppealRecord =
        AdminAppealRecord(
            appealId = getObject("id", UUID::class.java),
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
            reviewedByAdminId = getString("reviewed_by_admin_id"),
        )

    private fun String?.toJsonValue(): String = this?.let { "\"${it.jsonEscaped()}\"" } ?: "null"

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
}
