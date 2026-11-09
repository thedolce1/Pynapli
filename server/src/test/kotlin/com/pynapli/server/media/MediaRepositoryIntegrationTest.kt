package com.pynapli.server.media

import com.pynapli.server.grid.hydrateGridCards
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun migrationsCreateMediaFoundationTablesAndIndexes() {
        assertTrue(dataSource.tableExists("media_assets"))
        assertTrue(dataSource.tableExists("media_albums"))
        assertTrue(dataSource.tableExists("media_album_items"))
        assertTrue(dataSource.tableExists("media_album_access_requests"))
        assertTrue(dataSource.tableExists("media_album_access_grants"))
        assertTrue(dataSource.indexExists("idx_media_album_access_grants_active"))
    }

    @Test
    fun cleanupRemovesAbandonedAndRejectedButKeepsApproved() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000301")
            val repository = JdbcMediaRepository(dataSource)
            val cleanup = JdbcMediaCleanupRepository(dataSource)
            dataSource.insertUser(ownerId)

            val abandoned =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "abandoned"), { UUID.fromString("20000000-0000-0000-0000-000000000301") })
            val approved =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "approved"), { UUID.fromString("20000000-0000-0000-0000-000000000302") })
            repository.markAssetUploaded(ownerId, approved.mediaAssetId)
            repository.markAssetReadyApproved(ownerId, approved.mediaAssetId)
            val rejected =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "rejected"), { UUID.fromString("20000000-0000-0000-0000-000000000303") })
            // Mirror the real flow: an asset is uploaded, then moderation rejects it
            // (markAssetModerationRejected only transitions uploaded/processing/ready assets).
            repository.markAssetUploaded(ownerId, rejected.mediaAssetId)
            repository.markAssetModerationRejected(ownerId, rejected.mediaAssetId)

            val cutoff = Instant.now().plus(Duration.ofHours(1))
            val summary = cleanup.purgeStaleAssets(abandonedBefore = cutoff, rejectedBefore = cutoff, softDeletedBefore = cutoff)

            assertEquals(1, summary.abandonedUploads)
            assertEquals(1, summary.rejectedAssets)
            assertEquals(0, summary.softDeletedAssets)
            assertFalse(assetExists(abandoned.mediaAssetId))
            assertFalse(assetExists(rejected.mediaAssetId))
            assertTrue(assetExists(approved.mediaAssetId))
        }

    @Test
    fun decidingAnAlreadyDecidedAlbumRequestReplaysTheRecordedOutcome() =
        runBlocking {
            val owner = UUID.fromString("00000000-0000-0000-0000-000000000311")
            val requester = UUID.fromString("00000000-0000-0000-0000-000000000312")
            dataSource.insertUser(owner)
            dataSource.insertUser(requester)
            val repository = JdbcMediaRepository(dataSource)
            assertNotNull(repository.requestAlbumAccess(owner, requester))

            val decided = assertNotNull(repository.decideAlbumAccessRequest(owner, requester, approved = true))
            assertEquals("approved", decided.status)

            // A retried decide whose response was lost replays the recorded outcome
            // instead of a not-found — and never flips the decision, even when the
            // retry carries the opposite flag.
            val replaySame = assertNotNull(repository.decideAlbumAccessRequest(owner, requester, approved = true))
            assertEquals(decided.requestId, replaySame.requestId)
            assertEquals("approved", replaySame.status)

            val replayOpposite = assertNotNull(repository.decideAlbumAccessRequest(owner, requester, approved = false))
            assertEquals("approved", replayOpposite.status)
        }

    private fun sampleAssetCreate(
        ownerId: UUID,
        label: String,
        intendedAlbumKind: String? = null,
    ) = MediaAssetCreate(
        mediaType = "image",
        storageProvider = "gcs",
        storageBucket = "pynapli-test-media",
        storageObjectKey = "media/$label-$ownerId",
        contentType = "image/jpeg",
        byteSize = 1_024,
        pixelWidth = 800,
        pixelHeight = 600,
        intendedAlbumKind = intendedAlbumKind,
    )

    private fun assetExists(id: UUID): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM media_assets WHERE id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }

    @Test
    fun privateAlbumRequiresGrantAndCanBeRevoked() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000101")
            val granteeId = UUID.fromString("00000000-0000-0000-0000-000000000102")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            dataSource.insertUser(granteeId)

            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "private",
                    title = "Private album",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000001") },
                )
            val asset =
                repository.createAsset(
                    ownerUserId = ownerId,
                    create =
                        MediaAssetCreate(
                            mediaType = "image",
                            storageProvider = "gcs",
                            storageBucket = "pynapli-test-media",
                            storageObjectKey = "users/$ownerId/private/photo-1.jpg",
                            contentType = "image/jpeg",
                            byteSize = 1_024,
                            pixelWidth = 800,
                            pixelHeight = 600,
                        ),
                    assetIdFactory = { UUID.fromString("20000000-0000-0000-0000-000000000001") },
                )
            repository.markAssetUploaded(ownerId, asset.mediaAssetId)
            repository.markAssetReadyApproved(ownerId, asset.mediaAssetId)
            assertNotNull(
                repository.addAssetToAlbum(
                    ownerUserId = ownerId,
                    albumId = album.albumId,
                    mediaAssetId = asset.mediaAssetId,
                    sortOrder = 0,
                ),
            )

            assertNull(repository.listAlbumItemsForViewer(granteeId, album.albumId))

            val grant =
                repository.grantAlbumAccess(
                    ownerUserId = ownerId,
                    albumId = album.albumId,
                    granteeUserId = granteeId,
                )

            assertNotNull(grant)
            assertTrue(repository.hasAlbumAccess(granteeId, album.albumId))
            assertEquals(1, repository.listAlbumItemsForViewer(granteeId, album.albumId)?.size)

            val revokedGrant =
                repository.revokeAlbumAccess(
                    ownerUserId = ownerId,
                    albumId = album.albumId,
                    granteeUserId = granteeId,
                )

            assertNotNull(revokedGrant?.revokedAt)
            assertFalse(repository.hasAlbumAccess(granteeId, album.albumId))
            assertNull(repository.listAlbumItemsForViewer(granteeId, album.albumId))
        }

    @Test
    fun albumItemsFilterModerationStateByViewerRole() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000501")
            val granteeId = UUID.fromString("00000000-0000-0000-0000-000000000502")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            dataSource.insertUser(granteeId)
            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "private",
                    title = "Private album",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000501") },
                )
            val pending =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "pending-visible-owner"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000501")
                })
            repository.markAssetUploaded(ownerId, pending.mediaAssetId)
            repository.addAssetToAlbum(ownerId, album.albumId, pending.mediaAssetId, sortOrder = 0)
            val approved =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "approved-visible-grantee"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000502")
                })
            repository.markAssetUploaded(ownerId, approved.mediaAssetId)
            repository.markAssetReadyApproved(ownerId, approved.mediaAssetId)
            repository.addAssetToAlbum(ownerId, album.albumId, approved.mediaAssetId, sortOrder = 1)
            val removed =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "removed-hidden"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000503")
                })
            repository.markAssetUploaded(ownerId, removed.mediaAssetId)
            repository.markAssetModerationRejected(ownerId, removed.mediaAssetId)
            repository.addAssetToAlbum(ownerId, album.albumId, removed.mediaAssetId, sortOrder = 2)
            repository.grantAlbumAccess(ownerId, album.albumId, granteeId)

            val ownerItems = repository.listAlbumItemsForViewer(ownerId, album.albumId).orEmpty()
            val granteeItems = repository.listAlbumItemsForViewer(granteeId, album.albumId).orEmpty()

            // Owner listing is honest: pending review AND moderation-removed items stay visible
            // to the owner (so the client can show "in review" / "not approved" states), while
            // other viewers only ever see ready+approved media.
            assertEquals(
                setOf(pending.mediaAssetId, approved.mediaAssetId, removed.mediaAssetId),
                ownerItems.map { it.mediaAsset.mediaAssetId }.toSet(),
            )
            assertEquals(listOf(approved.mediaAssetId), granteeItems.map { it.mediaAsset.mediaAssetId })
        }

    @Test
    fun listPrivateAlbumGrantsForOwnerReturnsActiveGranteesWithDisplayName() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000301")
            val granteeWithName = UUID.fromString("00000000-0000-0000-0000-000000000302")
            val granteeWithoutName = UUID.fromString("00000000-0000-0000-0000-000000000303")
            val revokedGrantee = UUID.fromString("00000000-0000-0000-0000-000000000304")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            dataSource.insertUser(granteeWithName)
            dataSource.insertUser(granteeWithoutName)
            dataSource.insertUser(revokedGrantee)
            dataSource.setProfileDisplayName(granteeWithName, "Maya")

            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "private",
                    title = "Private album",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000003") },
                )
            repository.grantAlbumAccess(ownerId, album.albumId, granteeWithName)
            repository.grantAlbumAccess(ownerId, album.albumId, granteeWithoutName)
            repository.grantAlbumAccess(ownerId, album.albumId, revokedGrantee)
            repository.revokeAlbumAccess(ownerId, album.albumId, revokedGrantee)

            val summaries = repository.listPrivateAlbumGrantsForOwner(ownerId)

            val granteeIds = summaries.map { it.granteeUserId }
            assertEquals(listOf(granteeWithName, granteeWithoutName).toSet(), granteeIds.toSet())
            val withName = summaries.single { it.granteeUserId == granteeWithName }
            val withoutName = summaries.single { it.granteeUserId == granteeWithoutName }
            assertEquals("Maya", withName.granteeDisplayName)
            assertNull(withoutName.granteeDisplayName)
        }

    @Test
    fun profileAlbumIsVisibleWithoutGrant() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000201")
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000202")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            dataSource.insertUser(viewerId)

            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "profile",
                    title = "Profile photos",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000002") },
                )

            assertTrue(repository.hasAlbumAccess(viewerId, album.albumId))
        }

    @Test
    fun softDeleteAssetHidesItemFromViewersAndGridHydration() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000601")
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000602")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            dataSource.insertUser(viewerId)
            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "profile",
                    title = "Profile photos",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000601") },
                )
            val asset =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "grid-photo"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000601")
                })
            repository.markAssetUploaded(ownerId, asset.mediaAssetId)
            repository.markAssetReadyApproved(ownerId, asset.mediaAssetId)
            repository.addAssetToAlbum(ownerId, album.albumId, asset.mediaAssetId, sortOrder = 0)

            assertEquals(1, repository.listAlbumItemsForViewer(viewerId, album.albumId)?.size)
            val hydrationBefore = dataSource.connection.use { it.hydrateGridCards(viewerId, listOf(ownerId)) }
            assertEquals(asset.mediaAssetId, hydrationBefore[ownerId]?.thumbnailMediaAssetId)

            // Non-owner delete reads as not-found (no existence leak) and changes nothing.
            assertNull(repository.softDeleteAsset(viewerId, asset.mediaAssetId))
            assertEquals(1, repository.listAlbumItemsForViewer(viewerId, album.albumId)?.size)

            val deleted = assertNotNull(repository.softDeleteAsset(ownerId, asset.mediaAssetId))
            assertNotNull(deleted.deletedAt)

            assertEquals(0, repository.listAlbumItemsForViewer(viewerId, album.albumId)?.size)
            assertEquals(0, repository.listAlbumItemsForViewer(ownerId, album.albumId)?.size)
            val hydrationAfter = dataSource.connection.use { it.hydrateGridCards(viewerId, listOf(ownerId)) }
            assertNull(hydrationAfter[ownerId]?.thumbnailMediaAssetId)

            // Retried delete replays success and keeps the original deletion timestamp.
            val retried = assertNotNull(repository.softDeleteAsset(ownerId, asset.mediaAssetId))
            assertEquals(deleted.deletedAt, retried.deletedAt)
        }

    @Test
    fun reorderAlbumItemsRewritesSortOrderAndRejectsSetMismatch() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000611")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "profile",
                    title = "Profile photos",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000611") },
                )
            val assetIds =
                (1..3).map { index ->
                    val asset =
                        repository.createAsset(ownerId, sampleAssetCreate(ownerId, "reorder-$index"), {
                            UUID.fromString("20000000-0000-0000-0000-00000000061$index")
                        })
                    repository.markAssetUploaded(ownerId, asset.mediaAssetId)
                    repository.markAssetReadyApproved(ownerId, asset.mediaAssetId)
                    repository.addAssetToAlbum(ownerId, album.albumId, asset.mediaAssetId, sortOrder = index - 1)
                    asset.mediaAssetId
                }
            val (first, second, third) = assetIds

            val reordered =
                assertNotNull(repository.reorderAlbumItems(ownerId, album.albumId, listOf(third, first, second)))

            assertEquals(listOf(third, first, second), reordered.map { it.mediaAsset.mediaAssetId })
            assertEquals(listOf(0, 1, 2), reordered.map { it.sortOrder })
            assertEquals(
                listOf(third, first, second),
                repository.listAlbumItemsForViewer(ownerId, album.albumId)?.map { it.mediaAsset.mediaAssetId },
            )

            // A list that is not exactly the active set is rejected and leaves the order untouched.
            assertNull(repository.reorderAlbumItems(ownerId, album.albumId, listOf(first, second)))
            assertNull(
                repository.reorderAlbumItems(
                    ownerId,
                    album.albumId,
                    listOf(first, second, UUID.fromString("20000000-0000-0000-0000-000000000619")),
                ),
            )
            assertEquals(
                listOf(third, first, second),
                repository.listAlbumItemsForViewer(ownerId, album.albumId)?.map { it.mediaAsset.mediaAssetId },
            )
        }

    @Test
    fun ownerSeesHeldRemovedAndFailedItemsButGranteesDoNot() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000621")
            val granteeId = UUID.fromString("00000000-0000-0000-0000-000000000622")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            dataSource.insertUser(granteeId)
            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "private",
                    title = "Private album",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000621") },
                )

            val held =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "held"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000621")
                })
            repository.markAssetUploaded(ownerId, held.mediaAssetId)
            dataSource.setAssetModerationStatus(held.mediaAssetId, "held")
            repository.addAssetToAlbum(ownerId, album.albumId, held.mediaAssetId, sortOrder = 0)

            val removed =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "removed"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000622")
                })
            repository.markAssetUploaded(ownerId, removed.mediaAssetId)
            repository.markAssetModerationRejected(ownerId, removed.mediaAssetId)
            repository.addAssetToAlbum(ownerId, album.albumId, removed.mediaAssetId, sortOrder = 1)

            val failed =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "failed"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000623")
                })
            dataSource.setAssetProcessingStatus(failed.mediaAssetId, "failed")
            repository.addAssetToAlbum(ownerId, album.albumId, failed.mediaAssetId, sortOrder = 2)

            val approved =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "approved"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000624")
                })
            repository.markAssetUploaded(ownerId, approved.mediaAssetId)
            repository.markAssetReadyApproved(ownerId, approved.mediaAssetId)
            repository.addAssetToAlbum(ownerId, album.albumId, approved.mediaAssetId, sortOrder = 3)

            repository.grantAlbumAccess(ownerId, album.albumId, granteeId)

            val ownerItems = repository.listAlbumItemsForViewer(ownerId, album.albumId).orEmpty()
            val granteeItems = repository.listAlbumItemsForViewer(granteeId, album.albumId).orEmpty()

            assertEquals(
                setOf(held.mediaAssetId, removed.mediaAssetId, failed.mediaAssetId, approved.mediaAssetId),
                ownerItems.map { it.mediaAsset.mediaAssetId }.toSet(),
            )
            assertEquals(
                setOf("held", "removed", "pending", "approved"),
                ownerItems.map { it.mediaAsset.moderationStatus }.toSet(),
            )
            assertEquals(listOf(approved.mediaAssetId), granteeItems.map { it.mediaAsset.mediaAssetId })
        }

    @Test
    fun ownerListingOrdersBySortOrderThenNewestAddedToMatchDiscoveryPick() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000631")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "profile",
                    title = "Profile photos",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000631") },
                )
            val older =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "older"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000631")
                })
            val newer =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "newer"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000632")
                })
            val trailing =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "trailing"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000633")
                })
            listOf(older, newer, trailing).forEach { asset ->
                repository.markAssetUploaded(ownerId, asset.mediaAssetId)
                repository.markAssetReadyApproved(ownerId, asset.mediaAssetId)
            }
            repository.addAssetToAlbum(ownerId, album.albumId, older.mediaAssetId, sortOrder = 0)
            repository.addAssetToAlbum(ownerId, album.albumId, newer.mediaAssetId, sortOrder = 0)
            repository.addAssetToAlbum(ownerId, album.albumId, trailing.mediaAssetId, sortOrder = 1)
            dataSource.setItemAddedAt(album.albumId, older.mediaAssetId, Instant.now().minus(Duration.ofHours(1)))

            val items = repository.listAlbumItemsForViewer(ownerId, album.albumId).orEmpty()

            // sort_order ASC first; within a tied sort_order the newest added wins,
            // matching the discovery thumbnail pick (sort_order ASC, added_at DESC).
            assertEquals(
                listOf(newer.mediaAssetId, older.mediaAssetId, trailing.mediaAssetId),
                items.map { it.mediaAsset.mediaAssetId },
            )
        }

    @Test
    fun capCountsTrackActiveItemsOpenSlotsAndExclusions() =
        runBlocking {
            val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000641")
            val repository = JdbcMediaRepository(dataSource)
            dataSource.insertUser(ownerId)
            val album =
                repository.ensureAlbum(
                    ownerUserId = ownerId,
                    kind = "profile",
                    title = "Profile photos",
                    albumIdFactory = { UUID.fromString("10000000-0000-0000-0000-000000000641") },
                )
            val kept =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "kept"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000641")
                })
            val dropped =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "dropped"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000642")
                })
            listOf(kept, dropped).forEach { asset ->
                repository.markAssetUploaded(ownerId, asset.mediaAssetId)
                repository.addAssetToAlbum(ownerId, album.albumId, asset.mediaAssetId, sortOrder = 0)
            }
            assertEquals(2, repository.countActiveAlbumItems(ownerId, album.albumId))

            repository.softDeleteAsset(ownerId, dropped.mediaAssetId)
            assertEquals(1, repository.countActiveAlbumItems(ownerId, album.albumId))
            assertEquals(0, repository.countActiveAlbumItems(ownerId, album.albumId, excludingMediaAssetId = kept.mediaAssetId))

            val pendingSlot =
                repository.createAsset(ownerId, sampleAssetCreate(ownerId, "pending-slot", intendedAlbumKind = "profile"), {
                    UUID.fromString("20000000-0000-0000-0000-000000000643")
                })
            assertEquals(1, repository.countPendingUploadAssets(ownerId, "profile"))
            assertEquals(0, repository.countPendingUploadAssets(ownerId, "private"))

            repository.markAssetUploaded(ownerId, pendingSlot.mediaAssetId)
            assertEquals(0, repository.countPendingUploadAssets(ownerId, "profile"))
        }

    private fun DataSource.setAssetModerationStatus(
        mediaAssetId: UUID,
        status: String,
    ) {
        connection.use { connection ->
            connection.prepareStatement("UPDATE media_assets SET moderation_status = ? WHERE id = ?").use { statement ->
                statement.setString(1, status)
                statement.setObject(2, mediaAssetId)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.setAssetProcessingStatus(
        mediaAssetId: UUID,
        status: String,
    ) {
        connection.use { connection ->
            connection.prepareStatement("UPDATE media_assets SET processing_status = ? WHERE id = ?").use { statement ->
                statement.setString(1, status)
                statement.setObject(2, mediaAssetId)
                statement.executeUpdate()
            }
        }
    }

    private fun DataSource.setItemAddedAt(
        albumId: UUID,
        mediaAssetId: UUID,
        addedAt: Instant,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement("UPDATE media_album_items SET added_at = ? WHERE album_id = ? AND media_asset_id = ?")
                .use { statement ->
                    statement.setTimestamp(1, Timestamp.from(addedAt))
                    statement.setObject(2, albumId)
                    statement.setObject(3, mediaAssetId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertUser(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, status)
                    VALUES (?, 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.setProfileDisplayName(
        userId: UUID,
        displayName: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO profiles (user_id, display_name)
                    VALUES (?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET display_name = EXCLUDED.display_name
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, displayName)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.tableExists(tableName: String): Boolean =
        connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }

    private fun DataSource.indexExists(indexName: String): Boolean =
        connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, indexName)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }
}
