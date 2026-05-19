package com.pynapli.server.activity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class JdbcActivityRepository(
    private val dataSource: DataSource,
    private val profileViewCooldown: Duration = Duration.ofMinutes(30),
    private val flirtCooldown: Duration = Duration.ofMinutes(15),
    private val albumRequestCooldown: Duration = Duration.ofHours(24)
) : ActivityRepository {
    override suspend fun recordProfileView(viewerUserId: UUID, targetUserId: UUID): Boolean = withContext(Dispatchers.IO) {
        if (viewerUserId == targetUserId) return@withContext false

        dataSource.connection.use { connection ->
            connection.prepareStatement(UPSERT_ELIGIBLE_PROFILE_VIEW_QUERY).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, targetUserId)
                statement.setObject(3, viewerUserId)
                statement.setObject(4, targetUserId)
                statement.setObject(5, viewerUserId)
                statement.setObject(6, targetUserId)
                statement.setString(7, "${profileViewCooldown.toMinutes()} minutes")
                statement.setString(8, "${profileViewCooldown.toMinutes()} minutes")
                statement.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    override suspend fun recordFlirt(senderUserId: UUID, targetUserId: UUID): Boolean = withContext(Dispatchers.IO) {
        if (senderUserId == targetUserId) return@withContext false

        dataSource.connection.use { connection ->
            val eligible = connection.prepareStatement(FLIRT_ELIGIBILITY_QUERY).use { statement ->
                statement.setObject(1, senderUserId)
                statement.setObject(2, targetUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
            if (!eligible) return@withContext false

            connection.prepareStatement(UPSERT_FLIRT_QUERY).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, targetUserId)
                statement.setObject(3, senderUserId)
                statement.setObject(4, targetUserId)
                statement.setString(5, "${flirtCooldown.toMinutes()} minutes")
                statement.setString(6, "${flirtCooldown.toMinutes()} minutes")
                statement.executeUpdate() > 0
            }
        }
    }

    override suspend fun recordAlbumRequest(requesterUserId: UUID, targetUserId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            if (requesterUserId == targetUserId) return@withContext false

            dataSource.connection.use { connection ->
                val eligible = connection.prepareStatement(ALBUM_REQUEST_ELIGIBILITY_QUERY).use { statement ->
                    statement.setObject(1, requesterUserId)
                    statement.setObject(2, targetUserId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
                if (!eligible) return@withContext false

                connection.prepareStatement(UPSERT_ALBUM_REQUEST_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, targetUserId)
                    statement.setObject(3, requesterUserId)
                    statement.setObject(4, targetUserId)
                    statement.setString(5, "${albumRequestCooldown.toHours()} hours")
                    statement.setString(6, "${albumRequestCooldown.toHours()} hours")
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun recordSwipeInterest(actorUserId: UUID, targetUserId: UUID, eventType: String): Boolean =
        withContext(Dispatchers.IO) {
            if (actorUserId == targetUserId || eventType !in SWIPE_INTEREST_EVENT_TYPES) return@withContext false

            dataSource.connection.use { connection ->
                connection.prepareStatement(UPSERT_SWIPE_INTEREST_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, targetUserId)
                    statement.setObject(3, actorUserId)
                    statement.setString(4, eventType)
                    statement.setObject(5, targetUserId)
                    statement.setObject(6, actorUserId)
                    statement.setObject(7, targetUserId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    override suspend fun recordMatch(recipientUserId: UUID, actorUserId: UUID, matchId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            if (recipientUserId == actorUserId) return@withContext false

            dataSource.connection.use { connection ->
                connection.prepareStatement(UPSERT_MATCH_ACTIVITY_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, recipientUserId)
                    statement.setObject(3, actorUserId)
                    statement.setObject(4, recipientUserId)
                    statement.setString(5, """{"matchId":"$matchId"}""")
                    statement.setObject(6, actorUserId)
                    statement.setObject(7, recipientUserId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    override suspend fun recordPartnerChatInvite(
        inviterUserId: UUID,
        inviteeUserId: UUID,
        conversationId: UUID
    ): Boolean = withContext(Dispatchers.IO) {
        if (inviterUserId == inviteeUserId) return@withContext false

        dataSource.connection.use { connection ->
            connection.prepareStatement(UPSERT_PARTNER_CHAT_INVITE_QUERY).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, inviteeUserId)
                statement.setObject(3, inviterUserId)
                statement.setObject(4, inviteeUserId)
                statement.setString(5, """{"conversationId":"$conversationId"}""")
                statement.setObject(6, inviterUserId)
                statement.setObject(7, inviteeUserId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    override suspend fun listActivity(query: ActivityFeedQuery): List<ActivityEventRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(ACTIVITY_FEED_QUERY).use { statement ->
                    val eventTypeArray = connection.createArrayOf("text", query.eventTypes.toTypedArray())
                    statement.setObject(1, query.recipientUserId)
                    statement.setObject(2, query.recipientUserId)
                    statement.setBoolean(3, query.eventTypes.isEmpty())
                    statement.setArray(4, eventTypeArray)
                    statement.setBoolean(5, query.cursor != null)
                    statement.setTimestamp(6, query.cursor?.occurredAt?.let { Timestamp.from(it) })
                    statement.setTimestamp(7, query.cursor?.occurredAt?.let { Timestamp.from(it) })
                    statement.setObject(8, query.cursor?.eventId)
                    statement.setInt(9, query.limit)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.toActivityEventRecord())
                            }
                        }
                    }
                }
            }
        }

    override suspend fun dismissActivityEvent(recipientUserId: UUID, eventId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(DISMISS_ACTIVITY_EVENT_QUERY).use { statement ->
                    statement.setObject(1, eventId)
                    statement.setObject(2, recipientUserId)
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun getAlbumRequestEvent(
        recipientUserId: UUID,
        eventId: UUID
    ): AlbumRequestEventRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, recipient_user_id, actor_user_id
                FROM activity_events
                WHERE id = ?
                  AND recipient_user_id = ?
                  AND event_type = 'album_request'
                  AND visibility_status = 'visible'
                  AND deleted_at IS NULL
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.setObject(2, recipientUserId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        AlbumRequestEventRecord(
                            eventId = rs.getObject("id", UUID::class.java),
                            ownerUserId = rs.getObject("recipient_user_id", UUID::class.java),
                            requesterUserId = rs.getObject("actor_user_id", UUID::class.java)
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun ResultSet.toActivityEventRecord(): ActivityEventRecord {
        val distanceMeters = getDouble("distance_meters").takeUnless { wasNull() }
        return ActivityEventRecord(
            eventId = getObject("event_id", UUID::class.java),
            type = getString("event_type"),
            actorUserId = getObject("actor_user_id", UUID::class.java),
            actorDisplayName = getString("actor_display_name"),
            actorProfilePhotoMediaAssetId = getObject("actor_profile_photo_media_asset_id", UUID::class.java),
            count = getInt("aggregate_count"),
            occurredAt = getTimestamp("occurred_at").toInstant(),
            showDistance = getBoolean("actor_show_distance"),
            distanceMeters = distanceMeters,
            albumRequestStatus = getString("album_request_status")
        )
    }

    companion object {
        private val UPSERT_ELIGIBLE_PROFILE_VIEW_QUERY = """
            INSERT INTO activity_events (
                id,
                recipient_user_id,
                actor_user_id,
                event_type,
                target_user_id,
                aggregate_count,
                first_occurred_at,
                last_counted_at,
                occurred_at,
                updated_at
            )
            SELECT ?, ?, ?, 'profile_view', ?, 1, now(), now(), now(), now()
            WHERE EXISTS (
                SELECT 1
                FROM users viewer
                JOIN discovery_settings viewer_settings ON viewer_settings.user_id = viewer.id
                LEFT JOIN account_risk_states viewer_risk ON viewer_risk.user_id = viewer.id
                WHERE viewer.id = ?
                  AND viewer.status = 'active'
                  AND viewer.deleted_at IS NULL
                  AND viewer_settings.incognito = false
                  AND viewer_settings.show_in_viewed_lists = true
                  AND COALESCE(viewer_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
            )
            AND EXISTS (
                SELECT 1
                FROM users target
                WHERE target.id = ?
                  AND target.status = 'active'
                  AND target.deleted_at IS NULL
            )
            ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
            WHERE event_type = 'profile_view' AND deleted_at IS NULL
            DO UPDATE SET
                aggregate_count = CASE
                    WHEN activity_events.last_counted_at <= now() - (?::interval)
                    THEN activity_events.aggregate_count + 1
                    ELSE activity_events.aggregate_count
                END,
                last_counted_at = CASE
                    WHEN activity_events.last_counted_at <= now() - (?::interval)
                    THEN now()
                    ELSE activity_events.last_counted_at
                END,
                occurred_at = now(),
                updated_at = now(),
                visibility_status = 'visible'
            RETURNING id
        """.trimIndent()

        private val FLIRT_ELIGIBILITY_QUERY = """
            SELECT EXISTS (
                SELECT 1
                FROM users sender
                LEFT JOIN account_risk_states sender_risk ON sender_risk.user_id = sender.id
                WHERE sender.id = ?
                  AND sender.status = 'active'
                  AND sender.deleted_at IS NULL
                  AND COALESCE(sender_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
            )
            AND EXISTS (
                SELECT 1
                FROM users target
                JOIN profiles target_profile
                  ON target_profile.user_id = target.id
                 AND target_profile.visibility_status = 'visible'
                JOIN discovery_settings target_settings
                  ON target_settings.user_id = target.id
                 AND target_settings.allow_flirts = true
                WHERE target.id = ?
                  AND target.status = 'active'
                  AND target.deleted_at IS NULL
            )
        """.trimIndent()

        private val UPSERT_FLIRT_QUERY = """
            INSERT INTO activity_events (
                id,
                recipient_user_id,
                actor_user_id,
                event_type,
                target_user_id,
                aggregate_count,
                first_occurred_at,
                last_counted_at,
                occurred_at,
                updated_at
            )
            VALUES (?, ?, ?, 'flirt', ?, 1, now(), now(), now(), now())
            ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
            WHERE event_type = 'flirt' AND deleted_at IS NULL
            DO UPDATE SET
                aggregate_count = CASE
                    WHEN activity_events.last_counted_at <= now() - (?::interval)
                    THEN activity_events.aggregate_count + 1
                    ELSE activity_events.aggregate_count
                END,
                last_counted_at = CASE
                    WHEN activity_events.last_counted_at <= now() - (?::interval)
                    THEN now()
                    ELSE activity_events.last_counted_at
                END,
                occurred_at = now(),
                updated_at = now(),
                visibility_status = 'visible'
        """.trimIndent()

        private val ALBUM_REQUEST_ELIGIBILITY_QUERY = """
            SELECT EXISTS (
                SELECT 1
                FROM users requester
                LEFT JOIN account_risk_states requester_risk ON requester_risk.user_id = requester.id
                WHERE requester.id = ?
                  AND requester.status = 'active'
                  AND requester.deleted_at IS NULL
                  AND COALESCE(requester_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
            )
            AND EXISTS (
                SELECT 1
                FROM users target
                JOIN profiles target_profile
                  ON target_profile.user_id = target.id
                 AND target_profile.visibility_status = 'visible'
                JOIN discovery_settings target_settings
                  ON target_settings.user_id = target.id
                 AND target_settings.allow_album_requests = true
                WHERE target.id = ?
                  AND target.status = 'active'
                  AND target.deleted_at IS NULL
            )
        """.trimIndent()

        private val UPSERT_ALBUM_REQUEST_QUERY = """
            INSERT INTO activity_events (
                id,
                recipient_user_id,
                actor_user_id,
                event_type,
                target_user_id,
                aggregate_count,
                first_occurred_at,
                last_counted_at,
                occurred_at,
                updated_at
            )
            VALUES (?, ?, ?, 'album_request', ?, 1, now(), now(), now(), now())
            ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
            WHERE event_type = 'album_request' AND deleted_at IS NULL
            DO UPDATE SET
                aggregate_count = CASE
                    WHEN activity_events.last_counted_at <= now() - (?::interval)
                    THEN activity_events.aggregate_count + 1
                    ELSE activity_events.aggregate_count
                END,
                last_counted_at = CASE
                    WHEN activity_events.last_counted_at <= now() - (?::interval)
                    THEN now()
                    ELSE activity_events.last_counted_at
                END,
                occurred_at = now(),
                updated_at = now(),
                visibility_status = 'visible'
        """.trimIndent()

        private val UPSERT_SWIPE_INTEREST_QUERY = """
            INSERT INTO activity_events (
                id,
                recipient_user_id,
                actor_user_id,
                event_type,
                target_user_id,
                aggregate_count,
                first_occurred_at,
                last_counted_at,
                occurred_at,
                updated_at
            )
            SELECT ?, ?, ?, ?, ?, 1, now(), now(), now(), now()
            WHERE EXISTS (
                SELECT 1
                FROM users actor
                WHERE actor.id = ?
                  AND actor.status = 'active'
                  AND actor.deleted_at IS NULL
            )
            AND EXISTS (
                SELECT 1
                FROM users target
                JOIN profiles target_profile
                  ON target_profile.user_id = target.id
                 AND target_profile.visibility_status = 'visible'
                WHERE target.id = ?
                  AND target.status = 'active'
                  AND target.deleted_at IS NULL
            )
            ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
            WHERE event_type IN ('swipe_like', 'pineapple_like', 'intro_note') AND deleted_at IS NULL
            DO NOTHING
            RETURNING id
        """.trimIndent()

        private val UPSERT_MATCH_ACTIVITY_QUERY = """
            INSERT INTO activity_events (
                id,
                recipient_user_id,
                actor_user_id,
                event_type,
                target_user_id,
                aggregate_count,
                first_occurred_at,
                last_counted_at,
                occurred_at,
                updated_at,
                metadata
            )
            SELECT ?, ?, ?, 'match', ?, 1, now(), now(), now(), now(), ?::jsonb
            WHERE EXISTS (
                SELECT 1
                FROM users actor
                WHERE actor.id = ?
                  AND actor.status = 'active'
                  AND actor.deleted_at IS NULL
            )
            AND EXISTS (
                SELECT 1
                FROM users recipient
                WHERE recipient.id = ?
                  AND recipient.status = 'active'
                  AND recipient.deleted_at IS NULL
            )
            ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
            WHERE event_type = 'match' AND deleted_at IS NULL
            DO NOTHING
            RETURNING id
        """.trimIndent()

        private val UPSERT_PARTNER_CHAT_INVITE_QUERY = """
            INSERT INTO activity_events (
                id,
                recipient_user_id,
                actor_user_id,
                event_type,
                target_user_id,
                aggregate_count,
                first_occurred_at,
                last_counted_at,
                occurred_at,
                updated_at,
                metadata
            )
            SELECT ?, ?, ?, 'partner_chat_invite', ?, 1, now(), now(), now(), now(), ?::jsonb
            WHERE EXISTS (
                SELECT 1
                FROM users inviter
                WHERE inviter.id = ?
                  AND inviter.status = 'active'
                  AND inviter.deleted_at IS NULL
            )
            AND EXISTS (
                SELECT 1
                FROM users invitee
                WHERE invitee.id = ?
                  AND invitee.status = 'active'
                  AND invitee.deleted_at IS NULL
            )
            ON CONFLICT (recipient_user_id, actor_user_id, event_type, ((metadata->>'conversationId')))
            WHERE event_type = 'partner_chat_invite' AND deleted_at IS NULL
            DO NOTHING
            RETURNING id
        """.trimIndent()

        private val ACTIVITY_FEED_QUERY = """
            WITH recipient_location AS (
                SELECT geom
                FROM user_locations
                WHERE user_id = ?
            )
            SELECT ae.id AS event_id,
                   ae.event_type,
                   ae.actor_user_id,
                   COALESCE(NULLIF(actor_profile.display_name, ''), 'Pynapli User') AS actor_display_name,
                   actor_photo.media_asset_id AS actor_profile_photo_media_asset_id,
                   ae.aggregate_count,
                   ae.occurred_at,
                   album_request.status AS album_request_status,
                   actor_settings.show_distance AS actor_show_distance,
                   CASE
                       WHEN actor_settings.show_distance = true
                            AND recipient_location.geom IS NOT NULL
                            AND actor_location.geom IS NOT NULL
                       THEN ST_Distance(recipient_location.geom, actor_location.geom)
                       ELSE NULL
                   END AS distance_meters
            FROM activity_events ae
            JOIN users actor
              ON actor.id = ae.actor_user_id
             AND actor.status = 'active'
             AND actor.deleted_at IS NULL
            JOIN profiles actor_profile
              ON actor_profile.user_id = actor.id
             AND actor_profile.visibility_status = 'visible'
            JOIN discovery_settings actor_settings
              ON actor_settings.user_id = actor.id
            LEFT JOIN account_risk_states actor_risk
              ON actor_risk.user_id = actor.id
            LEFT JOIN recipient_location ON true
            LEFT JOIN user_locations actor_location ON actor_location.user_id = actor.id
            LEFT JOIN LATERAL (
                SELECT r.status
                FROM media_album_access_requests r
                JOIN media_albums a
                  ON a.id = r.album_id
                 AND a.owner_user_id = r.owner_user_id
                 AND a.kind = 'private'
                 AND a.deleted_at IS NULL
                WHERE ae.event_type = 'album_request'
                  AND r.owner_user_id = ae.recipient_user_id
                  AND r.requester_user_id = ae.actor_user_id
                ORDER BY r.requested_at DESC, r.id DESC
                LIMIT 1
            ) album_request ON true
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
                WHERE ma.owner_user_id = actor.id
                  AND ma.kind = 'profile'
                  AND ma.visibility_status = 'visible'
                  AND ma.deleted_at IS NULL
                  AND m.media_type = 'image'
                  AND m.processing_status = 'ready'
                  AND m.moderation_status = 'approved'
                ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                LIMIT 1
            ) actor_photo ON true
            WHERE ae.recipient_user_id = ?
              AND ae.visibility_status = 'visible'
              AND ae.deleted_at IS NULL
              AND (? = true OR ae.event_type = ANY (?::text[]))
              AND (
                  ? = false
                  OR ae.occurred_at < ?
                  OR (ae.occurred_at = ? AND ae.id < ?)
              )
              AND COALESCE(actor_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_blocks block
                  WHERE (
                      block.blocker_user_id = ae.recipient_user_id
                      AND block.blocked_user_id = ae.actor_user_id
                  )
                  OR (
                      block.blocker_user_id = ae.actor_user_id
                      AND block.blocked_user_id = ae.recipient_user_id
                  )
              )
              AND (
                  ae.event_type <> 'profile_view'
                  OR (
                      actor_settings.incognito = false
                      AND actor_settings.show_in_viewed_lists = true
                  )
              )
            ORDER BY ae.occurred_at DESC, ae.id DESC
            LIMIT ?
        """.trimIndent()

        private val DISMISS_ACTIVITY_EVENT_QUERY = """
            UPDATE activity_events
            SET deleted_at = now(),
                updated_at = now()
            WHERE id = ?
              AND recipient_user_id = ?
              AND deleted_at IS NULL
        """.trimIndent()

        private val SWIPE_INTEREST_EVENT_TYPES = setOf("swipe_like", "pineapple_like", "intro_note")
    }
}
