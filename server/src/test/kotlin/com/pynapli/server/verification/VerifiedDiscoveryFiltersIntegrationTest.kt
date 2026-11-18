package com.pynapli.server.verification

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.MediaVerificationSelfieGateway
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.CreatePhotoVerificationUploadRequestDto
import com.pynapli.shared.network.dto.SubmitPhotoVerificationRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * V-2: the Grid and Swipe "verified only" filters (and Grid "face photo verified") match people whose
 * selfie verification a reviewer approved, and stop matching them once it is revoked. Verification
 * goes through the real review flow; nothing writes the columns directly.
 */
class VerifiedDiscoveryFiltersIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun verifiedFiltersFollowReviewedPhotoVerification() =
        runBlocking {
            val viewer = UUID.randomUUID()
            val verified = UUID.randomUUID()
            val unverified = UUID.randomUUID()
            dataSource.insertDiscoverableUser(viewer, "Viewer", age = 38, longitude = -122.0841, latitude = 37.4220)
            dataSource.insertDiscoverableUser(verified, "Vera", age = 35, longitude = -122.0836, latitude = 37.4226)
            dataSource.insertDiscoverableUser(unverified, "Una", age = 33, longitude = -122.0838, latitude = 37.4228)
            val service = service()
            // Fresh repositories per read: each holds a 5-second in-process rank-window cache, so a
            // reused instance would answer from the window cached before verification changed.
            fun grid() = JdbcGridRepository(dataSource)

            fun swipe() = JdbcSwipeRepository(dataSource)

            assertEquals(emptySet(), gridIds(grid(), viewer, DiscoveryFilters(verifiedOnly = true)))

            service.verify(verified)

            assertEquals(setOf(verified), gridIds(grid(), viewer, DiscoveryFilters(verifiedOnly = true)))
            assertEquals(setOf(verified), gridIds(grid(), viewer, DiscoveryFilters(facePhotoVerifiedOnly = true)))
            assertEquals(
                setOf(verified),
                swipe()
                    .queryDeck(viewer, radiusMeters = 100_000, limit = 10, filters = DiscoveryFilters(verifiedOnly = true))
                    .cards
                    .map { it.userId }
                    .toSet(),
            )
            val card = grid().queryNearby(viewer, radiusMeters = 100_000, limit = 10).cards.single { it.userId == verified }
            assertEquals(true, card.verified)
            assertEquals(false, grid().queryNearby(viewer, radiusMeters = 100_000, limit = 10).cards.single { it.userId == unverified }.verified)

            service.revoke(verified.toString(), "senior-1", "Selfie did not match.")

            assertEquals(emptySet(), gridIds(grid(), viewer, DiscoveryFilters(verifiedOnly = true)))
        }

    private suspend fun gridIds(
        grid: JdbcGridRepository,
        viewer: UUID,
        filters: DiscoveryFilters,
    ): Set<UUID> =
        grid
            .queryNearby(viewer, radiusMeters = 100_000, limit = 10, filters = filters)
            .cards
            .map { it.userId }
            .toSet()

    private suspend fun PhotoVerificationService.verify(userId: UUID) {
        val challengeId = assertNotNull(requestChallenge(userId).challenge).challengeId
        val slot =
            createSelfieUpload(
                userId,
                CreatePhotoVerificationUploadRequestDto(challengeId = challengeId, contentType = "image/jpeg", byteSize = 40_000),
            )
        submit(userId, SubmitPhotoVerificationRequestDto(challengeId, slot.mediaAssetId))
        approve(challengeId, "senior-1", null)
    }

    private fun service(): PhotoVerificationService =
        PhotoVerificationService(
            repository = JdbcPhotoVerificationRepository(dataSource),
            selfieMedia =
                MediaVerificationSelfieGateway(
                    repository = JdbcMediaRepository(dataSource),
                    uploadSigner = StaticMediaUploadSigner("gcs", "verification-test", "http://upload.test", clock = clock),
                    selfieUrlResolver = LocalMediaAssetUrlResolver("http://media.test"),
                    mediaAssetUrlResolver = LocalMediaAssetUrlResolver("http://media.test"),
                    objectDeleter = null,
                ),
            clock = clock,
        )
}
