package com.pynapli.server.media

import com.pynapli.server.safety.RateLimitService
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import org.slf4j.LoggerFactory
import java.util.UUID

internal class MediaUploadActions(
    private val repository: MediaRepository,
    private val uploadSigner: MediaUploadSigner,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    private val moderationScanner: MediaModerationScanner,
    private val moderationEscalationPublisher: MediaModerationEscalationPublisher,
    private val rateLimitService: RateLimitService?,
) {
    suspend fun createUploadSlot(
        ownerUserId: UUID,
        request: CreateMediaUploadRequestDto,
    ): MediaUploadSlotDto {
        rateLimitService.enforceMediaUploadRateLimit(ownerUserId)
        val albumKind = request.albumKind.trim().lowercase()
        if (albumKind !in MEDIA_ALBUM_KINDS) throw MediaException("INVALID_MEDIA_ALBUM_KIND")

        val mediaType = request.mediaType.trim().lowercase()
        if (mediaType !in MEDIA_TYPES) throw MediaException("INVALID_MEDIA_TYPE")

        val contentType = request.contentType.trim().lowercase()
        if (contentType !in MEDIA_CONTENT_TYPES.getValue(mediaType)) throw MediaException("INVALID_MEDIA_CONTENT_TYPE")

        if (request.byteSize <= 0) throw MediaException("INVALID_MEDIA_BYTE_SIZE")
        if (request.byteSize > mediaType.maxMediaBytes()) throw MediaException("MEDIA_FILE_TOO_LARGE")
        request.pixelWidth?.let { if (it <= 0) throw MediaException("INVALID_MEDIA_DIMENSIONS") }
        request.pixelHeight?.let { if (it <= 0) throw MediaException("INVALID_MEDIA_DIMENSIONS") }

        val album =
            repository.ensureAlbum(
                ownerUserId = ownerUserId,
                kind = albumKind,
                title = albumKind.defaultMediaAlbumTitle(),
            )
        // Cap counts active album items plus already-open upload slots, so a full album
        // cannot be bypassed by opening many slots before completing any of them.
        val activeCount = repository.countActiveAlbumItems(ownerUserId, album.albumId)
        val pendingSlotCount = repository.countPendingUploadAssets(ownerUserId, albumKind)
        if (activeCount + pendingSlotCount >= albumKind.mediaAlbumPhotoCap()) {
            throw MediaException("MEDIA_ALBUM_PHOTO_CAP_EXCEEDED")
        }
        val mediaAssetId = UUID.randomUUID()
        val destination =
            uploadSigner.createUploadDestination(
                ownerUserId = ownerUserId,
                mediaAssetId = mediaAssetId,
                albumKind = albumKind,
                contentType = contentType,
            )
        val asset =
            repository.createAsset(
                ownerUserId = ownerUserId,
                create =
                    MediaAssetCreate(
                        mediaType = mediaType,
                        storageProvider = destination.storageProvider,
                        storageBucket = destination.storageBucket,
                        storageObjectKey = destination.storageObjectKey,
                        contentType = contentType,
                        byteSize = request.byteSize,
                        pixelWidth = request.pixelWidth,
                        pixelHeight = request.pixelHeight,
                        intendedAlbumKind = albumKind,
                    ),
                assetIdFactory = { mediaAssetId },
            )

        return MediaUploadSlotDto(
            mediaAsset = asset.toDto(mediaAssetUrlResolver),
            album = album.toDto(),
            uploadUrl = destination.uploadUrl,
            uploadMethod = destination.uploadMethod,
            uploadHeaders = destination.uploadHeaders,
            storageProvider = destination.storageProvider,
            storageBucket = destination.storageBucket,
            storageObjectKey = destination.storageObjectKey,
            expiresAt = destination.expiresAt.toString(),
        )
    }

    suspend fun completeUpload(
        ownerUserId: UUID,
        mediaAssetIdText: String,
        request: CompleteMediaUploadRequestDto,
    ): MediaAlbumItemDto {
        rateLimitService.enforceMediaUploadRateLimit(ownerUserId)
        val mediaAssetId = mediaAssetIdText.toMediaUuidOrThrow()
        val albumId = request.albumId.toMediaUuidOrThrow()
        if (request.sortOrder < 0) throw MediaException("INVALID_MEDIA_SORT_ORDER")

        val album =
            repository.findAlbum(ownerUserId, albumId)
                ?: throw MediaException("MEDIA_ALBUM_NOT_FOUND")
        // Re-check the cap at attach time (slots are created before items exist). The completing
        // asset is excluded from the count so a retried complete stays idempotent.
        val activeCount =
            repository.countActiveAlbumItems(
                ownerUserId = ownerUserId,
                albumId = albumId,
                excludingMediaAssetId = mediaAssetId,
            )
        if (activeCount >= album.kind.mediaAlbumPhotoCap()) {
            throw MediaException("MEDIA_ALBUM_PHOTO_CAP_EXCEEDED")
        }
        val item =
            repository.addAssetToAlbum(
                ownerUserId = ownerUserId,
                albumId = albumId,
                mediaAssetId = mediaAssetId,
                sortOrder = request.sortOrder,
            ) ?: throw MediaException("MEDIA_ALBUM_NOT_FOUND")

        val uploadedAsset =
            repository.markAssetUploaded(ownerUserId, mediaAssetId)
                ?: throw MediaException("MEDIA_ASSET_NOT_FOUND")
        // Moderation hook: public photos are held/removed before discovery; private albums remain
        // consent-gated unless a scanner returns a severe safety/legal rejection.
        val visibleAsset = applyModerationDecision(ownerUserId, mediaAssetId, album.kind, uploadedAsset)

        return item.copy(mediaAsset = visibleAsset).toDto(mediaAssetUrlResolver)
    }

    private suspend fun applyModerationDecision(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        uploadedAsset: MediaAssetRecord,
    ): MediaAssetRecord {
        val decision = moderationScanner.scan(uploadedAsset)
        return when (decision) {
            MediaModerationDecision.Approved ->
                repository.markAssetReadyApproved(ownerUserId, mediaAssetId) ?: uploadedAsset

            MediaModerationDecision.PendingReview ->
                if (albumKind == "profile") {
                    uploadedAsset
                } else {
                    repository.markAssetReadyApproved(ownerUserId, mediaAssetId) ?: uploadedAsset
                }

            is MediaModerationDecision.Rejected ->
                if (albumKind == "private" && decision.severity != MediaModerationSeverity.SEVERE) {
                    repository.markAssetReadyApproved(ownerUserId, mediaAssetId) ?: uploadedAsset
                } else {
                    val removed = repository.markAssetModerationRejected(ownerUserId, mediaAssetId) ?: uploadedAsset
                    if (decision.severity == MediaModerationSeverity.SEVERE) {
                        runCatching {
                            moderationEscalationPublisher.recordSevereMediaDecision(
                                ownerUserId = ownerUserId,
                                mediaAssetId = mediaAssetId,
                                albumKind = albumKind,
                                reason = decision.reason,
                                reportCategory = decision.reportCategory,
                            )
                        }.onFailure { error ->
                            logger.warn("Failed to create severe media moderation escalation for asset {}", mediaAssetId, error)
                        }
                    }
                    removed
                }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(MediaUploadActions::class.java)
    }
}
