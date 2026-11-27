package com.pynapli.server.profile

internal object JdbcProfileSql {
    val ME_QUERY =
        """
        SELECT u.id AS user_id,
               u.status AS user_status,
               p.display_name,
               p.birthdate,
               p.display_age,
               p.bio,
               p.neighborhood,
               p.gender,
               p.gender_detail,
               p.orientation,
               p.orientation_detail,
               p.relationship_context,
               p.discovery_style,
               p.lifestyle_tags,
               p.experience_level,
               p.boundaries,
               p.logistics_tags,
               p.intents,
               p.profile_completeness_score,
               p.moderation_status,
               p.visibility_status,
               (p.age_verified_at IS NOT NULL) AS age_verified,
               ds.grid_visible,
               ds.swipe_visible,
               ds.events_visible,
               ds.show_distance,
               ds.show_in_viewed_lists,
               ds.incognito,
               ds.allow_grid_messages,
               ds.grid_message_policy,
               ds.allow_flirts,
               ds.allow_intro_notes,
               ds.allow_album_requests,
               ds.solo_enabled,
               ds.together_enabled,
               ds.social_enabled,
               ds.visible_while_traveling,
               ds.share_psync_solo_matches,
               ds.partner_visibility,
               ds.show_met_in_person_badge
        FROM users u
        JOIN profiles p ON p.user_id = u.id
        JOIN discovery_settings ds ON ds.user_id = u.id
        WHERE u.id = ?
          AND u.deleted_at IS NULL
        """.trimIndent()

    val PUBLIC_PROFILE_QUERY =
        """
        SELECT u.id AS user_id,
               COALESCE(NULLIF(p.display_name, ''), 'New member') AS display_name,
               profile_photo.media_asset_id AS profile_photo_media_asset_id,
               profile_photo.media_asset_ids AS profile_photo_ids,
               linked_partner.linked_partner_user_id,
               linked_partner.linked_partner_display_name,
               linked_partner.linked_partner_thumbnail_media_asset_id,
               linked_partner.linked_partner_orientation,
               linked_partner.linked_partner_orientation_detail,
               linked_partner.linked_partner_experience_level,
               linked_partner.linked_partner_lifestyle_tags,
               linked_partner.linked_partner_age,
               linked_partner.linked_partner_gender,
               p.display_age,
               (p.verified_at IS NOT NULL) AS verified,
               -- Couple verified: this profile and its visible linked partner are both photo-verified.
               (p.verified_at IS NOT NULL AND COALESCE(linked_partner.linked_partner_verified, false)) AS couple_verified,
               -- "Met in person" (TRUST_AND_SAFETY_SPEC): read only from the weekly batch's vouch_badges,
               -- so nothing here changes when a confirmation is given. Shown only with the owner's
               -- opt-in; never while frozen. No placeholders added (bind order unchanged).
               COALESCE((
                   SELECT vb.person_badge
                          AND vb.frozen_at IS NULL
                          AND ds.show_met_in_person_badge
                          AND p.verified_at IS NOT NULL
                          AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                   FROM vouch_badges vb
                   WHERE vb.subject_user_id = u.id
               ), false) AS met_in_person,
               -- The couple badge needs the partner mini to be visible to this viewer (so it never
               -- reveals a hidden link), the same active link on both sides, and both opt-ins.
               COALESCE((
                   SELECT vb.couple_badge
                          AND vb.frozen_at IS NULL
                          AND ds.show_met_in_person_badge
                          AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                          AND linked_partner.linked_partner_user_id IS NOT NULL
                          -- Published after the link's last change: an unlink and relink (which reuse the
                          -- link rows and update them) can never bring back an older couple badge.
                          AND vb.couple_since IS NOT NULL
                          AND vb.couple_since >= (
                              SELECT max(lu.updated_at)
                              FROM user_profile_links lu
                              WHERE (lu.user_id = u.id AND lu.linked_user_id = linked_partner.linked_partner_user_id)
                                 OR (lu.user_id = linked_partner.linked_partner_user_id AND lu.linked_user_id = u.id)
                          )
                          AND EXISTS (
                              SELECT 1
                              FROM vouch_badges pvb
                              JOIN discovery_settings pds ON pds.user_id = pvb.subject_user_id
                              JOIN user_profile_links cl ON cl.id = vb.couple_link_id
                              WHERE pvb.subject_user_id = linked_partner.linked_partner_user_id
                                AND pvb.couple_badge
                                AND pvb.frozen_at IS NULL
                                AND pvb.couple_link_id = vb.couple_link_id
                                AND pvb.couple_since IS NOT NULL
                                AND pvb.couple_since >= (
                                    SELECT max(lp.updated_at)
                                    FROM user_profile_links lp
                                    WHERE (lp.user_id = u.id AND lp.linked_user_id = pvb.subject_user_id)
                                       OR (lp.user_id = pvb.subject_user_id AND lp.linked_user_id = u.id)
                                )
                                AND pds.show_met_in_person_badge
                                AND cl.status = 'active'
                                AND cl.unlinked_at IS NULL
                                AND (
                                    (cl.user_id = u.id AND cl.linked_user_id = pvb.subject_user_id)
                                    OR (cl.user_id = pvb.subject_user_id AND cl.linked_user_id = u.id)
                                )
                          )
                   FROM vouch_badges vb
                   WHERE vb.subject_user_id = u.id
               ), false) AS met_in_person_couple,
               p.bio,
               p.neighborhood,
               p.gender,
               p.gender_detail,
               p.orientation,
               p.orientation_detail,
               p.relationship_context,
               p.discovery_style,
               p.lifestyle_tags,
               p.experience_level,
               p.boundaries,
               p.logistics_tags,
               p.intents,
               CASE
                   WHEN ds.allow_grid_messages = false OR ds.grid_message_policy = 'no_one'
                   THEN false
                   WHEN ds.grid_message_policy = 'matches_only'
                   THEN EXISTS (
                       SELECT 1
                       FROM matches m
                       WHERE LEAST(m.user_a, m.user_b) = LEAST(?::uuid, u.id)
                         AND GREATEST(m.user_a, m.user_b) = GREATEST(?::uuid, u.id)
                   )
                   -- Same photo-verified condition the message gate uses (profiles.verified_at).
                   WHEN ds.grid_message_policy = 'verified_only'
                   THEN EXISTS (
                       SELECT 1
                       FROM profiles sender_profile
                       WHERE sender_profile.user_id = ?
                         AND sender_profile.verified_at IS NOT NULL
                   )
                   WHEN ds.grid_message_policy = 'everyone'
                   THEN true
                   ELSE false
               END AS allow_grid_messages,
               -- Plan G12: the owner takes Grid messages from verified members only. With
               -- allow_grid_messages false above, this can only mean the viewer is not verified.
               (ds.allow_grid_messages = true AND ds.grid_message_policy = 'verified_only') AS grid_message_verified_only,
               ds.allow_flirts,
               ds.allow_album_requests,
               COALESCE(private_album_access.status, 'none') AS private_album_access_status,
               -- L-12: cell-to-cell distance between both sides' EFFECTIVE locations (an active
               -- travel point overrides home on either side), and nothing at all when the owner hides
               -- distance. Never a raw GPS distance, never a traveller's home.
               CASE
                   WHEN ds.show_distance = true
                        AND viewer_location.geom IS NOT NULL
                        AND target_location.geom IS NOT NULL
                   THEN pynapli_coarse_distance_m(viewer_location.geom, target_location.geom)
                   ELSE NULL
               END AS distance_meters
        FROM users u
        JOIN profiles p ON p.user_id = u.id
        JOIN discovery_settings ds ON ds.user_id = u.id
        LEFT JOIN account_risk_states target_risk ON target_risk.user_id = u.id
        LEFT JOIN LATERAL (
            SELECT COALESCE(viewer_travel.geom, viewer_home.geom) AS geom
            FROM user_locations viewer_home
            LEFT JOIN travel_locations viewer_travel
              ON viewer_travel.user_id = viewer_home.user_id
             AND viewer_travel.status = 'active'
             AND viewer_travel.active_until IS NULL
            WHERE viewer_home.user_id = ?
        ) viewer_location ON true
        LEFT JOIN LATERAL (
            SELECT COALESCE(target_travel.geom, target_home.geom) AS geom
            FROM user_locations target_home
            LEFT JOIN travel_locations target_travel
              ON target_travel.user_id = target_home.user_id
             AND target_travel.status = 'active'
             AND target_travel.active_until IS NULL
            WHERE target_home.user_id = u.id
        ) target_location ON true
        LEFT JOIN LATERAL (
            SELECT CASE
                WHEN EXISTS (
                    SELECT 1
                    FROM media_albums ma
                    JOIN media_album_access_grants g
                      ON g.album_id = ma.id
                     AND g.grantee_user_id = ?
                     AND g.revoked_at IS NULL
                    WHERE ma.owner_user_id = u.id
                      AND ma.kind = 'private'
                      AND ma.visibility_status = 'visible'
                      AND ma.deleted_at IS NULL
                ) THEN 'granted'
                WHEN EXISTS (
                    SELECT 1
                    FROM media_albums ma
                    JOIN media_album_access_requests r
                      ON r.album_id = ma.id
                     AND r.requester_user_id = ?
                     AND r.status = 'pending'
                    WHERE ma.owner_user_id = u.id
                      AND ma.kind = 'private'
                      AND ma.deleted_at IS NULL
                ) THEN 'pending'
                ELSE 'none'
            END AS status
        ) private_album_access ON true
        LEFT JOIN LATERAL (
            SELECT (array_agg(media_asset_id ORDER BY rn))[1] AS media_asset_id,
                   array_agg(media_asset_id ORDER BY rn) AS media_asset_ids
            FROM (
                SELECT mai.media_asset_id,
                       row_number() OVER (ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC) AS rn
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
                ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                LIMIT 6
            ) ranked
        ) profile_photo ON true
        LEFT JOIN LATERAL (
            SELECT upl.linked_user_id AS linked_partner_user_id,
                   COALESCE(NULLIF(linked_profile.display_name, ''), 'New member') AS linked_partner_display_name,
                   linked_partner_photo.media_asset_id AS linked_partner_thumbnail_media_asset_id,
                   (linked_profile.verified_at IS NOT NULL) AS linked_partner_verified,
                   -- Partner preview: the partner's OWN declared orientation and lifestyle (each
                   -- account is one adult). Only swap/room tags; the rest stays on their profile.
                   linked_profile.orientation AS linked_partner_orientation,
                   linked_profile.orientation_detail AS linked_partner_orientation_detail,
                   linked_profile.experience_level AS linked_partner_experience_level,
                   -- Plan G2-3: the partner's own declared age and gender key, with this gated row only.
                   linked_profile.display_age AS linked_partner_age,
                   linked_profile.gender AS linked_partner_gender,
                   ARRAY(
                       SELECT t FROM unnest(COALESCE(linked_profile.lifestyle_tags, '{}'::text[])) AS t
                       WHERE t IN ('soft_swap', 'full_swap', 'same_room', 'separate_room')
                   ) AS linked_partner_lifestyle_tags
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
              -- Owner-controlled partner visibility (PROFILE_SETTINGS_SPEC). ds is the
              -- owner's discovery settings; 'hidden' falls through to no row.
              AND (
                  ds.partner_visibility = 'public'
                  OR (
                      ds.partner_visibility = 'verified_only'
                      AND EXISTS (
                          SELECT 1 FROM profiles vp
                          WHERE vp.user_id = ? AND vp.verified_at IS NOT NULL
                      )
                  )
                  OR (
                      ds.partner_visibility = 'matches_only'
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
        WHERE u.id = ?
          AND u.status = 'active'
          AND u.deleted_at IS NULL
          -- Discovery-visibility gates apply to OTHER profiles. A viewer may always preview their
          -- OWN public profile ("Preview profile" in the hub) regardless of grid/incognito/risk
          -- visibility, so self (u.id = viewer) bypasses these gates. For every other view the
          -- self branch is false and the gates apply unchanged.
          AND (
              u.id = ?
              OR (
                  p.visibility_status = 'visible'
                  AND ds.grid_visible = true
                  AND ds.incognito = false
                  AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
              )
          )
        """.trimIndent()
}
