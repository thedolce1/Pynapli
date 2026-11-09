package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MediaAlbumGrantDto
import com.pynapli.shared.network.dto.MediaAlbumGrantsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import com.pynapli.shared.network.dto.ReorderMediaAlbumRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpMethod

/** Ktor implementation of [MediaApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorMediaApi(
    private val support: KtorApiSupport,
) : MediaApi {
    override suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto> = support.authSend(HttpMethod.Post, "/v1/media/uploads", request)

    override suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto,
    ): AppResult<MediaAlbumItemDto> = support.authSend(HttpMethod.Post, "/v1/media/uploads/$mediaAssetId/complete", request)

    override suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto> = support.authGet("/v1/media/albums/$albumKind")

    override suspend fun deleteMediaAsset(mediaAssetId: String): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.delete(support.endpoint("/v1/media/assets/$mediaAssetId")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(support.json)
            }
        }

    override suspend fun reorderMediaAlbum(
        albumKind: String,
        request: ReorderMediaAlbumRequestDto,
    ): AppResult<MediaAlbumItemsResponseDto> = support.authSend(HttpMethod.Put, "/v1/media/albums/$albumKind/order", request)

    override suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto> = support.authGet("/v1/media/users/$ownerUserId/albums/private")

    override suspend fun listOwnPrivateAlbumGrants(): AppResult<MediaAlbumGrantsResponseDto> = support.authGet("/v1/media/albums/private/grants")

    override suspend fun revokePrivateAlbumAccess(granteeUserId: String): AppResult<MediaAlbumGrantDto> = support.authPost("/v1/media/albums/private/grants/$granteeUserId/revoke")

    override fun mediaAssetContentUrl(mediaAssetId: String): String = support.endpoint("/v1/media/uploads/local/$mediaAssetId")
}
