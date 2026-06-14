package com.pynapli.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.markMediaAssetUploaded(
    ownerUserId: UUID,
    mediaAssetId: UUID,
): MediaAssetRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.updateMediaAssetStatus(
                ownerUserId = ownerUserId,
                mediaAssetId = mediaAssetId,
                processingStatus = "uploaded",
                moderationStatus = null,
                allowedProcessingStatuses = listOf("pending_upload", "uploaded"),
                allowedModerationStatuses = emptyList(),
            )
        }
    }

internal suspend fun DataSource.markMediaAssetReadyApproved(
    ownerUserId: UUID,
    mediaAssetId: UUID,
): MediaAssetRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.updateMediaAssetStatus(
                ownerUserId = ownerUserId,
                mediaAssetId = mediaAssetId,
                processingStatus = "ready",
                moderationStatus = "approved",
                allowedProcessingStatuses = listOf("uploaded", "processing", "ready"),
                allowedModerationStatuses = listOf("pending", "approved"),
            )
        }
    }

internal suspend fun DataSource.markMediaAssetModerationRejected(
    ownerUserId: UUID,
    mediaAssetId: UUID,
): MediaAssetRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.updateMediaAssetStatus(
                ownerUserId = ownerUserId,
                mediaAssetId = mediaAssetId,
                processingStatus = "ready",
                moderationStatus = "removed",
                allowedProcessingStatuses = listOf("uploaded", "processing", "ready"),
                allowedModerationStatuses = listOf("pending", "approved"),
            )
        }
    }

private fun Connection.updateMediaAssetStatus(
    ownerUserId: UUID,
    mediaAssetId: UUID,
    processingStatus: String,
    moderationStatus: String?,
    allowedProcessingStatuses: List<String>,
    allowedModerationStatuses: List<String>,
): MediaAssetRecord? {
    val moderationSetClause = moderationStatus?.let { "moderation_status = ?," }.orEmpty()
    val moderationFilter =
        if (allowedModerationStatuses.isEmpty()) {
            ""
        } else {
            "AND moderation_status = ANY(?::text[])"
        }

    val sql =
        """
        UPDATE media_assets
        SET processing_status = ?,
            $moderationSetClause
            updated_at = now()
        WHERE id = ?
          AND owner_user_id = ?
          AND deleted_at IS NULL
          AND processing_status = ANY(?::text[])
          $moderationFilter
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
        """.trimIndent()

    return prepareStatement(sql).use { statement ->
        var index = 1
        statement.setString(index++, processingStatus)
        if (moderationStatus != null) statement.setString(index++, moderationStatus)
        statement.setObject(index++, mediaAssetId)
        statement.setObject(index++, ownerUserId)
        statement.setArray(index++, createArrayOf("text", allowedProcessingStatuses.toTypedArray()))
        if (allowedModerationStatuses.isNotEmpty()) {
            statement.setArray(index, createArrayOf("text", allowedModerationStatuses.toTypedArray()))
        }
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMediaAssetRecord() else null
        }
    }
}
