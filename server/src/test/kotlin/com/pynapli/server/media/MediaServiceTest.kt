package com.pynapli.server.media

import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MEDIA_IMAGE_MAX_BYTES
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaServiceTest {
    @Test
    fun createUploadSlotCreatesAlbumAssetAndDestination() = runBlocking {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val repository = InMemoryMediaRepository()
        val service = MediaService(repository, FixedMediaUploadSigner())

        val slot = service.createUploadSlot(
            ownerId,
            CreateMediaUploadRequestDto(
                albumKind = "private",
                mediaType = "image",
                contentType = "image/jpeg",
                byteSize = 1_024,
                pixelWidth = 800,
                pixelHeight = 600
            )
        )

        assertEquals("private", slot.album.kind)
        assertEquals("image", slot.mediaAsset.mediaType)
        assertEquals("pending_upload", slot.mediaAsset.processingStatus)
        assertEquals("PUT", slot.uploadMethod)
        assertEquals("image/jpeg", slot.uploadHeaders["Content-Type"])
        assertEquals("users/$ownerId/private/${slot.mediaAsset.mediaAssetId}.jpg", slot.storageObjectKey)
    }

    @Test
    fun createUploadSlotRejectsOversizedImages() = runBlocking {
        val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())

        val error = assertFailsWith<MediaException> {
            service.createUploadSlot(
                UUID.randomUUID(),
                CreateMediaUploadRequestDto(
                    albumKind = "private",
                    mediaType = "image",
                    contentType = "image/jpeg",
                    byteSize = MEDIA_IMAGE_MAX_BYTES + 1
                )
            )
        }

        assertEquals("MEDIA_FILE_TOO_LARGE", error.code)
    }

    @Test
    fun createUploadSlotRejectsUnsupportedContentType() = runBlocking {
        val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())

        val error = assertFailsWith<MediaException> {
            service.createUploadSlot(
                UUID.randomUUID(),
                CreateMediaUploadRequestDto(
                    albumKind = "private",
                    mediaType = "image",
                    contentType = "image/gif",
                    byteSize = 1_024
                )
            )
        }

        assertEquals("INVALID_MEDIA_CONTENT_TYPE", error.code)
    }

    @Test
    fun completeUploadMarksAssetUploadedAndAttachesToAlbum() = runBlocking {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemoryMediaRepository()
        val service = MediaService(repository, FixedMediaUploadSigner())
        val slot = service.createUploadSlot(
            ownerId,
            CreateMediaUploadRequestDto(
                albumKind = "profile",
                mediaType = "image",
                contentType = "image/webp",
                byteSize = 2_048
            )
        )

        val item = service.completeUpload(
            ownerId,
            slot.mediaAsset.mediaAssetId,
            CompleteMediaUploadRequestDto(albumId = slot.album.albumId, sortOrder = 3)
        )

        assertEquals(slot.album.albumId, item.albumId)
        assertEquals("uploaded", item.mediaAsset.processingStatus)
        assertEquals(3, item.sortOrder)
    }

    @Test
    fun completeUploadCanAutoApproveInLocalDevelopment() = runBlocking {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000009")
        val repository = InMemoryMediaRepository()
        val service = MediaService(
            repository = repository,
            uploadSigner = FixedMediaUploadSigner(),
            devAutoApproveUploads = true
        )
        val slot = service.createUploadSlot(
            ownerId,
            CreateMediaUploadRequestDto(
                albumKind = "profile",
                mediaType = "image",
                contentType = "image/jpeg",
                byteSize = 2_048
            )
        )

        val item = service.completeUpload(
            ownerId,
            slot.mediaAsset.mediaAssetId,
            CompleteMediaUploadRequestDto(albumId = slot.album.albumId)
        )

        assertEquals("approved", item.mediaAsset.moderationStatus)
        assertEquals("ready", item.mediaAsset.processingStatus)
    }

    @Test
    fun listOwnAlbumReturnsUploadedItemsNewestSortOrder() = runBlocking {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val repository = InMemoryMediaRepository()
        val service = MediaService(repository, FixedMediaUploadSigner())
        val slot = service.createUploadSlot(
            ownerId,
            CreateMediaUploadRequestDto(
                albumKind = "profile",
                mediaType = "image",
                contentType = "image/jpeg",
                byteSize = 2_048
            )
        )
        service.completeUpload(
            ownerId,
            slot.mediaAsset.mediaAssetId,
            CompleteMediaUploadRequestDto(albumId = slot.album.albumId, sortOrder = 2)
        )

        val album = service.listOwnAlbum(ownerId, "profile")

        assertEquals(slot.album.albumId, album.album.albumId)
        assertEquals("profile", album.album.kind)
        assertEquals(1, album.items.size)
        assertEquals(slot.mediaAsset.mediaAssetId, album.items.single().mediaAsset.mediaAssetId)
        assertEquals("uploaded", album.items.single().mediaAsset.processingStatus)
        assertEquals(2, album.items.single().sortOrder)
    }

    @Test
    fun completeUploadRejectsAlbumsOwnedBySomeoneElse() = runBlocking {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val otherUserId = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())
        val slot = service.createUploadSlot(
            ownerId,
            CreateMediaUploadRequestDto(
                albumKind = "private",
                mediaType = "image",
                contentType = "image/png",
                byteSize = 2_048
            )
        )

        val error = assertFailsWith<MediaException> {
            service.completeUpload(
                otherUserId,
                slot.mediaAsset.mediaAssetId,
                CompleteMediaUploadRequestDto(albumId = slot.album.albumId)
            )
        }

        assertEquals("MEDIA_ALBUM_NOT_FOUND", error.code)
    }
}

private class FixedMediaUploadSigner : MediaUploadSigner {
    override fun createUploadDestination(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        contentType: String
    ): MediaUploadDestination {
        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "bin"
        }
        val objectKey = "users/$ownerUserId/$albumKind/$mediaAssetId.$extension"
        return MediaUploadDestination(
            storageProvider = "gcs",
            storageBucket = "pynapli-test-media",
            storageObjectKey = objectKey,
            uploadUrl = "https://storage.example/$objectKey",
            uploadMethod = "PUT",
            uploadHeaders = mapOf("Content-Type" to contentType),
            expiresAt = Instant.parse("2026-04-24T18:10:00Z")
        )
    }
}

private class InMemoryMediaRepository : MediaRepository {
    private val albums = mutableMapOf<UUID, MediaAlbumRecord>()
    private val assets = mutableMapOf<UUID, MediaAssetRecord>()
    private val items = mutableMapOf<Pair<UUID, UUID>, MediaAlbumItemRecord>()
    private val accessRequests = mutableMapOf<Pair<UUID, UUID>, MediaAlbumAccessRequestRecord>()
    private val grants = mutableMapOf<Pair<UUID, UUID>, MediaAlbumAccessGrantRecord>()
    private var albumIndex = 1
    private val now = Instant.parse("2026-04-24T18:00:00Z")

    override suspend fun ensureAlbum(
        ownerUserId: UUID,
        kind: String,
        title: String,
        albumIdFactory: () -> UUID
    ): MediaAlbumRecord =
        albums.values.firstOrNull { album -> album.ownerUserId == ownerUserId && album.kind == kind && album.deletedAt == null }
            ?: MediaAlbumRecord(
                albumId = UUID.fromString("10000000-0000-0000-0000-${albumIndex++.toString().padStart(12, '0')}"),
                ownerUserId = ownerUserId,
                kind = kind,
                title = title,
                visibilityStatus = "visible",
                createdAt = now,
                updatedAt = now,
                deletedAt = null
            ).also { album -> albums[album.albumId] = album }

    override suspend fun createAsset(
        ownerUserId: UUID,
        create: MediaAssetCreate,
        assetIdFactory: () -> UUID
    ): MediaAssetRecord =
        MediaAssetRecord(
            mediaAssetId = assetIdFactory(),
            ownerUserId = ownerUserId,
            mediaType = create.mediaType,
            storageProvider = create.storageProvider,
            storageBucket = create.storageBucket,
            storageObjectKey = create.storageObjectKey,
            contentType = create.contentType,
            byteSize = create.byteSize,
            pixelWidth = create.pixelWidth,
            pixelHeight = create.pixelHeight,
            moderationStatus = "pending",
            processingStatus = "pending_upload",
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        ).also { asset -> assets[asset.mediaAssetId] = asset }

    override suspend fun markAssetUploaded(ownerUserId: UUID, mediaAssetId: UUID): MediaAssetRecord? {
        val asset = assets[mediaAssetId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        val uploaded = asset.copy(processingStatus = "uploaded")
        assets[mediaAssetId] = uploaded
        return uploaded
    }

    override suspend fun markAssetReadyApproved(ownerUserId: UUID, mediaAssetId: UUID): MediaAssetRecord? {
        val asset = assets[mediaAssetId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        val ready = asset.copy(
            moderationStatus = "approved",
            processingStatus = "ready"
        )
        assets[mediaAssetId] = ready
        return ready
    }

    override suspend fun addAssetToAlbum(
        ownerUserId: UUID,
        albumId: UUID,
        mediaAssetId: UUID,
        sortOrder: Int
    ): MediaAlbumItemRecord? {
        val album = albums[albumId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        val asset = assets[mediaAssetId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        return MediaAlbumItemRecord(
            albumId = album.albumId,
            mediaAsset = asset,
            sortOrder = sortOrder,
            addedAt = now,
            removedAt = null
        ).also { item -> items[albumId to mediaAssetId] = item }
    }

    override suspend fun listAlbumItemsForViewer(viewerUserId: UUID, albumId: UUID): List<MediaAlbumItemRecord>? =
        if (hasAlbumAccess(viewerUserId, albumId)) {
            items.values.filter { item -> item.albumId == albumId && item.removedAt == null }
        } else {
            null
        }

    override suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        ownerUserId: UUID,
        kind: String
    ): Pair<MediaAlbumRecord, List<MediaAlbumItemRecord>>? {
        val album = albums.values.firstOrNull { album ->
            album.ownerUserId == ownerUserId && album.kind == kind && album.deletedAt == null
        } ?: return null
        val albumItems = listAlbumItemsForViewer(viewerUserId, album.albumId) ?: return null
        return album to albumItems
    }

    override suspend fun requestAlbumAccess(
        ownerUserId: UUID,
        requesterUserId: UUID,
        requestIdFactory: () -> UUID
    ): MediaAlbumAccessRequestRecord? {
        if (ownerUserId == requesterUserId) return null
        val album = ensureAlbum(ownerUserId, "private", "Private album")
        return accessRequests[ownerUserId to requesterUserId]?.takeIf { it.status == "pending" }
            ?: MediaAlbumAccessRequestRecord(
                requestId = requestIdFactory(),
                albumId = album.albumId,
                ownerUserId = ownerUserId,
                requesterUserId = requesterUserId,
                status = "pending",
                requestedAt = now,
                decidedAt = null,
                decidedByUserId = null
            ).also { request -> accessRequests[ownerUserId to requesterUserId] = request }
    }

    override suspend fun decideAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean,
        decidedByUserId: UUID
    ): MediaAlbumAccessRequestRecord? {
        val request = accessRequests[ownerUserId to requesterUserId]?.takeIf { it.status == "pending" } ?: return null
        val decided = request.copy(
            status = if (approved) "approved" else "denied",
            decidedAt = now,
            decidedByUserId = decidedByUserId
        )
        accessRequests[ownerUserId to requesterUserId] = decided
        if (approved) {
            grantAlbumAccess(
                ownerUserId = ownerUserId,
                albumId = request.albumId,
                granteeUserId = requesterUserId,
                grantedByUserId = decidedByUserId
            )
        }
        return decided
    }

    override suspend fun grantAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        grantedByUserId: UUID
    ): MediaAlbumAccessGrantRecord? =
        albums[albumId]?.takeIf { it.ownerUserId == ownerUserId && it.kind == "private" }?.let {
            MediaAlbumAccessGrantRecord(
                albumId = albumId,
                ownerUserId = ownerUserId,
                granteeUserId = granteeUserId,
                grantedByUserId = grantedByUserId,
                grantedAt = now,
                revokedAt = null,
                revokedByUserId = null
            ).also { grant -> grants[albumId to granteeUserId] = grant }
        }

    override suspend fun revokeAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        revokedByUserId: UUID
    ): MediaAlbumAccessGrantRecord? {
        val grant = grants[albumId to granteeUserId]?.takeIf {
            it.ownerUserId == ownerUserId && it.revokedAt == null
        } ?: return null
        val revoked = grant.copy(revokedAt = now, revokedByUserId = revokedByUserId)
        grants[albumId to granteeUserId] = revoked
        return revoked
    }

    override suspend fun hasAlbumAccess(viewerUserId: UUID, albumId: UUID): Boolean {
        val album = albums[albumId] ?: return false
        return album.ownerUserId == viewerUserId ||
            album.kind == "profile" ||
            grants[albumId to viewerUserId]?.let { grant -> grant.revokedAt == null } == true
    }
}
