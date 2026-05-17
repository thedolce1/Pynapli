package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.profiling.profileBlocking
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
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?
    ): GridQueryResult = withContext(Dispatchers.IO) {
        profileBlocking("grid.db.connectionWait") { dataSource.connection }.use { connection ->
            val viewerHasLocation = profileBlocking("grid.db.viewerLocation") {
                connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM user_locations WHERE user_id = ?)"
                ).use { statement ->
                    statement.setObject(1, viewerUserId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
            }

            if (!viewerHasLocation) {
                return@withContext GridQueryResult(viewerHadLocation = false, cards = emptyList())
            }

            val fetchedCards = profileBlocking("grid.db.candidateQuery") {
                connection.prepareStatement(
                    """
                WITH viewer_location AS (
                    -- Passport/travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354):
                    -- when the viewer has an active travel_locations row, its
                    -- geom overrides the real GPS location for discovery. The
                    -- LEFT JOIN keeps single-? bind ergonomics; travel requires
                    -- the viewer to also have a base user_locations row.
                    SELECT COALESCE(tl.geom, ul.geom) AS geom
                    FROM user_locations ul
                    LEFT JOIN travel_locations tl
                      ON tl.user_id = ul.user_id
                     AND tl.status = 'active'
                     AND tl.active_until IS NULL
                    WHERE ul.user_id = ?
                ),
                base_candidates AS (
                SELECT u.id AS user_id,
                       COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                       ds.show_distance,
                       CASE WHEN EXISTS (
                           SELECT 1
                           FROM boost_activations ba
                           WHERE ba.user_id = u.id
                             AND ba.status = 'active'
                             AND ba.ends_at > now()
                       ) THEN 0 ELSE 1 END AS boost_bucket,
                       -- Distance + bucket reflect where the target *appears* to be,
                       -- so travel-mode targets sort into their virtual radius.
                       GREATEST(0, FLOOR(ST_Distance(v.geom, COALESCE(target_travel.geom, ul.geom)))::integer) AS distance_bucket_meters,
                       ST_Distance(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters,
                       -- SWIPE_IMPLEMENTATION_SPEC L348: target with an active
                       -- travel row is marked "visiting" in the grid too.
                       target_travel.id IS NOT NULL AS visiting,
                       EXISTS (
                           SELECT 1
                           FROM user_profile_links upl
                           JOIN users linked_user
                             ON linked_user.id = upl.linked_user_id
                           JOIN profiles linked_profile
                             ON linked_profile.user_id = linked_user.id
                           JOIN discovery_settings linked_ds
                             ON linked_ds.user_id = linked_user.id
                           LEFT JOIN account_risk_states linked_risk
                             ON linked_risk.user_id = linked_user.id
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
                           LIMIT 1
                       ) AS has_visible_linked_partner
                FROM viewer_location v
                JOIN user_locations ul ON true
                JOIN users u ON u.id = ul.user_id
                JOIN profiles p ON p.user_id = u.id
                JOIN discovery_settings ds ON ds.user_id = u.id
                LEFT JOIN account_risk_states ars ON ars.user_id = u.id
                -- SWIPE_IMPLEMENTATION_SPEC L344-348: target-side travel
                -- override. Travel-mode targets appear in destination grids
                -- the same way they appear in their own home city.
                LEFT JOIN travel_locations target_travel
                  ON target_travel.user_id = u.id
                 AND target_travel.status = 'active'
                 AND target_travel.active_until IS NULL
                WHERE u.id <> ?
                  AND u.status = 'active'
                  AND u.deleted_at IS NULL
                  AND p.visibility_status = 'visible'
                  AND ds.grid_visible = true
                  AND ds.incognito = false
                  AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  -- OR keeps both ST_DWithin halves index-eligible. First uses
                  -- the GiST on user_locations.geom; second uses the GiST on
                  -- travel_locations.geom for active-travel candidates only.
                  AND (
                      ST_DWithin(v.geom, ul.geom, ?)
                      OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))
                  )
                  AND (?::integer IS NULL OR p.display_age >= ?::integer)
                  AND (?::integer IS NULL OR p.display_age <= ?::integer)
                  AND (cardinality(?::text[]) = 0 OR lower(p.relationship_context) = ANY(?::text[]))
                  AND (?::boolean = false OR p.verified_at IS NOT NULL)
                  AND (?::boolean = false OR p.face_photo_verified_at IS NOT NULL)
                  AND (?::boolean = false OR p.age_verified_at IS NOT NULL)
                  AND (cardinality(?::text[]) = 0 OR p.discovery_style = ANY(?::text[]))
                  AND (cardinality(?::text[]) = 0 OR (p.lifestyle_tags IS NOT NULL AND p.lifestyle_tags && ?::text[]))
                  AND (cardinality(?::text[]) = 0 OR (p.logistics_tags IS NOT NULL AND p.logistics_tags && ?::text[]))
                  AND (?::boolean = false OR (p.tonight_available_until IS NOT NULL AND p.tonight_available_until > now()))
                  AND (cardinality(?::text[]) = 0 OR (p.intents IS NOT NULL AND p.intents && ?::text[]))
                  AND NOT EXISTS (
                      SELECT 1
                      FROM user_blocks ub
                      WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = u.id)
                         OR (ub.blocker_user_id = u.id AND ub.blocked_user_id = ?)
                  )
                ),
                ranked_candidates AS (
                    SELECT *
                    FROM base_candidates
                    WHERE (
                        ?::boolean IS NULL
                        OR (?::boolean = true AND has_visible_linked_partner)
                        OR (?::boolean = false AND NOT has_visible_linked_partner)
                    )
                ),
                paged_candidates AS (
                    SELECT *
                    FROM ranked_candidates
                    WHERE (
                        ?::boolean = false
                        OR boost_bucket > ?::integer
                        OR (boost_bucket = ?::integer AND distance_bucket_meters > ?::integer)
                        OR (
                            boost_bucket = ?::integer
                            AND distance_bucket_meters = ?::integer
                            AND user_id > ?::uuid
                        )
                    )
                    ORDER BY boost_bucket ASC,
                             distance_bucket_meters ASC,
                             user_id ASC
                    LIMIT ?
                )
                SELECT pc.user_id,
                       pc.display_name,
                       profile_photo.media_asset_id AS thumbnail_media_asset_id,
                       linked_partner.linked_partner_user_id,
                       linked_partner.linked_partner_display_name,
                       linked_partner.linked_partner_thumbnail_media_asset_id,
                       pc.show_distance,
                       pc.boost_bucket,
                       pc.distance_bucket_meters,
                       pc.distance_meters,
                       pc.visiting
                FROM paged_candidates pc
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
                    WHERE ma.owner_user_id = pc.user_id
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
                    WHERE upl.user_id = pc.user_id
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
                ORDER BY pc.boost_bucket ASC,
                         pc.distance_bucket_meters ASC,
                         pc.user_id ASC
                """.trimIndent()
                ).use { statement ->
                    val contexts = connection.createArrayOf("text", filters.relationshipContexts.toTypedArray())
                    statement.setObject(1, viewerUserId)
                    statement.setObject(2, viewerUserId)
                    statement.setObject(3, viewerUserId)
                    statement.setObject(4, viewerUserId)
                    // $5 + $6: ST_DWithin radius bound twice — once against
                    // target's real GPS, once against target's travel point.
                    statement.setDouble(5, radiusMeters.toDouble())
                    statement.setDouble(6, radiusMeters.toDouble())
                    statement.setObject(7, filters.minimumAge)
                    statement.setObject(8, filters.minimumAge)
                    statement.setObject(9, filters.maximumAge)
                    statement.setObject(10, filters.maximumAge)
                    statement.setArray(11, contexts)
                    statement.setArray(12, contexts)
                    statement.setBoolean(13, filters.verifiedOnly)
                    statement.setBoolean(14, filters.facePhotoVerifiedOnly)
                    statement.setBoolean(15, filters.ageVerifiedOnly)
                    val styles = connection.createArrayOf("text", filters.discoveryStyles.toTypedArray())
                    statement.setArray(16, styles)
                    statement.setArray(17, styles)
                    val lifestyleTags = connection.createArrayOf("text", filters.lifestyleTags.toTypedArray())
                    statement.setArray(18, lifestyleTags)
                    statement.setArray(19, lifestyleTags)
                    val logisticsTags = connection.createArrayOf("text", filters.logisticsTags.toTypedArray())
                    statement.setArray(20, logisticsTags)
                    statement.setArray(21, logisticsTags)
                    statement.setBoolean(22, filters.tonightAvailableOnly)
                    val intents = connection.createArrayOf("text", filters.intents.toTypedArray())
                    statement.setArray(23, intents)
                    statement.setArray(24, intents)
                    statement.setObject(25, viewerUserId)
                    statement.setObject(26, viewerUserId)
                    statement.setObject(27, filters.linkedOnly)
                    statement.setObject(28, filters.linkedOnly)
                    statement.setObject(29, filters.linkedOnly)
                    statement.setBoolean(30, cursor != null)
                    statement.setObject(31, cursor?.boostBucket)
                    statement.setObject(32, cursor?.boostBucket)
                    statement.setObject(33, cursor?.distanceBucketMeters)
                    statement.setObject(34, cursor?.boostBucket)
                    statement.setObject(35, cursor?.distanceBucketMeters)
                    statement.setObject(36, cursor?.userId)
                    statement.setInt(37, limit + 1)
                    statement.setObject(38, viewerUserId)
                    statement.setObject(39, viewerUserId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    GridCardRecord(
                                        userId = rs.getObject("user_id", UUID::class.java),
                                        displayName = rs.getString("display_name"),
                                        thumbnailMediaAssetId = rs.getObject(
                                            "thumbnail_media_asset_id",
                                            UUID::class.java
                                        ),
                                        showDistance = rs.getBoolean("show_distance"),
                                        distanceMeters = rs.getDouble("distance_meters"),
                                        boostBucket = rs.getInt("boost_bucket"),
                                        distanceBucketMeters = rs.getInt("distance_bucket_meters"),
                                        linkedPartnerUserId = rs.getObject("linked_partner_user_id", UUID::class.java),
                                        linkedPartnerDisplayName = rs.getString("linked_partner_display_name"),
                                        linkedPartnerThumbnailMediaAssetId = rs.getObject(
                                            "linked_partner_thumbnail_media_asset_id",
                                            UUID::class.java
                                        ),
                                        visiting = rs.getBoolean("visiting")
                                    )
                                )
                            }
                        }
                    }
                }
            }

            val cards = fetchedCards.take(limit)
            GridQueryResult(
                viewerHadLocation = true,
                cards = cards,
                nextCursorAnchor = if (fetchedCards.size > limit && cards.isNotEmpty()) {
                    cards.last().toCursorAnchor()
                } else {
                    null
                }
            )
        }
    }
}
