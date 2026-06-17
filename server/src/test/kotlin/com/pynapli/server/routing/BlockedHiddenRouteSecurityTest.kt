package com.pynapli.server.routing

import com.pynapli.server.activity.ActivityException
import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.configureActivityRoutes
import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.media.MediaException
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.configureMediaRoutes
import com.pynapli.server.messaging.MessagingException
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.messaging.configureMessagingRoutes
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.profile.ProfileException
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.profile.configureProfileRoutes
import com.pynapli.server.swipe.SwipeException
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.swipe.configureSwipeRoutes
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Route-level blocked/hidden matrix for the highest-risk social endpoints.
 *
 * Service and repository tests own the detailed blocking, hidden-profile, and swipe-eligibility
 * rules. This test pins the HTTP boundary those rules depend on: blocked/hidden outcomes must be
 * non-successful, must keep acting as the token-derived user, and must not become distinguishable
 * success states when a client calls the route directly.
 */
class BlockedHiddenRouteSecurityTest {
    private val actorUserId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val targetUserId = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    private val objectId = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
    private val actorToken = "actor-access-token"

    private val profileService = mockk<ProfileService>(relaxed = true)
    private val messagingService = mockk<MessagingService>(relaxed = true)
    private val activityService = mockk<ActivityService>(relaxed = true)
    private val mediaService = mockk<MediaService>(relaxed = true)
    private val swipeService = mockk<SwipeService>(relaxed = true)

    @Test
    fun blockedAndHiddenSocialRoutesMapToNonSuccessAndUseTokenIdentity() =
        runRouteTest { client ->
            coEvery { profileService.getPublicProfile(any(), any()) } throws ProfileException("PROFILE_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorGet("/v1/profiles/$targetUserId").status,
                "blocked or hidden profile open must not reveal existence",
            )
            coVerify { profileService.getPublicProfile(actorUserId, targetUserId) }

            coEvery { messagingService.createDirectConversation(any(), any()) } throws
                MessagingException("MESSAGING_NOT_ALLOWED")
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .actorPost(
                        "/v1/messages/threads",
                        """{"targetUserId":"$targetUserId","origin":"grid"}""",
                    ).status,
                "blocked direct conversation create must be forbidden",
            )
            coVerify {
                messagingService.createDirectConversation(
                    actorUserId,
                    match { it.targetUserId == targetUserId.toString() && it.origin == "grid" },
                )
            }

            coEvery { messagingService.sendMessage(any(), any(), any()) } throws
                MessagingException("MESSAGING_NOT_ALLOWED")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.actorPost("/v1/messages/threads/$objectId/messages", """{"body":"hello"}""").status,
                "blocked message send must be forbidden",
            )
            coVerify { messagingService.sendMessage(actorUserId, objectId.toString(), any()) }

            coEvery { activityService.sendFlirt(any(), any(), any()) } throws
                ActivityException("FLIRT_NOT_ALLOWED")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.actorPost("/v1/activity/flirts", """{"targetUserId":"$targetUserId","source":"grid"}""").status,
                "blocked flirt must be forbidden",
            )
            coVerify { activityService.sendFlirt(actorUserId, targetUserId, "grid") }

            coEvery { activityService.requestAlbumAccess(any(), any()) } throws
                ActivityException("ALBUM_REQUEST_NOT_ALLOWED")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.actorPost("/v1/activity/album-requests", """{"targetUserId":"$targetUserId"}""").status,
                "blocked private-album request must be forbidden before side effects",
            )
            coVerify { activityService.requestAlbumAccess(actorUserId, targetUserId) }

            coEvery { mediaService.listPrivateAlbumForViewer(any(), any()) } throws
                MediaException("MEDIA_GRANT_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorGet("/v1/media/users/$targetUserId/albums/private").status,
                "blocked or ungranted private album view must not reveal grant state",
            )
            coVerify { mediaService.listPrivateAlbumForViewer(actorUserId, targetUserId.toString()) }

            coEvery { swipeService.submitAction(any(), any()) } throws SwipeException("INVALID_SWIPE_CARD_TOKEN")
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .actorPost(
                        "/v1/swipe/actions",
                        """
                        {
                          "actionId": "$objectId",
                          "deckSessionId": "$objectId",
                          "cardToken": "stale-hidden-card",
                          "targetUserId": "$targetUserId",
                          "action": "like"
                        }
                        """.trimIndent(),
                    ).status,
                "stale or hidden swipe card action must not be accepted",
            )
            coVerify {
                swipeService.submitAction(
                    actorUserId,
                    match { it.targetUserId == targetUserId.toString() && it.action == "like" },
                )
            }
        }

    private fun runRouteTest(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                installBlockedHiddenRoutes()
            }
            block(client)
        }

    private fun Application.installBlockedHiddenRoutes() {
        val authService = actorAuthService()
        configureProfileRoutes(authService, profileService)
        configureMessagingRoutes(authService, messagingService)
        configureActivityRoutes(authService, activityService)
        configureMediaRoutes(authService, mediaService)
        configureSwipeRoutes(authService, swipeService)
    }

    private fun actorAuthService(): AuthService {
        val now = Instant.now()
        val actorSession =
            SessionRecord(
                id = UUID.randomUUID(),
                userId = actorUserId,
                accessTokenHash = Sha256.hash(actorToken),
                refreshTokenHash = "unused-refresh-hash",
                accessExpiresAt = now.plus(Duration.ofMinutes(10)),
                refreshExpiresAt = now.plus(Duration.ofDays(1)),
                revokedAt = null,
                userStatus = "active",
            )
        val repository =
            mockk<AuthRepository>(relaxed = true).also {
                coEvery { it.findSessionByAccessTokenHash(any()) } returns null
                coEvery { it.findSessionByAccessTokenHash(Sha256.hash(actorToken)) } returns actorSession
            }
        return AuthService(repository)
    }

    private suspend fun HttpClient.actorGet(path: String): HttpResponse = get(path) { header(HttpHeaders.Authorization, "Bearer $actorToken") }

    private suspend fun HttpClient.actorPost(
        path: String,
        body: String = "{}",
    ): HttpResponse =
        post(path) {
            header(HttpHeaders.Authorization, "Bearer $actorToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}
