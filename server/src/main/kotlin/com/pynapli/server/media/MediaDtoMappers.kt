package com.pynapli.server.media

import com.pynapli.shared.network.dto.MediaAlbumDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaAssetDto

internal fun MediaAlbumItemRecord.toDto(mediaAssetUrlResolver: MediaAssetUrlResolver?): MediaAlbumItemDto =
    MediaAlbumItemDto(
        albumId = albumId.toString(),
        mediaAsset = mediaAsset.toDto(mediaAssetUrlResolver),
        sortOrder = sortOrder,
        addedAt = addedAt.toString(),
    )

internal fun MediaAlbumRecord.toDto(): MediaAlbumDto =
    MediaAlbumDto(
        albumId = albumId.toString(),
        ownerUserId = ownerUserId.toString(),
        kind = kind,
        title = title,
        visibilityStatus = visibilityStatus,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

internal fun MediaAssetRecord.toDto(mediaAssetUrlResolver: MediaAssetUrlResolver?): MediaAssetDto =
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
        updatedAt = updatedAt.toString(),
        // Authorized callers only: album items are returned after grant checks, so attaching a
        // signed content URL here does not leak access. Null for local-dev (client builds it).
        contentUrl = mediaAssetUrlResolver?.publicContentUrl(mediaAssetId),
    )
