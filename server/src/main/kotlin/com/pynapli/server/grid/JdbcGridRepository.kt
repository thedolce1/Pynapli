package com.pynapli.server.grid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

class JdbcGridRepository(
    private val dataSource: DataSource
) : GridRepository {
    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int
    ): GridQueryResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val viewerHasLocation = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM user_locations WHERE user_id = ?)"
            ).use { statement ->
                statement.setObject(1, viewerUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }

            if (!viewerHasLocation) {
                return@withContext GridQueryResult(viewerHadLocation = false, cards = emptyList())
            }

            val cards = connection.prepareStatement(
                """
                WITH viewer_location AS (
                    SELECT geom
                    FROM user_locations
                    WHERE user_id = ?
                )
                SELECT u.id AS user_id,
                       COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                       profile_photo.media_asset_id AS thumbnail_media_asset_id,
                       ds.show_distance,
                       ST_Distance(v.geom, ul.geom) AS distance_meters
                FROM viewer_location v
                JOIN user_locations ul ON true
                JOIN users u ON u.id = ul.user_id
                JOIN profiles p ON p.user_id = u.id
                JOIN discovery_settings ds ON ds.user_id = u.id
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
                WHERE u.id <> ?
                  AND u.status = 'active'
                  AND u.deleted_at IS NULL
                  AND p.visibility_status = 'visible'
                  AND ds.grid_visible = true
                  AND ds.incognito = false
                  AND ST_DWithin(v.geom, ul.geom, ?)
                ORDER BY ST_Distance(v.geom, ul.geom) ASC, u.id ASC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, viewerUserId)
                statement.setObject(2, viewerUserId)
                statement.setDouble(3, radiusMeters.toDouble())
                statement.setInt(4, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                GridCardRecord(
                                    userId = rs.getObject("user_id", UUID::class.java),
                                    displayName = rs.getString("display_name"),
                                    thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", UUID::class.java),
                                    showDistance = rs.getBoolean("show_distance"),
                                    distanceMeters = rs.getDouble("distance_meters")
                                )
                            )
                        }
                    }
                }
            }

            GridQueryResult(viewerHadLocation = true, cards = cards)
        }
    }
}
