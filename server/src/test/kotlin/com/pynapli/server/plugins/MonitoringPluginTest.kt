package com.pynapli.server.plugins

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonitoringPluginTest {
    @Test
    fun logLineIsStatusMethodPathOnly() {
        assertEquals("200: GET /v1/grid/query", monitoringLogLine("GET", "/v1/grid/query", 200))
        assertEquals("-: POST /v1/auth/login", monitoringLogLine("POST", "/v1/auth/login", null))
    }

    @Test
    fun logLineNeverContainsQueryString() {
        // The line is built from request.path() (query-less); even if a caller passed a query-bearing
        // string the formatter does not synthesize one. Guards against regressing to the default
        // full-URI format that would log opaque cursors/filters.
        val line = monitoringLogLine("GET", "/v1/messages/threads/abc", 200)
        assertTrue(!line.contains("?"), line)
        assertTrue(!line.contains("cursor"), line)
    }

    @Test
    fun everyResponseCarriesARequestIdHonouringAWellFormedClientOne() =
        testApplication {
            application {
                configureMonitoring()
                routing { get("/ping") { call.respond(call.requestIdOrUnknown()) } }
            }

            val generated = client.get("/ping")
            val generatedId = generated.headers[REQUEST_ID_HEADER]
            assertTrue(generatedId != null && generatedId.length >= 8, "generated id: $generatedId")
            assertEquals(generatedId, generated.bodyAsText(), "the handler and the header agree")

            val honoured = client.get("/ping") { header(REQUEST_ID_HEADER, "client-req-12345") }
            assertEquals("client-req-12345", honoured.headers[REQUEST_ID_HEADER])

            // Anything outside the safe id alphabet is replaced, never echoed into logs or headers.
            val replaced = client.get("/ping") { header(REQUEST_ID_HEADER, "bad id; forged=1") }
            assertTrue(replaced.headers[REQUEST_ID_HEADER] != "bad id; forged=1")
            val tooShort = client.get("/ping") { header(REQUEST_ID_HEADER, "abc") }
            assertTrue(tooShort.headers[REQUEST_ID_HEADER] != "abc")
        }

    @Test
    fun responsesCarryBaselineSecurityHeadersAndHstsOutsideLocal() =
        testApplication {
            application {
                configureSecurityHeaders(environment = "production")
                routing { get("/ping") { call.respond("pong") } }
            }
            val response = client.get("/ping")
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertTrue(response.headers["Strict-Transport-Security"]?.startsWith("max-age=") == true)
        }

    @Test
    fun monitoringInstallsAndServesRequests() =
        testApplication {
            application {
                configureMonitoring()
                routing { get("/ping") { call.respond("pong") } }
            }
            val response = client.get("/ping?cursor=secret-token-value")
            assertEquals("pong", response.bodyAsText())
        }
}
