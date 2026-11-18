package com.pynapli.server.swipe

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

internal fun Connection.listLinkedCandidateUserIdsNear(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int = 2_000,
): Set<UUID> =
    prepareStatement(
        """
        WITH viewer_location AS (
            SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        )
        SELECT upl.user_id
        FROM viewer_location v
        JOIN user_profile_links upl
          ON upl.status = 'active'
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
          AND linked_user.status = 'active'
          AND linked_user.deleted_at IS NULL
          AND linked_profile.visibility_status = 'visible'
          AND linked_settings.incognito = false
          AND COALESCE(linked_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
          AND NOT EXISTS (
              SELECT 1
              FROM user_blocks ub
              WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = linked_user.id)
                 OR (ub.blocker_user_id = linked_user.id AND ub.blocked_user_id = ?)
          )
          -- Same membership as the deck: the effective location's cell within the radius step.
          AND pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) <= ?
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, viewerUserId)
        statement.setDouble(4, radiusMeters.toDouble())
        statement.setInt(5, limit)
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) {
                    add(rs.getObject("user_id", UUID::class.java))
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
