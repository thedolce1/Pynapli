package com.pynapli.server.activity

import com.pynapli.server.contactblocks.ContactBlockSql

internal object JdbcActivityFeedSql {
    val ACTIVITY_FEED_QUERY =
        """
        WITH recipient_location AS (
            -- The recipient's effective location (travel point when travelling, else home).
            SELECT COALESCE(tl.geom, ul.geom) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        )
        SELECT ae.id AS event_id,
               ae.event_type,
               ae.actor_user_id,
               COALESCE(NULLIF(actor_profile.display_name, ''), 'Pynapli User') AS actor_display_name,
               actor_photo.media_asset_id AS actor_profile_photo_media_asset_id,
               ae.aggregate_count,
               ae.occurred_at,
               (ae.seen_at IS NOT NULL) AS seen,
               ae.metadata->>'matchId' AS match_id,
               ae.metadata->>'conversationId' AS conversation_id,
               ae.metadata->>'sessionId' AS psync_session_id,
               ae.metadata->>'source' AS source,
               pair_profile.user_id AS pair_user_id,
               NULLIF(pair_profile.display_name, '') AS pair_display_name,
               ae.metadata->>'pairSessionId' AS pair_session_id,
               album_request.status AS album_request_status,
               like_note.note_text AS note_text,
               actor_settings.show_distance AS actor_show_distance,
               -- Location privacy (L-10/L-14): the public bucket of the cell-to-cell distance between
               -- both people's effective locations, so it never moves with a few metres and never
               -- reveals a traveller's home; nothing at all when the actor hides their distance.
               CASE
                   WHEN actor_settings.show_distance = true
                        AND recipient_location.geom IS NOT NULL
                        AND actor_location.geom IS NOT NULL
                   THEN pynapli_public_distance_m(
                       pynapli_coarse_distance_m(recipient_location.geom, COALESCE(actor_travel.geom, actor_location.geom))
                   )::double precision
                   ELSE NULL
               END AS distance_meters
        FROM activity_events ae
        JOIN users actor
          ON actor.id = ae.actor_user_id
         AND actor.status = 'active'
         AND actor.deleted_at IS NULL
        JOIN profiles actor_profile
          ON actor_profile.user_id = actor.id
         AND actor_profile.visibility_status = 'visible'
        JOIN discovery_settings actor_settings
          ON actor_settings.user_id = actor.id
        JOIN discovery_settings recipient_settings
          ON recipient_settings.user_id = ae.recipient_user_id
        LEFT JOIN account_risk_states actor_risk
          ON actor_risk.user_id = actor.id
        LEFT JOIN recipient_location ON true
        LEFT JOIN users pair_user
          ON pair_user.id = (ae.metadata->>'pairUserId')::uuid
         AND pair_user.status = 'active'
         AND pair_user.deleted_at IS NULL
        LEFT JOIN profiles pair_profile
          ON pair_profile.user_id = pair_user.id
         AND pair_profile.visibility_status = 'visible'
        LEFT JOIN user_locations actor_location ON actor_location.user_id = actor.id
        LEFT JOIN travel_locations actor_travel
          ON actor_travel.user_id = actor.id
         AND actor_travel.status = 'active'
         AND actor_travel.active_until IS NULL
        LEFT JOIN LATERAL (
            SELECT r.status
            FROM media_album_access_requests r
            JOIN media_albums a
              ON a.id = r.album_id
             AND a.owner_user_id = r.owner_user_id
             AND a.kind = 'private'
             AND a.deleted_at IS NULL
            WHERE ae.event_type = 'album_request'
              AND r.owner_user_id = ae.recipient_user_id
              AND r.requester_user_id = ae.actor_user_id
            ORDER BY r.requested_at DESC, r.id DESC
            LIMIT 1
        ) album_request ON true
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
            WHERE ma.owner_user_id = actor.id
              AND ma.kind = 'profile'
              AND ma.visibility_status = 'visible'
              AND ma.deleted_at IS NULL
              AND m.media_type = 'image'
              AND m.processing_status = 'ready'
              AND m.moderation_status = 'approved'
            ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
            LIMIT 1
        ) actor_photo ON true
        -- A Pineapple Like's optional note, read from the live like itself: a rewound like has none.
        LEFT JOIN LATERAL (
            SELECT sa.note_text
            FROM swipe_actions sa
            WHERE ae.event_type = 'pineapple_like'
              AND sa.actor_user_id = ae.actor_user_id
              AND sa.target_user_id = ae.recipient_user_id
              AND sa.action = 'pineapple_like'
              AND sa.rewound_at IS NULL
            ORDER BY sa.created_at DESC
            LIMIT 1
        ) like_note ON true
        WHERE ae.recipient_user_id = ?
          AND ae.visibility_status = 'visible'
          AND ae.deleted_at IS NULL
          AND (? = true OR ae.event_type = ANY (?::text[]))
          AND (
              ? = false
              OR ae.occurred_at < ?
              OR (ae.occurred_at = ? AND ae.id < ?)
          )
          AND COALESCE(actor_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
          -- "Hide from people you know": nothing from someone hiding from the recipient.
          AND ${ContactBlockSql.notHiddenFromViewer("ae.actor_user_id")}
          AND NOT EXISTS (
              SELECT 1
              FROM user_blocks block
              WHERE (
                  block.blocker_user_id = ae.recipient_user_id
                  AND block.blocked_user_id = ae.actor_user_id
              )
              OR (
                  block.blocker_user_id = ae.actor_user_id
                  AND block.blocked_user_id = ae.recipient_user_id
              )
          )
          AND (
              ae.event_type <> 'profile_view'
              OR (
                  recipient_settings.show_in_viewed_lists = true
                  AND
                  actor_settings.incognito = false
                  AND actor_settings.show_in_viewed_lists = true
              )
          )
        ORDER BY ae.occurred_at DESC, ae.id DESC
        LIMIT ?
        """.trimIndent()
}
