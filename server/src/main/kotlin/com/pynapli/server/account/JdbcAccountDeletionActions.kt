package com.pynapli.server.account

import com.pynapli.server.auth.CLEAR_REFRESH_REPLAY_SQL
import com.pynapli.server.contactblocks.ContactBlockTokens
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.metinperson.MetInPersonAccountData
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAccountDeletionActions(
    private val dataSource: DataSource,
    private val contactBlockTokens: ContactBlockTokens,
    private val metInPersonAccountData: MetInPersonAccountData? = null,
) {
    suspend fun deleteAccount(userId: UUID): AccountDeletionResult? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val deleted = markUserDeleted(connection, userId)
                if (!deleted) {
                    connection.rollback()
                    return@withConnection null
                }

                hideProfile(connection, userId)
                removeFromDiscovery(connection, userId)
                clearActiveTravelLocation(connection, userId)
                // GDPR/CCPA erasure: scrub PII while preserving safety/legal audit rows.
                anonymizeUserIdentity(connection, userId)
                anonymizeAuthIdentities(connection, userId)
                anonymizeProfile(connection, userId)
                val purgedLocationCount = purgeExactLocation(connection, userId)
                purgeFavoritesAndLastActive(connection, userId)
                releaseContactBlocks(connection, userId)
                // Met in person: confirmations given (by token) and received, and the badge.
                metInPersonAccountData?.erase(connection, userId)
                val endedPartnerLinkCount = endActivePartnerLinks(connection, userId)
                val softDeletedMediaCount = softDeleteOwnedMedia(connection, userId)
                val revokedSessionCount = revokeSessions(connection, userId)
                disablePushDevices(connection, userId)
                // Nothing they started keeps running for other people: live Tonight sessions end,
                // Psync sessions are cancelled (their likes are never delivered), and pushes still
                // queued to or about them are dropped.
                endLiveSessions(connection, userId)
                dropPendingNotifications(connection, userId)
                recordDeletionEvent(connection, userId, revokedSessionCount)

                connection.commit()
                AccountDeletionResult(
                    userId = userId,
                    revokedSessionCount = revokedSessionCount,
                    purgedLocationCount = purgedLocationCount,
                    endedPartnerLinkCount = endedPartnerLinkCount,
                    softDeletedMediaCount = softDeletedMediaCount,
                )
            }
        }

    private fun endLiveSessions(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE tonight_sessions
                SET status = 'ended', ended_at = COALESCE(ended_at, now())
                WHERE status IN ('proposed', 'active')
                  AND id IN (SELECT session_id FROM tonight_session_participants WHERE user_id = ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                UPDATE psync_sessions
                SET status = 'cancelled', ended_at = COALESCE(ended_at, now())
                WHERE status IN ('invited', 'configuring', 'ready', 'active', 'paused')
                  AND id IN (SELECT session_id FROM psync_session_participants WHERE user_id = ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun dropPendingNotifications(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE notification_outbox
                SET status = 'skipped', last_error = 'ACCOUNT_DELETED', updated_at = now()
                WHERE status = 'pending'
                  AND (recipient_user_id = ? OR actor_user_id = ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }

    private fun markUserDeleted(
        connection: Connection,
        userId: UUID,
    ): Boolean {
        connection
            .prepareStatement(
                """
                UPDATE users
                SET status = 'deleted',
                    deleted_at = COALESCE(deleted_at, now()),
                    updated_at = now()
                WHERE id = ?
                  AND deleted_at IS NULL
                  AND status <> 'deleted'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                return statement.executeUpdate() > 0
            }
    }

    private fun hideProfile(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE profiles
                SET visibility_status = 'hidden',
                    updated_at = now()
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun removeFromDiscovery(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE discovery_settings
                SET grid_visible = false,
                    swipe_visible = false,
                    events_visible = false,
                    show_in_viewed_lists = false,
                    allow_grid_messages = false,
                    allow_flirts = false,
                    allow_intro_notes = false,
                    allow_album_requests = false,
                    visible_while_traveling = false,
                    updated_at = now()
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun clearActiveTravelLocation(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE travel_locations
                SET status = 'cleared',
                    active_until = now()
                WHERE user_id = ?
                  AND status = 'active'
                  AND active_until IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun anonymizeUserIdentity(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE users
                SET email_hash = NULL,
                    phone_hash = NULL,
                    firebase_uid = NULL,
                    updated_at = now()
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun anonymizeAuthIdentities(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE auth_identities
                SET provider_subject = 'deleted:' || id,
                    password_hash = NULL,
                    password_salt = NULL,
                    password_iterations = NULL
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun anonymizeProfile(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE profiles
                SET display_name = NULL,
                    bio = NULL,
                    neighborhood = NULL,
                    birthdate = NULL,
                    gender = NULL,
                    gender_detail = NULL,
                    orientation = NULL,
                    orientation_detail = NULL,
                    lifestyle_tags = NULL,
                    experience_level = NULL,
                    boundaries = NULL,
                    logistics_tags = NULL,
                    intents = NULL,
                    updated_at = now()
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun purgeExactLocation(
        connection: Connection,
        userId: UUID,
    ): Int {
        connection
            .prepareStatement(
                """
                DELETE FROM user_locations
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                return statement.executeUpdate()
            }
    }

    /** Their private Favorites (both directions) and last-active timestamp go with the account. */
    private fun purgeFavoritesAndLastActive(
        connection: Connection,
        userId: UUID,
    ) {
        connection.prepareStatement("DELETE FROM favorite_profiles WHERE user_id = ? OR favorite_user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM user_last_active WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }

    /**
     * "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact blocks"): the addresses this person
     * added go with the account; rows other people added that point at this person (hidden or exempt)
     * go back to pending, so they bind again only if the address re-registers and is verified, and
     * tombstones pointing at this person are deleted (there is nobody left to hide anything from); and
     * the account's own contact token is cleared (users is anonymised, not deleted, so each is explicit).
     * The blocker's own rows, tombstones included, are deleted.
     */
    private fun releaseContactBlocks(
        connection: Connection,
        userId: UUID,
    ) {
        connection.prepareStatement("DELETE FROM contact_blocks WHERE blocker_user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection
            .prepareStatement("DELETE FROM contact_blocks WHERE target_token IN (?, ?) AND released_at IS NOT NULL")
            .use { statement ->
                statement.setString(1, contactBlockTokens.targetToken(userId))
                statement.setString(2, contactBlockTokens.exemptToken(userId))
                statement.executeUpdate()
            }
        connection
            .prepareStatement("UPDATE contact_blocks SET target_token = NULL WHERE target_token IN (?, ?)")
            .use { statement ->
                statement.setString(1, contactBlockTokens.targetToken(userId))
                statement.setString(2, contactBlockTokens.exemptToken(userId))
                statement.executeUpdate()
            }
        connection.prepareStatement("UPDATE users SET contact_token = NULL WHERE id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }

    private fun endActivePartnerLinks(
        connection: Connection,
        userId: UUID,
    ): Int {
        connection
            .prepareStatement(
                """
                UPDATE user_profile_links
                SET status = 'unlinked',
                    unlinked_at = COALESCE(unlinked_at, now()),
                    updated_at = now()
                WHERE (user_id = ? OR linked_user_id = ?)
                  AND status IN ('pending', 'active')
                  AND unlinked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, userId)
                return statement.executeUpdate()
            }
    }

    private fun softDeleteOwnedMedia(
        connection: Connection,
        userId: UUID,
    ): Int {
        connection
            .prepareStatement(
                """
                UPDATE media_assets
                SET deleted_at = COALESCE(deleted_at, now()),
                    updated_at = now()
                WHERE owner_user_id = ?
                  AND deleted_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                return statement.executeUpdate()
            }
    }

    // Includes devices registered before push devices followed sessions (session_id NULL).
    private fun disablePushDevices(
        connection: Connection,
        userId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE push_devices
                SET disabled_at = now(),
                    notifications_enabled = false,
                    updated_at = now()
                WHERE user_id = ?
                  AND disabled_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    private fun revokeSessions(
        connection: Connection,
        userId: UUID,
    ): Int {
        connection
            .prepareStatement(
                """
                UPDATE user_sessions
                SET revoked_at = now(),
                    $CLEAR_REFRESH_REPLAY_SQL
                WHERE user_id = ?
                  AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                return statement.executeUpdate()
            }
    }

    private fun recordDeletionEvent(
        connection: Connection,
        userId: UUID,
        revokedSessionCount: Int,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO account_deletion_events (
                    id,
                    user_id,
                    revoked_session_count,
                    retention_note
                )
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setInt(3, revokedSessionCount)
                statement.setString(4, ACCOUNT_DELETION_RETENTION_NOTE)
                statement.executeUpdate()
            }
    }

    private companion object {
        private const val ACCOUNT_DELETION_RETENTION_NOTE =
            "Account removed from discovery and sessions revoked; safety and legal retention records preserved."
    }
}
