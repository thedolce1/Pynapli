package com.pynapli.server.routing

import com.pynapli.server.activity.ActivityException
import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.configureActivityRoutes
import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.grid.SavedGridFilterService
import com.pynapli.server.grid.configureSavedGridFilterRoutes
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
import com.pynapli.server.profilelink.ProfileLinkException
import com.pynapli.server.profilelink.ProfileLinkService
import com.pynapli.server.profilelink.configureProfileLinkRoutes
import com.pynapli.server.safety.AppealService
import com.pynapli.server.safety.SafetyException
import com.pynapli.server.safety.configureAppealRoutes
import com.pynapli.server.swipe.SavedSwipeFilterService
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.swipe.configureSavedSwipeFilterRoutes
import com.pynapli.server.swipe.configureSwipeRoutes
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Route-level wrong-user (BOLA) matrix for object-ID endpoints, covering the
 * PRODUCTION_READINESS_AND_QUALITY_SPEC "Critical Backend Test Matrix" wrong-user row at the HTTP
 * layer, per the SECURITY_ROUTE_MATRIX "Remaining Hardening" list. Two properties are proven for
 * each endpoint:
 *
 * 1. The acting identity passed to the domain service is the *token-derived* user — never an ID
 *    from the path or body. Caller-supplied IDs only ever arrive as the object/target argument.
 * 2. The service's wrong-user outcome (not-found exception or false) maps to 404 at the HTTP
 *    layer — not 200, not a 5xx, and not a distinguishable error that would leak object existence.
 *
 * Ownership/membership enforcement itself is covered by service- and repository-layer tests; this
 * matrix pins the route contract those tests rely on.
 */
class WrongUserObjectAccessRouteTest {
    private val actorUserId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val foreignUserId = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    private val foreignObjectId = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
    private val actorToken = "actor-access-token"

    private val profileService = mockk<ProfileService>(relaxed = true)
    private val messagingService = mockk<MessagingService>(relaxed = true)
    private val mediaService = mockk<MediaService>(relaxed = true)
    private val activityService = mockk<ActivityService>(relaxed = true)
    private val profileLinkService = mockk<ProfileLinkService>(relaxed = true)
    private val appealService = mockk<AppealService>(relaxed = true)
    private val savedGridFilterService = mockk<SavedGridFilterService>(relaxed = true)
    private val savedSwipeFilterService = mockk<SavedSwipeFilterService>(relaxed = true)
    private val swipeService = mockk<SwipeService>(relaxed = true)

    @Test
    fun wrongUserObjectAccessReturnsNotFoundAndActsAsTokenIdentity() =
        runRouteTest { client ->
            coEvery { profileService.getPublicProfile(any(), any()) } throws ProfileException("PROFILE_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorGet("/v1/profiles/$foreignUserId").status,
                "foreign profile open must 404",
            )
            coVerify { profileService.getPublicProfile(actorUserId, foreignUserId) }

            coEvery { messagingService.deleteOwnMessage(any(), any()) } throws MessagingException("MESSAGE_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorPost("/v1/messages/$foreignObjectId/delete").status,
                "foreign message delete must 404",
            )
            coVerify { messagingService.deleteOwnMessage(actorUserId, foreignObjectId.toString()) }

            coEvery { messagingService.markConversationRead(any(), any()) } throws
                MessagingException("CONVERSATION_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorPost("/v1/messages/threads/$foreignObjectId/read").status,
                "foreign thread read must 404",
            )
            coVerify { messagingService.markConversationRead(actorUserId, foreignObjectId.toString()) }

            coEvery { messagingService.sendMessage(any(), any(), any()) } throws
                MessagingException("CONVERSATION_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorPost("/v1/messages/threads/$foreignObjectId/messages", """{"body":"hello"}""").status,
                "send into foreign thread must 404",
            )
            coVerify { messagingService.sendMessage(actorUserId, foreignObjectId.toString(), any()) }

            coEvery { mediaService.completeUpload(any(), any(), any()) } throws
                MediaException("MEDIA_ASSET_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .actorPost(
                        "/v1/media/uploads/$foreignObjectId/complete",
                        """{"albumId":"$foreignObjectId"}""",
                    ).status,
                "completing a foreign upload must 404",
            )
            coVerify { mediaService.completeUpload(actorUserId, foreignObjectId.toString(), any()) }

            coEvery { mediaService.listPrivateAlbumForViewer(any(), any()) } throws
                MediaException("MEDIA_GRANT_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorGet("/v1/media/users/$foreignUserId/albums/private").status,
                "ungranted private album view must 404",
            )
            coVerify { mediaService.listPrivateAlbumForViewer(actorUserId, foreignUserId.toString()) }

            coEvery { mediaService.revokePrivateAlbumAccess(any(), any()) } throws
                MediaException("MEDIA_GRANT_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorPost("/v1/media/albums/private/grants/$foreignUserId/revoke").status,
                "revoking a grant the actor does not own must 404",
            )
            coVerify { mediaService.revokePrivateAlbumAccess(actorUserId, foreignUserId.toString()) }

            coEvery { activityService.dismissActivityEvent(any(), any()) } throws
                ActivityException("ACTIVITY_EVENT_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorPost("/v1/activity/$foreignObjectId/dismiss").status,
                "dismissing a foreign activity event must 404",
            )
            coVerify { activityService.dismissActivityEvent(actorUserId, foreignObjectId) }

            coEvery { activityService.respondToAlbumAccessRequest(any(), any(), any()) } throws
                ActivityException("ALBUM_REQUEST_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .actorPost(
                        "/v1/activity/album-requests/$foreignObjectId/respond",
                        """{"approve":true}""",
                    ).status,
                "responding to a foreign album request must 404",
            )
            coVerify { activityService.respondToAlbumAccessRequest(actorUserId, foreignObjectId, true) }

            coEvery { profileLinkService.acceptInvitation(any(), any()) } throws
                ProfileLinkException("PROFILE_LINK_INVITE_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorPost("/v1/profile-links/invitations/$foreignObjectId/accept").status,
                "accepting a foreign link invite must 404",
            )
            coVerify { profileLinkService.acceptInvitation(actorUserId, foreignObjectId.toString()) }

            coEvery { appealService.withdrawAppeal(any(), any()) } throws SafetyException("APPEAL_NOT_FOUND")
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorPost("/v1/appeals/$foreignObjectId/withdraw").status,
                "withdrawing a foreign appeal must 404",
            )
            coVerify { appealService.withdrawAppeal(actorUserId, foreignObjectId.toString()) }

            coEvery { savedGridFilterService.delete(any(), any()) } returns false
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorDelete("/v1/me/grid-filters/$foreignObjectId").status,
                "deleting a foreign saved grid filter must 404",
            )
            coVerify { savedGridFilterService.delete(actorUserId, foreignObjectId.toString()) }

            coEvery { savedSwipeFilterService.delete(any(), any()) } returns false
            assertEquals(
                HttpStatusCode.NotFound,
                client.actorDelete("/v1/me/swipe-filters/$foreignObjectId").status,
                "deleting a foreign saved swipe filter must 404",
            )
            coVerify { savedSwipeFilterService.delete(actorUserId, foreignObjectId.toString()) }
        }

    @Test
    fun swipeActionActorComesFromTokenWhileBodyTargetStaysData() =
        runRouteTest { client ->
            // The body names another user as the *target* — legitimate data. The route must still
            // act as the token-derived user and never adopt a body-supplied identity as the actor.
            coEvery { swipeService.submitAction(any(), any()) } returns SwipeActionResponseDto(recorded = false)
            val response =
                client.actorPost(
                    "/v1/swipe/actions",
                    """
                    {
                      "actionId": "$foreignObjectId",
                      "deckSessionId": "$foreignObjectId",
                      "cardToken": "token",
                      "targetUserId": "$foreignUserId",
                      "action": "like"
                    }
                    """.trimIndent(),
                )
            assertEquals(HttpStatusCode.OK, response.status)
            coVerify {
                swipeService.submitAction(
                    actorUserId,
                    match { it.targetUserId == foreignUserId.toString() },
                )
            }
        }

    // ---- harness ----

    private fun runRouteTest(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                installObjectRoutes()
            }
            block(client)
        }

    private fun Application.installObjectRoutes() {
        val authService = actorAuthService()
        configureProfileRoutes(authService, profileService)
        configureMessagingRoutes(authService, messagingService)
        configureMediaRoutes(authService, mediaService)
        configureActivityRoutes(authService, activityService)
        configureProfileLinkRoutes(authService, profileLinkService)
        configureAppealRoutes(authService, appealService)
        configureSavedGridFilterRoutes(authService, savedGridFilterService)
        configureSavedSwipeFilterRoutes(authService, savedSwipeFilterService)
        configureSwipeRoutes(authService, swipeService)
    }

    /** A real AuthService whose repository recognizes exactly one active session: the actor's. */
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

    private suspend fun HttpClient.actorDelete(path: String): HttpResponse = delete(path) { header(HttpHeaders.Authorization, "Bearer $actorToken") }

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
