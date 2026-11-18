package com.pynapli.server.verification

import com.pynapli.shared.network.dto.PhotoVerificationUploadSlotDto
import java.util.UUID

/** Where a verification selfie is stored. Never logged: the object key identifies a person's face. */
data class StoredSelfie(
    val mediaAssetId: UUID,
    val storageProvider: String,
    val storageBucket: String,
    val storageObjectKey: String,
    val contentType: String,
)

/**
 * Media-side operations for verification selfies (implemented in the media module, like chat photos'
 * `MessageMediaGateway`). A selfie is a one-off image asset tagged `intended_album_kind =
 * 'verification'`: never added to an album and never made ready/approved, so it can't surface on a
 * profile, in an album, on Tonight or in chat.
 */
interface SelfieMediaGateway {
    /** Opens a signed upload slot for the owner's selfie (the same signer as album photos). */
    suspend fun createUploadSlot(
        ownerUserId: UUID,
        contentType: String,
        byteSize: Long,
        pixelWidth: Int?,
        pixelHeight: Int?,
    ): PhotoVerificationUploadSlotDto

    /** A short-lived signed URL for an admin reviewer. Callers gate who receives it. */
    fun reviewUrl(mediaAssetId: UUID): String?

    /** Signed URL of one of the owner's approved profile photos, for side-by-side review. */
    fun profilePhotoUrl(mediaAssetId: UUID): String?

    /**
     * Deletes the stored object. True when it is gone (or the storage has no app-side delete and
     * relies on bucket lifecycle rules); false when a delete was attempted and failed, so the
     * retention sweep keeps the row and tries again next run.
     */
    suspend fun deleteStoredObject(selfie: StoredSelfie): Boolean
}

/**
 * Counts faces in a selfie (Cloud Vision face detection when configured). Null means the check could
 * not run; the submission then simply waits for a human. Detection only: there is no automatic face
 * matching against profile photos, and approval always needs a reviewer.
 */
fun interface SelfieFaceDetector {
    suspend fun countFaces(selfie: StoredSelfie): Int?
}
