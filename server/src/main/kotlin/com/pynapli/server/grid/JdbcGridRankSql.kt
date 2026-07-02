package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import java.sql.Connection
import java.util.UUID

/**
 * Cacheable RANK phase of the Grid query. Returns a WINDOW of ranked candidate
 * identity rows (user_id + the cheap ranking / keyset-cursor fields), ordered by
 * the SAME keyset order as the original single query
 * (boost_bucket ASC, distance_bucket_meters ASC, user_id ASC), with NO cursor
 * WHERE clause and LIMIT = [limit] (the caller passes the window size).
 *
 * When [userSpecificViewerId] is null the query is EXCLUSION-FREE: no viewer-self
 * filter and no per-viewer block subquery, so the window can be cached and shared
 * across viewers in the same coarse location + filter class. When it is non-null
 * (uncached bypass paths) the self filter and the linked-partner block subquery
 * are applied inline, preserving the original query's semantics exactly.
 *
 * Filter-class predicates (age/gender/relationship/verified/lifestyle/logistics/
 * tonight/intents/linkedOnly) stay in this SQL because they define the cache
 * class. Only per-viewer IDENTITY exclusions move out.
 */
internal fun Connection.queryGridRank(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int,
    filters: DiscoveryFilters,
    userSpecificViewerId: UUID?,
    cursor: GridCursorAnchor? = null,
): List<GridRankRow> {
    val sql =
        """
        WITH viewer_location AS (
            -- Passport/travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354): when the
            -- viewer has an active travel_locations row, its geom overrides the
            -- real GPS location for discovery.
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
                   GREATEST(0, FLOOR(ST_Distance(v.geom, COALESCE(target_travel.geom, ul.geom)))::integer) AS distance_bucket_meters,
                   ST_Distance(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters,
                   target_travel.id IS NOT NULL AS visiting,
                   -- has_visible_linked_partner only feeds the linkedOnly filter,
                   -- which always runs on the uncached path (userSpecificViewerId
                   -- set). On the cached path (?::uuid IS NULL) the per-viewer
                   -- block subquery is skipped and the value is unused.
                   (
                       ?::uuid IS NOT NULL
                       AND EXISTS (
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
                                 WHERE (ub.blocker_user_id = ?::uuid AND ub.blocked_user_id = linked_user.id)
                                    OR (ub.blocker_user_id = linked_user.id AND ub.blocked_user_id = ?::uuid)
                             )
                           LIMIT 1
                       )
                   ) AS has_visible_linked_partner
            FROM viewer_location v
            JOIN user_locations ul ON true
            JOIN users u ON u.id = ul.user_id
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            LEFT JOIN account_risk_states ars ON ars.user_id = u.id
            LEFT JOIN travel_locations target_travel
              ON target_travel.user_id = u.id
             AND target_travel.status = 'active'
             AND target_travel.active_until IS NULL
            WHERE (?::uuid IS NULL OR u.id <> ?::uuid)
              AND u.status = 'active'
              AND u.deleted_at IS NULL
              AND p.visibility_status = 'visible'
              AND ds.grid_visible = true
              AND ds.incognito = false
              AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)
              AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
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
              AND (
                  -- PLAYING_TONIGHT_SPEC: "playing tonight" means a live session OR the
                  -- legacy availability flag (transitional). Only evaluated when the
                  -- filter is on; the leading boolean short-circuits the default path.
                  ?::boolean = false
                  OR EXISTS (
                      SELECT 1
                      FROM tonight_session_participants tsp
                      JOIN tonight_sessions ts ON ts.id = tsp.session_id
                      WHERE tsp.user_id = u.id
                        AND ts.status = 'active'
                        AND ts.paused_at IS NULL
                        AND ts.expires_at > now()
                  )
                  OR (p.tonight_available_until IS NOT NULL AND p.tonight_available_until > now())
              )
              AND (cardinality(?::text[]) = 0 OR (p.intents IS NOT NULL AND p.intents && ?::text[]))
              -- Structured identity filters: hard match against the profile's stored gender /
              -- orientation key (self-describe keys never match a structured filter value).
              AND (cardinality(?::text[]) = 0 OR p.gender = ANY(?::text[]))
              AND (cardinality(?::text[]) = 0 OR p.orientation = ANY(?::text[]))
              -- FILTERS_SPEC L377 "Has photos": opt-in gate to at least one approved, visible
              -- public (profile-album) photo. Same definition as the profile_photo projection.
              AND (
                  ?::boolean = false
                  OR EXISTS (
                      SELECT 1
                      FROM media_albums has_photo_ma
                      JOIN media_album_items has_photo_mai
                        ON has_photo_mai.album_id = has_photo_ma.id
                       AND has_photo_mai.removed_at IS NULL
                      JOIN media_assets has_photo_m
                        ON has_photo_m.id = has_photo_mai.media_asset_id
                       AND has_photo_m.owner_user_id = has_photo_ma.owner_user_id
                       AND has_photo_m.deleted_at IS NULL
                      WHERE has_photo_ma.owner_user_id = u.id
                        AND has_photo_ma.kind = 'profile'
                        AND has_photo_ma.visibility_status = 'visible'
                        AND has_photo_ma.deleted_at IS NULL
                        AND has_photo_m.media_type = 'image'
                        AND has_photo_m.processing_status = 'ready'
                        AND has_photo_m.moderation_status = 'approved'
                  )
              )
              -- FILTERS_SPEC "Active today" (Grid Presence): profiles whose owner has a non-revoked
              -- session seen in the last 24h. "Online now" is Redis-live; "active today" is the
              -- durable session-activity signal, so it lives in SQL as a hard existence gate.
              AND (
                  ?::boolean = false
                  OR EXISTS (
                      SELECT 1
                      FROM user_sessions active_today_us
                      WHERE active_today_us.user_id = u.id
                        AND active_today_us.revoked_at IS NULL
                        AND active_today_us.last_seen_at > now() - interval '24 hours'
                  )
              )
              -- FILTERS_SPEC "Recently joined" (Grid Presence): account created within the last week.
              AND (?::boolean = false OR u.created_at > now() - interval '7 days')
              -- FILTERS_SPEC "Traveling here" (Grid Presence): the candidate is near the viewer via an
              -- active travel/passport location (the card's "visiting" state), not their home GPS.
              AND (?::boolean = false OR target_travel.id IS NOT NULL)
              -- Per-viewer self/block exclusion ONLY when userSpecificViewerId is
              -- set (uncached path). On the cached path these are applied in Kotlin
              -- (self) or post-repository in GridService (block).
              AND (
                  ?::uuid IS NULL
                  OR NOT EXISTS (
                      SELECT 1
                      FROM user_blocks ub
                      WHERE (ub.blocker_user_id = ?::uuid AND ub.blocked_user_id = u.id)
                         OR (ub.blocker_user_id = u.id AND ub.blocked_user_id = ?::uuid)
                  )
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
                -- Keyset cursor predicate (same as the original single query).
                -- Applied here on the uncached path only; the cached path passes
                -- cursor=null and slices the shared window in Kotlin.
                ?::boolean = false
                OR boost_bucket > ?::integer
                OR (boost_bucket = ?::integer AND distance_bucket_meters > ?::integer)
                OR (
                    boost_bucket = ?::integer
                    AND distance_bucket_meters = ?::integer
                    AND user_id > ?::uuid
                )
            )
        )
        SELECT pc.user_id,
               pc.display_name,
               pc.show_distance,
               pc.boost_bucket,
               pc.distance_bucket_meters,
               pc.distance_meters,
               pc.visiting
        FROM paged_candidates pc
        ORDER BY pc.boost_bucket ASC,
                 pc.distance_bucket_meters ASC,
                 pc.user_id ASC
        LIMIT ?
        """.trimIndent()

    return prepareStatement(sql).use { statement ->
        val contexts = createArrayOf("text", filters.relationshipContexts.toTypedArray())
        val styles = createArrayOf("text", filters.discoveryStyles.toTypedArray())
        val lifestyleTags = createArrayOf("text", filters.lifestyleTags.toTypedArray())
        val logisticsTags = createArrayOf("text", filters.logisticsTags.toTypedArray())
        val intents = createArrayOf("text", filters.intents.toTypedArray())
        val genders = createArrayOf("text", filters.genders.toTypedArray())
        val orientations = createArrayOf("text", filters.orientations.toTypedArray())
        statement.setObject(1, viewerUserId)
        // has_visible_linked_partner gate + per-viewer block params (skipped when null).
        statement.setNullableUuid(2, userSpecificViewerId)
        statement.setNullableUuid(3, userSpecificViewerId)
        statement.setNullableUuid(4, userSpecificViewerId)
        // self exclusion gate.
        statement.setNullableUuid(5, userSpecificViewerId)
        statement.setNullableUuid(6, userSpecificViewerId)
        // ST_DWithin radius bound twice (real GPS + travel point).
        statement.setDouble(7, radiusMeters.toDouble())
        statement.setDouble(8, radiusMeters.toDouble())
        statement.setObject(9, filters.minimumAge)
        statement.setObject(10, filters.minimumAge)
        statement.setObject(11, filters.maximumAge)
        statement.setObject(12, filters.maximumAge)
        statement.setArray(13, contexts)
        statement.setArray(14, contexts)
        statement.setBoolean(15, filters.verifiedOnly)
        statement.setBoolean(16, filters.facePhotoVerifiedOnly)
        statement.setBoolean(17, filters.ageVerifiedOnly)
        statement.setArray(18, styles)
        statement.setArray(19, styles)
        statement.setArray(20, lifestyleTags)
        statement.setArray(21, lifestyleTags)
        statement.setArray(22, logisticsTags)
        statement.setArray(23, logisticsTags)
        statement.setBoolean(24, filters.tonightAvailableOnly)
        statement.setArray(25, intents)
        statement.setArray(26, intents)
        statement.setArray(27, genders)
        statement.setArray(28, genders)
        statement.setArray(29, orientations)
        statement.setArray(30, orientations)
        // FILTERS_SPEC "Has photos" gate.
        statement.setBoolean(31, filters.withPhotosOnly)
        // FILTERS_SPEC "Active today" gate.
        statement.setBoolean(32, filters.activeTodayOnly)
        // FILTERS_SPEC "Recently joined" / "Traveling here" gates.
        statement.setBoolean(33, filters.recentlyJoinedOnly)
        statement.setBoolean(34, filters.travelingHereOnly)
        // per-viewer block exclusion (self path).
        statement.setNullableUuid(35, userSpecificViewerId)
        statement.setNullableUuid(36, userSpecificViewerId)
        statement.setNullableUuid(37, userSpecificViewerId)
        // linkedOnly filter (?::boolean IS NULL / true / false).
        statement.setObject(38, filters.linkedOnly)
        statement.setObject(39, filters.linkedOnly)
        statement.setObject(40, filters.linkedOnly)
        // Keyset cursor predicate (no-op when cursor is null).
        statement.setBoolean(41, cursor != null)
        statement.setObject(42, cursor?.boostBucket)
        statement.setObject(43, cursor?.boostBucket)
        statement.setObject(44, cursor?.distanceBucketMeters)
        statement.setObject(45, cursor?.boostBucket)
        statement.setObject(46, cursor?.distanceBucketMeters)
        statement.setObject(47, cursor?.userId)
        statement.setInt(48, limit)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        GridRankRow(
                            userId = rs.getObject("user_id", UUID::class.java),
                            displayName = rs.getString("display_name"),
                            showDistance = rs.getBoolean("show_distance"),
                            boostBucket = rs.getInt("boost_bucket"),
                            distanceBucketMeters = rs.getInt("distance_bucket_meters"),
                            distanceMeters = rs.getDouble("distance_meters"),
                            visiting = rs.getBoolean("visiting"),
                        ),
                    )
                }
            }
        }
    }
}
