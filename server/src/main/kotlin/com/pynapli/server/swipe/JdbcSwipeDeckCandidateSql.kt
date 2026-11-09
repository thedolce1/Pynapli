package com.pynapli.server.swipe

import java.sql.Connection
import java.util.UUID

internal fun Connection.findViewerLocationCacheKey(viewerUserId: UUID): DeckLocationCacheKey? =
    prepareStatement(
        """
        SELECT ST_X(geom::geometry) AS longitude,
               ST_Y(geom::geometry) AS latitude
        FROM (
            SELECT COALESCE(tl.geom, ul.geom) AS geom
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
                // Swipe is a recommendation deck, not the exact immediacy grid.
                // Bucket rank-cache centers to ~100m so nearby viewers share the
                // expensive spatial rank window while card distances stay close
                // enough for the swipe UI's coarse labels.
                DeckLocationCacheKey(
                    longitudeE3 = Math.round(rs.getDouble("longitude") * 1_000.0).toInt(),
                    latitudeE3 = Math.round(rs.getDouble("latitude") * 1_000.0).toInt(),
                )
            }
        }
    }

internal fun Connection.listActiveTravelCandidateUserIdsNear(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int = 2_000,
): Set<UUID> =
    prepareStatement(
        """
        WITH viewer_location AS (
            SELECT COALESCE(viewer_travel.geom, ul.geom) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations viewer_travel
              ON viewer_travel.user_id = ul.user_id
             AND viewer_travel.status = 'active'
             AND viewer_travel.active_until IS NULL
            WHERE ul.user_id = ?
        )
        SELECT tl.user_id
        FROM viewer_location v
        JOIN travel_locations tl
          ON tl.status = 'active'
         AND tl.active_until IS NULL
         AND ST_DWithin(v.geom, tl.geom, ?)
        JOIN users u ON u.id = tl.user_id
        JOIN profiles p ON p.user_id = u.id
        JOIN discovery_settings ds ON ds.user_id = u.id
        LEFT JOIN account_risk_states ars ON ars.user_id = u.id
        WHERE u.id <> ?
          AND u.status = 'active'
          AND u.deleted_at IS NULL
          AND p.visibility_status = 'visible'
          AND ds.swipe_visible = true
          AND ds.incognito = false
          AND ds.visible_while_traveling = true
          AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
        ORDER BY v.geom <-> tl.geom
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.setDouble(2, radiusMeters.toDouble())
        statement.setObject(3, viewerUserId)
        statement.setInt(4, limit)
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) {
                    add(rs.getObject("user_id", UUID::class.java))
                }
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
            SELECT COALESCE(tl.geom, ul.geom) AS geom
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
          AND ST_DWithin(v.geom, COALESCE(target_travel.geom, ul.geom), ?)
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

internal fun Connection.hasActiveTravelLocations(): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM travel_locations
            WHERE status = 'active'
              AND active_until IS NULL
        )
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
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
