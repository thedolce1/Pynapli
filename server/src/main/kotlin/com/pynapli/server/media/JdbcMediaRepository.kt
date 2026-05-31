package com.pynapli.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

class JdbcMediaRepository(
    private val dataSource: DataSource,
) : MediaRepository {
    override suspend fun ensureAlbum(
        ownerUserId: UUID,
        kind: String,
        title: String,
        albumIdFactory: () -> UUID,
    ): MediaAlbumRecord =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findActiveAlbum(ownerUserId, kind)?.let { return@withContext it }

                connection
                    .prepareStatement(
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
            }
        }

    override suspend fun createAsset(
        ownerUserId: UUID,
        create: MediaAssetCreate,
        assetIdFactory: () -> UUID,
    ): MediaAssetRecord =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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
                            pixel_height
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.toMediaAssetRecord()
                        }
                    }
            }
        }

    override suspend fun markAssetUploaded(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE media_assets
                        SET processing_status = 'uploaded',
                            updated_at = now()
                        WHERE id = ?
                          AND owner_user_id = ?
                          AND deleted_at IS NULL
                          AND processing_status IN ('pending_upload', 'uploaded')
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
            }
        }

    override suspend fun markAssetReadyApproved(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE media_assets
                        SET processing_status = 'ready',
                            moderation_status = 'approved',
                            updated_at = now()
                        WHERE id = ?
                          AND owner_user_id = ?
                          AND deleted_at IS NULL
                          AND processing_status IN ('uploaded', 'processing', 'ready')
                          AND moderation_status IN ('pending', 'approved')
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
            }
        }

    override suspend fun markAssetModerationRejected(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE media_assets
                        SET processing_status = 'ready',
                            moderation_status = 'removed',
                            updated_at = now()
                        WHERE id = ?
                          AND owner_user_id = ?
                          AND deleted_at IS NULL
                          AND processing_status IN ('uploaded', 'processing', 'ready')
                          AND moderation_status IN ('pending', 'approved')
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
            }
        }

    override suspend fun addAssetToAlbum(
        ownerUserId: UUID,
        albumId: UUID,
        mediaAssetId: UUID,
        sortOrder: Int,
    ): MediaAlbumItemRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    override suspend fun requestAlbumAccess(
        ownerUserId: UUID,
        requesterUserId: UUID,
        requestIdFactory: () -> UUID,
    ): MediaAlbumAccessRequestRecord? =
        withContext(Dispatchers.IO) {
            if (ownerUserId == requesterUserId) return@withContext null

            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        WITH album AS (
                            INSERT INTO media_albums (id, owner_user_id, kind, title)
                            VALUES (?, ?, 'private', 'Private album')
                            ON CONFLICT (owner_user_id, kind) WHERE deleted_at IS NULL
                            DO UPDATE SET updated_at = media_albums.updated_at
                            RETURNING id, owner_user_id
                        ),
                        pending AS (
                            SELECT r.id,
                                   r.album_id,
                                   r.owner_user_id,
                                   r.requester_user_id,
                                   r.status,
                                   r.requested_at,
                                   r.decided_at,
                                   r.decided_by_user_id
                            FROM media_album_access_requests r
                            JOIN album a ON a.id = r.album_id
                            WHERE r.requester_user_id = ?
                              AND r.status = 'pending'
                            LIMIT 1
                        ),
                        inserted AS (
                            INSERT INTO media_album_access_requests (id, album_id, owner_user_id, requester_user_id)
                            SELECT ?, a.id, a.owner_user_id, ?
                            FROM album a
                            WHERE NOT EXISTS (SELECT 1 FROM pending)
                            RETURNING id,
                                      album_id,
                                      owner_user_id,
                                      requester_user_id,
                                      status,
                                      requested_at,
                                      decided_at,
                                      decided_by_user_id
                        )
                        SELECT * FROM inserted
                        UNION ALL
                        SELECT * FROM pending
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, UUID.randomUUID())
                        statement.setObject(2, ownerUserId)
                        statement.setObject(3, requesterUserId)
                        statement.setObject(4, requestIdFactory())
                        statement.setObject(5, requesterUserId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toMediaAlbumAccessRequestRecord() else null
                        }
                    }
            }
        }

    override suspend fun decideAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean,
        decidedByUserId: UUID,
    ): MediaAlbumAccessRequestRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val request =
                        connection
                            .prepareStatement(
                                """
                                UPDATE media_album_access_requests
                                SET status = ?,
                                    decided_at = now(),
                                    decided_by_user_id = ?
                                WHERE id = (
                                    SELECT r.id
                                    FROM media_album_access_requests r
                                    JOIN media_albums a
                                      ON a.id = r.album_id
                                     AND a.owner_user_id = r.owner_user_id
                                     AND a.kind = 'private'
                                     AND a.deleted_at IS NULL
                                    WHERE r.owner_user_id = ?
                                      AND r.requester_user_id = ?
                                      AND r.status = 'pending'
                                    ORDER BY r.requested_at DESC, r.id DESC
                                    LIMIT 1
                                )
                                RETURNING id,
                                          album_id,
                                          owner_user_id,
                                          requester_user_id,
                                          status,
                                          requested_at,
                                          decided_at,
                                          decided_by_user_id
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, if (approved) "approved" else "denied")
                                statement.setObject(2, decidedByUserId)
                                statement.setObject(3, ownerUserId)
                                statement.setObject(4, requesterUserId)
                                statement.executeQuery().use { rs ->
                                    if (rs.next()) rs.toMediaAlbumAccessRequestRecord() else null
                                }
                            }

                    if (request == null) {
                        connection.rollback()
                        return@withContext null
                    }

                    if (approved) {
                        connection
                            .prepareStatement(
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
                                VALUES (?, ?, ?, ?, now(), NULL, NULL)
                                ON CONFLICT (album_id, grantee_user_id) DO UPDATE
                                SET granted_by_user_id = EXCLUDED.granted_by_user_id,
                                    granted_at = now(),
                                    revoked_at = NULL,
                                    revoked_by_user_id = NULL
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setObject(1, request.albumId)
                                statement.setObject(2, request.ownerUserId)
                                statement.setObject(3, request.requesterUserId)
                                statement.setObject(4, decidedByUserId)
                                statement.executeUpdate()
                            }
                    }

                    connection.commit()
                    request
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    override suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        albumId: UUID,
    ): List<MediaAlbumItemRecord>? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                if (!connection.hasAlbumAccess(viewerUserId, albumId)) return@withContext null

                connection
                    .prepareStatement(
                        """
                        SELECT album_id, media_asset_id, owner_user_id, sort_order, added_at, removed_at
                        FROM media_album_items
                        WHERE album_id = ?
                          AND removed_at IS NULL
                        ORDER BY sort_order ASC, added_at ASC, media_asset_id ASC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, albumId)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) add(rs.toMediaAlbumItemRecord(connection))
                            }
                        }
                    }
            }
        }

    override suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        ownerUserId: UUID,
        kind: String,
    ): Pair<MediaAlbumRecord, List<MediaAlbumItemRecord>>? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val album = connection.findActiveAlbum(ownerUserId, kind) ?: return@withContext null
                val items = listAlbumItemsForViewer(viewerUserId, album.albumId) ?: return@withContext null
                album to items
            }
        }

    override suspend fun grantAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        grantedByUserId: UUID,
    ): MediaAlbumAccessGrantRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
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
            }
        }

    override suspend fun revokeAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        revokedByUserId: UUID,
    ): MediaAlbumAccessGrantRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    override suspend fun listPrivateAlbumGrantsForOwner(
        ownerUserId: UUID,
    ): List<MediaAlbumGrantSummaryRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    override suspend fun hasAlbumAccess(
        viewerUserId: UUID,
        albumId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.hasAlbumAccess(viewerUserId, albumId)
            }
        }

    private fun Connection.findActiveAlbum(
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

    private fun Connection.hasAlbumAccess(
        viewerUserId: UUID,
        albumId: UUID,
    ): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM media_albums a
                WHERE a.id = ?
                  AND a.deleted_at IS NULL
                  AND (
                      a.owner_user_id = ?
                      OR (
                          a.visibility_status = 'visible'
                          AND a.kind = 'profile'
                      )
                      OR (
                          a.visibility_status = 'visible'
                          AND a.kind = 'private'
                          AND EXISTS (
                              SELECT 1
                              FROM media_album_access_grants g
                              WHERE g.album_id = a.id
                                AND g.grantee_user_id = ?
                                AND g.revoked_at IS NULL
                          )
                      )
                  )
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, albumId)
            statement.setObject(2, viewerUserId)
            statement.setObject(3, viewerUserId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
}
