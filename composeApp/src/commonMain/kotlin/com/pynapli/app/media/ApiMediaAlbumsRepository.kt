package com.pynapli.app.media

import com.pynapli.shared.core.result.AppResult
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
    override suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>> =
        when (val result = apiClient.listMediaAlbum(kind.apiValue)) {
            is AppResult.Success -> AppResult.Success(result.value.items.map { item -> item.toUiWithUrl(kind) })
            is AppResult.Failure -> result
        }

    override suspend fun deleteMedia(assetId: String): AppResult<Unit> = apiClient.deleteMediaAsset(assetId)

    override suspend fun reorderAlbum(
        kind: MediaAlbumKindUi,
        orderedAssetIds: List<String>,
    ): AppResult<List<MediaAlbumItemUi>> =
        when (
            val result =
                apiClient.reorderMediaAlbum(
                    albumKind = kind.apiValue,
                    request = ReorderMediaAlbumRequestDto(orderedAssetIds = orderedAssetIds),
                )
        ) {
            is AppResult.Success -> AppResult.Success(result.value.items.map { item -> item.toUiWithUrl(kind) })
            is AppResult.Failure -> result
        }

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
            ).let { result ->
                when (result) {
                    is AppResult.Success ->
                        AppResult.Success(
                            result.value.toUi(
                                kind = kind,
                                mediaUrl =
                                    result.value.mediaAsset.contentUrl
                                        ?: apiClient.mediaAssetContentUrl(result.value.mediaAsset.mediaAssetId),
                            ),
                        )
                    is AppResult.Failure -> result
                }
            }
    }

    override suspend fun loadPrivateGrants(): AppResult<List<MediaPrivateGrantUi>> =
        when (val result = apiClient.listOwnPrivateAlbumGrants()) {
            is AppResult.Success ->
                AppResult.Success(
                    result.value.grants.map { it.toUi() },
                )
            is AppResult.Failure -> result
        }

    override suspend fun revokePrivateGrant(granteeUserId: String): AppResult<Unit> =
        when (val result = apiClient.revokePrivateAlbumAccess(granteeUserId)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }
}
