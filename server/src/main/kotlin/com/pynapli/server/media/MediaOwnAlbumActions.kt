package com.pynapli.server.media

import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.ReorderMediaAlbumRequestDto
import java.util.UUID

internal class MediaOwnAlbumActions(
    private val repository: MediaRepository,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
) {
    suspend fun listOwnAlbum(
        ownerUserId: UUID,
        albumKindText: String,
    ): MediaAlbumItemsResponseDto {
        val albumKind = albumKindText.trim().lowercase()
        if (albumKind !in MEDIA_ALBUM_KINDS) throw MediaException("INVALID_MEDIA_ALBUM_KIND")

        val album =
            repository.ensureAlbum(
                ownerUserId = ownerUserId,
                kind = albumKind,
                title = albumKind.defaultMediaAlbumTitle(),
            )
        val items =
            repository.listAlbumItemsForViewer(
                viewerUserId = ownerUserId,
                albumId = album.albumId,
            ) ?: throw MediaException("MEDIA_ALBUM_NOT_FOUND")

        return MediaAlbumItemsResponseDto(
            album = album.toDto(),
            items = items.map { item -> item.toDto(mediaAssetUrlResolver) },
        )
    }

    suspend fun deleteAsset(
        ownerUserId: UUID,
        mediaAssetIdText: String,
    ) {
        val mediaAssetId = mediaAssetIdText.toMediaUuidOrThrow()
        // Missing or non-owned assets surface the same not-found (no existence leak);
        // deleting an already-deleted owned asset replays success (idempotent).
        repository.softDeleteAsset(ownerUserId, mediaAssetId)
            ?: throw MediaException("MEDIA_ASSET_NOT_FOUND")
    }

    suspend fun reorderAlbum(
        ownerUserId: UUID,
        albumKindText: String,
        request: ReorderMediaAlbumRequestDto,
    ): MediaAlbumItemsResponseDto {
        val albumKind = albumKindText.trim().lowercase()
        if (albumKind !in MEDIA_ALBUM_KINDS) throw MediaException("INVALID_MEDIA_ALBUM_KIND")

        val orderedAssetIds = request.orderedAssetIds.map { it.toMediaUuidOrThrow() }
        if (orderedAssetIds.toSet().size != orderedAssetIds.size) {
            throw MediaException("MEDIA_ALBUM_ORDER_MISMATCH")
        }

        val album =
            repository.ensureAlbum(
                ownerUserId = ownerUserId,
                kind = albumKind,
                title = albumKind.defaultMediaAlbumTitle(),
            )
        val items =
            repository.reorderAlbumItems(
                ownerUserId = ownerUserId,
                albumId = album.albumId,
                orderedAssetIds = orderedAssetIds,
            ) ?: throw MediaException("MEDIA_ALBUM_ORDER_MISMATCH")

        return MediaAlbumItemsResponseDto(
            album = album.toDto(),
            items = items.map { item -> item.toDto(mediaAssetUrlResolver) },
        )
    }
}
