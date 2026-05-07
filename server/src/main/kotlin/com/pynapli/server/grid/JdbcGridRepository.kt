package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
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
        limit: Int,
        filters: DiscoveryFilters
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
                       linked_partner.linked_partner_user_id,
                       linked_partner.linked_partner_display_name,
                       linked_partner.linked_partner_thumbnail_media_asset_id,
                       ds.show_distance,
                       ST_Distance(v.geom, ul.geom) AS distance_meters
                FROM viewer_location v
                JOIN user_locations ul ON true
                JOIN users u ON u.id = ul.user_id
                JOIN profiles p ON p.user_id = u.id
                JOIN discovery_settings ds ON ds.user_id = u.id
                LEFT JOIN account_risk_states ars ON ars.user_id = u.id
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
                LEFT JOIN LATERAL (
                    SELECT ba.ends_at
                    FROM boost_activations ba
                    WHERE ba.user_id = u.id
                      AND ba.status = 'active'
                      AND ba.ends_at > now()
                    ORDER BY ba.ends_at DESC, ba.id ASC
                    LIMIT 1
                ) active_boost ON true
                WHERE u.id <> ?
                  AND u.status = 'active'
                  AND u.deleted_at IS NULL
                  AND p.visibility_status = 'visible'
                  AND ds.grid_visible = true
                  AND ds.incognito = false
                  AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  AND ST_DWithin(v.geom, ul.geom, ?)
                  AND (?::integer IS NULL OR p.display_age >= ?::integer)
                  AND (?::integer IS NULL OR p.display_age <= ?::integer)
                  AND (cardinality(?::text[]) = 0 OR lower(p.relationship_context) = ANY(?::text[]))
                  AND (
                      ?::boolean IS NULL
                      OR (?::boolean = true AND linked_partner.linked_partner_user_id IS NOT NULL)
                      OR (?::boolean = false AND linked_partner.linked_partner_user_id IS NULL)
                  )
                ORDER BY CASE WHEN active_boost.ends_at IS NULL THEN 1 ELSE 0 END ASC,
                         ST_Distance(v.geom, ul.geom) ASC,
                         u.id ASC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                val contexts = connection.createArrayOf("text", filters.relationshipContexts.toTypedArray())
                statement.setObject(1, viewerUserId)
                statement.setObject(2, viewerUserId)
                statement.setObject(3, viewerUserId)
                statement.setObject(4, viewerUserId)
                statement.setDouble(5, radiusMeters.toDouble())
                statement.setObject(6, filters.minimumAge)
                statement.setObject(7, filters.minimumAge)
                statement.setObject(8, filters.maximumAge)
                statement.setObject(9, filters.maximumAge)
                statement.setArray(10, contexts)
                statement.setArray(11, contexts)
                statement.setObject(12, filters.linkedOnly)
                statement.setObject(13, filters.linkedOnly)
                statement.setObject(14, filters.linkedOnly)
                statement.setInt(15, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                GridCardRecord(
                                    userId = rs.getObject("user_id", UUID::class.java),
                                    displayName = rs.getString("display_name"),
                                    thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", UUID::class.java),
                                    showDistance = rs.getBoolean("show_distance"),
                                    distanceMeters = rs.getDouble("distance_meters"),
                                    linkedPartnerUserId = rs.getObject("linked_partner_user_id", UUID::class.java),
                                    linkedPartnerDisplayName = rs.getString("linked_partner_display_name"),
                                    linkedPartnerThumbnailMediaAssetId = rs.getObject(
                                        "linked_partner_thumbnail_media_asset_id",
                                        UUID::class.java
                                    )
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
