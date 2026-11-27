package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
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
 * (uncached bypass paths) the self filter and the per-viewer block subquery are
 * applied inline. The linkedOnly predicate is viewer-specific (the partner must be
 * shown to THIS viewer) and only ever runs uncached (isGridRankCacheEligible).
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
    // People hiding from the viewer (contact blocks): never counted as a shown partner (linkedOnly).
    hiddenFromViewerIds: Set<UUID> = emptySet(),
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
        -- Rank tier inputs (plan G3) for the nearby candidates only: one set each, which the planner
        -- builds once and hash-joins (G2-2 ticket 6). No bind parameters.
        tier_boosted AS (
            SELECT DISTINCT tier_ba.user_id
            FROM boost_activations tier_ba
            WHERE tier_ba.status = 'active'
              AND tier_ba.ends_at > now()
              AND tier_ba.user_id IN (SELECT tier_bn.user_id FROM nearby_ids tier_bn)
        ),
        tier_photo_owners AS (
            ${GridActivitySql.approvedProfilePhotoOwners(candidates = "nearby_ids", tag = "tier_po")}
        ),
        -- MATERIALIZED: planned as its own unit, so Postgres does not search join orders across
        -- every tier set and filter fragment on each call (planning 14 ms -> 4.5 ms; same results).
        base_candidates AS MATERIALIZED (
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'New member') AS display_name,
                   ds.show_distance,
                   -- Rank tier (plan G3), the first keyset column: 0 boosted, 1 everyone else,
                   -- 2 demoted (dormant 30+ days, or no approved public photo) so a thin member base
                   -- shows real, active, pictured people first. A boost outranks demotion. It depends
                   -- only on the candidate, never the viewer, so the shared window stays shareable.
                   -- G2-2: the tier reads three set lookups joined below (tier_boosted, tier_photo_owners,
                   -- user_last_active), not correlated subqueries per candidate before the LIMIT. Same
                   -- rule as GridActivitySql.demoted, so the order is unchanged (GridRankOrderPinTest).
                   CASE
                       WHEN tier_boost.user_id IS NOT NULL THEN 0
                       WHEN ${GridActivitySql.demotedJoined(user = "u", lastActive = "tier_ula", photoOwner = "tier_photo")} THEN 2
                       ELSE 1
                   END AS boost_bucket,
                   -- Cell-to-cell distance from the target's EFFECTIVE location (travel point when
                   -- travelling, else home): never a raw GPS distance, never a traveller's home.
                   pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters,
                   target_travel.id IS NOT NULL AS visiting
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
            LEFT JOIN tier_boosted tier_boost ON tier_boost.user_id = u.id
            LEFT JOIN tier_photo_owners tier_photo ON tier_photo.user_id = u.id
            LEFT JOIN user_last_active tier_ula ON tier_ula.user_id = u.id
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
              -- Every Grid filter (GridFilterSql), shared with "Visiting soon".
              ${GridFilterSql.predicates()}
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

    return withPinnedJoinOrder {
        prepareStatement(sql).use { statement ->
            // Bound in SQL order; the running index keeps added placeholders from shifting the rest.
            var index = 1
            statement.setObject(index++, viewerUserId)
            // nearby_ids index pre-filter radius (home GPS + travel point), padded by the cell slack.
            statement.setDouble(index++, paddedRadius)
            statement.setDouble(index++, paddedRadius)
            // self exclusion gate.
            statement.setNullableUuid(index++, userSpecificViewerId)
            statement.setNullableUuid(index++, userSpecificViewerId)
            // Exact cell-to-cell radius membership.
            statement.setDouble(index++, radiusMeters.toDouble())
            index = GridFilterSql.bind(statement, this, index, filters, viewerUserId, hiddenFromViewerIds)
            // per-viewer block exclusion (self path).
            statement.setNullableUuid(index++, userSpecificViewerId)
            statement.setNullableUuid(index++, userSpecificViewerId)
            statement.setNullableUuid(index++, userSpecificViewerId)
            // Order key: radius (hidden placement span), seed, seed.
            statement.setInt(index++, radiusMeters)
            statement.setString(index++, orderSeed)
            statement.setString(index++, orderSeed)
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
}

/**
 * Runs [block] with the planner held to the join order the rank SQL is written in (nearby_ids drives),
 * scoped to one short read transaction so nothing else on the pooled connection is affected. The
 * planner otherwise spends as long searching join orders across the tier sets and filter fragments
 * as the query takes to run (measured: 48 -> 38 ms per rank under 10 concurrent clients). A caller
 * already inside a transaction keeps the planner's own choice, since SET LOCAL would outlive the rank.
 */
internal inline fun <T> Connection.withPinnedJoinOrder(block: () -> T): T {
    if (!autoCommit) return block()
    autoCommit = false
    try {
        createStatement().use { statement ->
            statement.execute("SET LOCAL join_collapse_limit = 1")
            statement.execute("SET LOCAL from_collapse_limit = 1")
        }
        val result = block()
        commit()
        return result
    } catch (error: Throwable) {
        runCatching { rollback() }
        throw error
    } finally {
        autoCommit = true
    }
}
