package com.pynapli.server.media

import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MEDIA_IMAGE_MAX_BYTES
import com.pynapli.shared.network.dto.MEDIA_VIDEO_MAX_BYTES
import com.pynapli.shared.network.dto.MediaAlbumDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaAssetDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import java.util.UUID

class MediaService(
    private val repository: MediaRepository,
    private val uploadSigner: MediaUploadSigner,
    private val devAutoApproveUploads: Boolean = false
) {
    suspend fun createUploadSlot(
        ownerUserId: UUID,
        request: CreateMediaUploadRequestDto
    ): MediaUploadSlotDto {
        val albumKind = request.albumKind.trim().lowercase()
        if (albumKind !in ALBUM_KINDS) throw MediaException("INVALID_MEDIA_ALBUM_KIND")

        val mediaType = request.mediaType.trim().lowercase()
        if (mediaType !in MEDIA_TYPES) throw MediaException("INVALID_MEDIA_TYPE")

        val contentType = request.contentType.trim().lowercase()
        if (contentType !in CONTENT_TYPES.getValue(mediaType)) throw MediaException("INVALID_MEDIA_CONTENT_TYPE")

        if (request.byteSize <= 0) throw MediaException("INVALID_MEDIA_BYTE_SIZE")
        if (request.byteSize > mediaType.maxBytes()) throw MediaException("MEDIA_FILE_TOO_LARGE")
        request.pixelWidth?.let { if (it <= 0) throw MediaException("INVALID_MEDIA_DIMENSIONS") }
        request.pixelHeight?.let { if (it <= 0) throw MediaException("INVALID_MEDIA_DIMENSIONS") }

        val album = repository.ensureAlbum(
            ownerUserId = ownerUserId,
            kind = albumKind,
            title = albumKind.defaultTitle()
        )
        val mediaAssetId = UUID.randomUUID()
        val destination = uploadSigner.createUploadDestination(
            ownerUserId = ownerUserId,
            mediaAssetId = mediaAssetId,
            albumKind = albumKind,
            contentType = contentType
        )
        val asset = repository.createAsset(
            ownerUserId = ownerUserId,
            create = MediaAssetCreate(
                mediaType = mediaType,
                storageProvider = destination.storageProvider,
                storageBucket = destination.storageBucket,
                storageObjectKey = destination.storageObjectKey,
                contentType = contentType,
                byteSize = request.byteSize,
                pixelWidth = request.pixelWidth,
                pixelHeight = request.pixelHeight
            ),
            assetIdFactory = { mediaAssetId }
        )

        return MediaUploadSlotDto(
            mediaAsset = asset.toDto(),
            album = album.toDto(),
            uploadUrl = destination.uploadUrl,
            uploadMethod = destination.uploadMethod,
            uploadHeaders = destination.uploadHeaders,
            storageProvider = destination.storageProvider,
            storageBucket = destination.storageBucket,
            storageObjectKey = destination.storageObjectKey,
            expiresAt = destination.expiresAt.toString()
        )
    }

    suspend fun completeUpload(
        ownerUserId: UUID,
        mediaAssetIdText: String,
        request: CompleteMediaUploadRequestDto
    ): MediaAlbumItemDto {
        val mediaAssetId = mediaAssetIdText.toUuidOrThrow()
        val albumId = request.albumId.toUuidOrThrow()
        if (request.sortOrder < 0) throw MediaException("INVALID_MEDIA_SORT_ORDER")

        val item = repository.addAssetToAlbum(
            ownerUserId = ownerUserId,
            albumId = albumId,
            mediaAssetId = mediaAssetId,
            sortOrder = request.sortOrder
        ) ?: throw MediaException("MEDIA_ALBUM_NOT_FOUND")

        val uploadedAsset = repository.markAssetUploaded(ownerUserId, mediaAssetId)
            ?: throw MediaException("MEDIA_ASSET_NOT_FOUND")
        val visibleAsset = if (devAutoApproveUploads) {
            repository.markAssetReadyApproved(ownerUserId, mediaAssetId) ?: uploadedAsset
        } else {
            uploadedAsset
        }

        return item.copy(mediaAsset = visibleAsset).toDto()
    }

    suspend fun listOwnAlbum(
        ownerUserId: UUID,
        albumKindText: String
    ): MediaAlbumItemsResponseDto {
        val albumKind = albumKindText.trim().lowercase()
        if (albumKind !in ALBUM_KINDS) throw MediaException("INVALID_MEDIA_ALBUM_KIND")

        val album = repository.ensureAlbum(
            ownerUserId = ownerUserId,
            kind = albumKind,
            title = albumKind.defaultTitle()
        )
        val items = repository.listAlbumItemsForViewer(
            viewerUserId = ownerUserId,
            albumId = album.albumId
        ) ?: throw MediaException("MEDIA_ALBUM_NOT_FOUND")

        return MediaAlbumItemsResponseDto(
            album = album.toDto(),
            items = items.map { item -> item.toDto() }
        )
    }

    suspend fun listPrivateAlbumForViewer(
        viewerUserId: UUID,
        ownerUserIdText: String
    ): MediaAlbumItemsResponseDto {
        val ownerUserId = ownerUserIdText.toUuidOrThrow()
        val result = repository.listAlbumItemsForViewer(
            viewerUserId = viewerUserId,
            ownerUserId = ownerUserId,
            kind = "private"
        ) ?: throw MediaException("MEDIA_ALBUM_NOT_FOUND")

        val (album, items) = result
        return MediaAlbumItemsResponseDto(
            album = album.toDto(),
            items = items.map { item -> item.toDto() }
        )
    }

    suspend fun requestPrivateAlbumAccess(
        requesterUserId: UUID,
        ownerUserId: UUID
    ): Boolean {
        if (requesterUserId == ownerUserId) throw MediaException("INVALID_ALBUM_REQUEST_TARGET")
        return repository.requestAlbumAccess(
            ownerUserId = ownerUserId,
            requesterUserId = requesterUserId
        ) != null
    }

    suspend fun decidePrivateAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean
    ): String {
        val request = repository.decideAlbumAccessRequest(
            ownerUserId = ownerUserId,
            requesterUserId = requesterUserId,
            approved = approved,
            decidedByUserId = ownerUserId
        ) ?: throw MediaException("ALBUM_REQUEST_NOT_FOUND")
        return request.status
    }

    private fun MediaAlbumItemRecord.toDto(): MediaAlbumItemDto =
        MediaAlbumItemDto(
            albumId = albumId.toString(),
            mediaAsset = mediaAsset.toDto(),
            sortOrder = sortOrder,
            addedAt = addedAt.toString()
        )

    private fun MediaAlbumRecord.toDto(): MediaAlbumDto =
        MediaAlbumDto(
            albumId = albumId.toString(),
            ownerUserId = ownerUserId.toString(),
            kind = kind,
            title = title,
            visibilityStatus = visibilityStatus,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString()
        )

    private fun MediaAssetRecord.toDto(): MediaAssetDto =
        MediaAssetDto(
            mediaAssetId = mediaAssetId.toString(),
            ownerUserId = ownerUserId.toString(),
            mediaType = mediaType,
            contentType = contentType,
            byteSize = byteSize,
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            moderationStatus = moderationStatus,
            processingStatus = processingStatus,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString()
        )

    private fun String.toUuidOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw MediaException("INVALID_ID") }

    private fun String.maxBytes(): Long = when (this) {
        "image" -> MEDIA_IMAGE_MAX_BYTES
        "video" -> MEDIA_VIDEO_MAX_BYTES
        else -> 0
    }

    private fun String.defaultTitle(): String = when (this) {
        "profile" -> "Profile photos"
        "private" -> "Private album"
        else -> "Media"
    }

    companion object {
        private val ALBUM_KINDS = setOf("profile", "private")
        private val MEDIA_TYPES = setOf("image", "video")
        private val CONTENT_TYPES = mapOf(
            "image" to setOf("image/jpeg", "image/png", "image/webp"),
            "video" to setOf("video/mp4", "video/quicktime")
        )
    }
}

class MediaException(val code: String) : RuntimeException(code)
