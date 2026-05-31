package com.pynapli.server.messaging

import java.sql.Connection
import java.util.UUID

internal fun Connection.targetAllowsConversation(
    viewerUserId: UUID,
    targetUserId: UUID,
    origin: String,
): Boolean =
    when (origin) {
        "grid" -> targetAllowsGridConversation(viewerUserId, targetUserId)
        "swipe" -> targetAllowsSwipeConversation(viewerUserId, targetUserId)
        else -> false
    }

private fun Connection.targetAllowsGridConversation(
    viewerUserId: UUID,
    targetUserId: UUID,
): Boolean {
    val policy =
        prepareStatement(
            """
            SELECT ds.allow_grid_messages, ds.grid_message_policy
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            WHERE u.id = ?
              AND u.status = 'active'
              AND u.deleted_at IS NULL
              AND p.visibility_status = 'visible'
              AND ds.grid_visible = true
              AND ds.incognito = false
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, targetUserId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return false
                GridMessagePolicy(
                    allowGridMessages = rs.getBoolean("allow_grid_messages"),
                    gridMessagePolicy = rs.getString("grid_message_policy"),
                )
            }
        }

    return when {
        !policy.allowGridMessages -> false
        policy.gridMessagePolicy == "everyone" -> true
        policy.gridMessagePolicy == "matches_only" -> targetAllowsSwipeConversation(viewerUserId, targetUserId)
        else -> false
    }
}

private fun Connection.targetAllowsSwipeConversation(
    viewerUserId: UUID,
    targetUserId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM matches m
            JOIN users target
              ON target.id = ?
             AND target.status = 'active'
             AND target.deleted_at IS NULL
            JOIN profiles target_profile
              ON target_profile.user_id = target.id
             AND target_profile.visibility_status = 'visible'
            WHERE LEAST(m.user_a, m.user_b) = LEAST(?::uuid, ?::uuid)
              AND GREATEST(m.user_a, m.user_b) = GREATEST(?::uuid, ?::uuid)
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, targetUserId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, targetUserId)
        statement.setObject(4, viewerUserId)
        statement.setObject(5, targetUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }
