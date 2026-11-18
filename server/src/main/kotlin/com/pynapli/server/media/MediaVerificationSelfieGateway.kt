package com.pynapli.server.media

import com.pynapli.server.verification.SelfieMediaGateway
import com.pynapli.server.verification.StoredSelfie
import com.pynapli.shared.network.dto.PhotoVerificationUploadSlotDto
import java.util.UUID

/**
 * Media-module implementation of [SelfieMediaGateway]: verification selfies use the same upload signer
 * and asset rows as album photos (the existing upload path), tagged `intended_album_kind =
 * 'verification'`. They are never added to an album or approved, so only the owner (who holds the
 * upload URL) and admin reviewers (who get [reviewUrl]) can reach them.
 */
class MediaVerificationSelfieGateway(
    private val repository: MediaRepository,
    private val uploadSigner: MediaUploadSigner,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    private val objectDeleter: MediaObjectDeleter?,
) : SelfieMediaGateway {
    override suspend fun createUploadSlot(
        ownerUserId: UUID,
        contentType: String,
        byteSize: Long,
        pixelWidth: Int?,
        pixelHeight: Int?,
    ): PhotoVerificationUploadSlotDto {
        val mediaAssetId = UUID.randomUUID()
        val destination =
            uploadSigner.createUploadDestination(
                ownerUserId = ownerUserId,
                mediaAssetId = mediaAssetId,
                albumKind = VERIFICATION_MEDIA_KIND,
                contentType = contentType,
            )
        repository.createAsset(
            ownerUserId = ownerUserId,
            create =
                MediaAssetCreate(
                    mediaType = "image",
                    storageProvider = destination.storageProvider,
                    storageBucket = destination.storageBucket,
                    storageObjectKey = destination.storageObjectKey,
                    contentType = contentType,
                    byteSize = byteSize,
                    pixelWidth = pixelWidth,
                    pixelHeight = pixelHeight,
                    intendedAlbumKind = VERIFICATION_MEDIA_KIND,
                ),
            assetIdFactory = { mediaAssetId },
        )
        return PhotoVerificationUploadSlotDto(
            mediaAssetId = mediaAssetId.toString(),
            uploadUrl = destination.uploadUrl,
            uploadMethod = destination.uploadMethod,
            uploadHeaders = destination.uploadHeaders,
            expiresAt = destination.expiresAt.toString(),
        )
    }

    override fun reviewUrl(mediaAssetId: UUID): String? = mediaAssetUrlResolver?.publicContentUrl(mediaAssetId)

    override fun profilePhotoUrl(mediaAssetId: UUID): String? = mediaAssetUrlResolver?.publicContentUrl(mediaAssetId)

    override suspend fun deleteStoredObject(selfie: StoredSelfie): Boolean {
        // Without an app-side deleter (static/other providers) the object is left to bucket lifecycle
        // rules, like every other media object; the row is still removed.
        val deleter = objectDeleter ?: return true
        return deleter.delete(
            storageProvider = selfie.storageProvider,
            storageBucket = selfie.storageBucket,
            storageObjectKey = selfie.storageObjectKey,
            mediaAssetId = selfie.mediaAssetId,
        )
    }

    private companion object {
        const val VERIFICATION_MEDIA_KIND = "verification"
    }
}
