package com.pynapli.server.admin

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaRepository
import com.pynapli.server.media.ProfilePhotoApprovedListener
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Review of profile photos held by the moderation scanner (the manual-review default, or a Cloud
 * Vision "possible" result). Approval uses the same ready/approved transition as an automatic
 * approval; rejection marks the photo removed (hidden everywhere; the media cleanup sweep deletes it
 * after its retention) and tells the owner through a system-safety push. Operator-level: these are
 * photos their owners asked to show publicly.
 */
class AdminMediaReviewService(
    private val repository: AdminMediaReviewRepository,
    private val mediaRepository: MediaRepository,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    private val notificationPublisher: NotificationPublisher? = null,
    // Grid thumbnails (G2-2): an approved profile photo gets its thumbnail made. Best effort.
    private val profilePhotoApproved: ProfilePhotoApprovedListener? = null,
) {
    suspend fun listPending(
        limitText: String?,
        cursor: String?,
    ): AdminPendingMediaListDto {
        val limit = limitText?.let { it.toIntOrNull() ?: throw ModerationException("INVALID_REVIEW_LIMIT") } ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) throw ModerationException("INVALID_REVIEW_LIMIT")
        val rows = repository.listPendingProfilePhotos(limit + 1, cursor?.let(::decodeCursor))
        val page = rows.take(limit)
        return AdminPendingMediaListDto(
            items =
                page.map { row ->
                    AdminPendingMediaItemDto(
                        mediaAssetId = row.mediaAssetId.toString(),
                        ownerUserId = row.ownerUserId.toString(),
                        albumKind = "profile",
                        contentType = row.contentType,
                        uploadedAt = row.createdAt.toString(),
                        url = mediaAssetUrlResolver?.publicContentUrl(row.mediaAssetId),
                    )
                },
            nextCursor = if (rows.size > limit) page.last().let { encodeCursor(it.createdAt, it.mediaAssetId) } else null,
        )
    }

    suspend fun approve(
        mediaAssetIdText: String,
        actorId: String,
        reason: String?,
    ): AdminMediaReviewDecisionDto {
        val asset = pendingProfilePhoto(mediaAssetIdText)
        val approved =
            mediaRepository.markAssetReadyApproved(asset.ownerUserId, asset.mediaAssetId)
                ?: throw ModerationException("MEDIA_REVIEW_NOT_PENDING")
        repository.recordDecisionAudit(actorId, "media_review.approve", asset.mediaAssetId, asset.ownerUserId, reason.cleanReason())
        runCatching { profilePhotoApproved?.profilePhotoApproved(asset.mediaAssetId) }
            .onFailure { logger.warn("Thumbnail scheduling failed ({})", it.javaClass.simpleName) }
        return AdminMediaReviewDecisionDto(asset.mediaAssetId.toString(), asset.ownerUserId.toString(), approved.moderationStatus)
    }

    suspend fun reject(
        mediaAssetIdText: String,
        actorId: String,
        reason: String?,
    ): AdminMediaReviewDecisionDto {
        val userReason = reason.cleanReason() ?: throw ModerationException("MODERATION_REASON_REQUIRED")
        val asset = pendingProfilePhoto(mediaAssetIdText)
        val removed =
            mediaRepository.markAssetModerationRejected(asset.ownerUserId, asset.mediaAssetId)
                ?: throw ModerationException("MEDIA_REVIEW_NOT_PENDING")
        repository.recordDecisionAudit(actorId, "media_review.reject", asset.mediaAssetId, asset.ownerUserId, userReason)
        notifyOwner(asset.ownerUserId, asset.mediaAssetId)
        return AdminMediaReviewDecisionDto(asset.mediaAssetId.toString(), asset.ownerUserId.toString(), removed.moderationStatus)
    }

    private suspend fun pendingProfilePhoto(mediaAssetIdText: String): ReviewedMediaAsset {
        val mediaAssetId = runCatching { UUID.fromString(mediaAssetIdText.trim()) }.getOrElse { throw ModerationException("INVALID_ID") }
        val asset = repository.findAsset(mediaAssetId) ?: throw ModerationException("MEDIA_REVIEW_NOT_FOUND")
        if (!asset.inProfileAlbum) throw ModerationException("MEDIA_REVIEW_NOT_FOUND")
        if (asset.processingStatus != "uploaded" || asset.moderationStatus != "pending") {
            throw ModerationException("MEDIA_REVIEW_NOT_PENDING")
        }
        return asset
    }

    // A push is a courtesy on top of the decision; it must never undo or fail it.
    private suspend fun notifyOwner(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ) {
        val publisher = notificationPublisher ?: return
        try {
            publisher.enqueue(
                NotificationEventDraft(
                    recipientUserId = ownerUserId,
                    actorUserId = null,
                    kind = NotificationKind.SYSTEM_SAFETY,
                    subjectId = mediaAssetId,
                    idempotencyKey = "media_review:$mediaAssetId:rejected",
                    payload = mapOf("event" to "profile_photo_rejected"),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Profile photo rejection notification failed: {}", error.javaClass.simpleName)
        }
    }

    private fun String?.cleanReason(): String? {
        val cleaned = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (cleaned.length > MAX_REASON_LENGTH) throw ModerationException("MODERATION_REASON_TOO_LONG")
        return cleaned
    }

    private fun encodeCursor(
        createdAt: Instant,
        mediaAssetId: UUID,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt|$mediaAssetId".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String): AdminMediaReviewCursor =
        runCatching {
            val (createdAt, id) = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split('|', limit = 2)
            AdminMediaReviewCursor(Instant.parse(createdAt), UUID.fromString(id))
        }.getOrElse { throw ModerationException("INVALID_REVIEW_CURSOR") }

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        const val MAX_REASON_LENGTH = 500
        val logger = LoggerFactory.getLogger(AdminMediaReviewService::class.java)
    }
}

@Serializable
data class AdminPendingMediaListDto(
    val items: List<AdminPendingMediaItemDto>,
    val nextCursor: String? = null,
)

@Serializable
data class AdminPendingMediaItemDto(
    val mediaAssetId: String,
    val ownerUserId: String,
    val albumKind: String,
    val contentType: String,
    val uploadedAt: String,
    // Short-lived signed URL for the reviewer.
    val url: String? = null,
)

@Serializable
data class AdminMediaReviewRequestDto(
    // Required to reject (kept in the audit log); optional when approving.
    val reason: String? = null,
)

@Serializable
data class AdminMediaReviewDecisionDto(
    val mediaAssetId: String,
    val ownerUserId: String,
    val moderationStatus: String,
)
