package com.pynapli.server.media

import java.sql.Connection
import java.util.UUID

internal fun Connection.findActiveAlbum(
    ownerUserId: UUID,
    kind: String,
): MediaAlbumRecord? =
    prepareStatement(
        """
        SELECT id, owner_user_id, kind, title, visibility_status, created_at, updated_at, deleted_at
        FROM media_albums
        WHERE owner_user_id = ?
          AND kind = ?
          AND deleted_at IS NULL
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, ownerUserId)
        statement.setString(2, kind)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMediaAlbumRecord() else null
        }
    }

internal fun Connection.findActiveAlbum(
    ownerUserId: UUID,
    albumId: UUID,
): MediaAlbumRecord? =
    prepareStatement(
        """
        SELECT id, owner_user_id, kind, title, visibility_status, created_at, updated_at, deleted_at
        FROM media_albums
        WHERE id = ?
          AND owner_user_id = ?
          AND deleted_at IS NULL
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, albumId)
        statement.setObject(2, ownerUserId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMediaAlbumRecord() else null
        }
    }
