package com.pynapli.server.media

import com.pynapli.server.safety.BlockRepository
import com.pynapli.shared.network.dto.MediaAlbumGrantDto
import com.pynapli.shared.network.dto.MediaAlbumGrantsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import java.util.UUID

internal class MediaPrivateAlbumAccessActions(
    private val repository: MediaRepository,
    private val blockRepository: BlockRepository?,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    suspend fun listPrivateAlbumForViewer(
        viewerUserId: UUID,
        ownerUserIdText: String,
    ): MediaAlbumItemsResponseDto {
        val ownerUserId = ownerUserIdText.toMediaUuidOrThrow()
        if (blockRepository?.isBlockedEitherWay(viewerUserId, ownerUserId) == true) {
            throw MediaException("MEDIA_ALBUM_NOT_FOUND")
        }
        if (contactBlockVisibility?.isHiddenFrom(viewerUserId, ownerUserId) == true) {
            throw MediaException("MEDIA_ALBUM_NOT_FOUND")
        }
        val result =
            repository.listAlbumItemsForViewer(
                viewerUserId = viewerUserId,
                ownerUserId = ownerUserId,
                kind = "private",
            ) ?: throw MediaException("MEDIA_ALBUM_NOT_FOUND")

        val (album, items) = result
        return MediaAlbumItemsResponseDto(
            album = album.toDto(),
            items = items.map { item -> item.toDto(mediaAssetUrlResolver) },
        )
    }

    suspend fun requestPrivateAlbumAccess(
        requesterUserId: UUID,
        ownerUserId: UUID,
    ): Boolean {
        if (requesterUserId == ownerUserId) throw MediaException("INVALID_ALBUM_REQUEST_TARGET")
        if (blockRepository?.isBlockedEitherWay(requesterUserId, ownerUserId) == true) {
            throw MediaException("INVALID_ALBUM_REQUEST_TARGET")
        }
        if (contactBlockVisibility?.isHiddenFrom(requesterUserId, ownerUserId) == true) {
            throw MediaException("INVALID_ALBUM_REQUEST_TARGET")
        }
        return repository.requestAlbumAccess(
            ownerUserId = ownerUserId,
            requesterUserId = requesterUserId,
        ) != null
    }

    suspend fun decidePrivateAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean,
    ): String {
        val request =
            repository.decideAlbumAccessRequest(
                ownerUserId = ownerUserId,
                requesterUserId = requesterUserId,
                approved = approved,
                decidedByUserId = ownerUserId,
            ) ?: throw MediaException("ALBUM_REQUEST_NOT_FOUND")
        return request.status
    }

    suspend fun listOwnPrivateAlbumGrants(
        ownerUserId: UUID,
    ): MediaAlbumGrantsResponseDto {
        val grants = repository.listPrivateAlbumGrantsForOwner(ownerUserId)
        return MediaAlbumGrantsResponseDto(
            grants =
                grants.map { record ->
                    MediaAlbumGrantDto(
                        albumId = record.albumId.toString(),
                        granteeUserId = record.granteeUserId.toString(),
                        granteeDisplayName = record.granteeDisplayName,
                        grantedAt = record.grantedAt.toString(),
                        revokedAt = null,
                    )
                },
        )
    }

    suspend fun revokePrivateAlbumAccess(
        ownerUserId: UUID,
        granteeUserIdText: String,
    ): MediaAlbumGrantDto {
        val granteeUserId = granteeUserIdText.toMediaUuidOrThrow()
        if (granteeUserId == ownerUserId) throw MediaException("MEDIA_GRANT_NOT_FOUND")

        val album =
            repository.ensureAlbum(
                ownerUserId = ownerUserId,
                kind = "private",
                title = "Private album",
            )
        val revoked =
            repository.revokeAlbumAccess(
                ownerUserId = ownerUserId,
                albumId = album.albumId,
                granteeUserId = granteeUserId,
                revokedByUserId = ownerUserId,
            ) ?: throw MediaException("MEDIA_GRANT_NOT_FOUND")

        return MediaAlbumGrantDto(
            albumId = revoked.albumId.toString(),
            granteeUserId = revoked.granteeUserId.toString(),
            granteeDisplayName = null,
            grantedAt = revoked.grantedAt.toString(),
            revokedAt = revoked.revokedAt?.toString(),
        )
    }
}
