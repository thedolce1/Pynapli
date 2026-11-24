package com.pynapli.server.activity

import com.pynapli.server.contactblocks.ContactBlockSql

internal object JdbcActivitySummarySql {
    val ACTIVITY_SUMMARY_QUERY =
        """
        WITH visible_events AS (
            SELECT ae.event_type,
                   ae.occurred_at,
                   ae.seen_at,
                   album_request.status AS album_request_status
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
            WHERE ae.recipient_user_id = ?
              AND ae.visibility_status = 'visible'
              AND ae.deleted_at IS NULL
              AND COALESCE(actor_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
              -- "Hide from people you know": counts and the badge leave out anyone hiding from them.
              AND ${ContactBlockSql.actNotHiddenFromViewer("ae.actor_user_id", "ae.first_occurred_at")}
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
                      AND actor_settings.incognito = false
                      AND actor_settings.show_in_viewed_lists = true
                  )
              )
        )
        SELECT COUNT(*) FILTER (WHERE event_type = 'profile_view')::integer AS profile_view_count,
               COUNT(*) FILTER (WHERE event_type = 'flirt' AND seen_at IS NULL)::integer AS flirt_count,
               COUNT(*) FILTER (
                   WHERE event_type IN ('swipe_like', 'pineapple_like', 'intro_note')
                     AND seen_at IS NULL
               )::integer AS like_count,
               COUNT(*) FILTER (
                   WHERE event_type = 'album_request'
                     AND seen_at IS NULL
                     AND COALESCE(album_request_status, 'pending') = 'pending'
               )::integer AS album_request_count,
               COUNT(*) FILTER (
                   WHERE event_type = 'partner_chat_invite'
                     AND seen_at IS NULL
               )::integer AS partner_invite_count,
               MAX(occurred_at) FILTER (
                   WHERE seen_at IS NULL
                     AND (
                       event_type IN (
                           'flirt',
                           'swipe_like',
                           'pineapple_like',
                           'intro_note',
                           'partner_chat_invite'
                       )
                       OR (
                           event_type = 'album_request'
                           AND COALESCE(album_request_status, 'pending') = 'pending'
                       )
                   )
               ) AS latest_badge_occurred_at
        FROM visible_events
        """.trimIndent()
}
