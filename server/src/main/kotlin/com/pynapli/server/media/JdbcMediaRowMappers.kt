package com.pynapli.server.media

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

internal fun Connection.loadAsset(mediaAssetId: UUID): MediaAssetRecord? =
    prepareStatement(
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
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, mediaAssetId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMediaAssetRecord() else null
        }
    }

internal fun ResultSet.toMediaAlbumItemRecord(connection: Connection): MediaAlbumItemRecord {
    val mediaAssetId = getObject("media_asset_id", UUID::class.java)
    return MediaAlbumItemRecord(
        albumId = getObject("album_id", UUID::class.java),
        mediaAsset = requireNotNull(connection.loadAsset(mediaAssetId)),
        sortOrder = getInt("sort_order"),
        addedAt = getTimestamp("added_at").toInstant(),
        removedAt = getTimestamp("removed_at")?.toInstant(),
    )
}

internal fun ResultSet.toMediaAssetRecord(): MediaAssetRecord =
    MediaAssetRecord(
        mediaAssetId = getObject("id", UUID::class.java),
        ownerUserId = getObject("owner_user_id", UUID::class.java),
        mediaType = getString("media_type"),
        storageProvider = getString("storage_provider"),
        storageBucket = getString("storage_bucket"),
        storageObjectKey = getString("storage_object_key"),
        contentType = getString("content_type"),
        byteSize = getLong("byte_size"),
        pixelWidth = getInt("pixel_width").takeUnless { wasNull() },
        pixelHeight = getInt("pixel_height").takeUnless { wasNull() },
        moderationStatus = getString("moderation_status"),
        processingStatus = getString("processing_status"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        deletedAt = getTimestamp("deleted_at")?.toInstant(),
    )

internal fun ResultSet.toMediaAlbumRecord(): MediaAlbumRecord =
    MediaAlbumRecord(
        albumId = getObject("id", UUID::class.java),
        ownerUserId = getObject("owner_user_id", UUID::class.java),
        kind = getString("kind"),
        title = getString("title"),
        visibilityStatus = getString("visibility_status"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        deletedAt = getTimestamp("deleted_at")?.toInstant(),
    )

internal fun ResultSet.toMediaAlbumAccessGrantRecord(): MediaAlbumAccessGrantRecord =
    MediaAlbumAccessGrantRecord(
        albumId = getObject("album_id", UUID::class.java),
        ownerUserId = getObject("owner_user_id", UUID::class.java),
        granteeUserId = getObject("grantee_user_id", UUID::class.java),
        grantedByUserId = getObject("granted_by_user_id", UUID::class.java),
        grantedAt = getTimestamp("granted_at").toInstant(),
        revokedAt = getTimestamp("revoked_at")?.toInstant(),
        revokedByUserId = getObject("revoked_by_user_id", UUID::class.java),
    )

internal fun ResultSet.toMediaAlbumAccessRequestRecord(): MediaAlbumAccessRequestRecord =
    MediaAlbumAccessRequestRecord(
        requestId = getObject("id", UUID::class.java),
        albumId = getObject("album_id", UUID::class.java),
        ownerUserId = getObject("owner_user_id", UUID::class.java),
        requesterUserId = getObject("requester_user_id", UUID::class.java),
        status = getString("status"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        decidedAt = getTimestamp("decided_at")?.toInstant(),
        decidedByUserId = getObject("decided_by_user_id", UUID::class.java),
    )
