package com.pynapli.server.routing

import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.configureGridRoutes
import com.pynapli.server.location.LocationService
import com.pynapli.server.location.configureLocationRoutes
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.configureMediaRoutes
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.messaging.configureMessagingRoutes
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.profile.configureProfileRoutes
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
 * Route-level account-status matrix for the PRODUCTION_READINESS_AND_QUALITY_SPEC
 * "banned/quarantined user" backend row. AuthService already rejects non-active sessions; this
 * pins the HTTP boundary so core routes cannot accidentally invoke domain services for a paused,
 * suspended, banned, or deleted account.
 */
class AccountStatusRouteSecurityTest {
    private data class ProtectedEndpoint(
        val name: String,
        val method: String,
        val path: String,
        val body: String = "{}",
    )

    private val protectedEndpoints =
        listOf(
            ProtectedEndpoint("grid query", "POST", "/v1/grid/query"),
            ProtectedEndpoint("swipe deck", "POST", "/v1/swipe/deck/query"),
            ProtectedEndpoint(
                name = "swipe action",
                method = "POST",
                path = "/v1/swipe/actions",
                body =
                    """
                    {
                      "actionId": "00000000-0000-0000-0000-0000000000cc",
                      "deckSessionId": "00000000-0000-0000-0000-0000000000dd",
                      "cardToken": "token",
                      "targetUserId": "00000000-0000-0000-0000-0000000000bb",
                      "action": "like"
                    }
                    """.trimIndent(),
            ),
            ProtectedEndpoint("me", "GET", "/v1/me"),
            ProtectedEndpoint("location current", "POST", "/v1/location/current"),
            ProtectedEndpoint("media complete", "POST", "/v1/media/uploads/some-asset/complete"),
            ProtectedEndpoint("messages delete", "POST", "/v1/messages/some-message/delete"),
        )

    private val gridService = mockk<GridService>(relaxed = true)
    private val swipeService = mockk<SwipeService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val locationService = mockk<LocationService>(relaxed = true)
    private val mediaService = mockk<MediaService>(relaxed = true)
    private val messagingService = mockk<MessagingService>(relaxed = true)

    @Test
    fun nonActiveAccountsAreForbiddenBeforeCoreDomainServicesRun() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                installCoreRoutes()
            }

            for (status in NON_ACTIVE_STATUSES) {
                for (endpoint in protectedEndpoints) {
                    val response = client.callProtected(endpoint, token = tokenFor(status))
                    assertEquals(
                        expected = HttpStatusCode.Forbidden,
                        actual = response.status,
                        message = "${endpoint.name} must reject $status accounts at auth boundary",
                    )
                }
            }

            coVerify(exactly = 0) { gridService.queryGrid(any(), any()) }
            coVerify(exactly = 0) { swipeService.queryDeck(any(), any()) }
            coVerify(exactly = 0) { swipeService.submitAction(any(), any()) }
            coVerify(exactly = 0) { profileService.getMe(any()) }
            coVerify(exactly = 0) { locationService.updateCurrentLocation(any(), any()) }
            coVerify(exactly = 0) { mediaService.completeUpload(any(), any(), any()) }
            coVerify(exactly = 0) { messagingService.deleteOwnMessage(any(), any()) }
        }

    private fun Application.installCoreRoutes() {
        val authService = inactiveAwareAuthService()
        configureGridRoutes(authService, gridService)
        configureSwipeRoutes(authService, swipeService)
        configureProfileRoutes(authService, profileService)
        configureLocationRoutes(authService, locationService)
        configureMediaRoutes(authService, mediaService)
        configureMessagingRoutes(authService, messagingService)
    }

    private fun inactiveAwareAuthService(): AuthService {
        val now = Instant.now()
        val repository =
            mockk<AuthRepository>(relaxed = true).also { repo ->
                coEvery { repo.findSessionByAccessTokenHash(any()) } returns null
                for (status in NON_ACTIVE_STATUSES) {
                    val tokenHash = Sha256.hash(tokenFor(status))
                    coEvery { repo.findSessionByAccessTokenHash(tokenHash) } returns
                        SessionRecord(
                            id = UUID.randomUUID(),
                            userId = USER_ID,
                            accessTokenHash = tokenHash,
                            refreshTokenHash = "unused-refresh-hash-$status",
                            accessExpiresAt = now.plus(Duration.ofMinutes(10)),
                            refreshExpiresAt = now.plus(Duration.ofDays(1)),
                            revokedAt = null,
                            userStatus = status,
                        )
                }
            }
        return AuthService(repository)
    }

    private suspend fun HttpClient.callProtected(
        endpoint: ProtectedEndpoint,
        token: String,
    ): HttpResponse {
        val builder: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (endpoint.method == "POST") {
                contentType(ContentType.Application.Json)
                setBody(endpoint.body)
            }
        }
        return if (endpoint.method == "POST") post(endpoint.path, builder) else get(endpoint.path, builder)
    }

    private companion object {
        private val USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        private val NON_ACTIVE_STATUSES = listOf("paused", "suspended", "banned", "deleted")

        private fun tokenFor(status: String): String = "$status-access-token"
    }
}
