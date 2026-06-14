package com.pynapli.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.reorderMediaAlbumItems(
    ownerUserId: UUID,
    albumId: UUID,
    orderedAssetIds: List<UUID>,
): List<MediaAlbumItemRecord>? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                if (connection.findActiveAlbum(ownerUserId, albumId) == null) {
                    connection.rollback()
                    return@withContext null
                }

                val activeAssetIds =
                    connection
                        .prepareStatement(
                            """
                            SELECT i.media_asset_id
                            FROM media_album_items i
                            JOIN media_assets m
                              ON m.id = i.media_asset_id
                             AND m.owner_user_id = i.owner_user_id
                             AND m.deleted_at IS NULL
                            WHERE i.album_id = ?
                              AND i.owner_user_id = ?
                              AND i.removed_at IS NULL
                            FOR UPDATE OF i
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, albumId)
                            statement.setObject(2, ownerUserId)
                            statement.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) add(rs.getObject("media_asset_id", UUID::class.java))
                                }
                            }
                        }

                if (orderedAssetIds.size != activeAssetIds.size || orderedAssetIds.toSet() != activeAssetIds.toSet()) {
                    connection.rollback()
                    return@withContext null
                }

                connection
                    .prepareStatement(
                        """
                        UPDATE media_album_items i
                        SET sort_order = (u.ord - 1)::int
                        FROM unnest(?::uuid[]) WITH ORDINALITY AS u(media_asset_id, ord)
                        WHERE i.album_id = ?
                          AND i.owner_user_id = ?
                          AND i.media_asset_id = u.media_asset_id
                          AND i.removed_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setArray(1, connection.createArrayOf("uuid", orderedAssetIds.toTypedArray()))
                        statement.setObject(2, albumId)
                        statement.setObject(3, ownerUserId)
                        statement.executeUpdate()
                    }

                val items = connection.listActiveAlbumItems(ownerUserId, albumId)
                connection.commit()
                items
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

internal suspend fun DataSource.countActiveMediaAlbumItems(
    ownerUserId: UUID,
    albumId: UUID,
    excludingMediaAssetId: UUID?,
): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val exclusionClause = if (excludingMediaAssetId != null) "AND i.media_asset_id <> ?" else ""
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM media_album_items i
                    JOIN media_assets m
                      ON m.id = i.media_asset_id
                     AND m.owner_user_id = i.owner_user_id
                     AND m.deleted_at IS NULL
                    WHERE i.album_id = ?
                      AND i.owner_user_id = ?
                      AND i.removed_at IS NULL
                      $exclusionClause
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, albumId)
                    statement.setObject(2, ownerUserId)
                    excludingMediaAssetId?.let { statement.setObject(3, it) }
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
    }

internal suspend fun DataSource.findMediaAlbum(
    ownerUserId: UUID,
    albumId: UUID,
): MediaAlbumRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.findActiveAlbum(ownerUserId, albumId)
        }
    }

internal suspend fun DataSource.addMediaAssetToAlbum(
    ownerUserId: UUID,
    albumId: UUID,
    mediaAssetId: UUID,
    sortOrder: Int,
): MediaAlbumItemRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO media_album_items (album_id, media_asset_id, owner_user_id, sort_order)
                    SELECT a.id, m.id, a.owner_user_id, ?
                    FROM media_albums a
                    JOIN media_assets m
                      ON m.id = ?
                     AND m.owner_user_id = a.owner_user_id
                     AND m.deleted_at IS NULL
                    WHERE a.id = ?
                      AND a.owner_user_id = ?
                      AND a.deleted_at IS NULL
                    ON CONFLICT (album_id, media_asset_id) DO UPDATE
                    SET sort_order = EXCLUDED.sort_order,
                        removed_at = NULL,
                        added_at = now()
                    RETURNING album_id, media_asset_id, owner_user_id, sort_order, added_at, removed_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, sortOrder)
                    statement.setObject(2, mediaAssetId)
                    statement.setObject(3, albumId)
                    statement.setObject(4, ownerUserId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@withContext null
                        rs.toMediaAlbumItemRecord(connection)
                    }
                }
        }
    }

internal suspend fun DataSource.listMediaAlbumItemsForViewer(
    viewerUserId: UUID,
    albumId: UUID,
): List<MediaAlbumItemRecord>? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            if (!connection.hasAlbumAccess(viewerUserId, albumId)) return@withContext null
            connection.listAlbumItemsVisibleToViewer(viewerUserId, albumId)
        }
    }

internal suspend fun DataSource.listMediaAlbumItemsForViewer(
    viewerUserId: UUID,
    ownerUserId: UUID,
    kind: String,
): Pair<MediaAlbumRecord, List<MediaAlbumItemRecord>>? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val album = connection.findActiveAlbum(ownerUserId, kind) ?: return@withContext null
            if (!connection.hasAlbumAccess(viewerUserId, album.albumId)) return@withContext null
            album to connection.listAlbumItemsVisibleToViewer(viewerUserId, album.albumId)
        }
    }

private fun java.sql.Connection.listActiveAlbumItems(
    ownerUserId: UUID,
    albumId: UUID,
): List<MediaAlbumItemRecord> =
    prepareStatement(
        """
        SELECT i.album_id,
               i.media_asset_id,
               i.owner_user_id,
               i.sort_order,
               i.added_at,
               i.removed_at
        FROM media_album_items i
        JOIN media_assets m
          ON m.id = i.media_asset_id
         AND m.owner_user_id = i.owner_user_id
         AND m.deleted_at IS NULL
        WHERE i.album_id = ?
          AND i.owner_user_id = ?
          AND i.removed_at IS NULL
        ORDER BY i.sort_order ASC, i.added_at DESC, i.media_asset_id ASC
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, albumId)
        statement.setObject(2, ownerUserId)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.toMediaAlbumItemRecord(this@listActiveAlbumItems))
            }
        }
    }

private fun java.sql.Connection.listAlbumItemsVisibleToViewer(
    viewerUserId: UUID,
    albumId: UUID,
): List<MediaAlbumItemRecord> =
    prepareStatement(
        """
        SELECT i.album_id,
               i.media_asset_id,
               i.owner_user_id,
               i.sort_order,
               i.added_at,
               i.removed_at
        FROM media_album_items i
        JOIN media_assets m
          ON m.id = i.media_asset_id
         AND m.owner_user_id = i.owner_user_id
         AND m.deleted_at IS NULL
        JOIN media_albums a
          ON a.id = i.album_id
         AND a.owner_user_id = i.owner_user_id
         AND a.deleted_at IS NULL
        WHERE i.album_id = ?
          AND i.removed_at IS NULL
          AND (
              a.owner_user_id = ?
              OR (
                  a.owner_user_id <> ?
                  AND m.processing_status = 'ready'
                  AND m.moderation_status = 'approved'
              )
          )
        ORDER BY sort_order ASC, added_at DESC, media_asset_id ASC
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, albumId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, viewerUserId)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.toMediaAlbumItemRecord(this@listAlbumItemsVisibleToViewer))
            }
        }
    }
