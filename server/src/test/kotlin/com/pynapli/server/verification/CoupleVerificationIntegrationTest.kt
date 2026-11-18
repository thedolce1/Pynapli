package com.pynapli.server.verification

import com.pynapli.server.grid.hydrateGridCards
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.MediaVerificationSelfieGateway
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.CreatePhotoVerificationUploadRequestDto
import com.pynapli.shared.network.dto.SubmitPhotoVerificationRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * V-2: a couple is verified only when both linked partners are individually photo-verified, and the
 * verified-only partner visibility follows real verification. Verification is driven through the
 * real review flow, not by writing the columns directly.
 */
class CoupleVerificationIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun gridCardIsCoupleVerifiedOnlyWhileBothLinkedPartnersAreVerified() =
        runBlocking {
            val (viewer, owner, partner) = threeUsers()
            dataSource.linkPartners(owner, partner)
            val service = service()

            assertEquals(GridVerification(verified = false, coupleVerified = false), gridVerification(viewer, owner))

            service.verify(owner)
            assertEquals(GridVerification(verified = true, coupleVerified = false), gridVerification(viewer, owner))

            service.verify(partner)
            assertEquals(GridVerification(verified = true, coupleVerified = true), gridVerification(viewer, owner))
            assertEquals(GridVerification(verified = true, coupleVerified = true), gridVerification(viewer, partner))

            // Either partner losing verification removes it.
            service.revoke(partner.toString(), "senior-1", "Photos show someone else.")
            assertEquals(GridVerification(verified = true, coupleVerified = false), gridVerification(viewer, owner))

            clock.advance(java.time.Duration.ofHours(25))
            service.verify(partner)
            assertEquals(GridVerification(verified = true, coupleVerified = true), gridVerification(viewer, owner))

            // Unlinking removes it.
            dataSource.unlinkPartners(owner, partner)
            assertEquals(GridVerification(verified = true, coupleVerified = false), gridVerification(viewer, owner))
        }

    @Test
    fun profileDetailShowsCoupleVerifiedOnlyWhenThePartnerMiniIsVisible() =
        runBlocking {
            val (viewer, owner, partner) = threeUsers()
            dataSource.linkPartners(owner, partner)
            val service = service()
            val profiles = JdbcProfileRepository(dataSource)

            service.verify(owner)
            assertEquals(false, assertNotNull(profiles.getPublicProfile(viewer, owner)).coupleVerified)

            service.verify(partner)
            val detail = assertNotNull(profiles.getPublicProfile(viewer, owner))
            assertEquals(true, detail.verified)
            assertEquals(true, detail.coupleVerified)

            // A hidden partner is not revealed through the couple badge either.
            dataSource.setPartnerVisibility(owner, "hidden")
            val hidden = assertNotNull(profiles.getPublicProfile(viewer, owner))
            assertNull(hidden.linkedPartnerUserId)
            assertEquals(false, hidden.coupleVerified)
            assertEquals(true, hidden.verified)
        }

    @Test
    fun verifiedOnlyPartnerVisibilityOpensOnceTheViewerIsVerified() =
        runBlocking {
            val (viewer, owner, partner) = threeUsers()
            dataSource.linkPartners(owner, partner)
            dataSource.setPartnerVisibility(owner, "verified_only")
            val service = service()
            val profiles = JdbcProfileRepository(dataSource)

            assertNull(assertNotNull(profiles.getPublicProfile(viewer, owner)).linkedPartnerUserId)
            assertNull(gridPartner(viewer, owner))

            service.verify(viewer)

            assertEquals(partner, assertNotNull(profiles.getPublicProfile(viewer, owner)).linkedPartnerUserId)
            assertEquals(partner, gridPartner(viewer, owner))

            service.revoke(viewer.toString(), "senior-1", "Selfie was not you.")
            assertNull(assertNotNull(profiles.getPublicProfile(viewer, owner)).linkedPartnerUserId)
        }

    private data class GridVerification(
        val verified: Boolean,
        val coupleVerified: Boolean,
    )

    private fun gridVerification(
        viewer: UUID,
        owner: UUID,
    ): GridVerification {
        val hydration = assertNotNull(dataSource.connection.use { it.hydrateGridCards(viewer, listOf(owner)) }[owner])
        return GridVerification(verified = hydration.verified, coupleVerified = hydration.coupleVerified)
    }

    private fun gridPartner(
        viewer: UUID,
        owner: UUID,
    ): UUID? = dataSource.connection.use { it.hydrateGridCards(viewer, listOf(owner)) }[owner]?.linkedPartnerUserId

    private fun threeUsers(): Triple<UUID, UUID, UUID> =
        Triple(
            dataSource.insertVerificationUser("Viewer"),
            dataSource.insertVerificationUser("Owner"),
            dataSource.insertVerificationUser("Partner"),
        )

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
                    mediaAssetUrlResolver = LocalMediaAssetUrlResolver("http://media.test"),
                    objectDeleter = null,
                ),
            clock = clock,
        )
}

private fun DataSource.linkPartners(
    a: UUID,
    b: UUID,
) {
    connection.use { connection ->
        listOf(a to b, b to a).forEach { (user, linked) ->
            connection
                .prepareStatement(
                    "INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at) VALUES (?, ?, ?, 'active', now())",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, user)
                    statement.setObject(3, linked)
                    statement.executeUpdate()
                }
        }
    }
}

private fun DataSource.unlinkPartners(
    a: UUID,
    b: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE user_profile_links SET status = 'unlinked', unlinked_at = now() WHERE user_id IN (?, ?) AND status = 'active'",
            ).use { statement ->
                statement.setObject(1, a)
                statement.setObject(2, b)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.setPartnerVisibility(
    userId: UUID,
    value: String,
) {
    connection.use { connection ->
        connection.prepareStatement("UPDATE discovery_settings SET partner_visibility = ? WHERE user_id = ?").use { statement ->
            statement.setString(1, value)
            statement.setObject(2, userId)
            statement.executeUpdate()
        }
    }
}
