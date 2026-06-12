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

/**
 * Media slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers upload slots/completion,
 * album listing, private-album grants, and content-URL resolution. Signatures are unchanged.
 */
interface MediaApi {
    suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto>

    suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto,
    ): AppResult<MediaAlbumItemDto>

    suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto>

    /** Owner soft-deletes one of their assets; idempotent on the server side. */
    suspend fun deleteMediaAsset(mediaAssetId: String): AppResult<Unit>

    /**
     * Owner rewrites an album's order; [ReorderMediaAlbumRequestDto.orderedAssetIds] must be the
     * exact active set (server rejects with MEDIA_ALBUM_ORDER_MISMATCH otherwise). Index 0 is the
     * primary photo. Responds with the canonical re-sorted album listing.
     */
    suspend fun reorderMediaAlbum(
        albumKind: String,
        request: ReorderMediaAlbumRequestDto,
    ): AppResult<MediaAlbumItemsResponseDto>

    suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto>

    suspend fun listOwnPrivateAlbumGrants(): AppResult<MediaAlbumGrantsResponseDto>

    suspend fun revokePrivateAlbumAccess(granteeUserId: String): AppResult<MediaAlbumGrantDto>

    fun mediaAssetContentUrl(mediaAssetId: String): String
}
