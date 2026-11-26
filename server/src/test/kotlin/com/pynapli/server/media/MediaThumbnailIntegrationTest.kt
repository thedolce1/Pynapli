package com.pynapli.server.media

import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.verification.insertVerificationUser
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G2-2 ticket 1 (TRUST_AND_SAFETY_SPEC "Photo pre-screening"): a grid thumbnail exists only for an
 * approved, live public profile photo. Held, pending, private and removed media never get one, and a
 * photo that stops being eligible loses its object.
 */
class MediaThumbnailIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val repository = JdbcMediaThumbnailRepository(dataSource)
    private val store = FakeThumbnailStore()
    private val thumbnails = MediaThumbnailService(repository = repository, store = store)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        store.objects.clear()
        store.originals.clear()
        store.beforeWrite = {}
    }

    @Test
    fun anApprovedProfilePhotoGetsAThumbnailThatResolvesAsTheTileImage() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val asset = mediaService(flagged = false).upload(owner, "profile")

            assertEquals(ThumbnailOutcome.READY, thumbnails.generate(asset))

            assertTrue(asset in store.objects)
            assertEquals(setOf(asset), repository.readyAmong(listOf(asset), MediaThumbnails.VARIANT))
            val images = MediaImageResolver(LocalMediaAssetUrlResolver("http://media.test"), repository).forAssets(listOf(asset))
            val tile = assertNotNull(images.tile(asset))
            assertEquals("http://media.test/v1/media/uploads/local/$asset/thumb", tile.url)
            assertEquals("$asset:v1-thumb-480", tile.cacheKey)
            // Full-size surfaces keep the original.
            assertEquals("$asset:orig", assertNotNull(images.original(asset)).cacheKey)
            assertEquals("$asset:v1-thumb-480", images.thumbnailCacheKey(asset))
        }

    @Test
    fun aHeldProfilePhotoNeverGetsAThumbnail() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val held = mediaService(flagged = true).upload(owner, "profile")

            assertEquals(ThumbnailOutcome.SKIPPED, thumbnails.generate(held))
            thumbnails.reconcile(backfillLimit = 50, sweepLimit = 50, sweepAfter = null)

            assertTrue(store.objects.isEmpty())
            assertTrue(repository.readyAmong(listOf(held), MediaThumbnails.VARIANT).isEmpty())
            assertNull(variantOf(held))
            // The tile falls back to the original, which the photo projection doesn't serve anyway.
            val images = MediaImageResolver(LocalMediaAssetUrlResolver("http://media.test"), repository).forAssets(listOf(held))
            assertEquals("$held:orig", assertNotNull(images.tile(held)).cacheKey)
        }

    @Test
    fun privateAlbumMediaNeverGetsAThumbnail() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val privatePhoto = mediaService(flagged = false).upload(owner, "private")
            assertEquals("approved", statusOf(privatePhoto).second, "test setup: private photos are approved on upload")

            assertEquals(ThumbnailOutcome.SKIPPED, thumbnails.generate(privatePhoto))
            thumbnails.reconcile(backfillLimit = 50, sweepLimit = 50, sweepAfter = null)

            assertTrue(store.objects.isEmpty())
            assertNull(variantOf(privatePhoto))
        }

    @Test
    fun theUploadHookSchedulesOnlyApprovedProfilePhotos() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val told = mutableListOf<UUID>()
            val clean = mediaService(flagged = false, listener = { told += it }).upload(owner, "profile")
            mediaService(flagged = true, listener = { told += it }).upload(owner, "profile")
            mediaService(flagged = false, listener = { told += it }).upload(owner, "private")

            assertEquals(listOf(clean), told)
        }

    @Test
    fun theBackfillMakesMissingThumbnailsOnceAndIsIdempotent() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val media = mediaService(flagged = false)
            val first = media.upload(owner, "profile")
            val second = media.upload(owner, "profile")

            val run = thumbnails.reconcile(backfillLimit = 50, sweepLimit = 50, sweepAfter = null)
            val again = thumbnails.reconcile(backfillLimit = 50, sweepLimit = 50, sweepAfter = null)

            assertEquals(2, run.generated)
            assertEquals(0, again.generated)
            assertEquals(setOf(first, second), store.objects.keys)
        }

    @Test
    fun theBackfillIsBoundedPerTick() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val media = mediaService(flagged = false)
            repeat(3) { media.upload(owner, "profile") }

            val run = thumbnails.reconcile(backfillLimit = 2, sweepLimit = 50, sweepAfter = null)

            assertEquals(2, run.generated)
            assertEquals(2, store.objects.size)
        }

    @Test
    fun aRemovedPhotoLosesItsThumbnailOnTheNextSweepAndIsNeverServedMeanwhile() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val asset = mediaService(flagged = false).upload(owner, "profile")
            thumbnails.generate(asset)

            setModerationStatus(asset, "removed")
            // Served nowhere from the moment it is removed, before any sweep.
            assertTrue(repository.readyAmong(listOf(asset), MediaThumbnails.VARIANT).isEmpty())

            val sweep = thumbnails.reconcile(backfillLimit = 50, sweepLimit = 50, sweepAfter = null)

            assertEquals(1, sweep.retired)
            assertFalse(asset in store.objects)
            assertNull(variantOf(asset))
        }

    @Test
    fun aPhotoTakenOutOfTheProfileAlbumOrDeletedLosesItsThumbnail() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val media = mediaService(flagged = false)
            val takenOut = media.upload(owner, "profile")
            val deleted = media.upload(owner, "profile")
            thumbnails.reconcile(backfillLimit = 50, sweepLimit = 50, sweepAfter = null)
            assertEquals(setOf(takenOut, deleted), store.objects.keys)

            execute("UPDATE media_album_items SET removed_at = now() WHERE media_asset_id = ?", takenOut)
            execute("UPDATE media_assets SET deleted_at = now() WHERE id = ?", deleted)
            thumbnails.reconcile(backfillLimit = 50, sweepLimit = 50, sweepAfter = null)

            assertTrue(store.objects.isEmpty())
            assertNull(variantOf(takenOut))
            assertNull(variantOf(deleted))
        }

    @Test
    fun aRemovalThatLandsWhileTheThumbnailIsBeingWrittenNeverLeavesOneStanding() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val asset = mediaService(flagged = false).upload(owner, "profile")
            // A moderator removes the photo between the claim and the ready mark.
            store.beforeWrite = { id -> setModerationStatus(id, "removed") }

            assertEquals(ThumbnailOutcome.RETIRED, thumbnails.generate(asset))

            assertFalse(asset in store.objects)
            assertNull(variantOf(asset))
            assertTrue(repository.readyAmong(listOf(asset), MediaThumbnails.VARIANT).isEmpty())
        }

    @Test
    fun anOriginalImageIoCannotReadIsTerminalAndKeepsTheOriginal() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val asset = mediaService(flagged = false).upload(owner, "profile")
            store.originals[asset] = "RIFF....WEBP".toByteArray()

            assertEquals(ThumbnailOutcome.UNRENDERABLE, thumbnails.generate(asset))
            val later = thumbnails.reconcile(backfillLimit = 50, sweepLimit = 50, sweepAfter = null)

            assertEquals(0, later.generated + later.failed, "a terminal failure is not retried")
            assertTrue(store.objects.isEmpty())
            assertNull(variantOf(asset))
        }

    private fun mediaService(
        flagged: Boolean,
        listener: ProfilePhotoApprovedListener? = null,
    ): MediaService =
        MediaService(
            repository = JdbcMediaRepository(dataSource),
            uploadSigner = StaticMediaUploadSigner("gcs", "media-test", "http://upload.test"),
            mediaAssetUrlResolver = LocalMediaAssetUrlResolver("http://media.test"),
            moderationScanner =
                CloudVisionMediaModerationScanner(
                    client = {
                        SafeSearchAnnotation(
                            adult = if (flagged) "POSSIBLE" else "VERY_UNLIKELY",
                            racy = "LIKELY",
                            violence = "VERY_UNLIKELY",
                        )
                    },
                ),
            profilePhotoApproved = listener,
        )

    private suspend fun MediaService.upload(
        owner: UUID,
        albumKind: String,
    ): UUID {
        val slot =
            createUploadSlot(
                owner,
                CreateMediaUploadRequestDto(albumKind = albumKind, mediaType = "image", contentType = "image/jpeg", byteSize = 1_000),
            )
        completeUpload(owner, slot.mediaAsset.mediaAssetId, CompleteMediaUploadRequestDto(albumId = slot.album.albumId))
        return UUID.fromString(slot.mediaAsset.mediaAssetId)
    }

    private fun setModerationStatus(
        assetId: UUID,
        status: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE media_assets SET moderation_status = ? WHERE id = ?").use { statement ->
                statement.setString(1, status)
                statement.setObject(2, assetId)
                statement.executeUpdate()
            }
        }
    }

    private fun execute(
        sql: String,
        assetId: UUID,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, assetId)
                statement.executeUpdate()
            }
        }
    }

    private fun variantOf(assetId: UUID): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT thumbnail_variant FROM media_assets WHERE id = ?").use { statement ->
                statement.setObject(1, assetId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    private fun statusOf(assetId: UUID): Pair<String, String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT processing_status, moderation_status FROM media_assets WHERE id = ?").use { statement ->
                statement.setObject(1, assetId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1) to rs.getString(2)
                }
            }
        }

    /** In-memory storage: a real JPEG as every original unless a test sets one. */
    private class FakeThumbnailStore : MediaThumbnailStore {
        val objects = ConcurrentHashMap<UUID, ByteArray>()
        val originals = ConcurrentHashMap<UUID, ByteArray>()
        var beforeWrite: (UUID) -> Unit = {}

        override fun supports(storageProvider: String): Boolean = true

        override suspend fun readOriginal(source: ThumbnailSource): ByteArray = originals[source.mediaAssetId] ?: SAMPLE_JPEG

        override suspend fun writeThumbnail(
            source: ThumbnailSource,
            bytes: ByteArray,
        ) {
            beforeWrite(source.mediaAssetId)
            objects[source.mediaAssetId] = bytes
        }

        override suspend fun deleteThumbnail(
            storageProvider: String,
            storageBucket: String,
            mediaAssetId: UUID,
        ): Boolean {
            objects.remove(mediaAssetId)
            return true
        }

        companion object {
            val SAMPLE_JPEG: ByteArray =
                ByteArrayOutputStream()
                    .also { ImageIO.write(BufferedImage(900, 1200, BufferedImage.TYPE_INT_RGB), "jpeg", it) }
                    .toByteArray()
        }
    }
}
