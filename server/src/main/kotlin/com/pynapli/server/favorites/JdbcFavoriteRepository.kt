package com.pynapli.server.favorites

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.lockForTransaction
import com.pynapli.server.db.withConnection
import com.pynapli.server.grid.GridHydration
import com.pynapli.server.grid.hydrateGridCards
import java.util.UUID
import javax.sql.DataSource

internal class JdbcFavoriteRepository(
    private val dataSource: DataSource,
) : FavoriteRepository {
    override suspend fun add(
        userId: UUID,
        favoriteUserId: UUID,
        cap: Int,
    ): FavoriteAddResult =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                // Serialise one user's adds so two concurrent stars can't both slip under the cap.
                lockForTransaction("favorites", userId.toString())
                val exists =
                    prepareStatement("SELECT 1 FROM favorite_profiles WHERE user_id = ? AND favorite_user_id = ?").use { statement ->
                        statement.setObject(1, userId)
                        statement.setObject(2, favoriteUserId)
                        statement.executeQuery().use { it.next() }
                    }
                if (exists) return@inTransaction FavoriteAddResult.ALREADY_FAVORITE
                val targetLive =
                    prepareStatement("SELECT 1 FROM users WHERE id = ? AND status = 'active' AND deleted_at IS NULL").use { statement ->
                        statement.setObject(1, favoriteUserId)
                        statement.executeQuery().use { it.next() }
                    }
                if (!targetLive) return@inTransaction FavoriteAddResult.TARGET_NOT_FOUND
                val count =
                    prepareStatement("SELECT count(*) FROM favorite_profiles WHERE user_id = ?").use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                if (count >= cap) return@inTransaction FavoriteAddResult.LIMIT_REACHED
                prepareStatement(
                    "INSERT INTO favorite_profiles (user_id, favorite_user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, favoriteUserId)
                    statement.executeUpdate()
                }
                FavoriteAddResult.ADDED
            }
        }

    override suspend fun remove(
        userId: UUID,
        favoriteUserId: UUID,
    ) {
        dataSource.withConnection { connection ->
            connection.prepareStatement("DELETE FROM favorite_profiles WHERE user_id = ? AND favorite_user_id = ?").use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, favoriteUserId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun isFavorite(
        userId: UUID,
        favoriteUserId: UUID,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.prepareStatement("SELECT 1 FROM favorite_profiles WHERE user_id = ? AND favorite_user_id = ?").use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, favoriteUserId)
                statement.executeQuery().use { it.next() }
            }
        }

    override suspend fun listVisible(
        viewerUserId: UUID,
        limit: Int,
    ): List<FavoriteRankRow> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(LIST_VISIBLE_FAVORITES).use { statement ->
                statement.setObject(1, viewerUserId) // viewer location
                statement.setObject(2, viewerUserId) // favorites owner
                statement.setObject(3, viewerUserId) // block: viewer blocked them
                statement.setObject(4, viewerUserId) // block: they blocked the viewer
                statement.setObject(5, viewerUserId) // hidden by the viewer
                statement.setString(6, "favorites:$viewerUserId") // order seed (hidden bucket)
                statement.setString(7, "favorites:$viewerUserId") // order seed (tiebreak)
                statement.setInt(8, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                FavoriteRankRow(
                                    userId = rs.getObject("user_id", UUID::class.java),
                                    displayName = rs.getString("display_name"),
                                    showDistance = rs.getBoolean("show_distance"),
                                    distanceMeters = rs.getDouble("distance_meters").takeUnless { rs.wasNull() },
                                    orderBucket = rs.getInt("order_bucket"),
                                    orderTiebreak = rs.getLong("order_tiebreak"),
                                    visiting = rs.getBoolean("visiting"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override suspend fun hydrate(
        viewerUserId: UUID,
        userIds: List<UUID>,
    ): Map<UUID, GridHydration> = dataSource.withConnection { connection -> connection.hydrateGridCards(viewerUserId, userIds) }
}

/**
 * Location privacy (GRID_IMPLEMENTATION_SPEC "Location privacy"): every location-derived value here is
 * a function of (viewer cell, target cell) through the V94/V95 helpers. Distance is cell to cell
 * between both EFFECTIVE locations (travel point when travelling, never a traveller's home). The order
 * bucket is the public display bucket for someone who shows distance (never finer than what the card
 * reads), and for someone who hides it (or has no location) a pseudo-random bucket seeded by the
 * viewer and the target id that does not depend on where they are. Visibility mirrors the Grid's
 * candidate predicates so a star is never a way to keep watching someone who hid.
 */
private val LIST_VISIBLE_FAVORITES =
    """
    WITH viewer_location AS (
        SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
        FROM user_locations ul
        LEFT JOIN travel_locations tl
          ON tl.user_id = ul.user_id
         AND tl.status = 'active'
         AND tl.active_until IS NULL
        WHERE ul.user_id = ?
    ),
    visible AS (
        SELECT u.id AS user_id,
               COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
               ds.show_distance,
               target_travel.id IS NOT NULL AS visiting,
               CASE
                   WHEN v.geom IS NOT NULL AND ul.user_id IS NOT NULL
                   THEN pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom))
               END AS distance_meters
        FROM favorite_profiles f
        JOIN users u ON u.id = f.favorite_user_id
        JOIN profiles p ON p.user_id = u.id
        JOIN discovery_settings ds ON ds.user_id = u.id
        LEFT JOIN account_risk_states ars ON ars.user_id = u.id
        LEFT JOIN user_locations ul ON ul.user_id = u.id
        LEFT JOIN travel_locations target_travel
          ON target_travel.user_id = u.id
         AND target_travel.status = 'active'
         AND target_travel.active_until IS NULL
        LEFT JOIN viewer_location v ON true
        WHERE f.user_id = ?
          AND u.status = 'active'
          AND u.deleted_at IS NULL
          AND p.visibility_status = 'visible'
          AND ds.grid_visible = true
          AND ds.incognito = false
          AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)
          AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
          AND NOT EXISTS (
              SELECT 1 FROM user_blocks ub
              WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = u.id)
                 OR (ub.blocker_user_id = u.id AND ub.blocked_user_id = ?)
          )
          AND NOT EXISTS (
              SELECT 1 FROM user_hidden_profiles h
              WHERE h.hider_user_id = ? AND h.hidden_user_id = u.id
          )
    )
    SELECT visible.*,
           CASE
               WHEN show_distance AND distance_meters IS NOT NULL THEN pynapli_public_distance_m(distance_meters)
               ELSE pynapli_order_bucket_m(false, 0, 25000, ?, user_id)
           END AS order_bucket,
           pynapli_order_tiebreak(?, user_id) AS order_tiebreak
    FROM visible
    LIMIT ?
    """.trimIndent()
