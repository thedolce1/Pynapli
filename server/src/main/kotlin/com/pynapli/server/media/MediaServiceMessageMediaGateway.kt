package com.pynapli.server.media

import com.pynapli.server.messaging.MessageMediaGateway
import com.pynapli.server.messaging.PreparedMessageMedia
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto
import com.pynapli.shared.network.dto.MEDIA_IMAGE_MAX_BYTES
import com.pynapli.shared.network.dto.MessageMediaUploadSlotDto
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Media-module implementation of the messaging [MessageMediaGateway]. Chat photos are one-off
 * image assets tagged `intended_album_kind='message'` — never added to an album, so sending a
 * photo never grants album access (MESSAGING_SPEC). Reuses the album media's signer, asset rows,
 * moderation scanner, and URL resolver.
 */
class MediaServiceMessageMediaGateway(
    private val repository: MediaRepository,
    private val uploadSigner: MediaUploadSigner,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    private val moderationScanner: MediaModerationScanner = ManualReviewMediaModerationScanner(),
    private val rateLimitService: RateLimitService? = null,
    // Chat photos reach the scanner only when private-media scanning is enabled.
    private val scanScope: MediaScanScope = MediaScanScope(),
) : MessageMediaGateway {
    override suspend fun createUploadSlot(
        ownerUserId: UUID,
        request: CreateMessageMediaUploadRequestDto,
    ): MessageMediaUploadSlotDto {
        if (rateLimitService?.check(ownerUserId, RateLimitedAction.MEDIA_UPLOAD)?.allowed == false) {
            throw MediaException("RATE_LIMITED")
        }
        val contentType = request.contentType.trim().lowercase()
        if (contentType !in IMAGE_CONTENT_TYPES) throw MediaException("INVALID_MEDIA_CONTENT_TYPE")
        if (request.byteSize <= 0) throw MediaException("INVALID_MEDIA_BYTE_SIZE")
        if (request.byteSize > MEDIA_IMAGE_MAX_BYTES) throw MediaException("MEDIA_FILE_TOO_LARGE")
        request.pixelWidth?.let { if (it <= 0) throw MediaException("INVALID_MEDIA_DIMENSIONS") }
        request.pixelHeight?.let { if (it <= 0) throw MediaException("INVALID_MEDIA_DIMENSIONS") }

        val mediaAssetId = UUID.randomUUID()
        val destination =
            uploadSigner.createUploadDestination(
                ownerUserId = ownerUserId,
                mediaAssetId = mediaAssetId,
                albumKind = MESSAGE_MEDIA_KIND,
                contentType = contentType,
            )
        repository.createAsset(
            ownerUserId = ownerUserId,
            create =
                MediaAssetCreate(
                    mediaType = "image",
                    storageProvider = destination.storageProvider,
                    storageBucket = destination.storageBucket,
                    storageObjectKey = destination.storageObjectKey,
                    contentType = contentType,
                    byteSize = request.byteSize,
                    pixelWidth = request.pixelWidth,
                    pixelHeight = request.pixelHeight,
                    intendedAlbumKind = MESSAGE_MEDIA_KIND,
                ),
            assetIdFactory = { mediaAssetId },
        )

        return MessageMediaUploadSlotDto(
            mediaAssetId = mediaAssetId.toString(),
            uploadUrl = destination.uploadUrl,
            uploadMethod = destination.uploadMethod,
            uploadHeaders = destination.uploadHeaders,
            expiresAt = destination.expiresAt.toString(),
        )
    }

    override suspend fun prepareOutboundMedia(
        senderUserId: UUID,
        mediaAssetId: UUID,
    ): PreparedMessageMedia? {
        val asset = repository.findMessageMediaAsset(senderUserId, mediaAssetId) ?: return null
        // Already finalized (retried send): approved is reusable; a prior rejection stays refused.
        if (asset.moderationStatus == "rejected") return null
        if (asset.processingStatus == "ready" && asset.moderationStatus == "approved") {
            return asset.toPrepared()
        }

        repository.markAssetUploaded(senderUserId, mediaAssetId)
        // Chat photos are private 1:1 content; only a SEVERE (safety/legal) hit refuses the send. By
        // default they are not scanned at all (no third-party scanner sees them) and send as before.
        val decision =
            if (scanScope.scans(MediaModerationTarget.CHAT_MEDIA)) moderationScanner.scan(asset) else MediaModerationDecision.Approved
        if (decision is MediaModerationDecision.Rejected && decision.severity == MediaModerationSeverity.SEVERE) {
            repository.markAssetModerationRejected(senderUserId, mediaAssetId)
            logger.info("Refused chat media {} for severe moderation decision", mediaAssetId)
            return null
        }
        val ready =
            repository.markAssetReadyApproved(senderUserId, mediaAssetId)
                ?: return null
        return ready.toPrepared()
    }

    override fun contentUrl(mediaAssetId: UUID): String? = mediaAssetUrlResolver?.publicContentUrl(mediaAssetId)

    private fun MediaAssetRecord.toPrepared(): PreparedMessageMedia =
        PreparedMessageMedia(
            contentType = contentType,
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
        )

    private companion object {
        private val logger = LoggerFactory.getLogger(MediaServiceMessageMediaGateway::class.java)
        private const val MESSAGE_MEDIA_KIND = "message"
        private val IMAGE_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
