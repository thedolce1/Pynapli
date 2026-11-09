package com.pynapli.server.profilelink

import com.pynapli.server.db.withConnection
import com.pynapli.shared.network.dto.ProfileLinkDirection
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcProfileLinkReadActions(
    private val dataSource: DataSource,
) {
    suspend fun getSummary(viewerUserId: UUID): ProfileLinksSummaryRecord =
        dataSource.withConnection { connection ->
            ProfileLinksSummaryRecord(
                current =
                    connection
                        .queryProfileLinks(viewerUserId, JdbcProfileLinkQueryKind.Current)
                        .firstOrNull(),
                incomingInvites =
                    connection.queryProfileLinks(viewerUserId, JdbcProfileLinkQueryKind.Incoming),
                outgoingInvites =
                    connection.queryProfileLinks(viewerUserId, JdbcProfileLinkQueryKind.Outgoing),
            )
        }

    suspend fun isLinkableUser(userId: UUID): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM users u
                        JOIN profiles p ON p.user_id = u.id
                        JOIN discovery_settings ds ON ds.user_id = u.id
                        WHERE u.id = ?
                          AND u.status = 'active'
                          AND u.deleted_at IS NULL
                          AND p.visibility_status = 'visible'
                          AND ds.incognito = false
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
        }

    suspend fun hasActiveLink(userId: UUID): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM user_profile_links
                        WHERE status = 'active'
                          AND unlinked_at IS NULL
                          AND (user_id = ? OR linked_user_id = ?)
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
        }

    suspend fun getPendingInvitationForRecipient(
        recipientUserId: UUID,
        linkId: UUID,
    ): PendingProfileLinkInvitationRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, user_id, linked_user_id
                    FROM user_profile_links
                    WHERE id = ?
                      AND linked_user_id = ?
                      AND status = 'pending'
                      AND unlinked_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, linkId)
                    statement.setObject(2, recipientUserId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.toJdbcPendingInvitationRecord()
                    }
                }
        }

    suspend fun findPendingBetween(
        userIdA: UUID,
        userIdB: UUID,
    ): PendingProfileLinkInvitationRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, user_id, linked_user_id
                    FROM user_profile_links
                    WHERE status = 'pending'
                      AND unlinked_at IS NULL
                      AND (
                          (user_id = ? AND linked_user_id = ?)
                          OR (user_id = ? AND linked_user_id = ?)
                      )
                    ORDER BY created_at DESC, id ASC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userIdA)
                    statement.setObject(2, userIdB)
                    statement.setObject(3, userIdB)
                    statement.setObject(4, userIdA)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.toJdbcPendingInvitationRecord()
                    }
                }
        }

    suspend fun findRecentUnlinkForEitherUser(
        userIdA: UUID,
        userIdB: UUID,
        since: Instant,
    ): ProfileLinkCooldownRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT user_id,
                           linked_user_id,
                           unlinked_at
                    FROM user_profile_links
                    WHERE status = 'unlinked'
                      AND unlinked_at IS NOT NULL
                      AND unlinked_at >= ?
                      AND (
                          user_id IN (?, ?)
                          OR linked_user_id IN (?, ?)
                      )
                    ORDER BY unlinked_at DESC, id ASC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(since))
                    statement.setObject(2, userIdA)
                    statement.setObject(3, userIdB)
                    statement.setObject(4, userIdA)
                    statement.setObject(5, userIdB)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.toJdbcProfileLinkCooldownRecord()
                    }
                }
        }
}

internal fun Connection.queryProfileLinks(
    viewerUserId: UUID,
    kind: JdbcProfileLinkQueryKind,
): List<ProfileLinkRecord> {
    val (whereClause, direction) =
        when (kind) {
            JdbcProfileLinkQueryKind.Current ->
                "upl.user_id = ? AND upl.status = 'active' AND upl.unlinked_at IS NULL" to
                    ProfileLinkDirection.CURRENT
            JdbcProfileLinkQueryKind.Incoming ->
                "upl.linked_user_id = ? AND upl.status = 'pending' AND upl.unlinked_at IS NULL" to
                    ProfileLinkDirection.INCOMING
            JdbcProfileLinkQueryKind.Outgoing ->
                "upl.user_id = ? AND upl.status = 'pending' AND upl.unlinked_at IS NULL" to
                    ProfileLinkDirection.OUTGOING
        }
    val otherUserExpression =
        when (kind) {
            JdbcProfileLinkQueryKind.Incoming -> "upl.user_id"
            JdbcProfileLinkQueryKind.Current,
            JdbcProfileLinkQueryKind.Outgoing,
            -> "upl.linked_user_id"
        }

    return prepareStatement(
        """
        SELECT upl.id,
               $otherUserExpression AS other_user_id,
               p.display_name AS other_display_name,
               profile_photo.media_asset_id AS other_photo_media_asset_id,
               upl.status,
               upl.created_at,
               upl.accepted_at,
               upl.unlinked_at
        FROM user_profile_links upl
        JOIN users other_user
          ON other_user.id = $otherUserExpression
        LEFT JOIN profiles p
          ON p.user_id = other_user.id
        LEFT JOIN LATERAL (
            SELECT mai.media_asset_id
            FROM media_albums ma
            JOIN media_album_items mai
              ON mai.album_id = ma.id
             AND mai.removed_at IS NULL
            JOIN media_assets m
              ON m.id = mai.media_asset_id
             AND m.owner_user_id = ma.owner_user_id
             AND m.deleted_at IS NULL
            WHERE ma.owner_user_id = other_user.id
              AND ma.kind = 'profile'
              AND ma.visibility_status = 'visible'
              AND ma.deleted_at IS NULL
              AND m.media_type = 'image'
              AND m.processing_status = 'ready'
              AND m.moderation_status = 'approved'
            ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
            LIMIT 1
        ) profile_photo ON true
        WHERE $whereClause
        ORDER BY upl.created_at DESC, upl.id ASC
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(rs.toJdbcProfileLinkRecord(direction))
                }
            }
        }
    }
}

internal enum class JdbcProfileLinkQueryKind {
    Current,
    Incoming,
    Outgoing,
}
