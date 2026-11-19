package com.pynapli.server.account

import com.pynapli.server.db.withConnection
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
                    put(
                        "activity",
                        connection.queryRows("SELECT * FROM activity_events WHERE recipient_user_id = ? ORDER BY occurred_at", userId),
                    )
                    EXPORT_SECTIONS.forEach { (name, sql) -> put(name, connection.queryRows(sql, userId)) }
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
                "albumAccessYouHave" to
                    "SELECT album_id, owner_user_id, granted_at, revoked_at FROM media_album_access_grants WHERE grantee_user_id = ? ORDER BY granted_at",
                "albumRequestsYouSent" to
                    "SELECT id, owner_user_id, status, requested_at, decided_at FROM media_album_access_requests WHERE requester_user_id = ? ORDER BY requested_at",
                "albumRequestsYouReceived" to
                    "SELECT id, requester_user_id, status, requested_at, decided_at FROM media_album_access_requests WHERE owner_user_id = ? ORDER BY requested_at",
                "likesAndPasses" to
                    "SELECT id, target_user_id, action, note_text, created_at, rewound_at, psync_session_id FROM swipe_actions WHERE actor_user_id = ? ORDER BY created_at",
                "matches" to
                    "SELECT id, CASE WHEN user_a = ? THEN user_b ELSE user_a END AS other_user_id, created_at FROM matches WHERE user_a = ? OR user_b = ? ORDER BY created_at",
                "conversations" to
                    "SELECT conversation_id, joined_at, left_at, last_read_at, invitation_status, message_expiry_seconds FROM conversation_participants WHERE user_id = ?",
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
                "partnerLinks" to
                    "SELECT id, CASE WHEN user_id = ? THEN linked_user_id ELSE user_id END AS partner_user_id, status, created_at, accepted_at, unlinked_at FROM user_profile_links WHERE user_id = ? OR linked_user_id = ? ORDER BY created_at",
                "subscriptions" to
                    "SELECT platform, tier, status, external_product_id, external_order_id, current_period_start, current_period_end, created_at FROM subscriptions WHERE user_id = ? ORDER BY created_at",
                // Purchased and plan-included units (source 'subscription', one row per period).
                "premiumItems" to
                    "SELECT inventory_type, quantity, source, expires_at, allowance_period_start, allowance_granted, allowance_withheld, allowance_tier, created_at FROM premium_inventory WHERE user_id = ? ORDER BY created_at",
                // When the person's weekly/monthly included-allowance cycle started (V116).
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
    }
}
