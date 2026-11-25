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
