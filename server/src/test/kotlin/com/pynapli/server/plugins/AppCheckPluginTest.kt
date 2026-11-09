package com.pynapli.server.plugins

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.safety.DisabledAppCheckVerifier
import com.pynapli.server.safety.FirebaseAppCheckVerifier
import com.pynapli.server.safety.HeaderPresenceAppCheckVerifier
import com.pynapli.server.testsupport.testServerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppCheckPluginTest {
    @Test
    fun stateChangingNonExemptRequestsRequireAppCheck() {
        assertTrue(appCheckRequired(HttpMethod.Post, "/v1/me/profile"))
        assertTrue(appCheckRequired(HttpMethod.Patch, "/v1/me/profile"))
        assertTrue(appCheckRequired(HttpMethod.Put, "/v1/messages/threads/abc/expiry"))
        assertTrue(appCheckRequired(HttpMethod.Delete, "/v1/media/uploads/abc"))
    }

    @Test
    fun readsAndExemptOriginsAreNotRequiredToAttest() {
        // Reads never attest.
        assertFalse(appCheckRequired(HttpMethod.Get, "/v1/me/profile"))
        // Non-app origins: health probes, the Play RTDN webhook, the identity-provider age callback.
        assertFalse(appCheckRequired(HttpMethod.Post, "/health"))
        assertFalse(appCheckRequired(HttpMethod.Post, "/health/deep"))
        assertFalse(appCheckRequired(HttpMethod.Post, "/v1/webhooks/play-store/rtdn"))
        assertFalse(appCheckRequired(HttpMethod.Post, "/v1/verification/callback"))
    }

    @Test
    fun verifiersHonorTheirContract() =
        runBlocking {
            // Disabled accepts everything (enforcement off).
            assertTrue(DisabledAppCheckVerifier.verify(null))
            assertTrue(DisabledAppCheckVerifier.verify("anything"))
            // Header-presence stage requires a non-blank token but does not validate it cryptographically.
            assertFalse(HeaderPresenceAppCheckVerifier.verify(null))
            assertFalse(HeaderPresenceAppCheckVerifier.verify("  "))
            assertTrue(HeaderPresenceAppCheckVerifier.verify("some-token"))
        }

    @Test
    fun verifierSelectionFollowsTheEnforcementFlagAndProjectNumber() {
        // Off -> accept all.
        assertTrue(selectAppCheckVerifier(testConfig(enforced = false)) === DisabledAppCheckVerifier)
        // Enforced without a project number -> staged header-presence.
        assertTrue(selectAppCheckVerifier(testConfig(enforced = true)) === HeaderPresenceAppCheckVerifier)
        // Enforced with a project number -> cryptographic verification.
        assertTrue(
            selectAppCheckVerifier(testConfig(enforced = true, projectNumber = "209241465883")) is FirebaseAppCheckVerifier,
        )
    }

    @Test
    fun enforcedBoundaryRejectsMutationsMissingTheHeaderAndAllowsAttestedOnes() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAppCheck(enforced = true, verifier = HeaderPresenceAppCheckVerifier)
                echoRoutes()
            }

            val missing = client.post("/v1/echo")
            assertEquals(HttpStatusCode.Forbidden, missing.status)
            assertTrue(missing.bodyAsText().contains("APP_CHECK_REQUIRED"), missing.bodyAsText())

            val attested = client.post("/v1/echo") { header(APP_CHECK_HEADER, "valid-token") }
            assertEquals(HttpStatusCode.OK, attested.status)

            // Reads pass without a header even when enforcement is on.
            assertEquals(HttpStatusCode.OK, client.get("/v1/echo").status)

            // Exempt non-app origin passes without a header.
            assertEquals(HttpStatusCode.OK, client.post("/v1/webhooks/play-store/rtdn").status)
        }

    @Test
    fun disabledBoundaryIsANoOp() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAppCheck(enforced = false, verifier = HeaderPresenceAppCheckVerifier)
                echoRoutes()
            }

            // No plugin installed -> mutations pass without any header.
            assertEquals(HttpStatusCode.OK, client.post("/v1/echo").status)
        }

    private fun Application.echoRoutes() {
        routing {
            post("/v1/echo") { call.respondText("ok") }
            get("/v1/echo") { call.respondText("ok") }
            post("/v1/webhooks/play-store/rtdn") { call.respondText("ok") }
        }
    }

    private fun testConfig(
        enforced: Boolean,
        projectNumber: String? = null,
    ): ServerConfig =
        testServerConfig().copy(
            appCheckEnforced = enforced,
            appCheckProjectNumber = projectNumber,
        )
}
