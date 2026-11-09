package com.pynapli.server.tonight

import com.pynapli.server.db.withConnection
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
 * precise form — only the raw distance + coarse E3 lat/lon for the zone bucket.
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
    ): List<TonightFeedRow> =
        dataSource.withConnection { connection ->
            connection.prepareStatement(SQL).use { statement ->
                statement.setObject(1, viewerUserId) // viewer_location CTE
                statement.setTimestamp(2, Timestamp.from(now)) // ts.expires_at > ?
                statement.setObject(3, viewerUserId) // owner.id <> ?
                statement.setDouble(4, radiusMeters.toDouble()) // ST_DWithin radius
                statement.setString(5, intent) // intent filter sentinel
                statement.setString(6, intent) // intent filter value
                statement.setInt(7, limit)
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
                                    ownerLatE3 = rs.getInt("owner_lat_e3"),
                                    ownerLonE3 = rs.getInt("owner_lon_e3"),
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

    private companion object {
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
            ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
            LIMIT 1
            """.trimIndent()

        val SQL =
            """
            WITH viewer_location AS (
                -- Passport/travel mode: an active travel_locations row overrides the
                -- viewer's real GPS for discovery (same CTE the Grid uses).
                SELECT COALESCE(tl.geom, ul.geom) AS geom
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
                   ) AS photo_media_asset_id,
                   owner_user.id AS owner_user_id,
                   COALESCE(NULLIF(owner_profile.display_name, ''), 'Pynapli User') AS owner_display_name,
                   owner_ds.show_distance AS owner_show_distance,
                   ST_Distance(v.geom, owner_ul.geom) AS owner_distance_meters,
                   ROUND(ST_Y(owner_ul.geom::geometry) * 1000)::integer AS owner_lat_e3,
                   ROUND(ST_X(owner_ul.geom::geometry) * 1000)::integer AS owner_lon_e3,
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
                       COALESCE(NULLIF(partner_profile.display_name, ''), 'Pynapli User') AS partner_display_name,
                       partner_photo.media_asset_id AS partner_thumbnail_media_asset_id
                FROM tonight_session_participants partner_tsp
                JOIN users partner_user ON partner_user.id = partner_tsp.user_id
                JOIN profiles partner_profile ON partner_profile.user_id = partner_user.id
                LEFT JOIN LATERAL (
                    ${profilePhotoLateral("partner_user.id")}
                ) partner_photo ON true
                WHERE partner_tsp.session_id = ts.id
                  AND partner_tsp.role = 'partner'
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
              AND ST_DWithin(v.geom, owner_ul.geom, ?)
              AND (?::text IS NULL OR ts.intent = ?::text)
            ORDER BY owner_distance_meters ASC, ts.id ASC
            LIMIT ?
            """.trimIndent()
    }
}
