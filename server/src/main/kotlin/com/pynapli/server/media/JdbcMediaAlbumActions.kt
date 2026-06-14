package com.pynapli.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.ensureMediaAlbum(
    ownerUserId: UUID,
    kind: String,
    title: String,
    albumIdFactory: () -> UUID,
): MediaAlbumRecord =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.findActiveAlbum(ownerUserId, kind)?.let { return@withContext it }
            connection.insertMediaAlbum(ownerUserId, kind, title, albumIdFactory)
        }
    }

private fun Connection.insertMediaAlbum(
    ownerUserId: UUID,
    kind: String,
    title: String,
    albumIdFactory: () -> UUID,
): MediaAlbumRecord =
    prepareStatement(
        """
        INSERT INTO media_albums (id, owner_user_id, kind, title)
        VALUES (?, ?, ?, ?)
        RETURNING id, owner_user_id, kind, title, visibility_status, created_at, updated_at, deleted_at
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, albumIdFactory())
        statement.setObject(2, ownerUserId)
        statement.setString(3, kind)
        statement.setString(4, title)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.toMediaAlbumRecord()
        }
    }
