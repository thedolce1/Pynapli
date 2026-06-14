package com.pynapli.server.activity

internal object JdbcActivityMutationSql {
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
}
