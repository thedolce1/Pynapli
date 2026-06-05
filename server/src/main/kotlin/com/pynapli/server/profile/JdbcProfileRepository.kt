package com.pynapli.server.profile

import com.pynapli.server.profiling.profileBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Date
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

class JdbcProfileRepository(
    private val dataSource: DataSource,
) : ProfileRepository {
    override suspend fun getMe(userId: UUID): MeRecord? =
        withContext(Dispatchers.IO) {
            profileBlocking("profile.db.connectionWait") { dataSource.connection }.use { connection ->
                profileBlocking("profile.db.meQuery") {
                    connection.prepareStatement(ME_QUERY).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toMeRecord() else null
                        }
                    }
                }
            }
        }

    override suspend fun getPublicProfile(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): PublicProfileRecord? =
        withContext(Dispatchers.IO) {
            profileBlocking("profile.db.connectionWait") { dataSource.connection }.use { connection ->
                profileBlocking("profile.db.publicQuery") {
                    connection.prepareStatement(PUBLIC_PROFILE_QUERY).use { statement ->
                        statement.setObject(1, viewerUserId)
                        statement.setObject(2, viewerUserId)
                        statement.setObject(3, viewerUserId)
                        statement.setObject(4, viewerUserId)
                        statement.setObject(5, viewerUserId)
                        statement.setObject(6, viewerUserId)
                        statement.setObject(7, viewerUserId)
                        statement.setObject(8, targetUserId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toPublicProfileRecord() else null
                        }
                    }
                }
            }
        }

    override suspend fun updateProfile(
        userId: UUID,
        update: ProfileUpdate,
    ): MeRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE profiles
                        SET display_name = ?,
                            birthdate = ?,
                            display_age = ?,
                            gender = ?,
                            orientation = ?,
                            relationship_context = ?,
                            discovery_style = COALESCE(?, discovery_style),
                            profile_completeness_score = ?,
                            updated_at = now()
                        WHERE user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, update.displayName)
                        statement.setDate(2, update.birthdate?.let { Date.valueOf(it) })
                        update.displayAge?.let { statement.setInt(3, it) } ?: statement.setNull(3, java.sql.Types.INTEGER)
                        statement.setString(4, update.gender)
                        statement.setString(5, update.orientation)
                        statement.setString(6, update.relationshipContext)
                        statement.setString(7, update.discoveryStyle)
                        statement.setInt(8, update.profileCompletenessScore)
                        statement.setObject(9, userId)
                        if (statement.executeUpdate() == 0) return@withContext null
                    }
            }

            getMe(userId)
        }

    override suspend fun updateDiscoverySettings(
        userId: UUID,
        update: DiscoverySettingsUpdate,
    ): MeRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE discovery_settings
                        SET grid_visible = COALESCE(?::boolean, grid_visible),
                            swipe_visible = COALESCE(?::boolean, swipe_visible),
                            show_distance = COALESCE(?::boolean, show_distance),
                            show_in_viewed_lists = COALESCE(?::boolean, show_in_viewed_lists),
                            allow_grid_messages = COALESCE(?::boolean, allow_grid_messages),
                            grid_message_policy = COALESCE(?::text, grid_message_policy),
                            allow_flirts = COALESCE(?::boolean, allow_flirts),
                            allow_intro_notes = COALESCE(?::boolean, allow_intro_notes),
                            allow_album_requests = COALESCE(?::boolean, allow_album_requests),
                            visible_while_traveling = COALESCE(?::boolean, visible_while_traveling),
                            share_psync_solo_matches = COALESCE(?::boolean, share_psync_solo_matches),
                            updated_at = now()
                        WHERE user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, update.gridVisible)
                        statement.setObject(2, update.swipeVisible)
                        statement.setObject(3, update.showDistance)
                        statement.setObject(4, update.showInViewedLists)
                        statement.setObject(5, update.allowGridMessages)
                        statement.setString(6, update.gridMessagePolicy)
                        statement.setObject(7, update.allowFlirts)
                        statement.setObject(8, update.allowIntroNotes)
                        statement.setObject(9, update.allowAlbumRequests)
                        statement.setObject(10, update.visibleWhileTraveling)
                        statement.setObject(11, update.sharePsyncSoloMatches)
                        statement.setObject(12, userId)
                        if (statement.executeUpdate() == 0) return@withContext null
                    }
            }

            getMe(userId)
        }

    private fun ResultSet.toPublicProfileRecord(): PublicProfileRecord {
        val distanceMeters = getDouble("distance_meters").takeUnless { wasNull() }
        return PublicProfileRecord(
            userId = getObject("user_id", UUID::class.java),
            displayName = getString("display_name"),
            profilePhotoMediaAssetId = getObject("profile_photo_media_asset_id", UUID::class.java),
            linkedPartnerUserId = getObject("linked_partner_user_id", UUID::class.java),
            linkedPartnerDisplayName = getString("linked_partner_display_name"),
            linkedPartnerThumbnailMediaAssetId = getObject("linked_partner_thumbnail_media_asset_id", UUID::class.java),
            displayAge = getInt("display_age").takeUnless { wasNull() },
            gender = getString("gender"),
            orientation = getString("orientation"),
            relationshipContext = getString("relationship_context"),
            discoveryStyle = getString("discovery_style"),
            distanceMeters = distanceMeters,
            privateAlbumAccessStatus = getString("private_album_access_status"),
            allowGridMessages = getBoolean("allow_grid_messages"),
            allowFlirts = getBoolean("allow_flirts"),
            allowAlbumRequests = getBoolean("allow_album_requests"),
        )
    }

    private fun ResultSet.toMeRecord(): MeRecord {
        val userId = getObject("user_id", UUID::class.java)
        return MeRecord(
            userId = userId,
            userStatus = getString("user_status"),
            profile =
                ProfileRecord(
                    userId = userId,
                    displayName = getString("display_name"),
                    birthdate = getDate("birthdate")?.toLocalDate(),
                    displayAge = getInt("display_age").takeUnless { wasNull() },
                    gender = getString("gender"),
                    orientation = getString("orientation"),
                    relationshipContext = getString("relationship_context"),
                    discoveryStyle = getString("discovery_style"),
                    profileCompletenessScore = getInt("profile_completeness_score"),
                    moderationStatus = getString("moderation_status"),
                    visibilityStatus = getString("visibility_status"),
                    ageVerified = getBoolean("age_verified"),
                ),
            discoverySettings =
                DiscoverySettingsRecord(
                    gridVisible = getBoolean("grid_visible"),
                    swipeVisible = getBoolean("swipe_visible"),
                    eventsVisible = getBoolean("events_visible"),
                    showDistance = getBoolean("show_distance"),
                    showInViewedLists = getBoolean("show_in_viewed_lists"),
                    incognito = getBoolean("incognito"),
                    allowGridMessages = getBoolean("allow_grid_messages"),
                    gridMessagePolicy = getString("grid_message_policy"),
                    allowFlirts = getBoolean("allow_flirts"),
                    allowIntroNotes = getBoolean("allow_intro_notes"),
                    allowAlbumRequests = getBoolean("allow_album_requests"),
                    soloEnabled = getBoolean("solo_enabled"),
                    togetherEnabled = getBoolean("together_enabled"),
                    socialEnabled = getBoolean("social_enabled"),
                    visibleWhileTraveling = getBoolean("visible_while_traveling"),
                    sharePsyncSoloMatches = getBoolean("share_psync_solo_matches"),
                ),
        )
    }

    companion object {
        private val ME_QUERY =
            """
            SELECT u.id AS user_id,
                   u.status AS user_status,
                   p.display_name,
                   p.birthdate,
                   p.display_age,
                   p.gender,
                   p.orientation,
                   p.relationship_context,
                   p.discovery_style,
                   p.profile_completeness_score,
                   p.moderation_status,
                   p.visibility_status,
                   (p.age_verified_at IS NOT NULL) AS age_verified,
                   ds.grid_visible,
                   ds.swipe_visible,
                   ds.events_visible,
                   ds.show_distance,
                   ds.show_in_viewed_lists,
                   ds.incognito,
                   ds.allow_grid_messages,
                   ds.grid_message_policy,
                   ds.allow_flirts,
                   ds.allow_intro_notes,
                   ds.allow_album_requests,
                   ds.solo_enabled,
                   ds.together_enabled,
                   ds.social_enabled,
                   ds.visible_while_traveling,
                   ds.share_psync_solo_matches
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            WHERE u.id = ?
              AND u.deleted_at IS NULL
            """.trimIndent()

        private val PUBLIC_PROFILE_QUERY =
            """
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                   profile_photo.media_asset_id AS profile_photo_media_asset_id,
                   linked_partner.linked_partner_user_id,
                   linked_partner.linked_partner_display_name,
                   linked_partner.linked_partner_thumbnail_media_asset_id,
                   p.display_age,
                   p.gender,
                   p.orientation,
                   p.relationship_context,
                   p.discovery_style,
                   CASE
                       WHEN ds.allow_grid_messages = false OR ds.grid_message_policy = 'no_one'
                       THEN false
                       WHEN ds.grid_message_policy = 'matches_only'
                       THEN EXISTS (
                           SELECT 1
                           FROM matches m
                           WHERE LEAST(m.user_a, m.user_b) = LEAST(?::uuid, u.id)
                             AND GREATEST(m.user_a, m.user_b) = GREATEST(?::uuid, u.id)
                       )
                       WHEN ds.grid_message_policy = 'everyone'
                       THEN true
                       ELSE false
                   END AS allow_grid_messages,
                   ds.allow_flirts,
                   ds.allow_album_requests,
                   COALESCE(private_album_access.status, 'none') AS private_album_access_status,
                   CASE
                       WHEN ds.show_distance = true
                            AND viewer_location.geom IS NOT NULL
                            AND target_location.geom IS NOT NULL
                       THEN ST_Distance(viewer_location.geom, target_location.geom)
                       ELSE NULL
                   END AS distance_meters
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            LEFT JOIN account_risk_states target_risk ON target_risk.user_id = u.id
            LEFT JOIN user_locations viewer_location ON viewer_location.user_id = ?
            LEFT JOIN user_locations target_location ON target_location.user_id = u.id
            LEFT JOIN LATERAL (
                SELECT CASE
                    WHEN EXISTS (
                        SELECT 1
                        FROM media_albums ma
                        JOIN media_album_access_grants g
                          ON g.album_id = ma.id
                         AND g.grantee_user_id = ?
                         AND g.revoked_at IS NULL
                        WHERE ma.owner_user_id = u.id
                          AND ma.kind = 'private'
                          AND ma.visibility_status = 'visible'
                          AND ma.deleted_at IS NULL
                    ) THEN 'granted'
                    WHEN EXISTS (
                        SELECT 1
                        FROM media_albums ma
                        JOIN media_album_access_requests r
                          ON r.album_id = ma.id
                         AND r.requester_user_id = ?
                         AND r.status = 'pending'
                        WHERE ma.owner_user_id = u.id
                          AND ma.kind = 'private'
                          AND ma.deleted_at IS NULL
                    ) THEN 'pending'
                    ELSE 'none'
                END AS status
            ) private_album_access ON true
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
                WHERE ma.owner_user_id = u.id
                  AND ma.kind = 'profile'
                  AND ma.visibility_status = 'visible'
                  AND ma.deleted_at IS NULL
                  AND m.media_type = 'image'
                  AND m.processing_status = 'ready'
                  AND m.moderation_status = 'approved'
                ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                LIMIT 1
            ) profile_photo ON true
            LEFT JOIN LATERAL (
                SELECT upl.linked_user_id AS linked_partner_user_id,
                       COALESCE(NULLIF(linked_profile.display_name, ''), 'Pynapli User') AS linked_partner_display_name,
                       linked_partner_photo.media_asset_id AS linked_partner_thumbnail_media_asset_id
                FROM user_profile_links upl
                JOIN users linked_user
                  ON linked_user.id = upl.linked_user_id
                JOIN profiles linked_profile
                  ON linked_profile.user_id = linked_user.id
                JOIN discovery_settings linked_ds
                  ON linked_ds.user_id = linked_user.id
                LEFT JOIN account_risk_states linked_risk
                  ON linked_risk.user_id = linked_user.id
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
                    WHERE ma.owner_user_id = linked_user.id
                      AND ma.kind = 'profile'
                      AND ma.visibility_status = 'visible'
                      AND ma.deleted_at IS NULL
                      AND m.media_type = 'image'
                      AND m.processing_status = 'ready'
                      AND m.moderation_status = 'approved'
                    ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                    LIMIT 1
                ) linked_partner_photo ON true
                WHERE upl.user_id = u.id
                  AND upl.status = 'active'
                  AND upl.unlinked_at IS NULL
                  AND linked_user.status = 'active'
                  AND linked_user.deleted_at IS NULL
                  AND linked_profile.visibility_status = 'visible'
                  AND linked_ds.incognito = false
                  AND COALESCE(linked_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM user_blocks ub
                      WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = linked_user.id)
                         OR (ub.blocker_user_id = linked_user.id AND ub.blocked_user_id = ?)
                  )
                ORDER BY upl.accepted_at DESC NULLS LAST, upl.created_at DESC, upl.id ASC
                LIMIT 1
            ) linked_partner ON true
            WHERE u.id = ?
              AND u.status = 'active'
              AND u.deleted_at IS NULL
              AND p.visibility_status = 'visible'
              AND ds.grid_visible = true
              AND ds.incognito = false
              AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
            """.trimIndent()
    }
}
