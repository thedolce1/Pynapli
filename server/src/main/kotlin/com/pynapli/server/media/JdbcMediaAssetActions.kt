package com.pynapli.server.media

import com.pynapli.server.db.inTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.createMediaAsset(
    ownerUserId: UUID,
    create: MediaAssetCreate,
    assetIdFactory: () -> UUID,
): MediaAssetRecord =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO media_assets (
                        id,
                        owner_user_id,
                        media_type,
                        storage_provider,
                        storage_bucket,
                        storage_object_key,
                        content_type,
                        byte_size,
                        pixel_width,
                        pixel_height,
                        intended_album_kind
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id,
                              owner_user_id,
                              media_type,
                              storage_provider,
                              storage_bucket,
                              storage_object_key,
                              content_type,
                              byte_size,
                              pixel_width,
                              pixel_height,
                              moderation_status,
                              processing_status,
                              created_at,
                              updated_at,
                              deleted_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, assetIdFactory())
                    statement.setObject(2, ownerUserId)
                    statement.setString(3, create.mediaType)
                    statement.setString(4, create.storageProvider)
                    statement.setString(5, create.storageBucket)
                    statement.setString(6, create.storageObjectKey)
                    statement.setString(7, create.contentType)
                    statement.setLong(8, create.byteSize)
                    create.pixelWidth?.let { statement.setInt(9, it) } ?: statement.setNull(9, Types.INTEGER)
                    create.pixelHeight?.let { statement.setInt(10, it) } ?: statement.setNull(10, Types.INTEGER)
                    create.intendedAlbumKind?.let { statement.setString(11, it) } ?: statement.setNull(11, Types.VARCHAR)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.toMediaAssetRecord()
                    }
                }
        }
    }

internal suspend fun DataSource.findMessageMediaAsset(
    ownerUserId: UUID,
    mediaAssetId: UUID,
): MediaAssetRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id,
                           owner_user_id,
                           media_type,
                           storage_provider,
                           storage_bucket,
                           storage_object_key,
                           content_type,
                           byte_size,
                           pixel_width,
                           pixel_height,
                           moderation_status,
                           processing_status,
                           created_at,
                           updated_at,
                           deleted_at
                    FROM media_assets
                    WHERE id = ?
                      AND owner_user_id = ?
                      AND intended_album_kind = 'message'
                      AND deleted_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.setObject(2, ownerUserId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toMediaAssetRecord() else null
                    }
                }
        }
    }

internal suspend fun DataSource.softDeleteMediaAsset(
    ownerUserId: UUID,
    mediaAssetId: UUID,
): MediaAssetRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val asset =
                    connection
                        .prepareStatement(
                            """
                            UPDATE media_assets
                            SET deleted_at = COALESCE(deleted_at, now()),
                                updated_at = now()
                            WHERE id = ?
                              AND owner_user_id = ?
                            RETURNING id,
                                      owner_user_id,
                                      media_type,
                                      storage_provider,
                                      storage_bucket,
                                      storage_object_key,
                                      content_type,
                                      byte_size,
                                      pixel_width,
                                      pixel_height,
                                      moderation_status,
                                      processing_status,
                                      created_at,
                                      updated_at,
                                      deleted_at
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, mediaAssetId)
                            statement.setObject(2, ownerUserId)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) rs.toMediaAssetRecord() else null
                            }
                        }

                if (asset == null) {
                    connection.rollback()
                    return@withContext null
                }

                connection
                    .prepareStatement(
                        """
                        UPDATE media_album_items
                        SET removed_at = now()
                        WHERE media_asset_id = ?
                          AND owner_user_id = ?
                          AND removed_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, mediaAssetId)
                        statement.setObject(2, ownerUserId)
                        statement.executeUpdate()
                    }

                connection.commit()
                asset
            }
        }
    }

internal suspend fun DataSource.countPendingMediaUploadAssets(
    ownerUserId: UUID,
    intendedAlbumKind: String,
): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM media_assets
                    WHERE owner_user_id = ?
                      AND deleted_at IS NULL
                      AND processing_status = 'pending_upload'
                      AND intended_album_kind = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, ownerUserId)
                    statement.setString(2, intendedAlbumKind)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
    }
