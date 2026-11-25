package com.pynapli.server.media

import java.util.UUID
import javax.sql.DataSource

class JdbcMediaRepository(
    private val dataSource: DataSource,
) : MediaRepository {
    override suspend fun ensureAlbum(
        ownerUserId: UUID,
        kind: String,
        title: String,
        albumIdFactory: () -> UUID,
    ): MediaAlbumRecord = dataSource.ensureMediaAlbum(ownerUserId, kind, title, albumIdFactory)

    override suspend fun createAsset(
        ownerUserId: UUID,
        create: MediaAssetCreate,
        assetIdFactory: () -> UUID,
    ): MediaAssetRecord = dataSource.createMediaAsset(ownerUserId, create, assetIdFactory)

    override suspend fun markAssetUploaded(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? = dataSource.markMediaAssetUploaded(ownerUserId, mediaAssetId)

    override suspend fun findMessageMediaAsset(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? = dataSource.findMessageMediaAsset(ownerUserId, mediaAssetId)

    override suspend fun markAssetReadyApproved(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? = dataSource.markMediaAssetReadyApproved(ownerUserId, mediaAssetId)

    override suspend fun markAssetModerationRejected(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? = dataSource.markMediaAssetModerationRejected(ownerUserId, mediaAssetId)

    override suspend fun softDeleteAsset(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? = dataSource.softDeleteMediaAsset(ownerUserId, mediaAssetId)

    override suspend fun reorderAlbumItems(
        ownerUserId: UUID,
        albumId: UUID,
        orderedAssetIds: List<UUID>,
    ): List<MediaAlbumItemRecord>? = dataSource.reorderMediaAlbumItems(ownerUserId, albumId, orderedAssetIds)

    override suspend fun countActiveAlbumItems(
        ownerUserId: UUID,
        albumId: UUID,
        excludingMediaAssetId: UUID?,
    ): Int = dataSource.countActiveMediaAlbumItems(ownerUserId, albumId, excludingMediaAssetId)

    override suspend fun countPendingUploadAssets(
        ownerUserId: UUID,
        intendedAlbumKind: String,
    ): Int = dataSource.countPendingMediaUploadAssets(ownerUserId, intendedAlbumKind)

    override suspend fun findAlbum(
        ownerUserId: UUID,
        albumId: UUID,
    ): MediaAlbumRecord? = dataSource.findMediaAlbum(ownerUserId, albumId)

    override suspend fun addAssetToAlbum(
        ownerUserId: UUID,
        albumId: UUID,
        mediaAssetId: UUID,
        sortOrder: Int,
    ): MediaAlbumItemRecord? = dataSource.addMediaAssetToAlbum(ownerUserId, albumId, mediaAssetId, sortOrder)

    override suspend fun completeAlbumUpload(
        ownerUserId: UUID,
        albumId: UUID,
        mediaAssetId: UUID,
        sortOrder: Int,
    ): AlbumUploadCompletion = dataSource.completeMediaAlbumUpload(ownerUserId, albumId, mediaAssetId, sortOrder)

    override suspend fun markModerationScanned(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ) = dataSource.markMediaModerationScanned(ownerUserId, mediaAssetId)

    override suspend fun requestAlbumAccess(
        ownerUserId: UUID,
        requesterUserId: UUID,
        requestIdFactory: () -> UUID,
    ): MediaAlbumAccessRequestRecord? = dataSource.requestMediaAlbumAccess(ownerUserId, requesterUserId, requestIdFactory)

    override suspend fun decideAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean,
        decidedByUserId: UUID,
    ): MediaAlbumAccessRequestRecord? = dataSource.decideMediaAlbumAccessRequest(ownerUserId, requesterUserId, approved, decidedByUserId)

    override suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        albumId: UUID,
    ): List<MediaAlbumItemRecord>? = dataSource.listMediaAlbumItemsForViewer(viewerUserId, albumId)

    override suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        ownerUserId: UUID,
        kind: String,
    ): Pair<MediaAlbumRecord, List<MediaAlbumItemRecord>>? = dataSource.listMediaAlbumItemsForViewer(viewerUserId, ownerUserId, kind)

    override suspend fun grantAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        grantedByUserId: UUID,
    ): MediaAlbumAccessGrantRecord? = dataSource.grantMediaAlbumAccess(ownerUserId, albumId, granteeUserId, grantedByUserId)

    override suspend fun revokeAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        revokedByUserId: UUID,
    ): MediaAlbumAccessGrantRecord? = dataSource.revokeMediaAlbumAccess(ownerUserId, albumId, granteeUserId, revokedByUserId)

    override suspend fun listPrivateAlbumGrantsForOwner(
        ownerUserId: UUID,
    ): List<MediaAlbumGrantSummaryRecord> = dataSource.listPrivateMediaAlbumGrantsForOwner(ownerUserId)

    override suspend fun hasAlbumAccess(
        viewerUserId: UUID,
        albumId: UUID,
    ): Boolean = dataSource.hasMediaAlbumAccess(viewerUserId, albumId)
}
