package com.pynapli.server.media

import com.pynapli.server.db.inTransaction
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
            connection.inTransaction {
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

/**
 * Upload-complete, once per asset (TRUST_AND_SAFETY_SPEC "Photo pre-screening", "Completion is once
 * per asset"). In one transaction, with the asset row locked:
 * - the asset must have been opened for this album's kind (`intended_album_kind = album.kind`), so a
 *   profile upload can never be completed into the private album (which is not pre-screened) or the
 *   other way round;
 * - an asset already in any other album is refused;
 * - a repeat into the same album returns the existing item (idempotent retry, no new item);
 * - otherwise the item is inserted and the asset moves pending_upload -> uploaded together, so a
 *   failed status change never leaves an item behind.
 */
internal suspend fun DataSource.completeMediaAlbumUpload(
    ownerUserId: UUID,
    albumId: UUID,
    mediaAssetId: UUID,
    sortOrder: Int,
): AlbumUploadCompletion =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val asset =
                    prepareStatement(
                        """
                        SELECT intended_album_kind, processing_status, moderation_scanned_at IS NOT NULL AS scanned
                        FROM media_assets
                        WHERE id = ? AND owner_user_id = ? AND deleted_at IS NULL
                        FOR UPDATE
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, mediaAssetId)
                        statement.setObject(2, ownerUserId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) Triple(rs.getString("intended_album_kind"), rs.getString("processing_status"), rs.getBoolean("scanned")) else null
                        }
                    } ?: return@inTransaction AlbumUploadCompletion.Refused("MEDIA_ASSET_NOT_FOUND")
                val album =
                    findActiveAlbum(ownerUserId, albumId)
                        ?: return@inTransaction AlbumUploadCompletion.Refused("MEDIA_ALBUM_NOT_FOUND")
                val intendedKind = asset.first
                if (intendedKind == null || intendedKind !in ALBUM_UPLOAD_KINDS) {
                    // A chat photo, a verification selfie or a legacy slot never joins an album; same
                    // answer as before (no hint that the asset exists).
                    return@inTransaction AlbumUploadCompletion.Refused("MEDIA_ALBUM_NOT_FOUND")
                }
                if (asset.first != album.kind) {
                    return@inTransaction AlbumUploadCompletion.Refused("MEDIA_UPLOAD_ALBUM_MISMATCH")
                }

                // Every album this asset was ever attached to, removed or not.
                val existing =
                    prepareStatement(
                        """
                        SELECT album_id, media_asset_id, owner_user_id, sort_order, added_at, removed_at
                        FROM media_album_items
                        WHERE media_asset_id = ? AND owner_user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, mediaAssetId)
                        statement.setObject(2, ownerUserId)
                        statement.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toMediaAlbumItemRecord(this@inTransaction)) }
                        }
                    }
                if (existing.any { it.albumId != albumId }) {
                    return@inTransaction AlbumUploadCompletion.Refused("MEDIA_UPLOAD_ALREADY_COMPLETED")
                }
                val sameAlbum = existing.firstOrNull()
                if (sameAlbum != null) {
                    return@inTransaction if (sameAlbum.removedAt == null) {
                        AlbumUploadCompletion.AlreadyCompleted(sameAlbum, scanned = asset.third)
                    } else {
                        AlbumUploadCompletion.Refused("MEDIA_UPLOAD_ALREADY_COMPLETED")
                    }
                }
                if (asset.second != "pending_upload") {
                    return@inTransaction AlbumUploadCompletion.Refused("MEDIA_UPLOAD_ALREADY_COMPLETED")
                }

                val inserted =
                    prepareStatement(
                        """
                        INSERT INTO media_album_items (album_id, media_asset_id, owner_user_id, sort_order)
                        VALUES (?, ?, ?, ?)
                        RETURNING album_id, media_asset_id, owner_user_id, sort_order, added_at, removed_at
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, albumId)
                        statement.setObject(2, mediaAssetId)
                        statement.setObject(3, ownerUserId)
                        statement.setInt(4, sortOrder)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            Triple(rs.getInt("sort_order"), rs.getTimestamp("added_at").toInstant(), rs.getTimestamp("removed_at")?.toInstant())
                        }
                    }
                val updated =
                    prepareStatement(
                        """
                        UPDATE media_assets
                        SET processing_status = 'uploaded', updated_at = now()
                        WHERE id = ? AND owner_user_id = ? AND processing_status = 'pending_upload'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, mediaAssetId)
                        statement.setObject(2, ownerUserId)
                        statement.executeUpdate()
                    }
                check(updated == 1) { "asset status changed under the row lock" }
                AlbumUploadCompletion.Attached(
                    MediaAlbumItemRecord(
                        albumId = albumId,
                        mediaAsset = requireNotNull(loadAsset(mediaAssetId)),
                        sortOrder = inserted.first,
                        addedAt = inserted.second,
                        removedAt = inserted.third,
                    ),
                )
            }
        }
    }

private val ALBUM_UPLOAD_KINDS = setOf("profile", "private")

/** First decision only: a retry never moves the timestamp. */
internal suspend fun DataSource.markMediaModerationScanned(
    ownerUserId: UUID,
    mediaAssetId: UUID,
) {
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE media_assets
                    SET moderation_scanned_at = now()
                    WHERE id = ? AND owner_user_id = ? AND moderation_scanned_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.setObject(2, ownerUserId)
                    statement.executeUpdate()
                }
        }
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
                     -- Only album uploads go into albums: never a chat photo or a verification selfie,
                     -- and only into the album kind the upload was opened for.
                     AND (m.intended_album_kind IS NULL OR m.intended_album_kind = a.kind)
                    WHERE a.id = ?
                      AND a.owner_user_id = ?
                      AND a.deleted_at IS NULL
                      -- An asset lives in one album only (see completeMediaAlbumUpload).
                      AND NOT EXISTS (
                          SELECT 1 FROM media_album_items other
                          WHERE other.media_asset_id = m.id AND other.album_id <> a.id
                      )
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
                  -- Others only see an asset in the album kind it was uploaded (and screened) for.
                  AND m.intended_album_kind = a.kind
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
