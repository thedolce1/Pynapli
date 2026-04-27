package com.pynapli.server.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Date
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

class JdbcProfileRepository(
    private val dataSource: DataSource
) : ProfileRepository {
    override suspend fun getMe(userId: UUID): MeRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(ME_QUERY).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext null
                    rs.toMeRecord()
                }
            }
        }
    }

    override suspend fun getPublicProfile(
        viewerUserId: UUID,
        targetUserId: UUID
    ): PublicProfileRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(PUBLIC_PROFILE_QUERY).use { statement ->
                statement.setObject(1, viewerUserId)
                statement.setObject(2, viewerUserId)
                statement.setObject(3, viewerUserId)
                statement.setObject(4, targetUserId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext null
                    rs.toPublicProfileRecord()
                }
            }
        }
    }

    override suspend fun updateProfile(userId: UUID, update: ProfileUpdate): MeRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
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
                """.trimIndent()
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

    private fun ResultSet.toPublicProfileRecord(): PublicProfileRecord {
        val distanceMeters = getDouble("distance_meters").takeUnless { wasNull() }
        return PublicProfileRecord(
            userId = getObject("user_id", UUID::class.java),
            displayName = getString("display_name"),
            profilePhotoMediaAssetId = getObject("profile_photo_media_asset_id", UUID::class.java),
            displayAge = getInt("display_age").takeUnless { wasNull() },
            gender = getString("gender"),
            orientation = getString("orientation"),
            relationshipContext = getString("relationship_context"),
            discoveryStyle = getString("discovery_style"),
            distanceMeters = distanceMeters,
            privateAlbumAccessStatus = getString("private_album_access_status"),
            allowGridMessages = getBoolean("allow_grid_messages"),
            allowFlirts = getBoolean("allow_flirts"),
            allowAlbumRequests = getBoolean("allow_album_requests")
        )
    }

    private fun ResultSet.toMeRecord(): MeRecord {
        val userId = getObject("user_id", UUID::class.java)
        return MeRecord(
            userId = userId,
            userStatus = getString("user_status"),
            profile = ProfileRecord(
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
                visibilityStatus = getString("visibility_status")
            ),
            discoverySettings = DiscoverySettingsRecord(
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
                socialEnabled = getBoolean("social_enabled")
            )
        )
    }

    companion object {
        private val ME_QUERY = """
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
                   ds.social_enabled
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            WHERE u.id = ?
              AND u.deleted_at IS NULL
        """.trimIndent()

        private val PUBLIC_PROFILE_QUERY = """
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                   profile_photo.media_asset_id AS profile_photo_media_asset_id,
                   p.display_age,
                   p.gender,
                   p.orientation,
                   p.relationship_context,
                   p.discovery_style,
                   ds.allow_grid_messages,
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
            WHERE u.id = ?
              AND u.status = 'active'
              AND u.deleted_at IS NULL
              AND p.visibility_status = 'visible'
              AND ds.grid_visible = true
              AND ds.incognito = false
        """.trimIndent()
    }
}
