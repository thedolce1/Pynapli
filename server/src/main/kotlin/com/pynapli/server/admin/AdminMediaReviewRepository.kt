package com.pynapli.server.admin

import com.pynapli.server.db.withConnection
import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface AdminMediaReviewRepository {
    /** Profile-album photos held for review (uploaded, moderation pending), oldest upload first. */
    suspend fun listPendingProfilePhotos(
        limit: Int,
        after: AdminMediaReviewCursor?,
    ): List<PendingProfilePhoto>

    /** The asset's owner and statuses, or null when it does not exist (or is deleted). */
    suspend fun findAsset(mediaAssetId: UUID): ReviewedMediaAsset?

    suspend fun recordDecisionAudit(
        actorId: String,
        action: String,
        mediaAssetId: UUID,
        ownerUserId: UUID,
        reason: String?,
    )
}

data class PendingProfilePhoto(
    val mediaAssetId: UUID,
    val ownerUserId: UUID,
    val contentType: String,
    val createdAt: Instant,
)

data class ReviewedMediaAsset(
    val mediaAssetId: UUID,
    val ownerUserId: UUID,
    val processingStatus: String,
    val moderationStatus: String,
    val inProfileAlbum: Boolean,
)

data class AdminMediaReviewCursor(
    val createdAt: Instant,
    val mediaAssetId: UUID,
)

class JdbcAdminMediaReviewRepository(
    private val dataSource: DataSource,
) : AdminMediaReviewRepository {
    override suspend fun listPendingProfilePhotos(
        limit: Int,
        after: AdminMediaReviewCursor?,
    ): List<PendingProfilePhoto> =
        dataSource.withConnection { connection ->
            val cursorClause = if (after == null) "" else "AND (m.created_at, m.id) > (?, ?)"
            connection
                .prepareStatement(
                    """
                    SELECT m.id, m.owner_user_id, m.content_type, m.created_at
                    FROM media_assets m
                    JOIN users u ON u.id = m.owner_user_id AND u.deleted_at IS NULL
                    WHERE m.deleted_at IS NULL
                      AND m.processing_status = 'uploaded'
                      AND m.moderation_status = 'pending'
                      AND EXISTS ($PROFILE_ALBUM_ITEM)
                      $cursorClause
                    ORDER BY m.created_at ASC, m.id ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    var index = 1
                    if (after != null) {
                        statement.setTimestamp(index++, Timestamp.from(after.createdAt))
                        statement.setObject(index++, after.mediaAssetId)
                    }
                    statement.setInt(index, limit)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    PendingProfilePhoto(
                                        mediaAssetId = rs.getObject("id", UUID::class.java),
                                        ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
                                        contentType = rs.getString("content_type"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun findAsset(mediaAssetId: UUID): ReviewedMediaAsset? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT m.id, m.owner_user_id, m.processing_status, m.moderation_status,
                           EXISTS ($PROFILE_ALBUM_ITEM) AS in_profile_album
                    FROM media_assets m
                    WHERE m.id = ? AND m.deleted_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toReviewedAsset() else null }
                }
        }

    override suspend fun recordDecisionAudit(
        actorId: String,
        action: String,
        mediaAssetId: UUID,
        ownerUserId: UUID,
        reason: String?,
    ) {
        dataSource.withConnection { connection ->
            connection.insertAdminAuditLog(
                actorId = actorId,
                action = action,
                targetType = "media_asset",
                targetId = mediaAssetId,
                reason = reason,
                metadataJson = buildJsonObject { put("ownerUserId", ownerUserId.toString()) }.toString(),
            )
        }
    }

    private fun ResultSet.toReviewedAsset(): ReviewedMediaAsset =
        ReviewedMediaAsset(
            mediaAssetId = getObject("id", UUID::class.java),
            ownerUserId = getObject("owner_user_id", UUID::class.java),
            processingStatus = getString("processing_status"),
            moderationStatus = getString("moderation_status"),
            inProfileAlbum = getBoolean("in_profile_album"),
        )

    private companion object {
        // The asset sits (not removed) in its owner's profile album.
        const val PROFILE_ALBUM_ITEM =
            "SELECT 1 FROM media_album_items mai JOIN media_albums ma ON ma.id = mai.album_id " +
                "WHERE mai.media_asset_id = m.id AND mai.removed_at IS NULL AND ma.kind = 'profile' AND ma.deleted_at IS NULL"
    }
}
