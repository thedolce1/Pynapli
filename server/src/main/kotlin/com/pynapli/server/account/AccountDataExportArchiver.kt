package com.pynapli.server.account

import com.pynapli.server.contactblocks.ContactBlockTokens
import com.pynapli.server.db.withConnection
import com.pynapli.server.metinperson.MetInPersonAccountData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/** The serialized archive bytes plus a suggested filename + content type. */
data class ExportArchive(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

interface AccountDataExportArchiver {
    suspend fun buildArchive(userId: UUID): ExportArchive
}

/**
 * Builds a GDPR-style JSON archive of a user's **own** data: account basics, profile,
 * discovery/privacy settings, the messages they sent, and the activity directed at them. Other
 * users' private content is not included. Rows are serialized generically (column -> string) so the
 * export stays correct as columns evolve; sensitive auth columns (password/email hashes, tokens)
 * live in other tables and are never queried here.
 */
class JdbcAccountDataExportArchiver(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    // The server's contact block keys (ServerDependencies passes the configured ones).
    private val contactBlockTokens: ContactBlockTokens,
    // K3-dependent: required, so a dropped wiring fails the build instead of exporting nothing.
    private val metInPersonAccountData: MetInPersonAccountData,
) : AccountDataExportArchiver {
    override suspend fun buildArchive(userId: UUID): ExportArchive =
        dataSource.withConnection { connection ->
            val document =
                buildJsonObject {
                    put("exportedAt", clock.instant().toString())
                    put("userId", userId.toString())
                    put("account", connection.queryRows("SELECT id, created_at FROM users WHERE id = ?", userId))
                    put("profile", connection.queryRows("SELECT * FROM profiles WHERE user_id = ?", userId))
                    put("discoverySettings", connection.queryRows("SELECT * FROM discovery_settings WHERE user_id = ?", userId))
                    put(
                        "sentMessages",
                        connection.queryRows("SELECT * FROM messages WHERE sender_user_id = ? ORDER BY created_at", userId),
                    )
                    EXPORT_SECTIONS.forEach { (name, sql) -> put(name, connection.queryRows(sql, userId)) }
                    // Rows other people caused that this person was never shown ("Hide from people you
                    // know") stay out, exactly as they stay out of every other read of theirs.
                    val hiddenToken = contactBlockTokens.targetToken(userId)
                    VIEWER_FILTERED_SECTIONS.forEach { (name, sql) -> put(name, connection.queryRowsForViewer(sql, userId, hiddenToken)) }
                    // Confirmations the person gave (by token); as a subject, only their badge state.
                    put("metInPerson", metInPersonAccountData.exportSection(connection, userId, hiddenToken))
                }
            ExportArchive(
                filename = "pynapli-data-export-$userId.json",
                contentType = "application/json",
                bytes = PRETTY_JSON.encodeToString(JsonObject.serializer(), document).encodeToByteArray(),
            )
        }

    private fun Connection.queryRows(
        sql: String,
        userId: UUID,
    ): JsonArray =
        prepareStatement(sql).use { statement ->
            // Every placeholder is the exporting user.
            repeat(statement.parameterMetaData.parameterCount) { index -> statement.setObject(index + 1, userId) }
            statement.executeQuery().use { rs -> rs.rowsAsJson() }
        }

    /** [VIEWER_FILTERED_SECTIONS]: parameter 1 is the exporting user, parameter 2 their hidden-from token. */
    private fun Connection.queryRowsForViewer(
        sql: String,
        userId: UUID,
        hiddenToken: String,
    ): JsonArray =
        prepareStatement(sql).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, hiddenToken)
            statement.executeQuery().use { rs -> rs.rowsAsJson() }
        }

    private fun ResultSet.rowsAsJson(): JsonArray {
        val columns = (1..metaData.columnCount).map { metaData.getColumnLabel(it) }
        val rows =
            buildList {
                while (next()) {
                    add(
                        buildJsonObject {
                            columns.forEachIndexed { index, name -> put(name, getString(index + 1)) }
                        },
                    )
                }
            }
        return JsonArray(rows)
    }

    companion object {
        private val PRETTY_JSON = kotlinx.serialization.json.Json { prettyPrint = true }

        /**
         * Everything else the person did or set up, column by column. Left out on purpose: secrets
         * (token hashes, push tokens, storage keys), exact location geometry, moderator-internal
         * fields (admin reasons, reviewer ids, review notes, report evidence), and other people's
         * message text.
         */
        internal val EXPORT_SECTIONS: List<Pair<String, String>> =
            listOf(
                "notificationPreferences" to
                    "SELECT * FROM notification_preferences WHERE user_id = ?",
                "photos" to
                    "SELECT id, media_type, content_type, byte_size, pixel_width, pixel_height, intended_album_kind, moderation_status, processing_status, created_at, deleted_at FROM media_assets WHERE owner_user_id = ? ORDER BY created_at",
                "albums" to
                    "SELECT id, kind, title, visibility_status, created_at, deleted_at FROM media_albums WHERE owner_user_id = ?",
                "albumAccessYouGave" to
                    "SELECT album_id, grantee_user_id, granted_at, revoked_at FROM media_album_access_grants WHERE owner_user_id = ? ORDER BY granted_at",
                "albumRequestsYouSent" to
                    "SELECT id, owner_user_id, status, requested_at, decided_at FROM media_album_access_requests WHERE requester_user_id = ? ORDER BY requested_at",
                "likesAndPasses" to
                    "SELECT id, target_user_id, action, note_text, created_at, rewound_at, psync_session_id FROM swipe_actions WHERE actor_user_id = ? ORDER BY created_at",
                "blocks" to
                    "SELECT blocked_user_id, reason, created_at FROM user_blocks WHERE blocker_user_id = ? ORDER BY created_at",
                "hiddenProfiles" to
                    "SELECT hidden_user_id, created_at FROM user_hidden_profiles WHERE hider_user_id = ? ORDER BY created_at",
                "favorites" to
                    "SELECT favorite_user_id, created_at FROM favorite_profiles WHERE user_id = ? ORDER BY created_at",
                "reportsYouFiled" to
                    "SELECT id, reported_user_id, target_type, target_id, category, note, status, created_at FROM safety_reports WHERE reporter_user_id = ? ORDER BY created_at",
                "appeals" to
                    "SELECT id, action_type, target_type, target_id, status, user_reason, admin_note, created_at, reviewed_at FROM moderation_appeals WHERE user_id = ? ORDER BY created_at",
                // "Hide from people you know": only when each address was added. The server can't show
                // the addresses (it keeps a keyed code) nor whether any belongs to a member.
                "hideFromPeopleYouKnow" to
                    "SELECT created_at FROM contact_blocks WHERE blocker_user_id = ? ORDER BY created_at",
                "subscriptions" to
                    "SELECT platform, tier, status, external_product_id, external_order_id, current_period_start, current_period_end, created_at FROM subscriptions WHERE user_id = ? ORDER BY created_at",
                // Purchased and plan-included units (source 'subscription', one row per period).
                "premiumItems" to
                    "SELECT inventory_type, quantity, source, expires_at, allowance_period_start, allowance_granted, allowance_withheld, allowance_tier, created_at FROM premium_inventory WHERE user_id = ? ORDER BY created_at",
                // When the person's weekly/monthly included-allowance cycle started (V117).
                "includedAllowanceCycle" to
                    "SELECT anchored_at, created_at FROM included_allowance_anchors WHERE user_id = ?",
                "travel" to
                    "SELECT city, region, country, status, active_from, active_until, created_at FROM travel_locations WHERE user_id = ? ORDER BY created_at",
                "playingTonight" to
                    "SELECT id, mode, intent, host_mode, note, map_visible, status, created_at, started_at, expires_at, ended_at FROM tonight_sessions WHERE created_by_user_id = ? ORDER BY created_at",
                "psyncSessions" to
                    "SELECT s.id, s.status, s.deck_size, s.location_label, s.created_at, s.started_at, s.ended_at FROM psync_sessions s JOIN psync_session_participants p ON p.session_id = s.id WHERE p.user_id = ? ORDER BY s.created_at",
                "psyncChoices" to
                    "SELECT session_id, target_user_id, action, created_at FROM psync_session_actions WHERE actor_user_id = ? ORDER BY created_at",
                "signIns" to
                    "SELECT platform, app_version, created_at, last_seen_at, revoked_at FROM user_sessions WHERE user_id = ? ORDER BY created_at",
                "pushDevices" to
                    "SELECT platform, app_version, locale, timezone, notifications_enabled, created_at, disabled_at FROM push_devices WHERE user_id = ?",
            )

        /**
         * Sections that hold rows other people caused. Someone hiding from this person ("Hide from
         * people you know") never reached them, so those rows are left out here too: the export can't
         * show what the app never did. Parameter 1 is the exporting user (`me.id`), parameter 2 their
         * hidden-from token (`me.hidden_token`).
         */
        internal val VIEWER_FILTERED_SECTIONS: List<Pair<String, String>> =
            listOf(
                "activity" to
                    """
                    WITH me AS (SELECT ?::uuid AS id, ?::text AS hidden_token)
                    SELECT ae.* FROM activity_events ae, me
                    WHERE ae.recipient_user_id = me.id
                      AND NOT EXISTS (SELECT 1 FROM contact_blocks cb WHERE cb.target_token = me.hidden_token AND cb.blocker_user_id = ae.actor_user_id AND (cb.released_at IS NULL OR ae.first_occurred_at < cb.released_at))
                    ORDER BY ae.occurred_at
                    """.trimIndent(),
                "albumAccessYouHave" to
                    """
                    WITH me AS (SELECT ?::uuid AS id, ?::text AS hidden_token)
                    SELECT g.album_id, g.owner_user_id, g.granted_at, g.revoked_at FROM media_album_access_grants g, me
                    WHERE g.grantee_user_id = me.id
                      AND NOT EXISTS (SELECT 1 FROM contact_blocks cb WHERE cb.target_token = me.hidden_token AND cb.blocker_user_id = g.owner_user_id AND (cb.released_at IS NULL OR g.granted_at < cb.released_at))
                    ORDER BY g.granted_at
                    """.trimIndent(),
                "albumRequestsYouReceived" to
                    """
                    WITH me AS (SELECT ?::uuid AS id, ?::text AS hidden_token)
                    SELECT r.id, r.requester_user_id, r.status, r.requested_at, r.decided_at FROM media_album_access_requests r, me
                    WHERE r.owner_user_id = me.id
                      AND NOT EXISTS (SELECT 1 FROM contact_blocks cb WHERE cb.target_token = me.hidden_token AND cb.blocker_user_id = r.requester_user_id AND (cb.released_at IS NULL OR r.requested_at < cb.released_at))
                    ORDER BY r.requested_at
                    """.trimIndent(),
                "conversations" to
                    """
                    WITH me AS (SELECT ?::uuid AS id, ?::text AS hidden_token)
                    SELECT cp.conversation_id, cp.joined_at, cp.left_at, cp.last_read_at, cp.invitation_status, cp.message_expiry_seconds
                    FROM conversation_participants cp
                    JOIN conversations c ON c.id = cp.conversation_id, me
                    WHERE cp.user_id = me.id
                      -- The inbox rule: a conversation whose every other participant hides from this
                      -- person (or did, when it was created) isn't theirs, whoever created it.
                      AND (
                          NOT EXISTS (
                              SELECT 1 FROM conversation_participants other
                              WHERE other.conversation_id = cp.conversation_id AND other.user_id <> me.id
                          )
                          OR EXISTS (
                              SELECT 1 FROM conversation_participants other
                              WHERE other.conversation_id = cp.conversation_id
                                AND other.user_id <> me.id
                                AND NOT EXISTS (
                                    SELECT 1 FROM contact_blocks cb
                                    WHERE cb.target_token = me.hidden_token
                                      AND cb.blocker_user_id = other.user_id
                                      AND (cb.released_at IS NULL OR c.created_at < cb.released_at)
                                )
                          )
                      )
                    """.trimIndent(),
                "partnerLinks" to
                    """
                    WITH me AS (SELECT ?::uuid AS id, ?::text AS hidden_token)
                    SELECT l.id, CASE WHEN l.user_id = me.id THEN l.linked_user_id ELSE l.user_id END AS partner_user_id,
                           l.status, l.created_at, l.accepted_at, l.unlinked_at
                    FROM user_profile_links l, me
                    WHERE (l.user_id = me.id OR l.linked_user_id = me.id)
                      AND NOT EXISTS (
                          SELECT 1 FROM contact_blocks cb
                          WHERE cb.target_token = me.hidden_token
                            AND cb.blocker_user_id = CASE WHEN l.user_id = me.id THEN l.linked_user_id ELSE l.user_id END
                            AND (cb.released_at IS NULL OR l.created_at < cb.released_at)
                      )
                    ORDER BY l.created_at
                    """.trimIndent(),
                // The durable link record (V124): the first invite and first acceptance per direction, by the
                // same hide rule as partnerLinks (the act's date is the first one on record).
                "partnerLinkHistory" to
                    """
                    WITH me AS (SELECT ?::uuid AS id, ?::text AS hidden_token)
                    SELECT CASE WHEN h.user_id = me.id THEN 'sent' ELSE 'received' END AS direction,
                           CASE WHEN h.user_id = me.id THEN h.linked_user_id ELSE h.user_id END AS partner_user_id,
                           h.first_invited_at, h.first_accepted_at
                    FROM profile_link_history h, me
                    WHERE (h.user_id = me.id OR h.linked_user_id = me.id)
                      AND NOT EXISTS (
                          SELECT 1 FROM contact_blocks cb
                          WHERE cb.target_token = me.hidden_token
                            AND cb.blocker_user_id = CASE WHEN h.user_id = me.id THEN h.linked_user_id ELSE h.user_id END
                            AND (
                                cb.released_at IS NULL
                                OR COALESCE(h.first_invited_at, h.first_accepted_at) < cb.released_at
                            )
                      )
                    ORDER BY COALESCE(h.first_invited_at, h.first_accepted_at)
                    """.trimIndent(),
                // A Psync together match can pair this person with someone hiding from them.
                "matches" to
                    """
                    WITH me AS (SELECT ?::uuid AS id, ?::text AS hidden_token)
                    SELECT m.id, CASE WHEN m.user_a = me.id THEN m.user_b ELSE m.user_a END AS other_user_id, m.created_at
                    FROM matches m, me
                    WHERE (m.user_a = me.id OR m.user_b = me.id)
                      AND NOT EXISTS (
                          SELECT 1 FROM contact_blocks cb
                          WHERE cb.target_token = me.hidden_token
                            AND cb.blocker_user_id = CASE WHEN m.user_a = me.id THEN m.user_b ELSE m.user_a END
                            AND (cb.released_at IS NULL OR m.created_at < cb.released_at)
                      )
                    ORDER BY m.created_at
                    """.trimIndent(),
            )
    }
}
