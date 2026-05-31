package com.pynapli.app.media

import com.pynapli.shared.network.dto.MediaAlbumGrantDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto

internal fun MediaAlbumItemDto.toUi(
    kind: MediaAlbumKindUi,
    mediaUrl: String? = null,
): MediaAlbumItemUi =
    MediaAlbumItemUi(
        mediaAssetId = mediaAsset.mediaAssetId,
        albumId = albumId,
        albumKind = kind,
        mediaType = mediaAsset.mediaType,
        contentType = mediaAsset.contentType,
        processingStatus = mediaAsset.processingStatus,
        moderationStatus = mediaAsset.moderationStatus,
        sortOrder = sortOrder,
        createdAt = addedAt,
        mediaUrl = mediaUrl,
    )

internal fun MediaAlbumGrantDto.toUi(): MediaPrivateGrantUi =
    MediaPrivateGrantUi(
        granteeUserId = granteeUserId,
        displayName = granteeDisplayName,
        grantedAt = grantedAt,
    )
