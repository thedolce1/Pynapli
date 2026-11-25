package com.pynapli.server.grid

import com.pynapli.server.discovery.CoupleVerifiedFilterSql
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.LifestyleFilterSql
import com.pynapli.server.distance.CoarseLocation
import java.sql.Connection
import java.util.UUID

/**
 * Cacheable RANK phase of the Grid query. Returns a WINDOW of ranked candidate
 * identity rows (user_id + the cheap ranking / keyset-cursor fields), ordered by
 * the SAME keyset order as the original single query
 * (boost_bucket ASC, order bucket ASC, order_tiebreak ASC, user_id ASC), with NO cursor
 * WHERE clause and LIMIT = [limit] (the caller passes the window size). `boost_bucket` is the rank
 * tier: 0 boosted, 1 normal, 2 demoted (GridActivitySql.demoted); the column keeps its name so
 * cursors and cached windows issued before the tier existed still decode.
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
    orderSeed: String,
    cursor: GridCursorAnchor? = null,
): List<GridRankRow> {
    val paddedRadius = radiusMeters + CoarseLocation.CELL_PAD_METERS
    val sql =
        """
        WITH viewer_location AS (
            -- Passport/travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354): when the
            -- viewer has an active travel_locations row, its geom overrides the
            -- real GPS location for discovery.
            -- Ranked from the CENTRE OF THE VIEWER'S CELL, never their exact GPS (L-10/L-14): every
            -- distance, radius test and order key below is a function of (viewer cell, target cell).
            SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        ),
        -- Candidate ids from two index-backed radius lookups (home GPS, active travel point). The
        -- radius test below is an OR across two LEFT-JOINed tables, which no index can serve, so
        -- without this every visible user was scanned and distance-tested (EXPLAIN on 106k users:
        -- seq scan, ~1.1 s). The radius here is padded by the cell slack so the exact cell-to-cell
        -- membership test in base_candidates never misses a candidate.
        nearby_ids AS (
            SELECT near_home.user_id
            FROM viewer_location v
            JOIN user_locations near_home ON ST_DWithin(v.geom, near_home.geom, ?)
            UNION
            SELECT near_travel.user_id
            FROM viewer_location v
            JOIN travel_locations near_travel
              ON near_travel.status = 'active'
             AND near_travel.active_until IS NULL
             AND ST_DWithin(v.geom, near_travel.geom, ?)
        ),
        base_candidates AS (
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                   ds.show_distance,
                   -- Rank tier (plan G3), the first keyset column: 0 boosted, 1 everyone else,
                   -- 2 demoted (dormant 30+ days, or no approved public photo) so a thin member base
                   -- shows real, active, pictured people first. A boost outranks demotion. It depends
                   -- only on the candidate, never the viewer, so the shared window stays shareable.
                   CASE
                       WHEN EXISTS (
                           SELECT 1
                           FROM boost_activations ba
                           WHERE ba.user_id = u.id
                             AND ba.status = 'active'
                             AND ba.ends_at > now()
                       ) THEN 0
                       WHEN ${GridActivitySql.demoted(user = "u", tag = "tier")} THEN 2
                       ELSE 1
                   END AS boost_bucket,
                   -- Cell-to-cell distance from the target's EFFECTIVE location (travel point when
                   -- travelling, else home): never a raw GPS distance, never a traveller's home.
                   pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters,
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
            JOIN nearby_ids n ON true
            JOIN user_locations ul ON ul.user_id = n.user_id
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
              -- Radius membership (L-09): cell-to-cell distance of the EFFECTIVE location within the
              -- snapped radius step. A traveller is matched only where they are travelling.
              AND pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) <= ?
              AND (?::integer IS NULL OR p.display_age >= ?::integer)
              AND (?::integer IS NULL OR p.display_age <= ?::integer)
              AND (cardinality(?::text[]) = 0 OR lower(p.relationship_context) = ANY(?::text[]))
              AND (?::boolean = false OR p.verified_at IS NOT NULL)
              AND (?::boolean = false OR p.face_photo_verified_at IS NOT NULL)
              AND (?::boolean = false OR p.age_verified_at IS NOT NULL)
              -- "Verified couples only": exactly the couple-verified badge for this viewer.
              ${CoupleVerifiedFilterSql.predicate(user = "u", profile = "p", settings = "ds")}
              AND (cardinality(?::text[]) = 0 OR p.discovery_style = ANY(?::text[]))
              -- Lifestyle groups + boundaries (LifestyleFilterSql): same predicate on every surface.
              ${LifestyleFilterSql.predicates("p")}
              AND (cardinality(?::text[]) = 0 OR (p.logistics_tags IS NOT NULL AND p.logistics_tags && ?::text[]))
              AND (
                  -- PLAYING_TONIGHT_SPEC: "playing tonight" means a live session. Only evaluated
                  -- when the filter is on; the leading boolean short-circuits the default path.
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
              -- FILTERS_SPEC "Has private album" (Media): opt-in gate to owning a visible private album
              -- with at least one approved photo. Discovery signal only — does not grant album access.
              AND (
                  ?::boolean = false
                  OR EXISTS (
                      SELECT 1
                      FROM media_albums has_private_ma
                      JOIN media_album_items has_private_mai
                        ON has_private_mai.album_id = has_private_ma.id
                       AND has_private_mai.removed_at IS NULL
                      JOIN media_assets has_private_m
                        ON has_private_m.id = has_private_mai.media_asset_id
                       AND has_private_m.owner_user_id = has_private_ma.owner_user_id
                       AND has_private_m.deleted_at IS NULL
                      WHERE has_private_ma.owner_user_id = u.id
                        AND has_private_ma.kind = 'private'
                        AND has_private_ma.visibility_status = 'visible'
                        AND has_private_ma.deleted_at IS NULL
                        AND has_private_m.media_type = 'image'
                        AND has_private_m.processing_status = 'ready'
                        AND has_private_m.moderation_status = 'approved'
                  )
              )
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
            -- L-08 order key: whole-km coarse bucket (or a distance-independent bucket when the
            -- candidate hides distance) + a stable pseudo-random tiebreak seeded by the viewer cell.
            SELECT bc.*,
                   pynapli_order_bucket_m(bc.show_distance, bc.distance_meters, ?, ?, bc.user_id) AS distance_bucket_meters,
                   pynapli_order_tiebreak(?, bc.user_id) AS order_tiebreak
            FROM base_candidates bc
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
                    AND order_tiebreak > ?::bigint
                )
                OR (
                    boost_bucket = ?::integer
                    AND distance_bucket_meters = ?::integer
                    AND order_tiebreak = ?::bigint
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
               pc.order_tiebreak,
               pc.visiting
        FROM paged_candidates pc
        ORDER BY pc.boost_bucket ASC,
                 pc.distance_bucket_meters ASC,
                 pc.order_tiebreak ASC,
                 pc.user_id ASC
        LIMIT ?
        """.trimIndent()

    return prepareStatement(sql).use { statement ->
        val contexts = createArrayOf("text", filters.relationshipContexts.toTypedArray())
        val styles = createArrayOf("text", filters.discoveryStyles.toTypedArray())
        val logisticsTags = createArrayOf("text", filters.logisticsTags.toTypedArray())
        val intents = createArrayOf("text", filters.intents.toTypedArray())
        val genders = createArrayOf("text", filters.genders.toTypedArray())
        val orientations = createArrayOf("text", filters.orientations.toTypedArray())
        // Bound in SQL order; the running index keeps added placeholders from shifting the rest.
        var index = 1
        statement.setObject(index++, viewerUserId)
        // nearby_ids index pre-filter radius (home GPS + travel point), padded by the cell slack.
        statement.setDouble(index++, paddedRadius)
        statement.setDouble(index++, paddedRadius)
        // has_visible_linked_partner gate + per-viewer block params (skipped when null).
        statement.setNullableUuid(index++, userSpecificViewerId)
        statement.setNullableUuid(index++, userSpecificViewerId)
        statement.setNullableUuid(index++, userSpecificViewerId)
        // self exclusion gate.
        statement.setNullableUuid(index++, userSpecificViewerId)
        statement.setNullableUuid(index++, userSpecificViewerId)
        // Exact cell-to-cell radius membership.
        statement.setDouble(index++, radiusMeters.toDouble())
        statement.setObject(index++, filters.minimumAge)
        statement.setObject(index++, filters.minimumAge)
        statement.setObject(index++, filters.maximumAge)
        statement.setObject(index++, filters.maximumAge)
        statement.setArray(index++, contexts)
        statement.setArray(index++, contexts)
        statement.setBoolean(index++, filters.verifiedOnly)
        statement.setBoolean(index++, filters.facePhotoVerifiedOnly)
        statement.setBoolean(index++, filters.ageVerifiedOnly)
        index = CoupleVerifiedFilterSql.bind(statement, index, filters.verifiedCouplesOnly, viewerUserId)
        statement.setArray(index++, styles)
        statement.setArray(index++, styles)
        index = LifestyleFilterSql.bind(statement, this, index, filters.lifestyleTags, filters.boundaryItems)
        statement.setArray(index++, logisticsTags)
        statement.setArray(index++, logisticsTags)
        statement.setBoolean(index++, filters.tonightAvailableOnly)
        statement.setArray(index++, intents)
        statement.setArray(index++, intents)
        statement.setArray(index++, genders)
        statement.setArray(index++, genders)
        statement.setArray(index++, orientations)
        statement.setArray(index++, orientations)
        // FILTERS_SPEC "Has photos" gate.
        statement.setBoolean(index++, filters.withPhotosOnly)
        // FILTERS_SPEC "Active today" gate.
        statement.setBoolean(index++, filters.activeTodayOnly)
        // FILTERS_SPEC "Recently joined" / "Traveling here" gates.
        statement.setBoolean(index++, filters.recentlyJoinedOnly)
        statement.setBoolean(index++, filters.travelingHereOnly)
        // FILTERS_SPEC "Has private album" gate.
        statement.setBoolean(index++, filters.hasPrivateAlbumOnly)
        // per-viewer block exclusion (self path).
        statement.setNullableUuid(index++, userSpecificViewerId)
        statement.setNullableUuid(index++, userSpecificViewerId)
        statement.setNullableUuid(index++, userSpecificViewerId)
        // Order key: radius (hidden placement span), seed, seed.
        statement.setInt(index++, radiusMeters)
        statement.setString(index++, orderSeed)
        statement.setString(index++, orderSeed)
        // linkedOnly filter (?::boolean IS NULL / true / false).
        statement.setObject(index++, filters.linkedOnly)
        statement.setObject(index++, filters.linkedOnly)
        statement.setObject(index++, filters.linkedOnly)
        // Keyset cursor predicate (no-op when cursor is null).
        statement.setBoolean(index++, cursor != null)
        statement.setObject(index++, cursor?.boostBucket)
        statement.setObject(index++, cursor?.boostBucket)
        statement.setObject(index++, cursor?.orderBucket)
        statement.setObject(index++, cursor?.boostBucket)
        statement.setObject(index++, cursor?.orderBucket)
        statement.setObject(index++, cursor?.orderTiebreak)
        statement.setObject(index++, cursor?.boostBucket)
        statement.setObject(index++, cursor?.orderBucket)
        statement.setObject(index++, cursor?.orderTiebreak)
        statement.setObject(index++, cursor?.userId)
        statement.setInt(index++, limit)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        GridRankRow(
                            userId = rs.getObject("user_id", UUID::class.java),
                            displayName = rs.getString("display_name"),
                            showDistance = rs.getBoolean("show_distance"),
                            boostBucket = rs.getInt("boost_bucket"),
                            orderBucket = rs.getInt("distance_bucket_meters"),
                            distanceMeters = rs.getDouble("distance_meters"),
                            orderTiebreak = rs.getLong("order_tiebreak"),
                            visiting = rs.getBoolean("visiting"),
                        ),
                    )
                }
            }
        }
    }
}
