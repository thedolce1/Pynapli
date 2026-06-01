package com.pynapli.server.routing

import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Route-level authorization guardrail for the core protected endpoints, covering the
 * PRODUCTION_READINESS_AND_QUALITY_SPEC "Critical Backend Test Matrix" row #1 (unauthenticated
 * request) at the HTTP layer. Every protected route must reject a request that carries no bearer
 * token, a non-bearer Authorization header, or a syntactically valid token with no matching
 * session — and must do so *before* the domain service is ever invoked.
 *
 * Services are relaxed mocks: if auth were ever bypassed, the verify below would catch the leak
 * because the service would have been called.
 */
class CoreRouteAuthorizationTest {
    private data class ProtectedEndpoint(
        val name: String,
        val method: String,
        val path: String,
    )

    private val protectedEndpoints =
        listOf(
            ProtectedEndpoint("grid query", "POST", "/v1/grid/query"),
            ProtectedEndpoint("swipe deck", "POST", "/v1/swipe/deck/query"),
            ProtectedEndpoint("swipe action", "POST", "/v1/swipe/actions"),
            ProtectedEndpoint("me", "GET", "/v1/me"),
            ProtectedEndpoint("location current", "POST", "/v1/location/current"),
            ProtectedEndpoint("media complete", "POST", "/v1/media/uploads/some-asset/complete"),
            ProtectedEndpoint("messages delete", "POST", "/v1/messages/some-message/delete"),
        )

    @Test
    fun coreEndpointsRejectMissingBearerToken() =
        runRouteTest { client ->
            for (endpoint in protectedEndpoints) {
                val response = client.callProtected(endpoint, authHeader = null)
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    response.status,
                    "${endpoint.name} must 401 without an Authorization header",
                )
            }
        }

    @Test
    fun coreEndpointsRejectNonBearerHeader() =
        runRouteTest { client ->
            for (endpoint in protectedEndpoints) {
                val response = client.callProtected(endpoint, authHeader = "Basic dXNlcjpwYXNz")
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    response.status,
                    "${endpoint.name} must 401 for a non-bearer Authorization header",
                )
            }
        }

    @Test
    fun coreEndpointsRejectUnknownBearerToken() =
        runRouteTest { client ->
            for (endpoint in protectedEndpoints) {
                val response = client.callProtected(endpoint, authHeader = "Bearer not-a-real-session-token")
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    response.status,
                    "${endpoint.name} must 401 for a syntactically valid but unknown token",
                )
            }
        }

    @Test
    fun unauthenticatedRequestsNeverReachDomainServices() = runRouteTest(::verifyServicesUntouched)

    // ---- harness ----

    private val gridService = mockk<GridService>(relaxed = true)
    private val swipeService = mockk<SwipeService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val locationService = mockk<LocationService>(relaxed = true)
    private val mediaService = mockk<MediaService>(relaxed = true)
    private val messagingService = mockk<MessagingService>(relaxed = true)

    private fun verifyServicesUntouched(client: io.ktor.client.HttpClient) {
        runBlocking {
            for (endpoint in protectedEndpoints) {
                client.callProtected(endpoint, authHeader = null)
                client.callProtected(endpoint, authHeader = "Bearer not-a-real-session-token")
            }
        }
        // No domain method on any core service should have run for an unauthenticated caller.
        coVerify(exactly = 0) { gridService.queryGrid(any(), any()) }
        coVerify(exactly = 0) { profileService.getMe(any()) }
        coVerify(exactly = 0) { locationService.updateCurrentLocation(any(), any()) }
    }

    private fun runRouteTest(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                installCoreRoutes()
            }
            block(client)
        }

    private fun Application.installCoreRoutes() {
        // A no-session auth service: findSessionByAccessTokenHash returns null, so any well-formed
        // token is UNAUTHENTICATED. The missing/non-bearer header cases throw before the repo is
        // ever consulted.
        val authRepository =
            mockk<AuthRepository>(relaxed = true).also {
                coEvery { it.findSessionByAccessTokenHash(any()) } returns null
            }
        val authService = AuthService(authRepository)
        configureGridRoutes(authService, gridService)
        configureSwipeRoutes(authService, swipeService)
        configureProfileRoutes(authService, profileService)
        configureLocationRoutes(authService, locationService)
        configureMediaRoutes(authService, mediaService)
        configureMessagingRoutes(authService, messagingService)
    }

    private suspend fun io.ktor.client.HttpClient.callProtected(
        endpoint: ProtectedEndpoint,
        authHeader: String?,
    ): HttpResponse {
        val builder: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (authHeader != null) header(HttpHeaders.Authorization, authHeader)
            if (endpoint.method == "POST") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
        return if (endpoint.method == "POST") post(endpoint.path, builder) else get(endpoint.path, builder)
    }
}
