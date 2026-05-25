package com.pynapli.app.media

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaAssetDto

class SampleMediaAlbumsRepository : MediaAlbumsRepository {
    private var nextAssetIndex = 1
    private val itemsByKind = mutableMapOf<MediaAlbumKindUi, List<MediaAlbumItemUi>>()
    private val grants = mutableListOf<MediaPrivateGrantUi>()

    override suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>> =
        AppResult.Success(itemsByKind[kind].orEmpty())

    override suspend fun loadPrivateGrants(): AppResult<List<MediaPrivateGrantUi>> =
        AppResult.Success(grants.toList())

    override suspend fun revokePrivateGrant(granteeUserId: String): AppResult<Unit> {
        grants.removeAll { it.granteeUserId == granteeUserId }
        return AppResult.Success(Unit)
    }

    override suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload
    ): AppResult<MediaAlbumItemUi> {
        val index = nextAssetIndex++
        val asset = MediaAssetDto(
            mediaAssetId = "sample-media-$index",
            ownerUserId = "viewer",
            mediaType = "image",
            contentType = media.contentType,
            byteSize = media.byteSize,
            pixelWidth = media.pixelWidth,
            pixelHeight = media.pixelHeight,
            moderationStatus = "pending",
            processingStatus = "uploaded",
            createdAt = "2026-04-24T18:00:00Z",
            updatedAt = "2026-04-24T18:00:00Z"
        )
        val item = MediaAlbumItemDto(
            albumId = "sample-${kind.apiValue}-album",
            mediaAsset = asset,
            sortOrder = 0,
            addedAt = "2026-04-24T18:00:00Z"
        )
        return AppResult.Success(
            item.toUi(
                kind = kind,
                mediaUrl = "https://media.example/sample-media-$index"
            )
        )
            .also { result ->
                itemsByKind[kind] = listOf(result.value) + itemsByKind[kind].orEmpty()
            }
    }
}
