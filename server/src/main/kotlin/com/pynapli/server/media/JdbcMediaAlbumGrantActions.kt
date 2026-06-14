package com.pynapli.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.grantMediaAlbumAccess(
    ownerUserId: UUID,
    albumId: UUID,
    granteeUserId: UUID,
    grantedByUserId: UUID,
): MediaAlbumAccessGrantRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.upsertAlbumAccessGrant(albumId, ownerUserId, granteeUserId, grantedByUserId)
        }
    }

internal suspend fun DataSource.revokeMediaAlbumAccess(
    ownerUserId: UUID,
    albumId: UUID,
    granteeUserId: UUID,
    revokedByUserId: UUID,
): MediaAlbumAccessGrantRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE media_album_access_grants
                    SET revoked_at = now(),
                        revoked_by_user_id = ?
                    WHERE album_id = ?
                      AND owner_user_id = ?
                      AND grantee_user_id = ?
                      AND revoked_at IS NULL
                    RETURNING album_id, owner_user_id, grantee_user_id, granted_by_user_id, granted_at, revoked_at, revoked_by_user_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, revokedByUserId)
                    statement.setObject(2, albumId)
                    statement.setObject(3, ownerUserId)
                    statement.setObject(4, granteeUserId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toMediaAlbumAccessGrantRecord() else null
                    }
                }
        }
    }

internal suspend fun DataSource.listPrivateMediaAlbumGrantsForOwner(ownerUserId: UUID): List<MediaAlbumGrantSummaryRecord> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT g.album_id,
                           g.owner_user_id,
                           g.grantee_user_id,
                           p.display_name,
                           g.granted_at
                    FROM media_album_access_grants g
                    JOIN media_albums a
                      ON a.id = g.album_id
                     AND a.owner_user_id = g.owner_user_id
                     AND a.kind = 'private'
                     AND a.deleted_at IS NULL
                    LEFT JOIN profiles p
                      ON p.user_id = g.grantee_user_id
                    WHERE g.owner_user_id = ?
                      AND g.revoked_at IS NULL
                    ORDER BY g.granted_at DESC, g.grantee_user_id ASC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, ownerUserId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    MediaAlbumGrantSummaryRecord(
                                        albumId = rs.getObject("album_id", UUID::class.java),
                                        ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
                                        granteeUserId = rs.getObject("grantee_user_id", UUID::class.java),
                                        granteeDisplayName = rs.getString("display_name"),
                                        grantedAt = rs.getTimestamp("granted_at").toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

internal fun Connection.upsertAlbumAccessGrant(
    albumId: UUID,
    ownerUserId: UUID,
    granteeUserId: UUID,
    grantedByUserId: UUID,
): MediaAlbumAccessGrantRecord? =
    prepareStatement(
        """
        INSERT INTO media_album_access_grants (
            album_id,
            owner_user_id,
            grantee_user_id,
            granted_by_user_id,
            granted_at,
            revoked_at,
            revoked_by_user_id
        )
        SELECT id, owner_user_id, ?, ?, now(), NULL, NULL
        FROM media_albums
        WHERE id = ?
          AND owner_user_id = ?
          AND kind = 'private'
          AND deleted_at IS NULL
        ON CONFLICT (album_id, grantee_user_id) DO UPDATE
        SET granted_by_user_id = EXCLUDED.granted_by_user_id,
            granted_at = now(),
            revoked_at = NULL,
            revoked_by_user_id = NULL
        RETURNING album_id, owner_user_id, grantee_user_id, granted_by_user_id, granted_at, revoked_at, revoked_by_user_id
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, granteeUserId)
        statement.setObject(2, grantedByUserId)
        statement.setObject(3, albumId)
        statement.setObject(4, ownerUserId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMediaAlbumAccessGrantRecord() else null
        }
    }
