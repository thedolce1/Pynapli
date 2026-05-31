package com.pynapli.server.messaging

import java.sql.Connection
import java.util.UUID

internal fun Connection.loadActiveLinkedPartnerUserId(userId: UUID): UUID? =
    prepareStatement(
        """
        SELECT upl.linked_user_id
        FROM user_profile_links upl
        JOIN users linked_user
          ON linked_user.id = upl.linked_user_id
         AND linked_user.status = 'active'
         AND linked_user.deleted_at IS NULL
        WHERE upl.user_id = ?
          AND upl.status = 'active'
          AND upl.unlinked_at IS NULL
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.getObject("linked_user_id", UUID::class.java) else null
        }
    }
