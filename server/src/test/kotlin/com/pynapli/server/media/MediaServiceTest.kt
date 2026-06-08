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
    fun createUploadSlotCreatesAlbumAssetAndDestination() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val repository = InMemoryMediaRepository()
            val service = MediaService(repository, FixedMediaUploadSigner())

            val slot =
                service.createUploadSlot(
                    ownerId,
                    CreateMediaUploadRequestDto(
                        albumKind = "private",
                        mediaType = "image",
                        contentType = "image/jpeg",
                        byteSize = 1_024,
                        pixelWidth = 800,
                        pixelHeight = 600,
                    ),
                )

            assertEquals("private", slot.album.kind)
            assertEquals("image", slot.mediaAsset.mediaType)
            assertEquals("pending_upload", slot.mediaAsset.processingStatus)
            assertEquals("PUT", slot.uploadMethod)
            assertEquals("image/jpeg", slot.uploadHeaders["Content-Type"])
            assertEquals("users/$ownerId/private/${slot.mediaAsset.mediaAssetId}.jpg", slot.storageObjectKey)
        }

    @Test
    fun assetDtoCarriesResolverContentUrlWhenResolverPresent() =
        runBlocking {
            val resolver =
                object : MediaAssetUrlResolver {
                    override fun publicContentUrl(mediaAssetId: UUID): String = "https://signed.example/$mediaAssetId"
                }
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner(), mediaAssetUrlResolver = resolver)

            val slot =
                service.createUploadSlot(
                    UUID.randomUUID(),
                    CreateMediaUploadRequestDto(albumKind = "private", mediaType = "image", contentType = "image/jpeg", byteSize = 1_024),
                )

            assertEquals("https://signed.example/${slot.mediaAsset.mediaAssetId}", slot.mediaAsset.contentUrl)
        }

    @Test
    fun assetDtoHasNoContentUrlWithoutResolver() =
        runBlocking {
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())

            val slot =
                service.createUploadSlot(
                    UUID.randomUUID(),
                    CreateMediaUploadRequestDto(albumKind = "private", mediaType = "image", contentType = "image/jpeg", byteSize = 1_024),
                )

            assertEquals(null, slot.mediaAsset.contentUrl)
        }

    @Test
    fun autoApproveScannerMakesUploadReadyAndApproved() =
        runBlocking {
            val ownerId = UUID.randomUUID()
            val service =
                MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner(), moderationScanner = AutoApproveMediaModerationScanner())

            val item = service.uploadAndComplete(ownerId)

            assertEquals("ready", item.mediaAsset.processingStatus)
            assertEquals("approved", item.mediaAsset.moderationStatus)
        }

    @Test
    fun manualReviewScannerHoldsProfileUploadPending() =
        runBlocking {
            val ownerId = UUID.randomUUID()
            val service =
                MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner(), moderationScanner = ManualReviewMediaModerationScanner())

            val item = service.uploadAndComplete(ownerId, albumKind = "profile")

            assertEquals("uploaded", item.mediaAsset.processingStatus)
            assertEquals("pending", item.mediaAsset.moderationStatus)
        }

    @Test
    fun manualReviewScannerAllowsPrivateUploadBecausePrivateAlbumsAreConsentGated() =
        runBlocking {
            val ownerId = UUID.randomUUID()
            val service =
                MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner(), moderationScanner = ManualReviewMediaModerationScanner())

            val item = service.uploadAndComplete(ownerId, albumKind = "private")

            assertEquals("ready", item.mediaAsset.processingStatus)
            assertEquals("approved", item.mediaAsset.moderationStatus)
        }

    @Test
    fun rejectingScannerRemovesProfileUpload() =
        runBlocking {
            val ownerId = UUID.randomUUID()
            val scanner =
                object : MediaModerationScanner {
                    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision = MediaModerationDecision.Rejected("policy")
                }
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner(), moderationScanner = scanner)

            val item = service.uploadAndComplete(ownerId, albumKind = "profile")

            assertEquals("removed", item.mediaAsset.moderationStatus)
        }

    @Test
    fun nonSeverePrivateRejectionDoesNotRemoveConsentGatedMedia() =
        runBlocking {
            val ownerId = UUID.randomUUID()
            val scanner =
                object : MediaModerationScanner {
                    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision = MediaModerationDecision.Rejected("policy")
                }
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner(), moderationScanner = scanner)

            val item = service.uploadAndComplete(ownerId, albumKind = "private")

            assertEquals("ready", item.mediaAsset.processingStatus)
            assertEquals("approved", item.mediaAsset.moderationStatus)
        }

    @Test
    fun severePrivateRejectionRemovesAndEscalates() =
        runBlocking {
            val ownerId = UUID.randomUUID()
            val scanner =
                object : MediaModerationScanner {
                    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision =
                        MediaModerationDecision.Rejected(
                            reason = "safe_search_violence_very_likely",
                            severity = MediaModerationSeverity.SEVERE,
                            reportCategory = "harassment_or_threats",
                        )
                }
            val escalationPublisher = RecordingMediaModerationEscalationPublisher()
            val service =
                MediaService(
                    repository = InMemoryMediaRepository(),
                    uploadSigner = FixedMediaUploadSigner(),
                    moderationScanner = scanner,
                    moderationEscalationPublisher = escalationPublisher,
                )

            val item = service.uploadAndComplete(ownerId, albumKind = "private")

            assertEquals("removed", item.mediaAsset.moderationStatus)
            assertEquals(ownerId, escalationPublisher.ownerUserId)
            assertEquals(item.mediaAsset.mediaAssetId, escalationPublisher.mediaAssetId.toString())
            assertEquals("private", escalationPublisher.albumKind)
            assertEquals("safe_search_violence_very_likely", escalationPublisher.reason)
        }

    private suspend fun MediaService.uploadAndComplete(
        ownerId: UUID,
        albumKind: String = "private",
    ) = createUploadSlot(
        ownerId,
        CreateMediaUploadRequestDto(albumKind = albumKind, mediaType = "image", contentType = "image/jpeg", byteSize = 1_024),
    ).let { slot ->
        completeUpload(ownerId, slot.mediaAsset.mediaAssetId, CompleteMediaUploadRequestDto(albumId = slot.album.albumId))
    }

    @Test
    fun createUploadSlotRejectsOversizedImages() =
        runBlocking {
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())

            val error =
                assertFailsWith<MediaException> {
                    service.createUploadSlot(
                        UUID.randomUUID(),
                        CreateMediaUploadRequestDto(
                            albumKind = "private",
                            mediaType = "image",
                            contentType = "image/jpeg",
                            byteSize = MEDIA_IMAGE_MAX_BYTES + 1,
                        ),
                    )
                }

            assertEquals("MEDIA_FILE_TOO_LARGE", error.code)
        }

    @Test
    fun createUploadSlotRejectsUnsupportedContentType() =
        runBlocking {
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())

            val error =
                assertFailsWith<MediaException> {
                    service.createUploadSlot(
                        UUID.randomUUID(),
                        CreateMediaUploadRequestDto(
                            albumKind = "private",
                            mediaType = "image",
                            contentType = "image/gif",
                            byteSize = 1_024,
                        ),
                    )
                }

            assertEquals("INVALID_MEDIA_CONTENT_TYPE", error.code)
        }

    @Test
    fun completeUploadMarksAssetUploadedAndAttachesToAlbum() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository = InMemoryMediaRepository()
            val service = MediaService(repository, FixedMediaUploadSigner())
            val slot =
                service.createUploadSlot(
                    ownerId,
                    CreateMediaUploadRequestDto(
                        albumKind = "profile",
                        mediaType = "image",
                        contentType = "image/webp",
                        byteSize = 2_048,
                    ),
                )

            val item =
                service.completeUpload(
                    ownerId,
                    slot.mediaAsset.mediaAssetId,
                    CompleteMediaUploadRequestDto(albumId = slot.album.albumId, sortOrder = 3),
                )

            assertEquals(slot.album.albumId, item.albumId)
            assertEquals("uploaded", item.mediaAsset.processingStatus)
            assertEquals(3, item.sortOrder)
        }

    @Test
    fun completeUploadCanAutoApproveInLocalDevelopment() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000009")
            val repository = InMemoryMediaRepository()
            val service =
                MediaService(
                    repository = repository,
                    uploadSigner = FixedMediaUploadSigner(),
                    moderationScanner = AutoApproveMediaModerationScanner(),
                )
            val slot =
                service.createUploadSlot(
                    ownerId,
                    CreateMediaUploadRequestDto(
                        albumKind = "profile",
                        mediaType = "image",
                        contentType = "image/jpeg",
                        byteSize = 2_048,
                    ),
                )

            val item =
                service.completeUpload(
                    ownerId,
                    slot.mediaAsset.mediaAssetId,
                    CompleteMediaUploadRequestDto(albumId = slot.album.albumId),
                )

            assertEquals("approved", item.mediaAsset.moderationStatus)
            assertEquals("ready", item.mediaAsset.processingStatus)
        }

    @Test
    fun listOwnAlbumReturnsUploadedItemsNewestSortOrder() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000005")
            val repository = InMemoryMediaRepository()
            val service = MediaService(repository, FixedMediaUploadSigner())
            val slot =
                service.createUploadSlot(
                    ownerId,
                    CreateMediaUploadRequestDto(
                        albumKind = "profile",
                        mediaType = "image",
                        contentType = "image/jpeg",
                        byteSize = 2_048,
                    ),
                )
            service.completeUpload(
                ownerId,
                slot.mediaAsset.mediaAssetId,
                CompleteMediaUploadRequestDto(albumId = slot.album.albumId, sortOrder = 2),
            )

            val album = service.listOwnAlbum(ownerId, "profile")

            assertEquals(slot.album.albumId, album.album.albumId)
            assertEquals("profile", album.album.kind)
            assertEquals(1, album.items.size)
            assertEquals(
                slot.mediaAsset.mediaAssetId,
                album.items
                    .single()
                    .mediaAsset.mediaAssetId,
            )
            assertEquals(
                "uploaded",
                album.items
                    .single()
                    .mediaAsset.processingStatus,
            )
            assertEquals(2, album.items.single().sortOrder)
        }

    @Test
    fun listOwnPrivateAlbumGrantsReturnsActiveGranteesNewestFirst() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000010")
            val granteeA = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
            val granteeB = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
            val repository = InMemoryMediaRepository()
            val service = MediaService(repository, FixedMediaUploadSigner())
            repository.setDisplayName(granteeA, "Alex")
            repository.setDisplayName(granteeB, "Maya")
            service.requestPrivateAlbumAccess(granteeA, ownerId)
            service.requestPrivateAlbumAccess(granteeB, ownerId)
            service.decidePrivateAlbumAccessRequest(ownerId, granteeA, approved = true)
            service.decidePrivateAlbumAccessRequest(ownerId, granteeB, approved = true)

            val response = service.listOwnPrivateAlbumGrants(ownerId)

            assertEquals(2, response.grants.size)
            val displayNames = response.grants.map { grant -> grant.granteeDisplayName }.toSet()
            assertEquals(setOf("Alex", "Maya"), displayNames)
            response.grants.forEach { grant -> assertEquals(null, grant.revokedAt) }
        }

    @Test
    fun listOwnPrivateAlbumGrantsExcludesRevokedGrants() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000020")
            val granteeId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
            val repository = InMemoryMediaRepository()
            val service = MediaService(repository, FixedMediaUploadSigner())
            service.requestPrivateAlbumAccess(granteeId, ownerId)
            service.decidePrivateAlbumAccessRequest(ownerId, granteeId, approved = true)
            service.revokePrivateAlbumAccess(ownerId, granteeId.toString())

            val response = service.listOwnPrivateAlbumGrants(ownerId)

            assertEquals(0, response.grants.size)
        }

    @Test
    fun revokePrivateAlbumAccessMarksGrantRevoked() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000030")
            val granteeId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
            val repository = InMemoryMediaRepository()
            val service = MediaService(repository, FixedMediaUploadSigner())
            service.requestPrivateAlbumAccess(granteeId, ownerId)
            service.decidePrivateAlbumAccessRequest(ownerId, granteeId, approved = true)

            val revoked = service.revokePrivateAlbumAccess(ownerId, granteeId.toString())

            assertEquals(granteeId.toString(), revoked.granteeUserId)
            assertEquals(false, revoked.revokedAt.isNullOrEmpty())
        }

    @Test
    fun revokePrivateAlbumAccessFailsWhenNoActiveGrant() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000040")
            val granteeId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())

            val error =
                assertFailsWith<MediaException> {
                    service.revokePrivateAlbumAccess(ownerId, granteeId.toString())
                }

            assertEquals("MEDIA_GRANT_NOT_FOUND", error.code)
        }

    @Test
    fun revokePrivateAlbumAccessRejectsSelfTarget() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000050")
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())

            val error =
                assertFailsWith<MediaException> {
                    service.revokePrivateAlbumAccess(ownerId, ownerId.toString())
                }

            assertEquals("MEDIA_GRANT_NOT_FOUND", error.code)
        }

    @Test
    fun completeUploadRejectsAlbumsOwnedBySomeoneElse() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000003")
            val otherUserId = UUID.fromString("00000000-0000-0000-0000-000000000004")
            val service = MediaService(InMemoryMediaRepository(), FixedMediaUploadSigner())
            val slot =
                service.createUploadSlot(
                    ownerId,
                    CreateMediaUploadRequestDto(
                        albumKind = "private",
                        mediaType = "image",
                        contentType = "image/png",
                        byteSize = 2_048,
                    ),
                )

            val error =
                assertFailsWith<MediaException> {
                    service.completeUpload(
                        otherUserId,
                        slot.mediaAsset.mediaAssetId,
                        CompleteMediaUploadRequestDto(albumId = slot.album.albumId),
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
        contentType: String,
    ): MediaUploadDestination {
        val extension =
            when (contentType) {
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
            expiresAt = Instant.parse("2026-04-24T18:10:00Z"),
        )
    }
}

private class RecordingMediaModerationEscalationPublisher : MediaModerationEscalationPublisher {
    var ownerUserId: UUID? = null
    var mediaAssetId: UUID? = null
    var albumKind: String? = null
    var reason: String? = null
    var reportCategory: String? = null

    override suspend fun recordSevereMediaDecision(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        reason: String,
        reportCategory: String,
    ) {
        this.ownerUserId = ownerUserId
        this.mediaAssetId = mediaAssetId
        this.albumKind = albumKind
        this.reason = reason
        this.reportCategory = reportCategory
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
        albumIdFactory: () -> UUID,
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
                deletedAt = null,
            ).also { album -> albums[album.albumId] = album }

    override suspend fun createAsset(
        ownerUserId: UUID,
        create: MediaAssetCreate,
        assetIdFactory: () -> UUID,
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
            deletedAt = null,
        ).also { asset -> assets[asset.mediaAssetId] = asset }

    override suspend fun markAssetUploaded(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? {
        val asset = assets[mediaAssetId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        val uploaded = asset.copy(processingStatus = "uploaded")
        assets[mediaAssetId] = uploaded
        updateAlbumItems(uploaded)
        return uploaded
    }

    override suspend fun markAssetReadyApproved(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? {
        val asset = assets[mediaAssetId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        val ready =
            asset.copy(
                moderationStatus = "approved",
                processingStatus = "ready",
            )
        assets[mediaAssetId] = ready
        updateAlbumItems(ready)
        return ready
    }

    override suspend fun markAssetModerationRejected(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ): MediaAssetRecord? {
        val asset = assets[mediaAssetId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        val rejected =
            asset.copy(
                moderationStatus = "removed",
                processingStatus = "ready",
            )
        assets[mediaAssetId] = rejected
        updateAlbumItems(rejected)
        return rejected
    }

    override suspend fun findAlbum(
        ownerUserId: UUID,
        albumId: UUID,
    ): MediaAlbumRecord? = albums[albumId]?.takeIf { album -> album.ownerUserId == ownerUserId && album.deletedAt == null }

    override suspend fun addAssetToAlbum(
        ownerUserId: UUID,
        albumId: UUID,
        mediaAssetId: UUID,
        sortOrder: Int,
    ): MediaAlbumItemRecord? {
        val album = albums[albumId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        val asset = assets[mediaAssetId]?.takeIf { it.ownerUserId == ownerUserId && it.deletedAt == null } ?: return null
        return MediaAlbumItemRecord(
            albumId = album.albumId,
            mediaAsset = asset,
            sortOrder = sortOrder,
            addedAt = now,
            removedAt = null,
        ).also { item -> items[albumId to mediaAssetId] = item }
    }

    override suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        albumId: UUID,
    ): List<MediaAlbumItemRecord>? =
        if (hasAlbumAccess(viewerUserId, albumId)) {
            items.values.filter { item -> item.albumId == albumId && item.removedAt == null }
        } else {
            null
        }

    override suspend fun listAlbumItemsForViewer(
        viewerUserId: UUID,
        ownerUserId: UUID,
        kind: String,
    ): Pair<MediaAlbumRecord, List<MediaAlbumItemRecord>>? {
        val album =
            albums.values.firstOrNull { album ->
                album.ownerUserId == ownerUserId && album.kind == kind && album.deletedAt == null
            } ?: return null
        val albumItems = listAlbumItemsForViewer(viewerUserId, album.albumId) ?: return null
        return album to albumItems
    }

    override suspend fun requestAlbumAccess(
        ownerUserId: UUID,
        requesterUserId: UUID,
        requestIdFactory: () -> UUID,
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
                decidedByUserId = null,
            ).also { request -> accessRequests[ownerUserId to requesterUserId] = request }
    }

    override suspend fun decideAlbumAccessRequest(
        ownerUserId: UUID,
        requesterUserId: UUID,
        approved: Boolean,
        decidedByUserId: UUID,
    ): MediaAlbumAccessRequestRecord? {
        val request = accessRequests[ownerUserId to requesterUserId]?.takeIf { it.status == "pending" } ?: return null
        val decided =
            request.copy(
                status = if (approved) "approved" else "denied",
                decidedAt = now,
                decidedByUserId = decidedByUserId,
            )
        accessRequests[ownerUserId to requesterUserId] = decided
        if (approved) {
            grantAlbumAccess(
                ownerUserId = ownerUserId,
                albumId = request.albumId,
                granteeUserId = requesterUserId,
                grantedByUserId = decidedByUserId,
            )
        }
        return decided
    }

    override suspend fun grantAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        grantedByUserId: UUID,
    ): MediaAlbumAccessGrantRecord? =
        albums[albumId]?.takeIf { it.ownerUserId == ownerUserId && it.kind == "private" }?.let {
            MediaAlbumAccessGrantRecord(
                albumId = albumId,
                ownerUserId = ownerUserId,
                granteeUserId = granteeUserId,
                grantedByUserId = grantedByUserId,
                grantedAt = now,
                revokedAt = null,
                revokedByUserId = null,
            ).also { grant -> grants[albumId to granteeUserId] = grant }
        }

    override suspend fun revokeAlbumAccess(
        ownerUserId: UUID,
        albumId: UUID,
        granteeUserId: UUID,
        revokedByUserId: UUID,
    ): MediaAlbumAccessGrantRecord? {
        val grant =
            grants[albumId to granteeUserId]?.takeIf {
                it.ownerUserId == ownerUserId && it.revokedAt == null
            } ?: return null
        val revoked = grant.copy(revokedAt = now, revokedByUserId = revokedByUserId)
        grants[albumId to granteeUserId] = revoked
        return revoked
    }

    override suspend fun hasAlbumAccess(
        viewerUserId: UUID,
        albumId: UUID,
    ): Boolean {
        val album = albums[albumId] ?: return false
        return album.ownerUserId == viewerUserId ||
            album.kind == "profile" ||
            grants[albumId to viewerUserId]?.let { grant -> grant.revokedAt == null } == true
    }

    override suspend fun listPrivateAlbumGrantsForOwner(
        ownerUserId: UUID,
    ): List<MediaAlbumGrantSummaryRecord> {
        val album =
            albums.values.firstOrNull { album ->
                album.ownerUserId == ownerUserId && album.kind == "private" && album.deletedAt == null
            } ?: return emptyList()
        return grants.values
            .filter { grant ->
                grant.albumId == album.albumId &&
                    grant.ownerUserId == ownerUserId &&
                    grant.revokedAt == null
            }.sortedWith(compareByDescending<MediaAlbumAccessGrantRecord> { it.grantedAt }.thenBy { it.granteeUserId })
            .map { grant ->
                MediaAlbumGrantSummaryRecord(
                    albumId = grant.albumId,
                    ownerUserId = grant.ownerUserId,
                    granteeUserId = grant.granteeUserId,
                    granteeDisplayName = displayNamesByUserId[grant.granteeUserId],
                    grantedAt = grant.grantedAt,
                )
            }
    }

    fun setDisplayName(
        userId: UUID,
        displayName: String?,
    ) {
        if (displayName == null) {
            displayNamesByUserId.remove(userId)
        } else {
            displayNamesByUserId[userId] = displayName
        }
    }

    private val displayNamesByUserId = mutableMapOf<UUID, String>()

    private fun updateAlbumItems(asset: MediaAssetRecord) {
        items.keys
            .filter { (_, mediaAssetId) -> mediaAssetId == asset.mediaAssetId }
            .forEach { key ->
                items[key] = items.getValue(key).copy(mediaAsset = asset)
            }
    }
}
