package com.pynapli.server.profilelink

import com.pynapli.shared.network.dto.ProfileLinkDirection
import com.pynapli.shared.network.dto.ProfileLinkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcProfileLinkRepository(
    private val dataSource: DataSource,
) : ProfileLinkRepository {
    override suspend fun getSummary(viewerUserId: UUID): ProfileLinksSummaryRecord =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                ProfileLinksSummaryRecord(
                    current = connection.queryLinks(viewerUserId, LinkQueryKind.Current).firstOrNull(),
                    incomingInvites = connection.queryLinks(viewerUserId, LinkQueryKind.Incoming),
                    outgoingInvites = connection.queryLinks(viewerUserId, LinkQueryKind.Outgoing),
                )
            }
        }

    override suspend fun isLinkableUser(userId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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
        }

    override suspend fun hasActiveLink(userId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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
        }

    override suspend fun getPendingInvitationForRecipient(
        recipientUserId: UUID,
        linkId: UUID,
    ): PendingProfileLinkInvitationRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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
                            if (!rs.next()) null else rs.toPendingInvitationRecord()
                        }
                    }
            }
        }

    override suspend fun findPendingBetween(
        userIdA: UUID,
        userIdB: UUID,
    ): PendingProfileLinkInvitationRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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
                            if (!rs.next()) null else rs.toPendingInvitationRecord()
                        }
                    }
            }
        }

    override suspend fun findRecentUnlinkForEitherUser(
        userIdA: UUID,
        userIdB: UUID,
        since: Instant,
    ): ProfileLinkCooldownRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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
                            if (!rs.next()) null else rs.toProfileLinkCooldownRecord()
                        }
                    }
            }
        }

    override suspend fun createInvitation(
        requesterUserId: UUID,
        targetUserId: UUID,
        linkIdFactory: () -> UUID,
    ): ProfileLinkRecord =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at, unlinked_at)
                        VALUES (?, ?, ?, 'pending', NULL, NULL)
                        ON CONFLICT (user_id, linked_user_id) DO UPDATE
                        SET status = 'pending',
                            accepted_at = NULL,
                            unlinked_at = NULL,
                            updated_at = now()
                        WHERE user_profile_links.status IN ('declined', 'unlinked')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, linkIdFactory())
                        statement.setObject(2, requesterUserId)
                        statement.setObject(3, targetUserId)
                        statement.executeUpdate()
                    }

                connection
                    .queryLinks(requesterUserId, LinkQueryKind.Outgoing)
                    .firstOrNull { it.otherUserId == targetUserId }
                    ?: throw ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")
            }
        }

    override suspend fun acceptInvitation(
        recipientUserId: UUID,
        linkId: UUID,
        reciprocalLinkIdFactory: () -> UUID,
    ): ProfileLinkAcceptOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val invitation =
                        connection.lockPendingInvitationForRecipient(recipientUserId, linkId)
                            ?: run {
                                connection.rollback()
                                return@withContext ProfileLinkAcceptOutcome.NotFound
                            }

                    if (connection.hasActiveLinkInPair(invitation.requesterUserId, invitation.recipientUserId)) {
                        connection.rollback()
                        return@withContext ProfileLinkAcceptOutcome.AlreadyLinked
                    }

                    connection
                        .prepareStatement(
                            """
                            UPDATE user_profile_links
                            SET status = 'active',
                                accepted_at = COALESCE(accepted_at, now()),
                                unlinked_at = NULL,
                                updated_at = now()
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, linkId)
                            statement.executeUpdate()
                        }

                    connection
                        .prepareStatement(
                            """
                            INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at, unlinked_at)
                            VALUES (?, ?, ?, 'active', now(), NULL)
                            ON CONFLICT (user_id, linked_user_id) DO UPDATE
                            SET status = 'active',
                                accepted_at = COALESCE(user_profile_links.accepted_at, EXCLUDED.accepted_at),
                                unlinked_at = NULL,
                                updated_at = now()
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, reciprocalLinkIdFactory())
                            statement.setObject(2, invitation.recipientUserId)
                            statement.setObject(3, invitation.requesterUserId)
                            statement.executeUpdate()
                        }

                    connection.declineOtherPendingInvites(invitation.requesterUserId, invitation.recipientUserId)
                    connection.commit()
                    ProfileLinkAcceptOutcome.Accepted
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    override suspend fun declineInvitation(
        recipientUserId: UUID,
        linkId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE user_profile_links
                        SET status = 'declined',
                            updated_at = now()
                        WHERE id = ?
                          AND linked_user_id = ?
                          AND status = 'pending'
                          AND unlinked_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, linkId)
                        statement.setObject(2, recipientUserId)
                        statement.executeUpdate() > 0
                    }
            }
        }

    override suspend fun unlinkCurrent(userId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        WITH current_link AS (
                            SELECT linked_user_id AS partner_user_id
                            FROM user_profile_links
                            WHERE user_id = ?
                              AND status = 'active'
                              AND unlinked_at IS NULL
                            LIMIT 1
                        )
                        UPDATE user_profile_links
                        SET status = 'unlinked',
                            unlinked_at = now(),
                            updated_at = now()
                        WHERE status = 'active'
                          AND unlinked_at IS NULL
                          AND (
                              (user_id = ? AND linked_user_id = (SELECT partner_user_id FROM current_link))
                              OR (user_id = (SELECT partner_user_id FROM current_link) AND linked_user_id = ?)
                          )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.setObject(2, userId)
                        statement.setObject(3, userId)
                        statement.executeUpdate() > 0
                    }
            }
        }

    private fun Connection.queryLinks(
        viewerUserId: UUID,
        kind: LinkQueryKind,
    ): List<ProfileLinkRecord> {
        val (whereClause, direction) =
            when (kind) {
                LinkQueryKind.Current ->
                    "upl.user_id = ? AND upl.status = 'active' AND upl.unlinked_at IS NULL" to
                        ProfileLinkDirection.CURRENT
                LinkQueryKind.Incoming ->
                    "upl.linked_user_id = ? AND upl.status = 'pending' AND upl.unlinked_at IS NULL" to
                        ProfileLinkDirection.INCOMING
                LinkQueryKind.Outgoing ->
                    "upl.user_id = ? AND upl.status = 'pending' AND upl.unlinked_at IS NULL" to
                        ProfileLinkDirection.OUTGOING
            }
        val otherUserExpression =
            when (kind) {
                LinkQueryKind.Incoming -> "upl.user_id"
                LinkQueryKind.Current,
                LinkQueryKind.Outgoing,
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
                        add(rs.toProfileLinkRecord(direction))
                    }
                }
            }
        }
    }

    private fun Connection.lockPendingInvitationForRecipient(
        recipientUserId: UUID,
        linkId: UUID,
    ): PendingProfileLinkInvitationRecord? =
        prepareStatement(
            """
            SELECT id, user_id, linked_user_id
            FROM user_profile_links
            WHERE id = ?
              AND linked_user_id = ?
              AND status = 'pending'
              AND unlinked_at IS NULL
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, linkId)
            statement.setObject(2, recipientUserId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) null else rs.toPendingInvitationRecord()
            }
        }

    private fun Connection.hasActiveLinkInPair(
        userIdA: UUID,
        userIdB: UUID,
    ): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM user_profile_links
                WHERE status = 'active'
                  AND unlinked_at IS NULL
                  AND (user_id IN (?, ?) OR linked_user_id IN (?, ?))
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userIdA)
            statement.setObject(2, userIdB)
            statement.setObject(3, userIdA)
            statement.setObject(4, userIdB)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.declineOtherPendingInvites(
        userIdA: UUID,
        userIdB: UUID,
    ) {
        prepareStatement(
            """
            UPDATE user_profile_links
            SET status = 'declined',
                updated_at = now()
            WHERE status = 'pending'
              AND unlinked_at IS NULL
              AND (user_id IN (?, ?) OR linked_user_id IN (?, ?))
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userIdA)
            statement.setObject(2, userIdB)
            statement.setObject(3, userIdA)
            statement.setObject(4, userIdB)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toProfileLinkRecord(direction: String): ProfileLinkRecord =
        ProfileLinkRecord(
            linkId = getObject("id", UUID::class.java),
            otherUserId = getObject("other_user_id", UUID::class.java),
            otherDisplayName = getString("other_display_name"),
            otherPhotoMediaAssetId = getObject("other_photo_media_asset_id", UUID::class.java),
            status = getString("status"),
            direction = direction,
            createdAt = getTimestamp("created_at").toInstant(),
            acceptedAt = getTimestamp("accepted_at")?.toInstant(),
            unlinkedAt = getTimestamp("unlinked_at")?.toInstant(),
        )

    private fun ResultSet.toPendingInvitationRecord(): PendingProfileLinkInvitationRecord =
        PendingProfileLinkInvitationRecord(
            linkId = getObject("id", UUID::class.java),
            requesterUserId = getObject("user_id", UUID::class.java),
            recipientUserId = getObject("linked_user_id", UUID::class.java),
        )

    private fun ResultSet.toProfileLinkCooldownRecord(): ProfileLinkCooldownRecord =
        ProfileLinkCooldownRecord(
            userId = getObject("user_id", UUID::class.java),
            linkedUserId = getObject("linked_user_id", UUID::class.java),
            unlinkedAt = getTimestamp("unlinked_at").toInstant(),
        )

    private enum class LinkQueryKind {
        Current,
        Incoming,
        Outgoing,
    }
}
