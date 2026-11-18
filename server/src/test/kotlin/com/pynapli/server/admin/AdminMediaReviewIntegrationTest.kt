package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.ManualReviewMediaModerationScanner
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.testServerConfig
import com.pynapli.server.verification.auditCount
import com.pynapli.server.verification.insertVerificationUser
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V-3: pending profile photos (manual-review scanner) can be listed, approved and rejected by an admin. */
class AdminMediaReviewIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val notifications = RecordingPublisher()

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun pendingProfilePhotosListOldestFirstInPages() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val first = uploadPendingProfilePhoto(owner)
            val second = uploadPendingProfilePhoto(owner)
            dataSource.setCreatedAt(first, "2026-11-01T10:00:00Z")
            dataSource.setCreatedAt(second, "2026-11-02T10:00:00Z")

            val page = service().listPending(limitText = "1", cursor = null)
            assertEquals(listOf(first.toString()), page.items.map { it.mediaAssetId })
            assertEquals(owner.toString(), page.items.single().ownerUserId)
            assertEquals("http://media.test/v1/media/uploads/local/$first", page.items.single().url)

            val next = service().listPending(limitText = "10", cursor = assertNotNull(page.nextCursor))
            assertEquals(listOf(second.toString()), next.items.map { it.mediaAssetId })
            assertEquals(null, next.nextCursor)
        }

    @Test
    fun approvalMakesThePhotoVisibleAndIsAudited() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val viewer = dataSource.insertVerificationUser("Viewer")
            val asset = uploadPendingProfilePhoto(owner)
            val profiles = JdbcProfileRepository(dataSource)
            assertEquals(null, assertNotNull(profiles.getPublicProfile(viewer, owner)).profilePhotoMediaAssetId)

            val decision = service().approve(asset.toString(), actorId = "ops-1", reason = null)

            assertEquals("approved", decision.moderationStatus)
            assertEquals(asset, assertNotNull(profiles.getPublicProfile(viewer, owner)).profilePhotoMediaAssetId)
            assertEquals(1, dataSource.auditCount("media_review.approve", asset))
            assertTrue(service().listPending(null, null).items.isEmpty())
            val twice = assertFailsWith<ModerationException> { service().approve(asset.toString(), "ops-1", null) }
            assertEquals("MEDIA_REVIEW_NOT_PENDING", twice.code)
        }

    @Test
    fun rejectionNeedsAReasonRemovesThePhotoAndNotifiesTheOwner() =
        runBlocking {
            val owner = dataSource.insertVerificationUser("Owner")
            val asset = uploadPendingProfilePhoto(owner)

            val missing = assertFailsWith<ModerationException> { service().reject(asset.toString(), "ops-1", "") }
            assertEquals("MODERATION_REASON_REQUIRED", missing.code)

            val decision = service().reject(asset.toString(), "ops-1", "Explicit content in a public photo.")

            assertEquals("removed", decision.moderationStatus)
            assertEquals("removed", dataSource.moderationStatus(asset))
            assertEquals(1, dataSource.auditCount("media_review.reject", asset))
            val draft = notifications.drafts.single()
            assertEquals(NotificationKind.SYSTEM_SAFETY, draft.kind)
            assertEquals(owner, draft.recipientUserId)
        }

    @Test
    fun unknownAssetIsNotFound() =
        runBlocking {
            val error = assertFailsWith<ModerationException> { service().approve(UUID.randomUUID().toString(), "ops-1", null) }
            assertEquals("MEDIA_REVIEW_NOT_FOUND", error.code)
        }

    @Test
    fun anOperatorCanReviewButTheTokenIsRequired() =
        testApplication {
            val owner = dataSource.insertVerificationUser("Owner")
            val asset = runBlocking { uploadPendingProfilePhoto(owner) }
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminMediaReviewRoutes(config(), service())
            }

            val noToken = client.get("/v1/admin/media/pending") { header("X-Admin-Id", "ops-1") }
            assertEquals(HttpStatusCode.Unauthorized, noToken.status)

            val list = client.get("/v1/admin/media/pending") { adminHeaders() }
            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains(asset.toString()))

            val noReason =
                client.post("/v1/admin/media/$asset/reject") {
                    adminHeaders()
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.BadRequest, noReason.status)

            val approve =
                client.post("/v1/admin/media/$asset/approve") {
                    adminHeaders()
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, approve.status, approve.bodyAsText())
        }

    private fun io.ktor.client.request.HttpRequestBuilder.adminHeaders() {
        header("X-Admin-Token", "test-admin-token")
        header("X-Admin-Id", "ops-1")
    }

    private fun config(): ServerConfig =
        testServerConfig().copy(
            adminApiToken = "test-admin-token",
            // Roles configured: ops-1 is only an operator, and that is enough here.
            adminRoles = mapOf("ops-1" to ADMIN_ROLE_OPERATOR),
            adminMfaEnforced = true,
        )

    private fun service(): AdminMediaReviewService =
        AdminMediaReviewService(
            repository = JdbcAdminMediaReviewRepository(dataSource),
            mediaRepository = JdbcMediaRepository(dataSource),
            mediaAssetUrlResolver = LocalMediaAssetUrlResolver("http://media.test"),
            notificationPublisher = notifications,
        )

    private suspend fun uploadPendingProfilePhoto(owner: UUID): UUID {
        val media =
            MediaService(
                repository = JdbcMediaRepository(dataSource),
                uploadSigner = StaticMediaUploadSigner("gcs", "media-test", "http://upload.test"),
                moderationScanner = ManualReviewMediaModerationScanner(),
            )
        val slot =
            media.createUploadSlot(
                owner,
                CreateMediaUploadRequestDto(albumKind = "profile", mediaType = "image", contentType = "image/jpeg", byteSize = 1_000),
            )
        val item =
            media.completeUpload(
                owner,
                slot.mediaAsset.mediaAssetId,
                CompleteMediaUploadRequestDto(albumId = slot.album.albumId, sortOrder = countItems(owner)),
            )
        assertEquals("pending", item.mediaAsset.moderationStatus)
        return UUID.fromString(slot.mediaAsset.mediaAssetId)
    }

    private fun countItems(owner: UUID): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM media_album_items WHERE owner_user_id = ?").use { statement ->
                statement.setObject(1, owner)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
}

private class RecordingPublisher : NotificationPublisher {
    val drafts = mutableListOf<NotificationEventDraft>()

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        drafts += notification
        return null
    }
}

private fun DataSource.setCreatedAt(
    assetId: UUID,
    instant: String,
) {
    connection.use { connection ->
        connection.prepareStatement("UPDATE media_assets SET created_at = ?::timestamptz WHERE id = ?").use { statement ->
            statement.setString(1, instant)
            statement.setObject(2, assetId)
            statement.executeUpdate()
        }
    }
}

private fun DataSource.moderationStatus(assetId: UUID): String? =
    connection.use { connection ->
        connection.prepareStatement("SELECT moderation_status FROM media_assets WHERE id = ?").use { statement ->
            statement.setObject(1, assetId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }
