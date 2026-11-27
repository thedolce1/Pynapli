package com.pynapli.server.swipe

import com.pynapli.server.discovery.CoupleVerifiedFilterSql
import com.pynapli.server.discovery.LinkedPartnerFilterSql
import com.pynapli.server.distance.CoarseLocation
import java.sql.Connection
import java.util.UUID

internal fun Connection.findViewerLocationCacheKey(viewerUserId: UUID): DeckLocationCacheKey? =
    prepareStatement(
        """
        SELECT ST_X(geom::geometry) AS longitude,
               ST_Y(geom::geometry) AS latitude
        FROM (
            -- The viewer's cell at their effective location: the rank window depends on nothing finer.
            SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        ) viewer_location
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                // Keyed by location cell, like the Grid: every viewer in a cell shares one ranked
                // window, and the window (which is ranked from the cell centre) is exact for each.
                val (cellLongitude, cellLatitude) = CoarseLocation.cellKey(rs.getDouble("longitude"), rs.getDouble("latitude"))
                DeckLocationCacheKey(cellLongitude = cellLongitude, cellLatitude = cellLatitude)
            }
        }
    }

/**
 * Linked-only deck candidates: eligible people with an eligible linked partner whose EFFECTIVE
 * location's cell is within [radiusMeters] of the viewer's cell (the same membership as
 * [queryDeckRank]). Candidates come from the same two GiST-backed ST_DWithin lookups as the deck
 * (home GPS, active travel point, padded by [CoarseLocation.CELL_PAD_METERS]), so the cost follows
 * the people near the viewer, not every linked user on the platform; the exact cell-to-cell test
 * then decides. Beyond [limit] the cut is the deck's own distance-independent tiebreak (seeded by
 * the viewer's cell), so it is stable and reveals nothing about distance.
 */
internal fun Connection.listLinkedCandidateUserIdsNear(
    viewerUserId: UUID,
    radiusMeters: Int,
    // People hiding from the viewer (contact blocks; for a Psync deck, from either partner).
    hiddenFromViewerIds: Set<UUID> = emptySet(),
    // A Psync deck's other partner (the partner must be shown to both), else null.
    coViewerUserId: UUID? = null,
    limit: Int = 2_000,
): Set<UUID> {
    // A Psync deck is shown to both partners: the partner must be shown to each (PSYNC_SPEC).
    val partnerViewers = listOfNotNull(viewerUserId, coViewerUserId)
    val partnerShown =
        CoupleVerifiedFilterSql.partnerShownToViewer(
            partnerUser = "linked_user",
            partnerProfile = "linked_profile",
            partnerSettings = "linked_settings",
            partnerRisk = "linked_risk",
            ownerSettings = "target_settings",
            owner = "target.id",
        )
    val partnerShownToEveryViewer = partnerViewers.joinToString("\n") { "AND $partnerShown" }
    return prepareStatement(
        """
        WITH viewer_location AS (
            SELECT cell.geom,
                   'deck:' || ST_AsText(cell.geom::geometry) AS order_seed
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            CROSS JOIN LATERAL (SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom) cell
            WHERE ul.user_id = ?
        ),
        -- Index pre-filter, as in queryDeckRank: anyone whose home or active travel point is within
        -- the padded radius. Every effective location that passes the exact test below is in here.
        near_ids AS (
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
        )
        SELECT upl.user_id
        FROM viewer_location v
        JOIN near_ids nearby
          ON true
        JOIN user_profile_links upl
          ON upl.user_id = nearby.user_id
         AND upl.status = 'active'
         AND upl.unlinked_at IS NULL
        JOIN users target
          ON target.id = upl.user_id
        JOIN profiles target_profile
          ON target_profile.user_id = target.id
        JOIN discovery_settings target_settings
          ON target_settings.user_id = target.id
        JOIN user_locations ul
          ON ul.user_id = target.id
        JOIN users linked_user
          ON linked_user.id = upl.linked_user_id
        JOIN profiles linked_profile
          ON linked_profile.user_id = linked_user.id
        JOIN discovery_settings linked_settings
          ON linked_settings.user_id = linked_user.id
        LEFT JOIN account_risk_states target_risk
          ON target_risk.user_id = target.id
        LEFT JOIN account_risk_states linked_risk
          ON linked_risk.user_id = linked_user.id
        LEFT JOIN travel_locations target_travel
          ON target_travel.user_id = target.id
         AND target_travel.status = 'active'
         AND target_travel.active_until IS NULL
        WHERE target.status = 'active'
          AND target.deleted_at IS NULL
          AND target_profile.visibility_status = 'visible'
          AND target_settings.swipe_visible = true
          AND target_settings.incognito = false
          AND (target_travel.id IS NULL OR target_settings.visible_while_traveling = true)
          AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
          -- The partner is the one the card's partner mini shows every viewer (LinkedPartnerFilterSql):
          -- not hiding from a viewer (contact blocks), and CoupleVerifiedFilterSql.partnerShownToViewer
          -- per viewer (status, blocks both ways, the viewer's hidden list, the owner's partner_visibility).
          AND linked_user.id <> ALL(?::uuid[])
          $partnerShownToEveryViewer
          -- Same membership as the deck: the effective location's cell within the radius step.
          AND pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) <= ?
        -- Only matters past the limit: a stable, distance-independent cut (the deck's L-08 tiebreak).
        ORDER BY pynapli_order_tiebreak(v.order_seed, upl.user_id), upl.user_id
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        val paddedRadius = radiusMeters + CoarseLocation.CELL_PAD_METERS
        // Bound in SQL order; the running index keeps added placeholders from shifting the rest.
        var index = 1
        statement.setObject(index++, viewerUserId)
        // Index pre-filter radius (home GPS + travel point), padded by the cell slack.
        statement.setDouble(index++, paddedRadius)
        statement.setDouble(index++, paddedRadius)
        // Partner shown to every viewer: hidden-from ids, then partnerShownToViewer's six ids per viewer.
        index = LinkedPartnerFilterSql.bindVisiblePartner(statement, this, index, partnerViewers, hiddenFromViewerIds)
        // Exact cell-to-cell radius membership.
        statement.setDouble(index++, radiusMeters.toDouble())
        statement.setInt(index++, limit)
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) {
                    add(rs.getObject("user_id", UUID::class.java))
                }
            }
        }
    }
}

internal fun Connection.listActiveSwipedTargetIds(
    actorUserId: UUID,
    targetUserIds: Set<UUID>,
): Set<UUID> {
    if (targetUserIds.isEmpty()) return emptySet()
    return prepareStatement(
        """
        SELECT target_user_id
        FROM swipe_actions
        WHERE actor_user_id = ?
          AND rewound_at IS NULL
          AND target_user_id = ANY(?::uuid[])
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, actorUserId)
        statement.setArray(2, createArrayOf("uuid", targetUserIds.toTypedArray()))
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) {
                    add(rs.getObject("target_user_id", UUID::class.java))
                }
            }
        }
    }
}
