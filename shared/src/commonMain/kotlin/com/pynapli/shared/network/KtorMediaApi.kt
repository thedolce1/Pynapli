package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MediaAlbumGrantDto
import com.pynapli.shared.network.dto.MediaAlbumGrantsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [MediaApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorMediaApi(
    private val support: KtorApiSupport,
) : MediaApi {
    override suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/media/uploads")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto,
    ): AppResult<MediaAlbumItemDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/media/uploads/$mediaAssetId/complete")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/media/albums/$albumKind")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/media/users/$ownerUserId/albums/private")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun listOwnPrivateAlbumGrants(): AppResult<MediaAlbumGrantsResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/media/albums/private/grants")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun revokePrivateAlbumAccess(granteeUserId: String): AppResult<MediaAlbumGrantDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/media/albums/private/grants/$granteeUserId/revoke")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override fun mediaAssetContentUrl(mediaAssetId: String): String = support.endpoint("/v1/media/uploads/local/$mediaAssetId")
}
