package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

const val MEDIA_IMAGE_MAX_BYTES = 15L * 1024L * 1024L
const val MEDIA_VIDEO_MAX_BYTES = 200L * 1024L * 1024L

@Serializable
data class CreateMediaUploadRequestDto(
    val albumKind: String,
    val mediaType: String,
    val contentType: String,
    val byteSize: Long,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
)

@Serializable
data class MediaUploadSlotDto(
    val mediaAsset: MediaAssetDto,
    val album: MediaAlbumDto,
    val uploadUrl: String,
    val uploadMethod: String,
    val uploadHeaders: Map<String, String>,
    val storageProvider: String,
    val storageBucket: String,
    val storageObjectKey: String,
    val expiresAt: String,
)

@Serializable
data class CompleteMediaUploadRequestDto(
    val albumId: String,
    val sortOrder: Int = 0,
)

@Serializable
data class ReorderMediaAlbumRequestDto(
    val orderedAssetIds: List<String>,
)

@Serializable
data class MediaAlbumItemDto(
    val albumId: String,
    val mediaAsset: MediaAssetDto,
    val sortOrder: Int,
    val addedAt: String,
)

@Serializable
data class MediaAlbumItemsResponseDto(
    val album: MediaAlbumDto,
    val items: List<MediaAlbumItemDto>,
)

@Serializable
data class MediaAlbumDto(
    val albumId: String,
    val ownerUserId: String,
    val kind: String,
    val title: String,
    val visibilityStatus: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MediaAlbumGrantDto(
    val albumId: String,
    val granteeUserId: String,
    val granteeDisplayName: String? = null,
    val grantedAt: String,
    val revokedAt: String? = null,
)

@Serializable
data class MediaAlbumGrantsResponseDto(
    val grants: List<MediaAlbumGrantDto>,
)

@Serializable
data class MediaAssetDto(
    val mediaAssetId: String,
    val ownerUserId: String,
    val mediaType: String,
    val contentType: String,
    val byteSize: Long,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val moderationStatus: String,
    val processingStatus: String,
    val createdAt: String,
    val updatedAt: String,
    // Server-issued content URL (a short-lived signed GET in production); null for local-dev where
    // the client builds the URL itself. Authorized callers only — grant checks run before this is set.
    val contentUrl: String? = null,
)
