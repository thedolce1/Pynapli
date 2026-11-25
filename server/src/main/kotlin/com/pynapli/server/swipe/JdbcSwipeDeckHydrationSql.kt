package com.pynapli.server.swipe

import java.sql.Connection
import java.util.UUID

internal fun Connection.hydrateDeckCards(
    viewerUserId: UUID,
    userIds: List<UUID>,
): Map<UUID, DeckHydration> {
    if (userIds.isEmpty()) return emptyMap()
    val sql =
        """
        SELECT u.id AS user_id,
               profile_photos.thumbnail_media_asset_id,
               profile_photos.media_asset_ids AS profile_photo_ids,
               linked_partner.linked_partner_user_id,
               linked_partner.linked_partner_display_name,
               linked_partner.linked_partner_thumbnail_media_asset_id,
               -- Couple badge (TRUST_AND_SAFETY_SPEC): only a visible linked partner counts.
               COALESCE(linked_partner.linked_partner_verified, false) AS linked_partner_verified
        FROM users u
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
                  AND m.intended_album_kind = 'profile'
            ) ranked
            WHERE rn <= 5
        ) profile_photos ON true
        LEFT JOIN LATERAL (
            SELECT upl.linked_user_id AS linked_partner_user_id,
                   COALESCE(NULLIF(linked_profile.display_name, ''), 'New member') AS linked_partner_display_name,
                   linked_partner_photo.media_asset_id AS linked_partner_thumbnail_media_asset_id,
                   (linked_profile.verified_at IS NOT NULL) AS linked_partner_verified
            FROM user_profile_links upl
            JOIN users linked_user
              ON linked_user.id = upl.linked_user_id
            JOIN profiles linked_profile
              ON linked_profile.user_id = linked_user.id
            JOIN discovery_settings linked_ds
              ON linked_ds.user_id = linked_user.id
            -- Owner's discovery settings drive the partner-visibility gate (the partner
            -- mini belongs to u.id, the deck card owner).
            JOIN discovery_settings owner_ds
              ON owner_ds.user_id = u.id
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
                  AND m.intended_album_kind = 'profile'
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
              -- Owner-controlled partner visibility (PROFILE_SETTINGS_SPEC). 'hidden'
              -- falls through to no row; viewer = the deck querier.
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
                          WHERE LEAST(mm.user_a, mm.user_b) = LEAST(?::uuid, u.id)
                            AND GREATEST(mm.user_a, mm.user_b) = GREATEST(?::uuid, u.id)
                      )
                  )
              )
            ORDER BY upl.accepted_at DESC NULLS LAST, upl.created_at DESC, upl.id ASC
            LIMIT 1
        ) linked_partner ON true
        WHERE u.id = ANY(?::uuid[])
        """.trimIndent()

    return prepareStatement(sql).use { statement ->
        val ids = createArrayOf("uuid", userIds.toTypedArray())
        statement.setObject(1, viewerUserId) // partner block check (blocker side)
        statement.setObject(2, viewerUserId) // partner block check (blocked side)
        statement.setObject(3, viewerUserId) // verified_only gate
        statement.setObject(4, viewerUserId) // matches_only gate (LEAST)
        statement.setObject(5, viewerUserId) // matches_only gate (GREATEST)
        statement.setArray(6, ids)
        statement.executeQuery().use { rs ->
            buildMap {
                while (rs.next()) {
                    val userId = rs.getObject("user_id", UUID::class.java)
                    put(
                        userId,
                        DeckHydration(
                            thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", UUID::class.java),
                            thumbnailMediaAssetIds = rs.readUuidArray("profile_photo_ids"),
                            linkedPartnerUserId = rs.getObject("linked_partner_user_id", UUID::class.java),
                            linkedPartnerDisplayName = rs.getString("linked_partner_display_name"),
                            linkedPartnerThumbnailMediaAssetId =
                                rs.getObject(
                                    "linked_partner_thumbnail_media_asset_id",
                                    UUID::class.java,
                                ),
                            linkedPartnerVerified = rs.getBoolean("linked_partner_verified"),
                        ),
                    )
                }
            }
        }
    }
}
