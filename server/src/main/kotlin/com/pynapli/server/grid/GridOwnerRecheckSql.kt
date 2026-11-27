package com.pynapli.server.grid

import java.sql.Connection
import java.util.UUID

/**
 * G2-2 ticket 5: the owner-side visibility of a page served from the SHARED rank window, re-read now.
 *
 * The rank query ([queryGridRank], JdbcGridRankSql `base_candidates` WHERE) applies account status,
 * profile visibility, `grid_visible`, incognito, visible-while-travelling and the risk tier while it
 * ranks. On the cached path those answers are as old as the window (its TTL), so a member who went
 * incognito, hid from the Grid, was suspended or quarantined a moment ago would still be served from
 * a window ranked before that. This re-checks exactly those predicates for the page's ids (a
 * primary-key lookup per id, a few dozen rows) so they hold at query time, the same as blocks,
 * hides and contact blocks (GridService) and self (JdbcGridRepository) already do.
 *
 * Filter-class predicates (age, tags, verified, ...) are not re-checked: a profile edit can take up
 * to one TTL to move someone in or out of a filtered window, which reveals nothing and hides no one.
 */
internal fun Connection.stillDiscoverableOnGrid(userIds: Collection<UUID>): Set<UUID> {
    if (userIds.isEmpty()) return emptySet()
    val sql =
        """
        SELECT u.id
        FROM users u
        JOIN profiles p ON p.user_id = u.id
        JOIN discovery_settings ds ON ds.user_id = u.id
        LEFT JOIN account_risk_states ars ON ars.user_id = u.id
        LEFT JOIN travel_locations target_travel
          ON target_travel.user_id = u.id
         AND target_travel.status = 'active'
         AND target_travel.active_until IS NULL
        WHERE u.id = ANY(?::uuid[])
          AND u.status = 'active'
          AND u.deleted_at IS NULL
          AND p.visibility_status = 'visible'
          AND ds.grid_visible = true
          AND ds.incognito = false
          AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)
          AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
        """.trimIndent()
    return prepareStatement(sql).use { statement ->
        statement.setArray(1, createArrayOf("uuid", userIds.toTypedArray()))
        statement.executeQuery().use { rs ->
            buildSet { while (rs.next()) add(rs.getObject("id", UUID::class.java)) }
        }
    }
}
