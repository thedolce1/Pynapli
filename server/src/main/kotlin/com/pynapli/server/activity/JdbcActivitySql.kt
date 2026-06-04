package com.pynapli.server.activity

internal object JdbcActivitySql {
    val UPSERT_ELIGIBLE_PROFILE_VIEW_QUERY =
        """
        INSERT INTO activity_events (
            id,
            recipient_user_id,
            actor_user_id,
            event_type,
            target_user_id,
            aggregate_count,
            first_occurred_at,
            last_counted_at,
            occurred_at,
            updated_at
        )
        SELECT ?, ?, ?, 'profile_view', ?, 1, now(), now(), now(), now()
        WHERE EXISTS (
            SELECT 1
            FROM users viewer
            JOIN discovery_settings viewer_settings ON viewer_settings.user_id = viewer.id
            LEFT JOIN account_risk_states viewer_risk ON viewer_risk.user_id = viewer.id
            WHERE viewer.id = ?
              AND viewer.status = 'active'
              AND viewer.deleted_at IS NULL
              AND viewer_settings.incognito = false
              AND viewer_settings.show_in_viewed_lists = true
              AND COALESCE(viewer_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
        )
        AND EXISTS (
            SELECT 1
            FROM users target
            JOIN discovery_settings target_settings ON target_settings.user_id = target.id
            WHERE target.id = ?
              AND target.status = 'active'
              AND target.deleted_at IS NULL
              AND target_settings.show_in_viewed_lists = true
        )
        ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
        WHERE event_type = 'profile_view' AND deleted_at IS NULL
        DO UPDATE SET
            aggregate_count = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN activity_events.aggregate_count + 1
                ELSE activity_events.aggregate_count
            END,
            last_counted_at = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN now()
                ELSE activity_events.last_counted_at
            END,
            occurred_at = now(),
            updated_at = now(),
            seen_at = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN NULL
                ELSE activity_events.seen_at
            END,
            visibility_status = 'visible'
        RETURNING id
        """.trimIndent()

    val FLIRT_ELIGIBILITY_QUERY =
        """
        SELECT EXISTS (
            SELECT 1
            FROM users sender
            LEFT JOIN account_risk_states sender_risk ON sender_risk.user_id = sender.id
            WHERE sender.id = ?
              AND sender.status = 'active'
              AND sender.deleted_at IS NULL
              AND COALESCE(sender_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
        )
        AND EXISTS (
            SELECT 1
            FROM users target
            JOIN profiles target_profile
              ON target_profile.user_id = target.id
             AND target_profile.visibility_status = 'visible'
            JOIN discovery_settings target_settings
              ON target_settings.user_id = target.id
             AND target_settings.allow_flirts = true
            WHERE target.id = ?
              AND target.status = 'active'
              AND target.deleted_at IS NULL
        )
        """.trimIndent()

    val UPSERT_FLIRT_QUERY =
        """
        INSERT INTO activity_events (
            id,
            recipient_user_id,
            actor_user_id,
            event_type,
            target_user_id,
            aggregate_count,
            first_occurred_at,
            last_counted_at,
            occurred_at,
            updated_at,
            metadata
        )
        VALUES (?, ?, ?, 'flirt', ?, 1, now(), now(), now(), now(), COALESCE(?::jsonb, '{}'::jsonb))
        ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
        WHERE event_type = 'flirt' AND deleted_at IS NULL
        DO UPDATE SET
            aggregate_count = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN activity_events.aggregate_count + 1
                ELSE activity_events.aggregate_count
            END,
            last_counted_at = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN now()
                ELSE activity_events.last_counted_at
            END,
            occurred_at = now(),
            updated_at = now(),
            metadata = activity_events.metadata || COALESCE(?::jsonb, '{}'::jsonb),
            seen_at = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN NULL
                ELSE activity_events.seen_at
            END,
            visibility_status = 'visible'
        """.trimIndent()

    val ALBUM_REQUEST_ELIGIBILITY_QUERY =
        """
        SELECT EXISTS (
            SELECT 1
            FROM users requester
            LEFT JOIN account_risk_states requester_risk ON requester_risk.user_id = requester.id
            WHERE requester.id = ?
              AND requester.status = 'active'
              AND requester.deleted_at IS NULL
              AND COALESCE(requester_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
        )
        AND EXISTS (
            SELECT 1
            FROM users target
            JOIN profiles target_profile
              ON target_profile.user_id = target.id
             AND target_profile.visibility_status = 'visible'
            JOIN discovery_settings target_settings
              ON target_settings.user_id = target.id
             AND target_settings.allow_album_requests = true
            WHERE target.id = ?
              AND target.status = 'active'
              AND target.deleted_at IS NULL
        )
        """.trimIndent()

    val UPSERT_ALBUM_REQUEST_QUERY =
        """
        INSERT INTO activity_events (
            id,
            recipient_user_id,
            actor_user_id,
            event_type,
            target_user_id,
            aggregate_count,
            first_occurred_at,
            last_counted_at,
            occurred_at,
            updated_at
        )
        VALUES (?, ?, ?, 'album_request', ?, 1, now(), now(), now(), now())
        ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
        WHERE event_type = 'album_request' AND deleted_at IS NULL
        DO UPDATE SET
            aggregate_count = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN activity_events.aggregate_count + 1
                ELSE activity_events.aggregate_count
            END,
            last_counted_at = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN now()
                ELSE activity_events.last_counted_at
            END,
            occurred_at = now(),
            updated_at = now(),
            seen_at = CASE
                WHEN activity_events.last_counted_at <= now() - (?::interval)
                THEN NULL
                ELSE activity_events.seen_at
            END,
            visibility_status = 'visible'
        """.trimIndent()

    val UPSERT_SWIPE_INTEREST_QUERY =
        """
        INSERT INTO activity_events (
            id,
            recipient_user_id,
            actor_user_id,
            event_type,
            target_user_id,
            aggregate_count,
            first_occurred_at,
            last_counted_at,
            occurred_at,
            updated_at
        )
        SELECT ?, ?, ?, ?, ?, 1, now(), now(), now(), now()
        WHERE EXISTS (
            SELECT 1
            FROM users actor
            WHERE actor.id = ?
              AND actor.status = 'active'
              AND actor.deleted_at IS NULL
        )
        AND EXISTS (
            SELECT 1
            FROM users target
            JOIN profiles target_profile
              ON target_profile.user_id = target.id
             AND target_profile.visibility_status = 'visible'
            WHERE target.id = ?
              AND target.status = 'active'
              AND target.deleted_at IS NULL
        )
        ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
        WHERE event_type IN ('swipe_like', 'pineapple_like', 'intro_note') AND deleted_at IS NULL
        DO NOTHING
        RETURNING id
        """.trimIndent()

    val UPSERT_MATCH_ACTIVITY_QUERY =
        """
        INSERT INTO activity_events (
            id,
            recipient_user_id,
            actor_user_id,
            event_type,
            target_user_id,
            aggregate_count,
            first_occurred_at,
            last_counted_at,
            occurred_at,
            updated_at,
            metadata
        )
        SELECT ?, ?, ?, 'match', ?, 1, now(), now(), now(), now(), ?::jsonb
        WHERE EXISTS (
            SELECT 1
            FROM users actor
            WHERE actor.id = ?
              AND actor.status = 'active'
              AND actor.deleted_at IS NULL
        )
        AND EXISTS (
            SELECT 1
            FROM users recipient
            WHERE recipient.id = ?
              AND recipient.status = 'active'
              AND recipient.deleted_at IS NULL
        )
        ON CONFLICT (recipient_user_id, actor_user_id, event_type, target_user_id)
        WHERE event_type = 'match' AND deleted_at IS NULL
        DO NOTHING
        RETURNING id
        """.trimIndent()

    val UPSERT_PARTNER_CHAT_INVITE_QUERY =
        """
        INSERT INTO activity_events (
            id,
            recipient_user_id,
            actor_user_id,
            event_type,
            target_user_id,
            aggregate_count,
            first_occurred_at,
            last_counted_at,
            occurred_at,
            updated_at,
            metadata
        )
        SELECT ?, ?, ?, 'partner_chat_invite', ?, 1, now(), now(), now(), now(), ?::jsonb
        WHERE EXISTS (
            SELECT 1
            FROM users inviter
            WHERE inviter.id = ?
              AND inviter.status = 'active'
              AND inviter.deleted_at IS NULL
        )
        AND EXISTS (
            SELECT 1
            FROM users invitee
            WHERE invitee.id = ?
              AND invitee.status = 'active'
              AND invitee.deleted_at IS NULL
        )
        ON CONFLICT (recipient_user_id, actor_user_id, event_type, ((metadata->>'conversationId')))
        WHERE event_type = 'partner_chat_invite' AND deleted_at IS NULL
        DO NOTHING
        RETURNING id
        """.trimIndent()

    // PS-4c: one Psync session-lifecycle card per recipient per session per type. The actor is
    // the OTHER partner (or the initiator for an invite); metadata.sessionId links to the session.
    val UPSERT_PSYNC_SESSION_EVENT_QUERY =
        """
        INSERT INTO activity_events (
            id,
            recipient_user_id,
            actor_user_id,
            event_type,
            target_user_id,
            aggregate_count,
            first_occurred_at,
            last_counted_at,
            occurred_at,
            updated_at,
            metadata
        )
        SELECT ?, ?, ?, ?, ?, 1, now(), now(), now(), now(), ?::jsonb
        WHERE EXISTS (
            SELECT 1
            FROM users actor
            WHERE actor.id = ?
              AND actor.status = 'active'
              AND actor.deleted_at IS NULL
        )
        AND EXISTS (
            SELECT 1
            FROM users recipient
            WHERE recipient.id = ?
              AND recipient.status = 'active'
              AND recipient.deleted_at IS NULL
        )
        ON CONFLICT (recipient_user_id, event_type, ((metadata->>'sessionId')))
        WHERE event_type IN ('psync_invite', 'psync_started', 'psync_recap', 'psync_expired') AND deleted_at IS NULL
        DO NOTHING
        RETURNING id
        """.trimIndent()

    val PSYNC_SESSION_EVENT_TYPES = setOf("psync_invite", "psync_started", "psync_recap", "psync_expired")

    val ACTIVITY_FEED_QUERY =
        """
        WITH recipient_location AS (
            SELECT geom
            FROM user_locations
            WHERE user_id = ?
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
               ae.metadata->>'source' AS source,
               album_request.status AS album_request_status,
               actor_settings.show_distance AS actor_show_distance,
               CASE
                   WHEN actor_settings.show_distance = true
                        AND recipient_location.geom IS NOT NULL
                        AND actor_location.geom IS NOT NULL
                   THEN ST_Distance(recipient_location.geom, actor_location.geom)
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
        LEFT JOIN user_locations actor_location ON actor_location.user_id = actor.id
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
               COUNT(*) FILTER (WHERE event_type = 'match' AND seen_at IS NULL)::integer AS match_count,
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
                           'match',
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

    val MARK_ACTIVITY_EVENTS_SEEN_QUERY =
        """
        UPDATE activity_events
        SET seen_at = now(),
            updated_at = now()
        WHERE recipient_user_id = ?
          AND id = ANY (?::uuid[])
          AND visibility_status = 'visible'
          AND deleted_at IS NULL
          AND seen_at IS NULL
        """.trimIndent()

    val DISMISS_ACTIVITY_EVENT_QUERY =
        """
        UPDATE activity_events
        SET deleted_at = now(),
            updated_at = now()
        WHERE id = ?
          AND recipient_user_id = ?
          AND deleted_at IS NULL
        """.trimIndent()

    val GET_ALBUM_REQUEST_EVENT_QUERY =
        """
        SELECT id, recipient_user_id, actor_user_id
        FROM activity_events
        WHERE id = ?
          AND recipient_user_id = ?
          AND event_type = 'album_request'
          AND visibility_status = 'visible'
          AND deleted_at IS NULL
        LIMIT 1
        """.trimIndent()

    val SWIPE_INTEREST_EVENT_TYPES = setOf("swipe_like", "pineapple_like", "intro_note")
}
