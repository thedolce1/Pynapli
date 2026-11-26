package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** One "Visiting soon" row: who, their soonest trip here, and its keyset position. */
internal data class VisitingSoonRow(
    val userId: UUID,
    val displayName: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    /** Epoch day of the arrival (`starts_on`): the first keyset column, soonest first. */
    val arrivalDay: Int,
    /** L-08 order bucket of the trip destination (distance-independent when the member hides distance). */
    val orderBucket: Int,
)

/**
 * "Visiting soon": members whose announced planned trip (opt-in per trip) reaches the viewer's area
 * within the radius step and starts in the next [VISITING_SOON_WINDOW_DAYS] days, and who pass EVERY Grid filter
 * (GridFilterSql, the same fragment as the nearby rank) and the same visibility rules as the Grid (a
 * live, visible, Grid-visible, non-incognito, not quarantined account; visible while traveling).
 * Blocks either way, the viewer's hides and people hiding from the viewer are left out here, so a
 * page is a full page; GridService applies them again as a backstop.
 *
 * One row per member (their soonest qualifying trip), ordered by arrival day, then the trip
 * destination's L-08 order bucket, then id, and paged by keyset on that order: the cursor anchor
 * carries (arrival day, bucket, id) in its (boostBucket, orderBucket, userId) slots, with a zero
 * tiebreak. Membership is cell-to-cell (L-09/L-10), from the viewer's cell centre.
 */
internal fun Connection.queryVisitingSoonRank(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int,
    filters: DiscoveryFilters,
    orderSeed: String,
    hiddenFromViewerIds: Set<UUID>,
    cursor: GridCursorAnchor?,
): List<VisitingSoonRow> {
    val sql =
        """
        WITH viewer_location AS (
            -- The viewer's cell centre, as on the Grid (L-09/L-10): membership is cell-to-cell.
            SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        ),
        trips_here AS (
            SELECT t.id,
                   t.user_id,
                   t.starts_on,
                   t.ends_on,
                   pynapli_coarse_distance_m(v.geom, ST_SetSRID(ST_MakePoint(t.longitude, t.latitude), 4326)::geography) AS distance_meters
            FROM viewer_location v
            JOIN travel_trips t
              ON t.status = 'planned'
             AND t.announce_to_locals = true
             AND t.ends_on >= current_date
             AND t.starts_on <= current_date + $VISITING_SOON_WINDOW_DAYS
        ),
        -- Each member's soonest trip into the area, so a member appears once.
        next_trip AS (
            SELECT DISTINCT ON (th.user_id) th.*
            FROM trips_here th
            WHERE th.distance_meters <= ?
            ORDER BY th.user_id, th.starts_on, th.id
        ),
        candidates AS (
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'New member') AS display_name,
                   nt.starts_on,
                   nt.ends_on,
                   (nt.starts_on - DATE '1970-01-01') AS arrival_day,
                   pynapli_order_bucket_m(ds.show_distance, nt.distance_meters, ?, ?, u.id) AS order_bucket
            FROM next_trip nt
            JOIN users u ON u.id = nt.user_id
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            LEFT JOIN account_risk_states ars ON ars.user_id = u.id
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
              AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)
              AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
              -- Every Grid filter, exactly as on the nearby rank.
              ${GridFilterSql.predicates()}
              -- Per-viewer exclusions: blocks either way, the viewer's hides, contact blocks.
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_blocks vs_ub
                  WHERE (vs_ub.blocker_user_id = ? AND vs_ub.blocked_user_id = u.id)
                     OR (vs_ub.blocker_user_id = u.id AND vs_ub.blocked_user_id = ?)
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_hidden_profiles vs_hidden
                  WHERE vs_hidden.hider_user_id = ?
                    AND vs_hidden.hidden_user_id = u.id
              )
              AND u.id <> ALL(?::uuid[])
        )
        SELECT c.user_id, c.display_name, c.starts_on, c.ends_on, c.arrival_day, c.order_bucket
        FROM candidates c
        WHERE (
            ?::boolean = false
            OR c.arrival_day > ?::integer
            OR (c.arrival_day = ?::integer AND c.order_bucket > ?::integer)
            OR (c.arrival_day = ?::integer AND c.order_bucket = ?::integer AND c.user_id > ?::uuid)
        )
        ORDER BY c.arrival_day ASC, c.order_bucket ASC, c.user_id ASC
        LIMIT ?
        """.trimIndent()

    return prepareStatement(sql).use { statement ->
        // Bound in SQL order; the running index keeps added placeholders from shifting the rest.
        var index = 1
        statement.setObject(index++, viewerUserId)
        // Snapped radius step (GridService), tested cell-to-cell.
        statement.setDouble(index++, radiusMeters.toDouble())
        // Order bucket: radius (hidden placement span), seed.
        statement.setInt(index++, radiusMeters)
        statement.setString(index++, orderSeed)
        // Self.
        statement.setObject(index++, viewerUserId)
        index = GridFilterSql.bind(statement, this, index, filters, viewerUserId, hiddenFromViewerIds)
        // Blocks (both directions), the viewer's hides, people hiding from the viewer.
        statement.setObject(index++, viewerUserId)
        statement.setObject(index++, viewerUserId)
        statement.setObject(index++, viewerUserId)
        statement.setArray(index++, createArrayOf("uuid", hiddenFromViewerIds.toTypedArray()))
        // Keyset cursor (no-op when null).
        statement.setBoolean(index++, cursor != null)
        statement.setObject(index++, cursor?.boostBucket, java.sql.Types.INTEGER)
        statement.setObject(index++, cursor?.boostBucket, java.sql.Types.INTEGER)
        statement.setObject(index++, cursor?.orderBucket, java.sql.Types.INTEGER)
        statement.setObject(index++, cursor?.boostBucket, java.sql.Types.INTEGER)
        statement.setObject(index++, cursor?.orderBucket, java.sql.Types.INTEGER)
        statement.setObject(index++, cursor?.userId)
        statement.setInt(index++, limit)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        VisitingSoonRow(
                            userId = rs.getObject("user_id", UUID::class.java),
                            displayName = rs.getString("display_name"),
                            startsOn = rs.getDate("starts_on").toLocalDate(),
                            endsOn = rs.getDate("ends_on").toLocalDate(),
                            arrivalDay = rs.getInt("arrival_day"),
                            orderBucket = rs.getInt("order_bucket"),
                        ),
                    )
                }
            }
        }
    }
}

/** How far ahead an announced trip counts as "visiting soon". */
internal const val VISITING_SOON_WINDOW_DAYS = 14
