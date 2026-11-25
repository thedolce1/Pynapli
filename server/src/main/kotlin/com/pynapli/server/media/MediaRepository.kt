package com.pynapli.server.media

import java.time.Instant
import java.util.UUID

interface MediaRepository {
    suspend fun ensureAlbum(
        ownerUserId: UUID,
        kind: String,
        title: String,
        albumIdFactory: () -> UUID = UUID::randomUUID,
    ): MediaAlbumRecord

    suspend fun createAsset(
        ownerUserId: UUID,
        create: MediaAssetCreate,
        assetIdFactory: () -> UUID = UUID::randomUUID,
    ): MediaAssetRecord

    suspend fun markAssetUploaded(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord?

    /** Loads a chat-photo asset (intended_album_kind='message') owned by [ownerUserId], or null. */
    suspend fun findMessageMediaAsset(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord?

    suspend fun markAssetReadyApproved(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord?

    suspend fun markAssetModerationRejected(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord?

    /**
     * Soft-deletes an asset the caller owns (sets `deleted_at`, mirrors the account-deletion
     * pattern) and marks its album items removed in the same transaction. Returns the asset
     * record (already-deleted assets are returned unchanged, so retries are idempotent) or
     * null when the asset does not exist or is not owned by [ownerUserId].
     */
    suspend fun softDeleteAsset(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord?

    /**
     * Rewrites `sort_order` to 0..n-1 following [orderedAssetIds] in one transaction.
     * Returns the reordered active items, or null when [orderedAssetIds] is not exactly
     * the set of the album's active (non-removed, non-deleted) items.
     */
    suspend fun reorderAlbumItems(
        ownerUserId: UUID,
        albumId: UUID,
        orderedAssetIds: List<UUID>,
    ): List<MediaAlbumItemRecord>?

    /** Counts active (non-removed) album items whose backing asset is not soft-deleted. */
    suspend fun countActiveAlbumItems(
        ownerUserId: UUID,
        albumId: UUID,
        excludingMediaAssetId: UUID? = null,
    ): Int

    /** Counts open upload slots (pending_upload, non-deleted) opened for [intendedAlbumKind]. */
    suspend fun countPendingUploadAssets(
        ownerUserId: UUID,
        intendedAlbumKind: String,
    ): Int

    suspend fun findAlbum(
        ownerUserId: UUID,
        albumId: UUID,
    ): MediaAlbumRecord?

    suspend fun addAssetToAlbum(
        ownerUserId: UUID,
        albumId: UUID,
        mediaAssetId: UUID,
        sortOrder: Int,
    ): MediaAlbumItemRecord?

    /**
     * Upload-complete, once per asset, atomically: the album kind must match the kind the upload
     * slot was opened for, an asset already in another album is refused, a repeat into the same
     * album returns the existing item, and the item insert and pending_upload -> uploaded change
     * commit together.
     */
    suspend fun completeAlbumUpload(
        ownerUserId: UUID,
        albumId: UUID,
        mediaAssetId: UUID,
        sortOrder: Int,
    ): AlbumUploadCompletion

    suspend fun requestAlbumAccess(
        ownerUserId: UUID,
        requesterUserId: UUID,
        requestIdFactory: () -> UUID = UUID::randomUUID,
    ): MediaAlbumAccessRequestRecord?

    suspend fun decideAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean,
        decidedByUserId: UUID = ownerUserId,
    ): MediaAlbumAccessRequestRecord?

    suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        albumId: UUID,
    ): List<MediaAlbumItemRecord>?

    suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        ownerUserId: UUID,
        kind: String,
    ): Pair<MediaAlbumRecord, List<MediaAlbumItemRecord>>?

    suspend fun grantAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        grantedByUserId: UUID = ownerUserId,
    ): MediaAlbumAccessGrantRecord?

    suspend fun revokeAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        revokedByUserId: UUID = ownerUserId,
    ): MediaAlbumAccessGrantRecord?

    suspend fun listPrivateAlbumGrantsForOwner(
        ownerUserId: UUID,
    ): List<MediaAlbumGrantSummaryRecord>

    suspend fun hasAlbumAccess(
        viewerUserId: UUID,
        albumId: UUID,
    ): Boolean
}

data class MediaAssetCreate(
    val mediaType: String,
    val storageProvider: String,
    val storageBucket: String,
    val storageObjectKey: String,
    val contentType: String,
    val byteSize: Long,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    /** Album kind this upload slot was opened for; used to count open slots against album caps. */
    val intendedAlbumKind: String? = null,
)

/** Outcome of [MediaRepository.completeAlbumUpload]. */
sealed interface AlbumUploadCompletion {
    /** First completion: the item exists and the asset is now `uploaded`; moderation runs next. */
    data class Attached(
        val item: MediaAlbumItemRecord,
    ) : AlbumUploadCompletion

    /** A retried completion into the same album: the existing item, nothing changed. */
    data class AlreadyCompleted(
        val item: MediaAlbumItemRecord,
    ) : AlbumUploadCompletion

    /** Refused with a media error code (not found, album mismatch, already completed elsewhere). */
    data class Refused(
        val code: String,
    ) : AlbumUploadCompletion
}

data class MediaAssetRecord(
    val mediaAssetId: UUID,
    val ownerUserId: UUID,
    val mediaType: String,
    val storageProvider: String,
    val storageBucket: String,
    val storageObjectKey: String,
    val contentType: String,
    val byteSize: Long,
    val pixelWidth: Int?,
    val pixelHeight: Int?,
    val moderationStatus: String,
    val processingStatus: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

data class MediaAlbumRecord(
    val albumId: UUID,
    val ownerUserId: UUID,
    val kind: String,
    val title: String,
    val visibilityStatus: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

data class MediaAlbumItemRecord(
    val albumId: UUID,
    val mediaAsset: MediaAssetRecord,
    val sortOrder: Int,
    val addedAt: Instant,
    val removedAt: Instant?,
)

data class MediaAlbumAccessRequestRecord(
    val requestId: UUID,
    val albumId: UUID,
    val ownerUserId: UUID,
    val requesterUserId: UUID,
    val status: String,
    val requestedAt: Instant,
    val decidedAt: Instant?,
    val decidedByUserId: UUID?,
)

data class MediaAlbumAccessGrantRecord(
    val albumId: UUID,
    val ownerUserId: UUID,
    val granteeUserId: UUID,
    val grantedByUserId: UUID,
    val grantedAt: Instant,
    val revokedAt: Instant?,
    val revokedByUserId: UUID?,
)

data class MediaAlbumGrantSummaryRecord(
    val albumId: UUID,
    val ownerUserId: UUID,
    val granteeUserId: UUID,
    val granteeDisplayName: String?,
    val grantedAt: Instant,
)
