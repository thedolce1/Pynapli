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
    private val scanScope: MediaScanScope = MediaScanScope(),
    // Grid thumbnails (G2-2): told about each profile photo approved on upload. Best effort.
    private val profilePhotoApproved: ProfilePhotoApprovedListener? = null,
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
        // Once per asset, atomically, and only into the album kind the slot was opened for: a held
        // profile photo can't be re-completed into the (unscanned) private album to get approved.
        val item =
            when (
                val completion =
                    repository.completeAlbumUpload(
                        ownerUserId = ownerUserId,
                        albumId = albumId,
                        mediaAssetId = mediaAssetId,
                        sortOrder = request.sortOrder,
                    )
            ) {
                is AlbumUploadCompletion.Refused -> throw MediaException(completion.code)
                is AlbumUploadCompletion.AlreadyCompleted -> {
                    // A retried complete into the same album returns the existing state: a held or
                    // decided photo is never scanned again. Only an asset no decision was ever
                    // recorded for (a crash between attach and moderation) is moderated now.
                    val asset = completion.item.mediaAsset
                    if (completion.scanned || asset.processingStatus != "uploaded" || asset.moderationStatus != "pending") {
                        return completion.item.toDto(mediaAssetUrlResolver)
                    }
                    completion.item
                }

                is AlbumUploadCompletion.Attached -> completion.item
            }

        // Moderation hook: a profile photo is approved or held for a person before discovery (never
        // auto-removed); private albums remain consent-gated, are scanned only when the scan scope
        // says so, and then change only on a severe safety/legal rejection.
        val visibleAsset = applyModerationDecision(ownerUserId, mediaAssetId, album.kind, item.mediaAsset)
        // Recorded after the decision is applied, so a crash before it leaves the asset re-scannable.
        repository.markModerationScanned(ownerUserId, mediaAssetId)
        if (album.kind == "profile" && visibleAsset.isApprovedReady()) notifyProfilePhotoApproved(mediaAssetId)

        return item.copy(mediaAsset = visibleAsset).toDto(mediaAssetUrlResolver)
    }

    private suspend fun applyModerationDecision(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        uploadedAsset: MediaAssetRecord,
    ): MediaAssetRecord {
        val target =
            when (albumKind) {
                "profile" -> MediaModerationTarget.PROFILE_PHOTO
                "private" -> MediaModerationTarget.PRIVATE_ALBUM
                else -> throw MediaException("INVALID_MEDIA_ALBUM_KIND")
            }
        // Unscanned private-album photos (the default: no third-party scanner sees them) are approved
        // on upload, which is what the manual and dev scanners always did for private albums.
        if (!scanScope.scans(target)) {
            return repository.markAssetReadyApproved(ownerUserId, mediaAssetId) ?: uploadedAsset
        }
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
                when {
                    // A profile photo is only ever approved or held (TRUST_AND_SAFETY_SPEC "Photo
                    // pre-screening"): it stays hidden in the human queue (uploaded/pending). No removal,
                    // no automatic report or risk points; the moderator's decision is the enforcement.
                    albumKind == "profile" -> uploadedAsset

                    albumKind == "private" && decision.severity != MediaModerationSeverity.SEVERE ->
                        repository.markAssetReadyApproved(ownerUserId, mediaAssetId) ?: uploadedAsset

                    else -> {
                        val removed = repository.markAssetModerationRejected(ownerUserId, mediaAssetId) ?: uploadedAsset
                        if (decision.severity == MediaModerationSeverity.SEVERE) escalate(ownerUserId, mediaAssetId, albumKind, decision)
                        removed
                    }
                }
        }
    }

    private fun notifyProfilePhotoApproved(mediaAssetId: UUID) {
        val listener = profilePhotoApproved ?: return
        runCatching { listener.profilePhotoApproved(mediaAssetId) }
            .onFailure { logger.warn("Thumbnail scheduling failed for asset {} ({})", mediaAssetId, it.javaClass.simpleName) }
    }

    private fun MediaAssetRecord.isApprovedReady(): Boolean = processingStatus == "ready" && moderationStatus == "approved"

    private suspend fun escalate(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        decision: MediaModerationDecision.Rejected,
    ) {
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

    private companion object {
        private val logger = LoggerFactory.getLogger(MediaUploadActions::class.java)
    }
}
