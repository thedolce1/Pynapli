package com.pynapli.server.routing

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.configureActivityRoutes
import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.favorites.FavoriteRepository
import com.pynapli.server.favorites.FavoriteService
import com.pynapli.server.favorites.configureFavoriteRoutes
import com.pynapli.server.grid.LockedGridCardTokens
import com.pynapli.server.media.MediaRepository
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.MediaUploadSigner
import com.pynapli.server.media.configureMediaRoutes
import com.pynapli.server.messaging.MessagingRepository
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.messaging.configureMessagingRoutes
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.profile.configureProfileRoutes
import com.pynapli.server.profilelink.ProfileLinkRepository
import com.pynapli.server.profilelink.ProfileLinkService
import com.pynapli.server.profilelink.configureProfileLinkRoutes
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockService
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.HiddenProfileService
import com.pynapli.server.safety.ReportRepository
import com.pynapli.server.safety.ReportService
import com.pynapli.server.safety.configureSafetyRoutes
import com.pynapli.server.swipe.SwipeRepository
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.swipe.configureSwipeRoutes
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Section 3 S3A-3: a locked Grid card's id is an opaque "locked_" token, never a user id. Every route
 * that targets a person (profile detail and peek, favourite, block, hide, report, Spark, album request,
 * private album, message, Swipe like and like-back, partner link invite) must refuse it exactly as it
 * refuses any malformed id: same status (400 or 404), same error code and message as for
 * "not-a-uuid", nothing reaching a repository, and nothing that says "locked" or "subscribe".
 *
 * Real services run on recording fakes, so the parsing under test is the production parsing.
 */
class LockedCardTokenRouteRejectionTest {
    private val actorUserId = UUID.fromString("00000000-0000-0000-0000-0000000001aa")
    private val actorToken = "locked-route-actor-token"
    private val lockedToken =
        LockedGridCardTokens("route-test-secret").tokenFor(actorUserId, UUID.fromString("00000000-0000-0000-0000-0000000001bb"))
    private val control = "not-a-uuid"

    private val favoriteRepository = mockk<FavoriteRepository>(relaxed = true)
    private val blockRepository = mockk<BlockRepository>(relaxed = true)
    private val hiddenProfileRepository = mockk<HiddenProfileRepository>(relaxed = true)
    private val reportRepository = mockk<ReportRepository>(relaxed = true)
    private val messagingRepository = mockk<MessagingRepository>(relaxed = true)
    private val mediaRepository = mockk<MediaRepository>(relaxed = true)
    private val mediaUploadSigner = mockk<MediaUploadSigner>(relaxed = true)
    private val swipeRepository = mockk<SwipeRepository>(relaxed = true)
    private val profileLinkRepository = mockk<ProfileLinkRepository>(relaxed = true)

    // These two routes parse the id themselves, before the service is called.
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val activityService = mockk<ActivityService>(relaxed = true)

    private data class Target(
        val name: String,
        val method: HttpMethod,
        val path: (String) -> String,
        val body: ((String) -> String)? = null,
    )

    private val targets =
        listOf(
            Target("profile detail / peek", HttpMethod.Get, { "/v1/profiles/$it" }),
            Target("favourite", HttpMethod.Put, { "/v1/favorites/$it" }),
            Target("unfavourite", HttpMethod.Delete, { "/v1/favorites/$it" }),
            Target("block", HttpMethod.Post, { "/v1/blocks/$it" }, { "{}" }),
            Target("unblock", HttpMethod.Delete, { "/v1/blocks/$it" }),
            Target("hide", HttpMethod.Post, { "/v1/hidden-profiles/$it" }, { "{}" }),
            Target("unhide", HttpMethod.Delete, { "/v1/hidden-profiles/$it" }),
            Target(
                "report",
                HttpMethod.Post,
                { "/v1/reports" },
                { """{"reportedUserId":"$it","targetType":"profile","category":"spam"}""" },
            ),
            Target("spark", HttpMethod.Post, { "/v1/activity/flirts" }, { """{"targetUserId":"$it","source":"grid"}""" }),
            Target("album request", HttpMethod.Post, { "/v1/activity/album-requests" }, { """{"targetUserId":"$it"}""" }),
            Target("private album", HttpMethod.Get, { "/v1/media/users/$it/albums/private" }),
            Target("message", HttpMethod.Post, { "/v1/messages/threads" }, { """{"targetUserId":"$it","origin":"grid"}""" }),
            Target(
                "swipe like",
                HttpMethod.Post,
                { "/v1/swipe/actions" },
                {
                    """{"actionId":"${UUID.randomUUID()}","deckSessionId":"deck","cardToken":"card","targetUserId":"$it","action":"like"}"""
                },
            ),
            Target("like back", HttpMethod.Post, { "/v1/swipe/like-back" }, { """{"targetUserId":"$it"}""" }),
            Target("partner link invite", HttpMethod.Post, { "/v1/profile-links/invitations" }, { """{"targetUserId":"$it"}""" }),
        )

    @Test
    fun everyPersonTargetedRouteRefusesALockedTokenLikeAnyMalformedId() =
        runRouteTest { client ->
            targets.forEach { target ->
                val locked = client.send(target, lockedToken)
                val malformed = client.send(target, control)
                val lockedBody = locked.bodyAsText()
                val malformedBody = malformed.bodyAsText()

                assertTrue(
                    locked.status == HttpStatusCode.BadRequest || locked.status == HttpStatusCode.NotFound,
                    "${target.name}: a locked token must be refused as an invalid id, got ${locked.status}",
                )
                assertEquals(malformed.status, locked.status, "${target.name}: same status as a malformed id")
                assertEquals(malformedBody.errorField("code"), lockedBody.errorField("code"), "${target.name}: same code")
                assertEquals(malformedBody.errorField("message"), lockedBody.errorField("message"), "${target.name}: same message")
                listOf("locked", "subscri", "upgrade", "entitlement", "social", "select").forEach { word ->
                    assertTrue(
                        !lockedBody.errorField("code").lowercase().contains(word) &&
                            !lockedBody.errorField("message").lowercase().contains(word),
                        "${target.name}: the refusal must not mention '$word': $lockedBody",
                    )
                }
            }

            // Nothing got past parsing: no repository was touched, no route-parsed service was called.
            confirmVerified(
                favoriteRepository,
                blockRepository,
                hiddenProfileRepository,
                reportRepository,
                messagingRepository,
                mediaRepository,
                swipeRepository,
                profileLinkRepository,
            )
            coVerify(exactly = 0) { profileService.getPublicProfile(any(), any()) }
            coVerify(exactly = 0) { activityService.sendFlirt(any(), any(), any()) }
            coVerify(exactly = 0) { activityService.requestAlbumAccess(any(), any()) }
        }

    // ---- harness ----

    private suspend fun HttpClient.send(
        target: Target,
        id: String,
    ): HttpResponse {
        val path = target.path(id)
        val auth = "Bearer $actorToken"
        return when (target.method) {
            HttpMethod.Get -> get(path) { header(HttpHeaders.Authorization, auth) }
            HttpMethod.Delete -> delete(path) { header(HttpHeaders.Authorization, auth) }
            HttpMethod.Put ->
                put(path) {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(target.body?.invoke(id) ?: "{}")
                }
            else ->
                post(path) {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(target.body?.invoke(id) ?: "{}")
                }
        }
    }

    private fun String.errorField(name: String): String =
        Json
            .parseToJsonElement(this)
            .jsonObject[name]
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    private fun runRouteTest(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                installTargetedRoutes()
            }
            block(client)
        }

    private fun Application.installTargetedRoutes() {
        val authService = actorAuthService()
        configureProfileRoutes(authService, profileService)
        configureFavoriteRoutes(
            authService,
            FavoriteService(repository = favoriteRepository, cursorCodec = DiscoveryCursorCodec("route-test-cursor-secret")),
        )
        configureSafetyRoutes(
            authService,
            BlockService(blockRepository),
            HiddenProfileService(hiddenProfileRepository),
            ReportService(reportRepository),
        )
        configureActivityRoutes(authService, activityService)
        configureMediaRoutes(authService, MediaService(repository = mediaRepository, uploadSigner = mediaUploadSigner))
        configureMessagingRoutes(authService, MessagingService(repository = messagingRepository))
        configureSwipeRoutes(authService, SwipeService(repository = swipeRepository))
        configureProfileLinkRoutes(authService, ProfileLinkService(repository = profileLinkRepository))
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
}
