package com.pynapli.server.activity

internal object JdbcActivitySignalSql {
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

    // ACTIVITY_SPEC "Private Album Grant": the requester learns the owner shared their private album.
    // One live card per owner -> requester; a re-grant re-surfaces it as new.
    val UPSERT_ALBUM_GRANTED_QUERY =
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
        SELECT ?, ?, ?, 'album_granted', ?, 1, now(), now(), now(), now()
        WHERE EXISTS (
            SELECT 1 FROM users requester
            WHERE requester.id = ? AND requester.status = 'active' AND requester.deleted_at IS NULL
        )
        ON CONFLICT (recipient_user_id, actor_user_id)
        WHERE event_type = 'album_granted' AND deleted_at IS NULL
        DO UPDATE SET occurred_at = now(), updated_at = now(), seen_at = NULL
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

    val SWIPE_INTEREST_EVENT_TYPES = setOf("swipe_like", "pineapple_like", "intro_note")
}
