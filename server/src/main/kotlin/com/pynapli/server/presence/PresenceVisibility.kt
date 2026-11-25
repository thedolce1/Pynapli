package com.pynapli.server.presence

import com.pynapli.server.db.withConnection
import java.util.UUID
import javax.sql.DataSource

/**
 * Whether a member's online/offline transitions may go out on the presence stream at all. The stream
 * accepts any subscribed id, so this is the gate: a delta is broadcast only for someone the Grid
 * itself could show (the same eligibility as the Grid rank and Favorites: active, not deleted, profile
 * visible, Grid-visible, not incognito, not hidden while travelling, not quarantined). Per-viewer rules
 * (blocks, contact blocks) are applied on top by the stream route.
 */
fun interface PresenceVisibility {
    suspend fun broadcastable(userId: UUID): Boolean
}

class JdbcPresenceVisibility(
    private val dataSource: DataSource,
) : PresenceVisibility {
    override suspend fun broadcastable(userId: UUID): Boolean =
        dataSource.withConnection { connection ->
            connection.prepareStatement(BROADCASTABLE_QUERY).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }

    private companion object {
        val BROADCASTABLE_QUERY =
            """
            SELECT EXISTS (
                SELECT 1
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                JOIN discovery_settings ds ON ds.user_id = u.id
                LEFT JOIN account_risk_states ars ON ars.user_id = u.id
                WHERE u.id = ?
                  AND u.status = 'active'
                  AND u.deleted_at IS NULL
                  AND p.visibility_status = 'visible'
                  AND ds.grid_visible = true
                  AND ds.incognito = false
                  AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  AND (
                      ds.visible_while_traveling = true
                      OR NOT EXISTS (
                          SELECT 1
                          FROM travel_locations tl
                          WHERE tl.user_id = u.id
                            AND tl.status = 'active'
                            AND tl.active_until IS NULL
                      )
                  )
            )
            """.trimIndent()
    }
}
