package com.pynapli.server.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Test
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
