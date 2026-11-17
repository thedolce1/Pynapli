package com.pynapli.server.grid

import java.sql.Connection
import java.util.UUID

/**
 * Cheap HYDRATE phase: resolves the profile photo + linked-partner mini block
 * for ONLY the small set of page user ids that survived cursor slicing. The
 * photo LATERAL and the linked-partner LATERAL are byte-for-byte the same as the
 * original single-query hydration block, so card fields are identical. The
 * per-viewer block check inside the linked-partner block is preserved (it is a
 * privacy rule on which partner is exposed, not a candidate-set exclusion).
 */
internal fun Connection.hydrateGridCards(
    viewerUserId: UUID,
    userIds: List<UUID>,
): Map<UUID, GridHydration> {
    if (userIds.isEmpty()) return emptyMap()
    val sql =
        """
        SELECT pc.user_id,
               profile_photo.media_asset_id AS thumbnail_media_asset_id,
               linked_partner.linked_partner_user_id,
               linked_partner.linked_partner_display_name,
               linked_partner.linked_partner_thumbnail_media_asset_id,
               -- PLAYING_TONIGHT_SPEC: a live session is the only Tonight signal (the legacy
               -- profiles.tonight_available_until column is no longer read). Per-tile signal only.
               EXISTS (
                   SELECT 1
                   FROM tonight_session_participants tsp
                   JOIN tonight_sessions ts ON ts.id = tsp.session_id
                   WHERE tsp.user_id = pc.user_id
                     AND ts.status = 'active'
                     AND ts.paused_at IS NULL
                     AND ts.expires_at > now()
               ) AS tonight_active
        FROM unnest(?::uuid[]) AS pc(user_id)
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
            WHERE ma.owner_user_id = pc.user_id
              AND ma.kind = 'profile'
              AND ma.visibility_status = 'visible'
              AND ma.deleted_at IS NULL
              AND m.media_type = 'image'
              AND m.processing_status = 'ready'
              AND m.moderation_status = 'approved'
            ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
            LIMIT 1
        ) profile_photo ON true
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
            -- Owner's discovery settings drive the partner-visibility gate (the partner
            -- mini belongs to pc.user_id, the card owner).
            JOIN discovery_settings owner_ds
              ON owner_ds.user_id = pc.user_id
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
            WHERE upl.user_id = pc.user_id
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
              -- Owner-controlled partner visibility (PROFILE_SETTINGS_SPEC). 'hidden'
              -- falls through to no row; viewer = the grid querier.
              AND (
                  owner_ds.partner_visibility = 'public'
                  OR (
                      owner_ds.partner_visibility = 'verified_only'
                      AND EXISTS (
                          SELECT 1 FROM profiles vp
                          WHERE vp.user_id = ? AND vp.verified_at IS NOT NULL
                      )
                  )
                  OR (
                      owner_ds.partner_visibility = 'matches_only'
                      AND EXISTS (
                          SELECT 1 FROM matches mm
                          WHERE LEAST(mm.user_a, mm.user_b) = LEAST(?::uuid, pc.user_id)
                            AND GREATEST(mm.user_a, mm.user_b) = GREATEST(?::uuid, pc.user_id)
                      )
                  )
              )
            ORDER BY upl.accepted_at DESC NULLS LAST, upl.created_at DESC, upl.id ASC
            LIMIT 1
        ) linked_partner ON true
        """.trimIndent()

    return prepareStatement(sql).use { statement ->
        val ids = createArrayOf("uuid", userIds.toTypedArray())
        statement.setArray(1, ids)
        statement.setObject(2, viewerUserId) // partner block check (blocker side)
        statement.setObject(3, viewerUserId) // partner block check (blocked side)
        statement.setObject(4, viewerUserId) // verified_only gate
        statement.setObject(5, viewerUserId) // matches_only gate (LEAST)
        statement.setObject(6, viewerUserId) // matches_only gate (GREATEST)
        statement.executeQuery().use { rs ->
            buildMap {
                while (rs.next()) {
                    val userId = rs.getObject("user_id", UUID::class.java)
                    put(
                        userId,
                        GridHydration(
                            thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", UUID::class.java),
                            linkedPartnerUserId = rs.getObject("linked_partner_user_id", UUID::class.java),
                            linkedPartnerDisplayName = rs.getString("linked_partner_display_name"),
                            linkedPartnerThumbnailMediaAssetId =
                                rs.getObject(
                                    "linked_partner_thumbnail_media_asset_id",
                                    UUID::class.java,
                                ),
                            tonightActive = rs.getBoolean("tonight_active"),
                        ),
                    )
                }
            }
        }
    }
}
