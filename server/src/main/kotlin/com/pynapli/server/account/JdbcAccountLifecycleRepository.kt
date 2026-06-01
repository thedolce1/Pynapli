package com.pynapli.server.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcAccountLifecycleRepository(
    private val dataSource: DataSource,
) : AccountLifecycleRepository {
    override suspend fun latestDataExportRequest(userId: UUID): AccountDataExportRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, user_id, status, requested_at, completed_at, expires_at, download_url, error_message
                        FROM account_data_export_requests
                        WHERE user_id = ?
                        ORDER BY requested_at DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) return@withContext null
                            rs.toAccountDataExportRecord()
                        }
                    }
            }
        }

    override suspend fun createDataExportRequest(userId: UUID): AccountDataExportRecord =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO account_data_export_requests (id, user_id, status)
                        VALUES (?, ?, 'requested')
                        RETURNING id, user_id, status, requested_at, completed_at, expires_at, download_url, error_message
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, requestId)
                        statement.setObject(2, userId)
                        statement.executeQuery().use { rs ->
                            check(rs.next()) { "Data export request insert returned no row." }
                            rs.toAccountDataExportRecord()
                        }
                    }
            }
        }

    override suspend fun deleteAccount(userId: UUID): AccountDeletionResult? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val deleted = markUserDeleted(connection, userId)
                    if (!deleted) {
                        connection.rollback()
                        return@withContext null
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
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
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

    /**
     * Erase account-level identity PII: null the email/phone/firebase hashes (all nullable + unique)
     * so the person can't be re-identified from the row and a future signup with the same email is
     * not blocked. The user row itself is retained (soft-deleted) for referential integrity of any
     * preserved safety/legal records.
     */
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

    /**
     * Erase auth credentials: drop the password hash/salt/iterations and replace the unique
     * provider_subject (NOT NULL) with a per-identity tombstone so the original subject (e.g. the
     * email/Firebase subject) is unrecoverable while the unique constraint stays satisfied.
     */
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

    /** Anonymize free-text and identifying profile fields; structure/audit columns are left intact. */
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
                    birthdate = NULL,
                    gender = NULL,
                    orientation = NULL,
                    updated_at = now()
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }

    /** Hard-delete the exact stored location — the most sensitive PII, with no retention basis. */
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

    /** End any active partner links on either side so the deleted user is no longer linked. */
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

    /**
     * End linked-partner subscription benefits on either side: a deleted subscriber stops granting,
     * and a deleted beneficiary stops receiving. Mirrors the MONETIZATION_SPEC "benefit ends when
     * the link ends" rule.
     */
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

    /** Soft-delete the user's media assets so their objects stop surfacing and the cleanup worker can purge them. */
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

    private fun ResultSet.toAccountDataExportRecord(): AccountDataExportRecord =
        AccountDataExportRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            status = getString("status"),
            requestedAt = getTimestamp("requested_at").toInstant(),
            completedAt = getTimestamp("completed_at")?.toInstant(),
            expiresAt = getTimestamp("expires_at")?.toInstant(),
            downloadUrl = getString("download_url"),
            errorMessage = getString("error_message"),
        )

    companion object {
        private const val ACCOUNT_DELETION_RETENTION_NOTE =
            "Account removed from discovery and sessions revoked; safety and legal retention records preserved."
    }
}
