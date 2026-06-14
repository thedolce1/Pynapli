package com.pynapli.server.media

import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MediaAlbumGrantDto
import com.pynapli.shared.network.dto.MediaAlbumGrantsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import com.pynapli.shared.network.dto.ReorderMediaAlbumRequestDto
import java.util.UUID

class MediaService(
    repository: MediaRepository,
    uploadSigner: MediaUploadSigner,
    blockRepository: BlockRepository? = null,
    mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    moderationScanner: MediaModerationScanner = ManualReviewMediaModerationScanner(),
    moderationEscalationPublisher: MediaModerationEscalationPublisher = NoOpMediaModerationEscalationPublisher,
    rateLimitService: RateLimitService? = null,
) {
    private val uploadActions =
        MediaUploadActions(
            repository = repository,
            uploadSigner = uploadSigner,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            moderationScanner = moderationScanner,
            moderationEscalationPublisher = moderationEscalationPublisher,
            rateLimitService = rateLimitService,
        )
    private val ownAlbumActions =
        MediaOwnAlbumActions(
            repository = repository,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
        )
    private val privateAlbumAccessActions =
        MediaPrivateAlbumAccessActions(
            repository = repository,
            blockRepository = blockRepository,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
        )

    suspend fun createUploadSlot(
        ownerUserId: UUID,
        request: CreateMediaUploadRequestDto,
    ): MediaUploadSlotDto =
        uploadActions.createUploadSlot(
            ownerUserId = ownerUserId,
            request = request,
        )

    suspend fun completeUpload(
        ownerUserId: UUID,
        mediaAssetIdText: String,
        request: CompleteMediaUploadRequestDto,
    ): MediaAlbumItemDto =
        uploadActions.completeUpload(
            ownerUserId = ownerUserId,
            mediaAssetIdText = mediaAssetIdText,
            request = request,
        )

    suspend fun listOwnAlbum(
        ownerUserId: UUID,
        albumKindText: String,
    ): MediaAlbumItemsResponseDto =
        ownAlbumActions.listOwnAlbum(
            ownerUserId = ownerUserId,
            albumKindText = albumKindText,
        )

    suspend fun deleteAsset(
        ownerUserId: UUID,
        mediaAssetIdText: String,
    ) {
        ownAlbumActions.deleteAsset(
            ownerUserId = ownerUserId,
            mediaAssetIdText = mediaAssetIdText,
        )
    }

    suspend fun reorderAlbum(
        ownerUserId: UUID,
        albumKindText: String,
        request: ReorderMediaAlbumRequestDto,
    ): MediaAlbumItemsResponseDto =
        ownAlbumActions.reorderAlbum(
            ownerUserId = ownerUserId,
            albumKindText = albumKindText,
            request = request,
        )

    suspend fun listPrivateAlbumForViewer(
        viewerUserId: UUID,
        ownerUserIdText: String,
    ): MediaAlbumItemsResponseDto =
        privateAlbumAccessActions.listPrivateAlbumForViewer(
            viewerUserId = viewerUserId,
            ownerUserIdText = ownerUserIdText,
        )

    suspend fun requestPrivateAlbumAccess(
        requesterUserId: UUID,
        ownerUserId: UUID,
    ): Boolean =
        privateAlbumAccessActions.requestPrivateAlbumAccess(
            requesterUserId = requesterUserId,
            ownerUserId = ownerUserId,
        )

    suspend fun decidePrivateAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean,
    ): String =
        privateAlbumAccessActions.decidePrivateAlbumAccessRequest(
            ownerUserId = ownerUserId,
            requesterUserId = requesterUserId,
            approved = approved,
        )

    suspend fun listOwnPrivateAlbumGrants(
        ownerUserId: UUID,
    ): MediaAlbumGrantsResponseDto = privateAlbumAccessActions.listOwnPrivateAlbumGrants(ownerUserId)

    suspend fun revokePrivateAlbumAccess(
        ownerUserId: UUID,
        granteeUserIdText: String,
    ): MediaAlbumGrantDto =
        privateAlbumAccessActions.revokePrivateAlbumAccess(
            ownerUserId = ownerUserId,
            granteeUserIdText = granteeUserIdText,
        )

    companion object {
        // Founder-adjustable album caps; counted as active items plus open upload slots.
        const val PROFILE_ALBUM_PHOTO_CAP = MEDIA_PROFILE_ALBUM_PHOTO_CAP
        const val PRIVATE_ALBUM_PHOTO_CAP = MEDIA_PRIVATE_ALBUM_PHOTO_CAP
    }
}
