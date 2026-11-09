package com.pynapli.server.account

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAccountDeletionActions(
    private val dataSource: DataSource,
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
                val endedPartnerLinkCount = endActivePartnerLinks(connection, userId)
                val endedBenefitCount = endLinkedPartnerBenefits(connection, userId)
                val softDeletedMediaCount = softDeleteOwnedMedia(connection, userId)
                val revokedSessionCount = revokeSessions(connection, userId)
                recordDeletionEvent(connection, userId, revokedSessionCount)

                connection.commit()
                AccountDeletionResult(
                    userId = userId,
                    revokedSessionCount = revokedSessionCount,
                    purgedLocationCount = purgedLocationCount,
                    endedPartnerLinkCount = endedPartnerLinkCount,
                    endedPartnerBenefitCount = endedBenefitCount,
                    softDeletedMediaCount = softDeletedMediaCount,
                )
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

    private fun endLinkedPartnerBenefits(
        connection: Connection,
        userId: UUID,
    ): Int {
        connection
            .prepareStatement(
                """
                UPDATE linked_partner_benefits
                SET status = 'ended',
                    ends_at = COALESCE(ends_at, now()),
                    updated_at = now()
                WHERE (subscriber_user_id = ? OR beneficiary_user_id = ?)
                  AND status = 'active'
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

    private fun revokeSessions(
        connection: Connection,
        userId: UUID,
    ): Int {
        connection
            .prepareStatement(
                """
                UPDATE user_sessions
                SET revoked_at = now()
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
