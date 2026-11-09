package com.pynapli.app.media

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.map
import com.pynapli.shared.network.MediaByteUploader
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.ReorderMediaAlbumRequestDto

class ApiMediaAlbumsRepository(
    private val apiClient: PynapliApiClient,
    private val mediaByteUploader: MediaByteUploader,
) : MediaAlbumsRepository {
    override suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>> = apiClient.listMediaAlbum(kind.apiValue).map { value -> value.items.map { item -> item.toUiWithUrl(kind) } }

    override suspend fun deleteMedia(assetId: String): AppResult<Unit> = apiClient.deleteMediaAsset(assetId)

    override suspend fun reorderAlbum(
        kind: MediaAlbumKindUi,
        orderedAssetIds: List<String>,
    ): AppResult<List<MediaAlbumItemUi>> =
        apiClient
            .reorderMediaAlbum(
                albumKind = kind.apiValue,
                request = ReorderMediaAlbumRequestDto(orderedAssetIds = orderedAssetIds),
            ).map { value -> value.items.map { item -> item.toUiWithUrl(kind) } }

    private fun MediaAlbumItemDto.toUiWithUrl(kind: MediaAlbumKindUi): MediaAlbumItemUi =
        toUi(
            kind = kind,
            // Prefer the server-issued (signed, in production) URL; fall back to the
            // local-dev content endpoint when the server did not supply one.
            mediaUrl =
                mediaAsset.contentUrl
                    ?: apiClient.mediaAssetContentUrl(mediaAsset.mediaAssetId),
        )

    override suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload,
    ): AppResult<MediaAlbumItemUi> {
        val slot =
            when (
                val result =
                    apiClient.createMediaUpload(
                        CreateMediaUploadRequestDto(
                            albumKind = kind.apiValue,
                            mediaType = "image",
                            contentType = media.contentType,
                            byteSize = media.byteSize,
                            pixelWidth = media.pixelWidth,
                            pixelHeight = media.pixelHeight,
                        ),
                    )
            ) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result
            }

        when (val result = mediaByteUploader.upload(slot, media.bytes)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return result
        }

        return apiClient
            .completeMediaUpload(
                mediaAssetId = slot.mediaAsset.mediaAssetId,
                request = CompleteMediaUploadRequestDto(albumId = slot.album.albumId),
            ).map { value ->
                value.toUi(
                    kind = kind,
                    mediaUrl =
                        value.mediaAsset.contentUrl
                            ?: apiClient.mediaAssetContentUrl(value.mediaAsset.mediaAssetId),
                )
            }
    }

    override suspend fun loadPrivateGrants(): AppResult<List<MediaPrivateGrantUi>> = apiClient.listOwnPrivateAlbumGrants().map { value -> value.grants.map { it.toUi() } }

    override suspend fun revokePrivateGrant(granteeUserId: String): AppResult<Unit> = apiClient.revokePrivateAlbumAccess(granteeUserId).map { value -> Unit }
}
