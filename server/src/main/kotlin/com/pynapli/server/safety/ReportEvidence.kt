package com.pynapli.server.safety

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

/**
 * Captures what a report is about, so moderators see the actual content rather than an id.
 *
 * A message report must point at a message the reported person sent into a conversation the
 * reporter belongs to (left or not): you can only report what was said to you, and nobody can
 * pull another conversation's text into a report by guessing an id.
 */
interface ReportEvidenceLookup {
    /** The message text, or null when the message isn't one this reporter can report against this person. */
    suspend fun messageEvidence(
        messageId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
    ): String?
}

class JdbcReportEvidenceLookup(
    private val dataSource: DataSource,
) : ReportEvidenceLookup {
    override suspend fun messageEvidence(
        messageId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
    ): String? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT m.body
                    FROM messages m
                    JOIN conversation_participants reporter
                      ON reporter.conversation_id = m.conversation_id
                     AND reporter.user_id = ?
                    WHERE m.id = ?
                      AND m.sender_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, reporterUserId)
                    statement.setObject(2, messageId)
                    statement.setObject(3, reportedUserId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
        }
}
