package com.pynapli.server.verification

import com.pynapli.server.admin.ADMIN_ROLE_OPERATOR
import com.pynapli.server.admin.ADMIN_ROLE_SENIOR
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.MediaVerificationSelfieGateway
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.testServerConfig
import com.pynapli.shared.network.dto.CreatePhotoVerificationUploadRequestDto
import com.pynapli.shared.network.dto.SubmitPhotoVerificationRequestDto
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminPhotoVerificationRoutesTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val clock = MutableClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun reviewRoutesNeedTheAdminToken() =
        testApplication {
            application { install(config(), service()) }

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/v1/admin/verification/photo/${java.util.UUID.randomUUID()}/approve") { header("X-Admin-Id", "ops-1") }.status,
            )
        }

    @Test
    fun anOperatorCannotDecideAVerification() =
        testApplication {
            val service = service()
            val pending = runBlocking { service.pendingSubmission() }
            application { install(config(roles = mapOf("ops-1" to ADMIN_ROLE_OPERATOR)), service) }

            val response =
                client.post("/v1/admin/verification/photo/$pending/approve") {
                    adminHeaders("ops-1")
                    jsonBody("""{"reason":"looks right"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("ADMIN_ROLE_REQUIRED"))
        }

    @Test
    fun deciderMustPassStepUpMfaWhenEnforced() =
        testApplication {
            val service = service()
            val pending = runBlocking { service.pendingSubmission() }
            application { install(config(mfaEnforced = true), service) }

            val response =
                client.post("/v1/admin/verification/photo/$pending/approve") {
                    adminHeaders("senior-1")
                    jsonBody("{}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("ADMIN_MFA_REQUIRED"))
        }

    @Test
    fun aSeniorAdminApprovesAndTheProfileIsVerified() =
        testApplication {
            val service = service()
            val userId = dataSource.insertVerificationUser()
            val pending = runBlocking { service.pendingSubmission(userId) }
            application { install(config(roles = mapOf("senior-1" to ADMIN_ROLE_SENIOR)), service) }

            val response =
                client.post("/v1/admin/verification/photo/$pending/approve") {
                    adminHeaders("senior-1")
                    jsonBody("{}")
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("\"status\":\"approved\""))
            assertNotNull(dataSource.facePhotoVerifiedAt(userId))
        }

    @Test
    fun rejectionWithoutAReasonIsRefused() =
        testApplication {
            val service = service()
            val userId = dataSource.insertVerificationUser()
            val pending = runBlocking { service.pendingSubmission(userId) }
            application { install(config(), service) }

            val response =
                client.post("/v1/admin/verification/photo/$pending/reject") {
                    adminHeaders("senior-1")
                    jsonBody("{}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("PHOTO_VERIFICATION_REASON_REQUIRED"))
            assertNull(dataSource.facePhotoVerifiedAt(userId))
        }

    @Test
    fun queueNeedsAReasonHeader() =
        testApplication {
            application { install(config(), service()) }

            val withoutReason =
                client.get("/v1/admin/verification/photo") {
                    header("X-Admin-Token", TOKEN)
                    header("X-Admin-Id", "senior-1")
                }
            val withReason = client.get("/v1/admin/verification/photo") { adminHeaders("senior-1") }

            assertEquals(HttpStatusCode.BadRequest, withoutReason.status)
            assertEquals(HttpStatusCode.OK, withReason.status)
        }

    @Test
    fun routesAreNotInstalledWithoutAnAdminToken() =
        testApplication {
            application { install(config().copy(adminApiToken = null), service()) }

            assertEquals(HttpStatusCode.NotFound, client.get("/v1/admin/verification/photo") { adminHeaders("senior-1") }.status)
        }

    private fun io.ktor.server.application.Application.install(
        config: ServerConfig,
        service: PhotoVerificationService,
    ) {
        configureSerialization()
        configureStatusPages()
        configureAdminPhotoVerificationRoutes(config, service)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.adminHeaders(actorId: String) {
        header("X-Admin-Token", TOKEN)
        header("X-Admin-Id", actorId)
        header("X-Admin-Reason", "verification review")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(json: String) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun PhotoVerificationService.pendingSubmission(userId: java.util.UUID = dataSource.insertVerificationUser()): String {
        val challengeId = assertNotNull(requestChallenge(userId).challenge).challengeId
        val slot =
            createSelfieUpload(
                userId,
                CreatePhotoVerificationUploadRequestDto(challengeId = challengeId, contentType = "image/jpeg", byteSize = 50_000),
            )
        submit(userId, SubmitPhotoVerificationRequestDto(challengeId, slot.mediaAssetId))
        return challengeId
    }

    private fun config(
        roles: Map<String, String> = emptyMap(),
        mfaEnforced: Boolean = false,
    ): ServerConfig =
        testServerConfig().copy(
            adminApiToken = TOKEN,
            adminRoles = roles,
            adminMfaEnforced = mfaEnforced,
        )

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

    private companion object {
        const val TOKEN = "test-admin-token"
    }
}
