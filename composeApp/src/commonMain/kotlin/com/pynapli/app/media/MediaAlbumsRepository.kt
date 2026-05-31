package com.pynapli.app.media

import com.pynapli.shared.core.result.AppResult

interface MediaAlbumsRepository {
    suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>>

    suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload,
    ): AppResult<MediaAlbumItemUi>

    suspend fun loadPrivateGrants(): AppResult<List<MediaPrivateGrantUi>>

    suspend fun revokePrivateGrant(granteeUserId: String): AppResult<Unit>
}
