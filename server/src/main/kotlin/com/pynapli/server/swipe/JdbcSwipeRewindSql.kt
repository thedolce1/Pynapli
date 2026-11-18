package com.pynapli.server.swipe

import com.pynapli.server.distance.CoarseLocation
import java.sql.Connection
import java.util.UUID

internal fun Connection.findLatestRewindableAction(
    viewerUserId: UUID,
    radiusMeters: Int,
): SwipeRewindCandidateRecord? =
    prepareStatement(
        """
        WITH viewer_location AS (
            -- Travel-mode override for rewindable-action discovery; an
            -- action recorded in travel mode is still rewindable while
            -- the user is in that travel city.
            -- Snapped to the viewer's cell, like the deck (L-10/L-14).
            SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        )
        SELECT sa.id AS action_id,
               sa.target_user_id,
               sa.action,
               sa.consumed_inventory_id,
               COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
               p.display_age,
               p.bio,
               array_remove(ARRAY[p.experience_level], NULL) || COALESCE(p.lifestyle_tags, '{}'::text[]) || COALESCE(p.logistics_tags, '{}'::text[]) AS profile_tags,
               p.verified_at IS NOT NULL AS verified,
               target_travel.id IS NOT NULL AS visiting,
               profile_photos.thumbnail_media_asset_id,
               profile_photos.media_asset_ids AS profile_photo_ids,
               linked_partner.linked_partner_user_id,
               linked_partner.linked_partner_display_name,
               linked_partner.linked_partner_thumbnail_media_asset_id,
               ds.show_distance,
               -- Cell-to-cell distance from the target's effective location, as on the dealt card;
               -- the card shows none when the owner hides it.
               pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters
        FROM swipe_actions sa
        JOIN viewer_location v ON true
        JOIN users u
          ON u.id = sa.target_user_id
        JOIN user_locations ul
          ON ul.user_id = u.id
        JOIN profiles p
          ON p.user_id = u.id
        JOIN discovery_settings ds
          ON ds.user_id = u.id
        -- Target-side travel must mirror queryDeck so a Liked visiting
        -- card remains rewindable and keeps its destination distance.
        LEFT JOIN travel_locations target_travel
          ON target_travel.user_id = u.id
         AND target_travel.status = 'active'
         AND target_travel.active_until IS NULL
        LEFT JOIN account_risk_states ars
          ON ars.user_id = u.id
        LEFT JOIN LATERAL (
            SELECT (array_agg(media_asset_id ORDER BY rn))[1] AS thumbnail_media_asset_id,
                   array_agg(media_asset_id ORDER BY rn) AS media_asset_ids
            FROM (
                SELECT mai.media_asset_id,
                       ROW_NUMBER() OVER (
                           ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                       ) AS rn
                FROM media_albums ma
                JOIN media_album_items mai
                  ON mai.album_id = ma.id
                 AND mai.removed_at IS NULL
                JOIN media_assets m
                  ON m.id = mai.media_asset_id
                 AND m.owner_user_id = ma.owner_user_id
                 AND m.deleted_at IS NULL
                WHERE ma.owner_user_id = u.id
                  AND ma.kind = 'profile'
                  AND ma.visibility_status = 'visible'
                  AND ma.deleted_at IS NULL
                  AND m.media_type = 'image'
                  AND m.processing_status = 'ready'
                  AND m.moderation_status = 'approved'
            ) ranked
            WHERE rn <= 5
        ) profile_photos ON true
        LEFT JOIN LATERAL (
            SELECT upl.linked_user_id AS linked_partner_user_id,
                   COALESCE(NULLIF(linked_profile.display_name, ''), 'Pynapli User') AS linked_partner_display_name,
                   linked_partner_photo.media_asset_id AS linked_partner_thumbnail_media_asset_id
            FROM user_profile_links upl
            JOIN users linked_user
              ON linked_user.id = upl.linked_user_id
            JOIN profiles linked_profile
              ON linked_profile.user_id = linked_user.id
            JOIN discovery_settings linked_ds
              ON linked_ds.user_id = linked_user.id
            LEFT JOIN account_risk_states linked_risk
              ON linked_risk.user_id = linked_user.id
            LEFT JOIN LATERAL (
                SELECT mai.media_asset_id
                FROM media_albums ma
                JOIN media_album_items mai
                  ON mai.album_id = ma.id
                 AND mai.removed_at IS NULL
                JOIN media_assets m
                  ON m.id = mai.media_asset_id
                 AND m.owner_user_id = ma.owner_user_id
                 AND m.deleted_at IS NULL
                WHERE ma.owner_user_id = linked_user.id
                  AND ma.kind = 'profile'
                  AND ma.visibility_status = 'visible'
                  AND ma.deleted_at IS NULL
                  AND m.media_type = 'image'
                  AND m.processing_status = 'ready'
                  AND m.moderation_status = 'approved'
                ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                LIMIT 1
            ) linked_partner_photo ON true
            WHERE upl.user_id = u.id
              AND upl.status = 'active'
              AND upl.unlinked_at IS NULL
              AND linked_user.status = 'active'
              AND linked_user.deleted_at IS NULL
              AND linked_profile.visibility_status = 'visible'
              AND linked_ds.incognito = false
              AND COALESCE(linked_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_blocks ub
                  WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = linked_user.id)
                     OR (ub.blocker_user_id = linked_user.id AND ub.blocked_user_id = ?)
              )
            ORDER BY upl.accepted_at DESC NULLS LAST, upl.created_at DESC, upl.id ASC
            LIMIT 1
        ) linked_partner ON true
        WHERE sa.actor_user_id = ?
          AND sa.rewound_at IS NULL
          AND sa.action IN ('like', 'pass', 'pineapple_like')
          AND u.status = 'active'
          AND u.deleted_at IS NULL
          AND p.visibility_status = 'visible'
          AND ds.swipe_visible = true
          AND ds.incognito = false
          AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)
          AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
          AND (
              ST_DWithin(v.geom, ul.geom, ?)
              OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))
          )
          AND pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) <= $radiusMeters
          AND NOT EXISTS (
              SELECT 1
              FROM user_blocks ub
              WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = u.id)
                 OR (ub.blocker_user_id = u.id AND ub.blocked_user_id = ?)
          )
          AND NOT EXISTS (
              SELECT 1
              FROM user_hidden_profiles uhp
              WHERE uhp.hider_user_id = ?
                AND uhp.hidden_user_id = u.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM matches m
              WHERE (m.user_a = sa.actor_user_id AND m.user_b = sa.target_user_id)
                 OR (m.user_a = sa.target_user_id AND m.user_b = sa.actor_user_id)
          )
          AND NOT EXISTS (
              SELECT 1
              FROM safety_reports sr
              WHERE sr.reporter_user_id = sa.actor_user_id
                AND sr.reported_user_id = sa.target_user_id
          )
        ORDER BY sa.created_at DESC, sa.id DESC
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, viewerUserId)
        statement.setObject(4, viewerUserId)
        statement.setDouble(5, radiusMeters + CoarseLocation.CELL_PAD_METERS)
        statement.setDouble(6, radiusMeters + CoarseLocation.CELL_PAD_METERS)
        statement.setObject(7, viewerUserId)
        statement.setObject(8, viewerUserId)
        statement.setObject(9, viewerUserId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                SwipeRewindCandidateRecord(
                    actionId = rs.getObject("action_id", UUID::class.java),
                    targetUserId = rs.getObject("target_user_id", UUID::class.java),
                    action = rs.getString("action"),
                    consumedInventoryId = rs.getObject("consumed_inventory_id", UUID::class.java),
                    card =
                        SwipeCardRecord(
                            userId = rs.getObject("target_user_id", UUID::class.java),
                            displayName = rs.getString("display_name"),
                            displayAge = rs.getInt("display_age").takeUnless { rs.wasNull() },
                            thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", UUID::class.java),
                            showDistance = rs.getBoolean("show_distance"),
                            distanceMeters = rs.getDouble("distance_meters"),
                            linkedPartnerUserId = rs.getObject("linked_partner_user_id", UUID::class.java),
                            linkedPartnerDisplayName = rs.getString("linked_partner_display_name"),
                            linkedPartnerThumbnailMediaAssetId =
                                rs.getObject(
                                    "linked_partner_thumbnail_media_asset_id",
                                    UUID::class.java,
                                ),
                            bio = rs.getString("bio"),
                            tags = rs.readStringArray("profile_tags"),
                            verified = rs.getBoolean("verified"),
                            visiting = rs.getBoolean("visiting"),
                        ),
                )
            }
        }
    }
