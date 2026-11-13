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

    /**
     * A private album the reported person owns and shared with the reporter (now or before): the
     * evidence lists its photos' media ids so moderators can review exactly what was shown.
     */
    suspend fun albumEvidence(
        albumId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
    ): String? = null
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
                    SELECT CASE
                               WHEN m.media_asset_id IS NOT NULL AND btrim(m.body) = '' THEN '[photo ' || m.media_asset_id || ']'
                               WHEN m.media_asset_id IS NOT NULL THEN m.body || ' [photo ' || m.media_asset_id || ']'
                               ELSE m.body
                           END
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

    override suspend fun albumEvidence(
        albumId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
    ): String? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT 'private album ' || a.id || ': ' ||
                           COALESCE(string_agg(i.media_asset_id::text, ', ' ORDER BY i.sort_order), 'no photos')
                    FROM media_albums a
                    JOIN media_album_access_grants g
                      ON g.album_id = a.id
                     AND g.grantee_user_id = ?
                    LEFT JOIN media_album_items i
                      ON i.album_id = a.id
                     AND i.removed_at IS NULL
                    WHERE a.id = ?
                      AND a.owner_user_id = ?
                      AND a.kind = 'private'
                    GROUP BY a.id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, reporterUserId)
                    statement.setObject(2, albumId)
                    statement.setObject(3, reportedUserId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
        }
}
