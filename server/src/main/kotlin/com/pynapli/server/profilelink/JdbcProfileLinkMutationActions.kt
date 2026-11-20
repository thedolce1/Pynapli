package com.pynapli.server.profilelink

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

internal class JdbcProfileLinkMutationActions(
    private val dataSource: DataSource,
) {
    suspend fun createInvitation(
        requesterUserId: UUID,
        targetUserId: UUID,
        linkIdFactory: () -> UUID,
    ): ProfileLinkRecord =
        dataSource.withConnection { connection ->
            try {
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
            } catch (error: SQLException) {
                // A reverse-direction invite won the pending-pair unique-index race
                // (V71): surface the same code the sequential duplicate check uses
                // instead of a 500.
                if (error.sqlState == UNIQUE_VIOLATION_SQLSTATE) {
                    throw ProfileLinkException("PROFILE_LINK_INVITE_EXISTS")
                }
                throw error
            }

            connection
                .queryProfileLinks(requesterUserId, JdbcProfileLinkQueryKind.Outgoing)
                .firstOrNull { it.otherUserId == targetUserId }
                ?: throw ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")
        }

    suspend fun acceptInvitation(
        recipientUserId: UUID,
        linkId: UUID,
        reciprocalLinkIdFactory: () -> UUID,
    ): ProfileLinkAcceptOutcome =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val invitation =
                    connection.lockPendingInvitationForRecipient(recipientUserId, linkId)
                        ?: run {
                            connection.rollback()
                            return@withConnection ProfileLinkAcceptOutcome.NotFound
                        }

                if (connection.hasActiveLinkInPair(invitation.requesterUserId, invitation.recipientUserId)) {
                    connection.rollback()
                    return@withConnection ProfileLinkAcceptOutcome.AlreadyLinked
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
            }
        }

    suspend fun declineInvitation(
        recipientUserId: UUID,
        linkId: UUID,
    ): Boolean =
        dataSource.withConnection { connection ->
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

    suspend fun unlinkCurrent(userId: UUID): Boolean =
        dataSource.withConnection { connection ->
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
                    ),
                    unlinked AS (
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
                        RETURNING id
                    ),
                    -- Met in person (TRUST_AND_SAFETY_SPEC): unlinking voids couple credit for good. The
                    -- link row id is reused on relink, so the couple fields are cleared here rather
                    -- than left to the link status; personal confirmations stay.
                    cleared_met_in_person AS (
                        UPDATE vouches
                        SET link_id = NULL
                        WHERE link_id IN (SELECT id FROM unlinked)
                        RETURNING id
                    ),
                    cleared_met_in_person_badges AS (
                        UPDATE vouch_badges
                        SET couple_badge = false, couple_link_id = NULL
                        WHERE couple_link_id IN (SELECT id FROM unlinked)
                        RETURNING subject_user_id
                    ),
                    -- PSYNC_SPEC edge case: ending the partner link ends any live Psync session. One
                    -- that never started is cancelled outright.
                    cancelled_psync AS (
                        UPDATE psync_sessions
                        SET status = 'cancelled', ended_at = now()
                        WHERE partner_link_id IN (SELECT id FROM unlinked)
                          AND status IN ('invited', 'configuring', 'ready')
                        RETURNING id
                    ),
                    -- A started one has decisions in it: put its deadline in the past so the Psync
                    -- service refuses further swipes and resolves it (likes made so far are
                    -- delivered, matches resolve) instead of discarding them. A minute back rather
                    -- than now() so app/database clock skew can't leave it briefly swipeable.
                    ended_psync AS (
                        UPDATE psync_sessions
                        SET expires_at = now() - interval '1 minute'
                        WHERE partner_link_id IN (SELECT id FROM unlinked)
                          AND status IN ('active', 'paused')
                        RETURNING id
                    ),
                    -- PLAYING_TONIGHT_SPEC: "Session ends if partner link ends." Without this a
                    -- couple who unlinked could stay live on the public Tonight feed as a pair.
                    ended_tonight AS (
                        UPDATE tonight_sessions ts
                        SET status = 'ended', ended_at = now()
                        WHERE EXISTS (SELECT 1 FROM unlinked)
                          AND ts.mode = 'together'
                          AND ts.status IN ('proposed', 'active')
                          AND EXISTS (
                              SELECT 1 FROM tonight_session_participants p
                              WHERE p.session_id = ts.id AND p.user_id = ?
                          )
                          AND EXISTS (
                              SELECT 1 FROM tonight_session_participants p
                              WHERE p.session_id = ts.id AND p.user_id = (SELECT partner_user_id FROM current_link)
                          )
                        RETURNING ts.id
                    )
                    SELECT count(*) AS unlinked_count FROM unlinked
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, userId)
                    statement.setObject(3, userId)
                    statement.setObject(4, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt("unlinked_count") > 0
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
                if (!rs.next()) null else rs.toJdbcPendingInvitationRecord()
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

    private companion object {
        private const val UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
