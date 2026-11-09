package com.pynapli.server.moderation

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class AdminModerationRoutesTest {
    @Test
    fun reviewQueueRequiresAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminModerationRoutes(testConfig(), MessageModerationService(RouteMessageModerationRepository()))
            }

            val response =
                client.get("/v1/admin/messages/review-queue") {
                    header("X-Admin-Id", "moderator-1")
                    header("X-Admin-Reason", "review")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun reviewQueueRejectsWrongToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminModerationRoutes(testConfig(), MessageModerationService(RouteMessageModerationRepository()))
            }

            val response =
                client.get("/v1/admin/messages/review-queue") {
                    header("X-Admin-Token", "wrong")
                    header("X-Admin-Id", "moderator-1")
                    header("X-Admin-Reason", "review")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun reviewQueueRequiresReasonForSensitiveList() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminModerationRoutes(testConfig(), MessageModerationService(RouteMessageModerationRepository()))
            }

            val response =
                client.get("/v1/admin/messages/review-queue") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "moderator-1")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun releaseHeldMessageUsesProtectedAdminRoute() =
        testApplication {
            val messageId = UUID.fromString("00000000-0000-0000-0000-000000000901")
            val repository =
                RouteMessageModerationRepository(
                    releaseAction =
                        MessageReviewActionRecord(
                            messageId = messageId,
                            conversationId = UUID.fromString("00000000-0000-0000-0000-000000000902"),
                            senderUserId = UUID.fromString("00000000-0000-0000-0000-000000000903"),
                            messageStatus = "visible",
                            queueStatus = "released",
                            reviewedAt = Instant.parse("2026-04-24T18:00:00Z"),
                        ),
                )
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminModerationRoutes(testConfig(), MessageModerationService(repository))
            }

            val response =
                client.post("/v1/admin/messages/review-queue/$messageId/release") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "moderator-1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"false positive"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("moderator-1", repository.lastActorId)
            assertEquals("false positive", repository.lastReason)
        }

    @Test
    fun adminRoutesAreNotInstalledWithoutConfiguredToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminModerationRoutes(testConfig(adminToken = null), MessageModerationService(RouteMessageModerationRepository()))
            }

            val response =
                client.get("/v1/admin/messages/review-queue") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "moderator-1")
                    header("X-Admin-Reason", "review")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private fun testConfig(adminToken: String? = "test-admin-token"): ServerConfig =
        ServerConfig(
            environment = "test",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
            adminApiToken = adminToken,
        )
}

private class RouteMessageModerationRepository(
    private val releaseAction: MessageReviewActionRecord? = null,
) : MessageModerationRepository {
    var lastActorId: String? = null
    var lastReason: String? = null

    override suspend fun listOpenMessageReviews(
        limit: Int,
        actorId: String,
        reason: String,
    ): List<MessageReviewRecord> {
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun releaseHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
    ): MessageReviewActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return releaseAction
    }

    override suspend fun removeHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
    ): MessageReviewActionRecord? = null
}
