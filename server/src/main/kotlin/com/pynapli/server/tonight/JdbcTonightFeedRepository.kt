package com.pynapli.server.tonight

import com.pynapli.server.db.withConnection
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.LifestyleFilterSql
import com.pynapli.server.distance.CoarseLocation
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC reader for the Playing Tonight feed (PT-3). The candidate query mirrors the
 * Grid's discovery query: a `viewer_location` CTE (travel point overrides real GPS),
 * `ST_DWithin`/`ST_Distance` against `user_locations.geom`, the same owner
 * eligibility gates, and the same profile-photo LATERAL for thumbnails. It anchors
 * each row on the session OWNER participant and resolves the linked partner for
 * `mode='together'` via a second participant + profile-photo LATERAL.
 *
 * Per-viewer block/hidden filtering and zone/distance/online resolution happen in
 * [TonightFeedService] (same split the Grid uses). No location ever leaves here in
 * precise form (L-11): the owner's EFFECTIVE location (travel point when travelling) is
 * only ever read as a cell-to-cell distance from the viewer's cell and as a coarse zone
 * index on a [ZONE_DEGREES] grid derived from the owner's cell; radius membership is cell-to-cell too.
 */
class JdbcTonightFeedRepository(
    private val dataSource: DataSource,
) : TonightFeedRepository {
    override suspend fun feedNear(
        viewerUserId: UUID,
        radiusMeters: Int,
        intent: String?,
        limit: Int,
        now: Instant,
        filters: DiscoveryFilters,
    ): List<TonightFeedRow> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(SQL).use { statement ->
                val paddedRadius = radiusMeters + CoarseLocation.CELL_PAD_METERS
                var index = 1
                statement.setObject(index++, viewerUserId) // viewer_location CTE
                statement.setInt(index++, radiusMeters) // order bucket: hidden placement span
                statement.setTimestamp(index++, Timestamp.from(now)) // ts.expires_at > ?
                statement.setObject(index++, viewerUserId) // owner.id <> ?
                statement.setDouble(index++, paddedRadius) // index pre-filter, home
                statement.setDouble(index++, paddedRadius) // index pre-filter, travel point
                statement.setDouble(index++, radiusMeters.toDouble()) // exact cell-to-cell radius
                statement.setString(index++, intent) // intent filter sentinel
                statement.setString(index++, intent) // intent filter value
                val orientations = connection.createArrayOf("text", filters.orientations.toTypedArray())
                statement.setArray(index++, orientations)
                statement.setArray(index++, orientations)
                index = LifestyleFilterSql.bind(statement, connection, index, filters.lifestyleTags, filters.boundaryItems)
                statement.setInt(index++, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                TonightFeedRow(
                                    sessionId = rs.getObject("session_id", UUID::class.java),
                                    mode = rs.getString("mode"),
                                    intent = rs.getString("intent"),
                                    hostMode = rs.getString("host_mode"),
                                    note = rs.getString("note"),
                                    photoMediaAssetId = rs.getObject("photo_media_asset_id", UUID::class.java),
                                    ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
                                    ownerDisplayName = rs.getString("owner_display_name"),
                                    ownerShowDistance = rs.getBoolean("owner_show_distance"),
                                    ownerDistanceMeters = rs.getDouble("owner_distance_meters"),
                                    ownerThumbnailMediaAssetId =
                                        rs.getObject("owner_thumbnail_media_asset_id", UUID::class.java),
                                    ownerZoneX = rs.getInt("owner_zone_x"),
                                    ownerZoneY = rs.getInt("owner_zone_y"),
                                    partnerUserId = rs.getObject("partner_user_id", UUID::class.java),
                                    partnerDisplayName = rs.getString("partner_display_name"),
                                    partnerThumbnailMediaAssetId =
                                        rs.getObject("partner_thumbnail_media_asset_id", UUID::class.java),
                                ),
                            )
                        }
                    }
                }
            }
        }

    companion object {
        /**
         * Zone grid for the Tonight map: 0.02 degree (~2.2 km north-south), two location cells per
         * axis. Documentation only: the zone is computed in SQL by `pynapli_tonight_zone_x/_y` (V116)
         * from the owner's cell, as floor(cell hundredths / 2) on integers. A cell centre on a zone
         * edge (even hundredths) belongs to the zone east / north of that edge, negatives included.
         */
        const val ZONE_DEGREES: Double = 0.02

        // Profile-photo LATERAL identical to the Grid's hydration block (same album/
        // asset gates + ordering), parameterized on the candidate user id column.
        private fun profilePhotoLateral(ownerColumn: String): String =
            """
            SELECT mai.media_asset_id
            FROM media_albums ma
            JOIN media_album_items mai
              ON mai.album_id = ma.id
             AND mai.removed_at IS NULL
            JOIN media_assets m
              ON m.id = mai.media_asset_id
             AND m.owner_user_id = ma.owner_user_id
             AND m.deleted_at IS NULL
            WHERE ma.owner_user_id = $ownerColumn
              AND ma.kind = 'profile'
              AND ma.visibility_status = 'visible'
              AND ma.deleted_at IS NULL
              AND m.media_type = 'image'
              AND m.processing_status = 'ready'
              AND m.moderation_status = 'approved'
              AND m.intended_album_kind = 'profile'
            ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
            LIMIT 1
            """.trimIndent()

        private val SQL =
            """
            WITH viewer_location AS (
                -- Passport/travel mode: an active travel_locations row overrides the
                -- viewer's real GPS for discovery, snapped to the viewer's cell (as the Grid).
                SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
                FROM user_locations ul
                LEFT JOIN travel_locations tl
                  ON tl.user_id = ul.user_id
                 AND tl.status = 'active'
                 AND tl.active_until IS NULL
                WHERE ul.user_id = ?
            )
            SELECT ts.id AS session_id,
                   ts.mode,
                   ts.intent,
                   ts.host_mode,
                   ts.note,
                   -- PLAYING_TONIGHT_SPEC: surface the optional session photo ONLY once it is
                   -- moderation-approved + processed. A pending/held/rejected/deleted photo is
                   -- nulled so the session "continues without photo" (Edge Cases) and no
                   -- unreviewed image is ever shown publicly.
                   (
                       SELECT m.id
                       FROM media_assets m
                       WHERE m.id = ts.photo_media_asset_id
                         AND m.deleted_at IS NULL
                         AND m.processing_status = 'ready'
                         AND m.moderation_status = 'approved'
                         -- Public surface: only a profile photo (pre-screened), never a private-album,
                         -- chat or verification asset the owner happens to own.
                         AND m.intended_album_kind = 'profile'
                   ) AS photo_media_asset_id,
                   owner_user.id AS owner_user_id,
                   COALESCE(NULLIF(owner_profile.display_name, ''), 'New member') AS owner_display_name,
                   owner_ds.show_distance AS owner_show_distance,
                   pynapli_coarse_distance_m(v.geom, COALESCE(owner_travel.geom, owner_ul.geom)) AS owner_distance_meters,
                   -- Zones come from the owner's CELL in integer arithmetic (V116), never the raw point.
                   pynapli_tonight_zone_x(COALESCE(owner_travel.geom, owner_ul.geom)) AS owner_zone_x,
                   pynapli_tonight_zone_y(COALESCE(owner_travel.geom, owner_ul.geom)) AS owner_zone_y,
                   pynapli_order_bucket_m(
                       owner_ds.show_distance,
                       pynapli_coarse_distance_m(v.geom, COALESCE(owner_travel.geom, owner_ul.geom)),
                       ?,
                       'tonight:' || ST_AsText(v.geom::geometry),
                       owner_user.id
                   ) AS order_bucket,
                   pynapli_order_tiebreak('tonight:' || ST_AsText(v.geom::geometry), owner_user.id) AS order_tiebreak,
                   owner_photo.media_asset_id AS owner_thumbnail_media_asset_id,
                   partner.partner_user_id,
                   partner.partner_display_name,
                   partner.partner_thumbnail_media_asset_id
            FROM tonight_sessions ts
            CROSS JOIN viewer_location v
            JOIN tonight_session_participants owner_tsp
              ON owner_tsp.session_id = ts.id
             AND owner_tsp.role = 'owner'
            JOIN users owner_user ON owner_user.id = owner_tsp.user_id
            JOIN profiles owner_profile ON owner_profile.user_id = owner_user.id
            JOIN discovery_settings owner_ds ON owner_ds.user_id = owner_user.id
            JOIN user_locations owner_ul ON owner_ul.user_id = owner_user.id
            LEFT JOIN travel_locations owner_travel
              ON owner_travel.user_id = owner_user.id
             AND owner_travel.status = 'active'
             AND owner_travel.active_until IS NULL
            LEFT JOIN account_risk_states owner_risk ON owner_risk.user_id = owner_user.id
            LEFT JOIN LATERAL (
                ${profilePhotoLateral("owner_user.id")}
            ) owner_photo ON true
            LEFT JOIN LATERAL (
                -- Partner is only relevant for together sessions; resolve the other
                -- participant (role='partner') + their display name + photo. The
                -- partner may be blocked/hidden by the viewer — that is nulled out in
                -- the service, not filtered here (the session row still shows).
                SELECT partner_user.id AS partner_user_id,
                       COALESCE(NULLIF(partner_profile.display_name, ''), 'New member') AS partner_display_name,
                       partner_photo.media_asset_id AS partner_thumbnail_media_asset_id
                FROM tonight_session_participants partner_tsp
                JOIN users partner_user ON partner_user.id = partner_tsp.user_id
                JOIN profiles partner_profile ON partner_profile.user_id = partner_user.id
                LEFT JOIN LATERAL (
                    ${profilePhotoLateral("partner_user.id")}
                ) partner_photo ON true
                WHERE partner_tsp.session_id = ts.id
                  AND partner_tsp.role = 'partner'
                  -- A suspended, banned or deleted partner is never shown on someone's session.
                  AND partner_user.status = 'active'
                  AND partner_user.deleted_at IS NULL
                ORDER BY partner_user.id ASC
                LIMIT 1
            ) partner ON ts.mode = 'together'
            WHERE ts.status = 'active'
              AND ts.paused_at IS NULL
              AND ts.expires_at > ?
              AND ts.map_visible = true
              AND owner_user.id <> ?
              AND owner_user.status = 'active'
              AND owner_user.deleted_at IS NULL
              AND owner_profile.visibility_status = 'visible'
              AND owner_ds.grid_visible = true
              AND owner_ds.incognito = false
              AND COALESCE(owner_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
              -- Same travel opt-out the Grid honours.
              AND (owner_travel.id IS NULL OR owner_ds.visible_while_traveling = true)
              -- Index-friendly padded pre-filter, then exact cell-to-cell membership of the
              -- owner's EFFECTIVE location (a traveller is never matched by their home).
              AND (
                  ST_DWithin(v.geom, owner_ul.geom, ?)
                  OR (owner_travel.geom IS NOT NULL AND ST_DWithin(v.geom, owner_travel.geom, ?))
              )
              AND pynapli_coarse_distance_m(v.geom, COALESCE(owner_travel.geom, owner_ul.geom)) <= ?
              AND (?::text IS NULL OR ts.intent = ?::text)
              -- The session OWNER's own orientation, lifestyle and boundaries (same predicates as
              -- Grid and Swipe); a linked partner's values never stand in for the owner's.
              AND (cardinality(?::text[]) = 0 OR owner_profile.orientation = ANY(?::text[]))
              ${LifestyleFilterSql.predicates("owner_profile")}
            -- L-08 ordering: coarse km bucket (distance-independent for owners who hide it),
            -- then a stable pseudo-random tiebreak; never exact distance.
            ORDER BY order_bucket ASC, order_tiebreak ASC, ts.id ASC
            LIMIT ?
            """.trimIndent()
    }
}
