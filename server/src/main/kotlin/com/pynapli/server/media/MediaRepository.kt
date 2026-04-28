package com.pynapli.server.media

import java.time.Instant
import java.util.UUID

interface MediaRepository {
    suspend fun ensureAlbum(
        ownerUserId: UUID,
        kind: String,
        title: String,
        albumIdFactory: () -> UUID = UUID::randomUUID
    ): MediaAlbumRecord

    suspend fun createAsset(
        ownerUserId: UUID,
        create: MediaAssetCreate,
        assetIdFactory: () -> UUID = UUID::randomUUID
    ): MediaAssetRecord

    suspend fun markAssetUploaded(
        ownerUserId: UUID,
        mediaAssetId: UUID
    ): MediaAssetRecord?

    suspend fun markAssetReadyApproved(
        ownerUserId: UUID,
        mediaAssetId: UUID
    ): MediaAssetRecord?

    suspend fun addAssetToAlbum(
        ownerUserId: UUID,
        albumId: UUID,
        mediaAssetId: UUID,
        sortOrder: Int
    ): MediaAlbumItemRecord?

    suspend fun requestAlbumAccess(
        ownerUserId: UUID,
        requesterUserId: UUID,
        requestIdFactory: () -> UUID = UUID::randomUUID
    ): MediaAlbumAccessRequestRecord?

    suspend fun decideAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean,
        decidedByUserId: UUID = ownerUserId
    ): MediaAlbumAccessRequestRecord?

    suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        albumId: UUID
    ): List<MediaAlbumItemRecord>?

    suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        ownerUserId: UUID,
        kind: String
    ): Pair<MediaAlbumRecord, List<MediaAlbumItemRecord>>?

    suspend fun grantAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        grantedByUserId: UUID = ownerUserId
    ): MediaAlbumAccessGrantRecord?

    suspend fun revokeAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        revokedByUserId: UUID = ownerUserId
    ): MediaAlbumAccessGrantRecord?

    suspend fun listPrivateAlbumGrantsForOwner(
        ownerUserId: UUID
    ): List<MediaAlbumGrantSummaryRecord>

    suspend fun hasAlbumAccess(
        viewerUserId: UUID,
        albumId: UUID
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
    val pixelHeight: Int? = null
)

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
    val deletedAt: Instant?
)

data class MediaAlbumRecord(
    val albumId: UUID,
    val ownerUserId: UUID,
    val kind: String,
    val title: String,
    val visibilityStatus: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?
)

data class MediaAlbumItemRecord(
    val albumId: UUID,
    val mediaAsset: MediaAssetRecord,
    val sortOrder: Int,
    val addedAt: Instant,
    val removedAt: Instant?
)

data class MediaAlbumAccessRequestRecord(
    val requestId: UUID,
    val albumId: UUID,
    val ownerUserId: UUID,
    val requesterUserId: UUID,
    val status: String,
    val requestedAt: Instant,
    val decidedAt: Instant?,
    val decidedByUserId: UUID?
)

data class MediaAlbumAccessGrantRecord(
    val albumId: UUID,
    val ownerUserId: UUID,
    val granteeUserId: UUID,
    val grantedByUserId: UUID,
    val grantedAt: Instant,
    val revokedAt: Instant?,
    val revokedByUserId: UUID?
)

data class MediaAlbumGrantSummaryRecord(
    val albumId: UUID,
    val ownerUserId: UUID,
    val granteeUserId: UUID,
    val granteeDisplayName: String?,
    val grantedAt: Instant
)
