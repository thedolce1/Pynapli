package com.pynapli.server.activity

internal object JdbcActivityEngagementSql {
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
}
