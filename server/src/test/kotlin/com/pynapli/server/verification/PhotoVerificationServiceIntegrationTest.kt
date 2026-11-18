package com.pynapli.server.verification

import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.MediaObjectDeleter
import com.pynapli.server.media.MediaVerificationSelfieGateway
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.JdbcRiskRepository
import com.pynapli.server.safety.RateLimitOutageFallback
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskService
import com.pynapli.server.safety.VerificationRiskSignalPublisher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.CreatePhotoVerificationUploadRequestDto
import com.pynapli.shared.network.dto.PhotoVerificationStatus
import com.pynapli.shared.network.dto.SubmitPhotoVerificationRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoVerificationServiceIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val clock = MutableClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
    private val deleter = RecordingObjectDeleter()
    private val notifications = RecordingNotificationPublisher()
    private var faces: Int? = null

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun challengeIssuesAServerPoseValidForTenMinutes() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()

            assertEquals(PhotoVerificationStatus.NONE, service.status(userId).status)
            val status = service.requestChallenge(userId)

            assertEquals(PhotoVerificationStatus.CHALLENGE, status.status)
            val challenge = assertNotNull(status.challenge)
            assertNotNull(PhotoVerificationPoses.byKey(challenge.poseKey))
            assertEquals(PhotoVerificationPoses.byKey(challenge.poseKey)?.instruction, challenge.instruction)
            assertEquals(clock.instant().plus(Duration.ofMinutes(10)).toString(), challenge.expiresAt)
        }

    @Test
    fun submittedSelfieWaitsForReviewAndStaysPrivate() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()

            val (challengeId, assetId) = service.challengeAndUpload(userId)
            val status = service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))

            assertEquals(PhotoVerificationStatus.PENDING, status.status)
            assertEquals(clock.instant().toString(), status.submittedAt)
            val asset = dataSource.selfieAssetState(UUID.fromString(assetId))
            assertEquals("verification", asset.intendedKind)
            assertEquals("held", asset.moderationStatus)
            assertEquals(0, dataSource.albumItemCount(UUID.fromString(assetId)))
            val again = assertFailsWith<VerificationException> { service.requestChallenge(userId) }
            assertEquals("PHOTO_VERIFICATION_PENDING", again.code)
        }

    @Test
    fun anExpiredChallengeCannotTakeASelfie() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()
            val challengeId = assertNotNull(service.requestChallenge(userId).challenge).challengeId

            clock.advance(Duration.ofMinutes(11))

            val error = assertFailsWith<VerificationException> { service.createSelfieUpload(userId, uploadRequest(challengeId)) }
            assertEquals("PHOTO_VERIFICATION_CHALLENGE_EXPIRED", error.code)
            assertEquals(PhotoVerificationStatus.NONE, service.status(userId).status)
        }

    @Test
    fun anotherUsersChallengeIsNotFound() =
        runBlocking {
            val owner = dataSource.insertVerificationUser()
            val other = dataSource.insertVerificationUser()
            val service = service()
            val challengeId = assertNotNull(service.requestChallenge(owner).challenge).challengeId

            val error = assertFailsWith<VerificationException> { service.createSelfieUpload(other, uploadRequest(challengeId)) }
            assertEquals("PHOTO_VERIFICATION_CHALLENGE_NOT_FOUND", error.code)
        }

    @Test
    fun faceCheckRejectsASelfieWithNoFaceOrSeveralFaces() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()

            faces = 0
            val (challengeId, assetId) = service.challengeAndUpload(userId)
            val noFace = service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))
            assertEquals(PhotoVerificationStatus.REJECTED, noFace.status)
            assertNotNull(noFace.rejectionReason)
            assertEquals(clock.instant().plus(Duration.ofMinutes(10)).toString(), noFace.retryAvailableAt)
            assertEquals("face_check", dataSource.latestDecisionSource(userId))

            val cooldown = assertFailsWith<VerificationException> { service.requestChallenge(userId) }
            assertEquals("PHOTO_VERIFICATION_COOLDOWN", cooldown.code)

            clock.advance(Duration.ofMinutes(11))
            faces = 2
            val (secondChallenge, secondAsset) = service.challengeAndUpload(userId)
            val twoFaces = service.submit(userId, SubmitPhotoVerificationRequestDto(secondChallenge, secondAsset))
            assertEquals(PhotoVerificationStatus.REJECTED, twoFaces.status)
            assertNull(dataSource.facePhotoVerifiedAt(userId))
        }

    @Test
    fun oneFaceStillWaitsForAHumanReviewer() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()
            faces = 1

            val (challengeId, assetId) = service.challengeAndUpload(userId)
            val status = service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))

            assertEquals(PhotoVerificationStatus.PENDING, status.status)
            assertNull(dataSource.facePhotoVerifiedAt(userId))
        }

    @Test
    fun approvalWritesVerificationTimestampsAuditAndNotifies() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()
            val (challengeId, assetId) = service.challengeAndUpload(userId)
            service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))

            clock.advance(Duration.ofHours(2))
            val decision = service.approve(challengeId, actorId = "senior-1", reason = "pose matches")

            assertEquals("approved", decision.status)
            assertEquals(clock.instant(), dataSource.facePhotoVerifiedAt(userId))
            assertEquals(clock.instant(), dataSource.verifiedAt(userId))
            assertEquals(PhotoVerificationStatus.VERIFIED, service.status(userId).status)
            assertEquals("senior-1", dataSource.latestReviewer(userId))
            assertEquals(1, dataSource.auditCount("photo_verification.approve", UUID.fromString(challengeId)))
            assertEquals(NotificationKind.SYSTEM_SAFETY, notifications.drafts.single().kind)
            assertEquals(userId, notifications.drafts.single().recipientUserId)
            val again = assertFailsWith<VerificationException> { service.requestChallenge(userId) }
            assertEquals("PHOTO_VERIFICATION_ALREADY_VERIFIED", again.code)
            val twice = assertFailsWith<VerificationException> { service.approve(challengeId, "senior-1", null) }
            assertEquals("PHOTO_VERIFICATION_NOT_PENDING", twice.code)
        }

    @Test
    fun rejectionNeedsAReasonShowsItAndAllowsRetryAfterADay() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()
            val (challengeId, assetId) = service.challengeAndUpload(userId)
            service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))

            val missing = assertFailsWith<VerificationException> { service.reject(challengeId, "senior-1", " ") }
            assertEquals("PHOTO_VERIFICATION_REASON_REQUIRED", missing.code)

            service.reject(challengeId, "senior-1", "Your face is not visible in the selfie.")
            val status = service.status(userId)
            assertEquals(PhotoVerificationStatus.REJECTED, status.status)
            assertEquals("Your face is not visible in the selfie.", status.rejectionReason)
            assertEquals(clock.instant().plus(Duration.ofHours(24)).toString(), status.retryAvailableAt)
            assertEquals(1, dataSource.auditCount("photo_verification.reject", UUID.fromString(challengeId)))
            assertNull(dataSource.facePhotoVerifiedAt(userId))

            assertEquals("PHOTO_VERIFICATION_COOLDOWN", assertFailsWith<VerificationException> { service.requestChallenge(userId) }.code)
            clock.advance(Duration.ofHours(25))
            assertEquals(PhotoVerificationStatus.CHALLENGE, service.requestChallenge(userId).status)
        }

    @Test
    fun revokingRemovesVerification() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()
            val (challengeId, assetId) = service.challengeAndUpload(userId)
            service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))
            service.approve(challengeId, "senior-1", null)

            service.revoke(userId.toString(), "senior-1", "Photos show a different person.")

            assertNull(dataSource.facePhotoVerifiedAt(userId))
            assertNull(dataSource.verifiedAt(userId))
            val status = service.status(userId)
            assertEquals(PhotoVerificationStatus.REJECTED, status.status)
            assertEquals("Photos show a different person.", status.rejectionReason)
            val notVerified = assertFailsWith<VerificationException> { service.revoke(userId.toString(), "senior-1", "again") }
            assertEquals("PHOTO_VERIFICATION_NOT_VERIFIED", notVerified.code)
        }

    @Test
    fun approvalLowersAccountRiskOnceEvenAfterRevokeAndReapproval() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val risk = RiskService(JdbcRiskRepository(dataSource, clock))
            risk.recordImpossibleTravel(userId, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            val service = service(verificationRiskSignals = risk)

            val (challengeId, assetId) = service.challengeAndUpload(userId)
            service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))
            service.approve(challengeId, "senior-1", null)
            assertEquals(RiskService.IMPOSSIBLE_TRAVEL_DELTA + RiskService.PHOTO_VERIFIED_DELTA, risk.getState(userId)?.score)

            // Revoked, then verified again with a new selfie: the reduction is not applied twice.
            service.revoke(userId.toString(), "senior-1", "Photos show a different person.")
            clock.advance(Duration.ofHours(25))
            val (secondChallenge, secondAsset) = service.challengeAndUpload(userId)
            service.submit(userId, SubmitPhotoVerificationRequestDto(secondChallenge, secondAsset))
            service.approve(secondChallenge, "senior-1", null)

            assertEquals(PhotoVerificationStatus.VERIFIED, service.status(userId).status)
            assertEquals(RiskService.IMPOSSIBLE_TRAVEL_DELTA + RiskService.PHOTO_VERIFIED_DELTA, risk.getState(userId)?.score)
        }

    @Test
    fun aRejectedSelfieDoesNotLowerAccountRisk() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val risk = RiskService(JdbcRiskRepository(dataSource, clock))
            risk.recordImpossibleTravel(userId, distanceKm = 900.0, elapsedSeconds = 600, speedKmh = 5400.0)
            val service = service(verificationRiskSignals = risk)
            val (challengeId, assetId) = service.challengeAndUpload(userId)
            service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))

            service.reject(challengeId, "senior-1", "Your face is not visible in the selfie.")

            assertEquals(RiskService.IMPOSSIBLE_TRAVEL_DELTA, risk.getState(userId)?.score)
        }

    @Test
    fun reviewQueueListsOldestFirstWithAShortLivedSelfieUrl() =
        runBlocking {
            val first = dataSource.insertVerificationUser()
            val second = dataSource.insertVerificationUser()
            val service = service()
            val (firstChallenge, firstAsset) = service.challengeAndUpload(first)
            service.submit(first, SubmitPhotoVerificationRequestDto(firstChallenge, firstAsset))
            clock.advance(Duration.ofMinutes(1))
            val (secondChallenge, secondAsset) = service.challengeAndUpload(second)
            service.submit(second, SubmitPhotoVerificationRequestDto(secondChallenge, secondAsset))

            val page = service.listPending(actorId = "senior-1", reason = "queue", limitText = "1", cursor = null)

            assertEquals(listOf(firstChallenge), page.items.map { it.verificationId })
            assertEquals("http://media.test/v1/media/uploads/local/$firstAsset", page.items.single().selfieUrl)
            val next = service.listPending("senior-1", "queue", "5", assertNotNull(page.nextCursor))
            assertEquals(listOf(secondChallenge), next.items.map { it.verificationId })
            assertEquals(2, dataSource.auditCount("photo_verification.queue_view", null))
        }

    @Test
    fun challengesAreRateLimited() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service(rateLimitService = RateLimitService(RateLimitOutageFallback().repository))

            repeat(10) { service.requestChallenge(userId) }
            val error = assertFailsWith<VerificationException> { service.requestChallenge(userId) }
            assertEquals("RATE_LIMITED", error.code)
        }

    @Test
    fun selfiesArePurgedAfterTheRetentionWindow() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val abandonedUser = dataSource.insertVerificationUser()
            val service = service()
            val (challengeId, assetId) = service.challengeAndUpload(userId)
            service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))
            service.approve(challengeId, "senior-1", null)
            val (_, abandonedAsset) = service.challengeAndUpload(abandonedUser)

            clock.advance(Duration.ofDays(6))
            assertEquals(1, service.purgeExpiredSelfies())
            assertEquals(listOf(abandonedAsset), deleter.deletedAssetIds)
            assertTrue(dataSource.assetExists(UUID.fromString(assetId)))

            clock.advance(Duration.ofDays(2))
            assertEquals(1, service.purgeExpiredSelfies())
            assertEquals(listOf(abandonedAsset, assetId), deleter.deletedAssetIds)
            assertTrue(!dataSource.assetExists(UUID.fromString(assetId)))
            assertNotNull(dataSource.selfiePurgedAt(UUID.fromString(challengeId)))
            assertEquals(PhotoVerificationStatus.VERIFIED, service.status(userId).status)
        }

    @Test
    fun aSelfieNobodyReviewsIsPurgedAndThePersonCanTryAgain() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()
            val (challengeId, assetId) = service.challengeAndUpload(userId)
            service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))

            clock.advance(PhotoVerificationService.UNREVIEWED_SELFIE_RETENTION.minus(Duration.ofHours(1)))
            assertEquals(0, service.purgeExpiredSelfies())
            assertEquals(PhotoVerificationStatus.PENDING, service.status(userId).status)

            clock.advance(Duration.ofHours(2))
            assertEquals(1, service.purgeExpiredSelfies())
            assertEquals(listOf(assetId), deleter.deletedAssetIds)
            assertTrue(!dataSource.assetExists(UUID.fromString(assetId)))
            assertNotNull(dataSource.selfiePurgedAt(UUID.fromString(challengeId)))
            // The attempt left the review queue, and the person is free to start over.
            assertTrue(service.listPending("senior-1", "queue", null, null).items.isEmpty())
            assertEquals(PhotoVerificationStatus.NONE, service.status(userId).status)
            assertEquals(PhotoVerificationStatus.CHALLENGE, service.requestChallenge(userId).status)
        }

    @Test
    fun aSelfieCannotBeAttachedToAPhotoAlbum() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service()
            val (_, selfieId) = service.challengeAndUpload(userId)
            val media =
                com.pynapli.server.media.MediaService(
                    repository = JdbcMediaRepository(dataSource),
                    uploadSigner = StaticMediaUploadSigner("gcs", "verification-test", "http://upload.test", clock = clock),
                    moderationScanner =
                        com.pynapli.server.media
                            .AutoApproveMediaModerationScanner(),
                )
            val albumSlot =
                media.createUploadSlot(
                    userId,
                    com.pynapli.shared.network.dto.CreateMediaUploadRequestDto(
                        albumKind = "profile",
                        mediaType = "image",
                        contentType = "image/jpeg",
                        byteSize = 1_000,
                    ),
                )

            val error =
                assertFailsWith<com.pynapli.server.media.MediaException> {
                    media.completeUpload(
                        userId,
                        selfieId,
                        com.pynapli.shared.network.dto
                            .CompleteMediaUploadRequestDto(albumId = albumSlot.album.albumId),
                    )
                }
            assertEquals("MEDIA_ALBUM_NOT_FOUND", error.code)
            assertEquals(0, dataSource.albumItemCount(UUID.fromString(selfieId)))
        }

    @Test
    fun aStaleChallengeDoesNotSpendTheSubmitBudget() =
        runBlocking {
            val userId = dataSource.insertVerificationUser()
            val service = service(rateLimitService = RateLimitService(RateLimitOutageFallback().repository))
            val (challengeId, assetId) = service.challengeAndUpload(userId)
            clock.advance(Duration.ofMinutes(11))

            repeat(6) {
                val error =
                    assertFailsWith<VerificationException> {
                        service.submit(userId, SubmitPhotoVerificationRequestDto(challengeId, assetId))
                    }
                assertEquals("PHOTO_VERIFICATION_CHALLENGE_EXPIRED", error.code)
            }
        }

    private suspend fun PhotoVerificationService.challengeAndUpload(userId: UUID): Pair<String, String> {
        val challengeId = assertNotNull(requestChallenge(userId).challenge).challengeId
        val slot = createSelfieUpload(userId, uploadRequest(challengeId))
        return challengeId to slot.mediaAssetId
    }

    private fun uploadRequest(challengeId: String) =
        CreatePhotoVerificationUploadRequestDto(
            challengeId = challengeId,
            contentType = "image/jpeg",
            byteSize = 120_000,
            pixelWidth = 1080,
            pixelHeight = 1440,
        )

    private fun service(
        rateLimitService: RateLimitService? = null,
        verificationRiskSignals: VerificationRiskSignalPublisher? = null,
    ): PhotoVerificationService =
        PhotoVerificationService(
            repository = JdbcPhotoVerificationRepository(dataSource),
            selfieMedia =
                MediaVerificationSelfieGateway(
                    repository = JdbcMediaRepository(dataSource),
                    uploadSigner =
                        StaticMediaUploadSigner(
                            storageProvider = "gcs",
                            storageBucket = "verification-test",
                            uploadBaseUrl = "http://upload.test",
                            clock = clock,
                        ),
                    selfieUrlResolver = LocalMediaAssetUrlResolver("http://media.test"),
                    mediaAssetUrlResolver = LocalMediaAssetUrlResolver("http://media.test"),
                    objectDeleter = deleter,
                ),
            faceDetector = { faces },
            rateLimitService = rateLimitService,
            notificationPublisher = notifications,
            clock = clock,
            poseRandom = Random(7),
            verificationRiskSignals = verificationRiskSignals,
        )
}

internal class MutableClock(
    private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

private class RecordingObjectDeleter : MediaObjectDeleter {
    val deletedAssetIds = mutableListOf<String>()

    override suspend fun delete(
        storageProvider: String,
        storageBucket: String,
        storageObjectKey: String,
        mediaAssetId: UUID,
    ): Boolean {
        deletedAssetIds += mediaAssetId.toString()
        return true
    }
}

private class RecordingNotificationPublisher : NotificationPublisher {
    val drafts = mutableListOf<NotificationEventDraft>()

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        drafts += notification
        return null
    }
}

internal fun DataSource.insertVerificationUser(displayName: String = "Verifier"): UUID {
    val userId = UUID.randomUUID()
    connection.use { connection ->
        connection.prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection
            .prepareStatement(
                "INSERT INTO profiles (user_id, display_name, moderation_status, visibility_status) VALUES (?, ?, 'approved', 'visible')",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, displayName)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                "INSERT INTO discovery_settings (user_id, grid_visible, swipe_visible, show_distance) VALUES (?, true, true, true)",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
    }
    return userId
}

private data class SelfieAssetState(
    val intendedKind: String?,
    val moderationStatus: String,
)

private fun DataSource.selfieAssetState(assetId: UUID): SelfieAssetState =
    connection.use { connection ->
        connection.prepareStatement("SELECT intended_album_kind, moderation_status FROM media_assets WHERE id = ?").use { statement ->
            statement.setObject(1, assetId)
            statement.executeQuery().use { rs ->
                check(rs.next())
                SelfieAssetState(rs.getString("intended_album_kind"), rs.getString("moderation_status"))
            }
        }
    }

private fun DataSource.albumItemCount(assetId: UUID): Int = count("SELECT count(*) FROM media_album_items WHERE media_asset_id = ?", assetId)

private fun DataSource.assetExists(assetId: UUID): Boolean = count("SELECT count(*) FROM media_assets WHERE id = ?", assetId) > 0

internal fun DataSource.facePhotoVerifiedAt(userId: UUID): Instant? = instant("SELECT face_photo_verified_at FROM profiles WHERE user_id = ?", userId)

internal fun DataSource.verifiedAt(userId: UUID): Instant? = instant("SELECT verified_at FROM profiles WHERE user_id = ?", userId)

private fun DataSource.selfiePurgedAt(verificationId: UUID): Instant? = instant("SELECT selfie_purged_at FROM photo_verifications WHERE id = ?", verificationId)

private fun DataSource.latestDecisionSource(userId: UUID): String? = latestColumn(userId, "decision_source")

private fun DataSource.latestReviewer(userId: UUID): String? = latestColumn(userId, "reviewer_id")

private fun DataSource.latestColumn(
    userId: UUID,
    column: String,
): String? =
    connection.use { connection ->
        connection
            .prepareStatement("SELECT $column FROM photo_verifications WHERE user_id = ? ORDER BY created_at DESC LIMIT 1")
            .use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
    }

internal fun DataSource.auditCount(
    action: String,
    targetId: UUID?,
): Int =
    connection.use { connection ->
        val sql =
            if (targetId == null) {
                "SELECT count(*) FROM admin_audit_log WHERE action = ?"
            } else {
                "SELECT count(*) FROM admin_audit_log WHERE action = ? AND target_id = ?"
            }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, action)
            targetId?.let { statement.setObject(2, it) }
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

private fun DataSource.count(
    sql: String,
    id: UUID,
): Int =
    connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

private fun DataSource.instant(
    sql: String,
    id: UUID,
): Instant? =
    connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
        }
    }
