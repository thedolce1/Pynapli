package com.pynapli.app.media

import com.pynapli.shared.core.result.AppResult

interface MediaAlbumsRepository {
    suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>>

    suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload,
    ): AppResult<MediaAlbumItemUi>

    suspend fun deleteMedia(assetId: String): AppResult<Unit>

    /**
     * Rewrites the album order; [orderedAssetIds] must cover the exact active set
     * (index 0 = primary). Returns the canonical server-sorted album listing.
     */
    suspend fun reorderAlbum(
        kind: MediaAlbumKindUi,
        orderedAssetIds: List<String>,
    ): AppResult<List<MediaAlbumItemUi>>

    suspend fun loadPrivateGrants(): AppResult<List<MediaPrivateGrantUi>>

    suspend fun revokePrivateGrant(granteeUserId: String): AppResult<Unit>
}
