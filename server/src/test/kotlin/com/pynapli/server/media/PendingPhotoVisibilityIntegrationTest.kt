package com.pynapli.server.media

import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.verification.insertVerificationUser
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Section 4: a new member's photo that pre-screening holds is visible to its owner ("Being
 * reviewed") and to nobody else until a moderator approves it; a clean one is public at once. And a
 * profile with no display name reads "New member", never a branded fake-looking name.
 */
class PendingPhotoVisibilityIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun aHeldPhotoIsListedForItsOwnerOnlyAndNeverOnThePublicProfile() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val viewer = dataSource.insertVerificationUser("Viewer")
            val media = mediaService(flagged = true)

            val assetId = media.uploadProfilePhoto(owner)

            val own = media.listOwnAlbum(owner, "profile")
            val ownItem = own.items.single { it.mediaAsset.mediaAssetId == assetId.toString() }
            assertEquals("pending", ownItem.mediaAsset.moderationStatus)
            assertNotNull(ownItem.mediaAsset.contentUrl, "the owner gets a URL to see their own pending photo")

            val public = assertNotNull(JdbcProfileRepository(dataSource).getPublicProfile(viewer, owner))
            assertEquals(null, public.profilePhotoMediaAssetId)
            assertTrue(assetId !in public.profilePhotoMediaAssetIds)
            // The album query another member's view goes through drops it too.
            val asViewer =
                JdbcMediaRepository(dataSource).listAlbumItemsForViewer(
                    viewerUserId = viewer,
                    albumId = UUID.fromString(own.album.albumId),
                )
            assertTrue(asViewer.orEmpty().none { it.mediaAsset.mediaAssetId == assetId })
        }

    @Test
    fun aCleanPhotoIsPublicStraightAfterUpload() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val viewer = dataSource.insertVerificationUser("Viewer")

            val assetId = mediaService(flagged = false).uploadProfilePhoto(owner)

            val public = assertNotNull(JdbcProfileRepository(dataSource).getPublicProfile(viewer, owner))
            assertEquals(assetId, public.profilePhotoMediaAssetId)
        }

    @Test
    fun aProfileWithoutANameReadsNewMember() =
        runBlocking {
            val unnamed = dataSource.insertVerificationUser("")
            val viewer = dataSource.insertVerificationUser("Viewer")

            val public = assertNotNull(JdbcProfileRepository(dataSource).getPublicProfile(viewer, unnamed))
            assertEquals("New member", public.displayName)
        }

    // ---- Completion is once per asset (review finding: re-complete bypass) ----

    @Test
    fun aHeldProfilePhotoCannotBeReCompletedIntoThePrivateAlbum() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val viewer = dataSource.insertVerificationUser("Viewer")
            val media = mediaService(flagged = true)
            val held = media.uploadProfilePhoto(owner)
            val privateAlbumId = media.listOwnAlbum(owner, "private").album.albumId

            val error =
                assertFailsWith<MediaException> {
                    media.completeUpload(owner, held.toString(), CompleteMediaUploadRequestDto(albumId = privateAlbumId))
                }

            assertEquals("MEDIA_UPLOAD_ALBUM_MISMATCH", error.code)
            assertEquals("uploaded" to "pending", dataSource.assetStatus(held))
            assertEquals(1, dataSource.itemCount(held))
            assertEquals(null, assertNotNull(JdbcProfileRepository(dataSource).getPublicProfile(viewer, owner)).profilePhotoMediaAssetId)
        }

    @Test
    fun aPrivatePhotoCannotBeCompletedIntoTheProfileAlbumAfterwards() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val viewer = dataSource.insertVerificationUser("Viewer")
            val media = mediaService(flagged = true)
            val slot =
                media.createUploadSlot(
                    owner,
                    CreateMediaUploadRequestDto(albumKind = "private", mediaType = "image", contentType = "image/jpeg", byteSize = 1_000),
                )
            media.completeUpload(owner, slot.mediaAsset.mediaAssetId, CompleteMediaUploadRequestDto(albumId = slot.album.albumId))
            val assetId = UUID.fromString(slot.mediaAsset.mediaAssetId)
            val profileAlbumId = media.listOwnAlbum(owner, "profile").album.albumId

            val error =
                assertFailsWith<MediaException> {
                    media.completeUpload(owner, assetId.toString(), CompleteMediaUploadRequestDto(albumId = profileAlbumId))
                }

            assertEquals("MEDIA_UPLOAD_ALBUM_MISMATCH", error.code)
            assertEquals(1, dataSource.itemCount(assetId), "no profile-album item is left behind")
            assertEquals(null, assertNotNull(JdbcProfileRepository(dataSource).getPublicProfile(viewer, owner)).profilePhotoMediaAssetId)
        }

    @Test
    fun aDoubleCompleteIntoTheSameAlbumReturnsTheSameHeldItem() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val media = mediaService(flagged = true)
            val slot =
                media.createUploadSlot(
                    owner,
                    CreateMediaUploadRequestDto(albumKind = "profile", mediaType = "image", contentType = "image/jpeg", byteSize = 1_000),
                )
            val request = CompleteMediaUploadRequestDto(albumId = slot.album.albumId)

            val first = media.completeUpload(owner, slot.mediaAsset.mediaAssetId, request)
            val second = media.completeUpload(owner, slot.mediaAsset.mediaAssetId, request)

            assertEquals(first.mediaAsset.mediaAssetId, second.mediaAsset.mediaAssetId)
            assertEquals("pending", second.mediaAsset.moderationStatus)
            assertEquals(1, dataSource.itemCount(UUID.fromString(slot.mediaAsset.mediaAssetId)))
        }

    @Test
    fun anApprovedPrivateAssetInAProfileAlbumNeverShowsPublicly() =
        runBlocking {
            // Defence in depth: even if a row slipped past completion, public queries require a
            // profile-kind asset.
            val owner = dataSource.insertVerificationUser("Owner")
            val viewer = dataSource.insertVerificationUser("Viewer")
            val media = mediaService(flagged = false)
            val slot =
                media.createUploadSlot(
                    owner,
                    CreateMediaUploadRequestDto(albumKind = "private", mediaType = "image", contentType = "image/jpeg", byteSize = 1_000),
                )
            media.completeUpload(owner, slot.mediaAsset.mediaAssetId, CompleteMediaUploadRequestDto(albumId = slot.album.albumId))
            val assetId = UUID.fromString(slot.mediaAsset.mediaAssetId)
            val profileAlbumId = UUID.fromString(media.listOwnAlbum(owner, "profile").album.albumId)
            dataSource.moveItemTo(assetId, profileAlbumId)

            val public = assertNotNull(JdbcProfileRepository(dataSource).getPublicProfile(viewer, owner))
            assertEquals(null, public.profilePhotoMediaAssetId)
            // Nor through the album listing another member's view goes through.
            val asViewer = JdbcMediaRepository(dataSource).listAlbumItemsForViewer(viewerUserId = viewer, albumId = profileAlbumId)
            assertTrue(asViewer.orEmpty().none { it.mediaAsset.mediaAssetId == assetId })
            // The owner still sees their own item wherever it sits.
            val asOwner = JdbcMediaRepository(dataSource).listAlbumItemsForViewer(viewerUserId = owner, albumId = profileAlbumId)
            assertTrue(asOwner.orEmpty().any { it.mediaAsset.mediaAssetId == assetId })
        }

    private fun DataSource.assetStatus(assetId: UUID): Pair<String, String> =
        connection.use { connection ->
            connection.prepareStatement("SELECT processing_status, moderation_status FROM media_assets WHERE id = ?").use { statement ->
                statement.setObject(1, assetId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1) to rs.getString(2)
                }
            }
        }

    private fun DataSource.itemCount(assetId: UUID): Int =
        connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM media_album_items WHERE media_asset_id = ?").use { statement ->
                statement.setObject(1, assetId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun DataSource.moveItemTo(
        assetId: UUID,
        albumId: UUID,
    ) {
        connection.use { connection ->
            connection.prepareStatement("UPDATE media_album_items SET album_id = ? WHERE media_asset_id = ?").use { statement ->
                statement.setObject(1, albumId)
                statement.setObject(2, assetId)
                statement.executeUpdate()
            }
        }
    }

    private fun mediaService(flagged: Boolean): MediaService =
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
        )

    private suspend fun MediaService.uploadProfilePhoto(owner: UUID): UUID {
        val slot =
            createUploadSlot(
                owner,
                CreateMediaUploadRequestDto(albumKind = "profile", mediaType = "image", contentType = "image/jpeg", byteSize = 1_000),
            )
        completeUpload(owner, slot.mediaAsset.mediaAssetId, CompleteMediaUploadRequestDto(albumId = slot.album.albumId))
        return UUID.fromString(slot.mediaAsset.mediaAssetId)
    }
}
