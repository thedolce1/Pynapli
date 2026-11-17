package com.pynapli.server.metrics

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import com.pynapli.server.plugins.CloudJsonLayout
import com.pynapli.server.profiling.DatabasePoolSnapshot
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperationalObservabilityTest {
    @Test
    fun jsonLogLinesCarryCloudLoggingSeverityRequestIdAndTheStackTrace() {
        val context = LoggerContext()
        val layout = CloudJsonLayout().apply { this.context = context }
        layout.start()
        val event =
            LoggingEvent(
                "com.pynapli.Test",
                context.getLogger("com.pynapli.Test"),
                Level.WARN,
                "Quote \" and\nnewline",
                IllegalStateException("boom"),
                null,
            )
        event.setMDCPropertyMap(mapOf("requestId" to "req-12345678"))

        val line = layout.doLayout(event)
        val json = Json.parseToJsonElement(line).jsonObject

        assertEquals("WARNING", json.getValue("severity").jsonPrimitive.content)
        assertEquals("req-12345678", json.getValue("requestId").jsonPrimitive.content)
        val message = json.getValue("message").jsonPrimitive.content
        assertTrue(message.startsWith("Quote \" and\nnewline"), message)
        assertTrue(message.contains("IllegalStateException: boom"), "stack trace rides in message for Error Reporting")
        assertTrue(line.endsWith("\n") && line.count { it == '\n' } == 1, "one line per entry")
    }

    @Test
    fun metricsNeedTheTokenAndReportPoolSocketsAndResponses() =
        testApplication {
            application {
                configurePrometheusMetrics(
                    token = "metrics-token-0123456789",
                    sources =
                        OperationalMetricsSources(
                            poolSnapshot = {
                                DatabasePoolSnapshot(
                                    activeConnections = 3,
                                    idleConnections = 7,
                                    totalConnections = 10,
                                    threadsAwaitingConnection = 1,
                                    maximumPoolSize = 10,
                                    minimumIdle = 2,
                                )
                            },
                            realtimeSubscribers = mapOf("messages" to { 42 }),
                        ),
                )
            }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)

            val body = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer metrics-token-0123456789") }.bodyAsText()
            assertTrue(body.contains("pynapli_db_pool_active_connections 3"), body)
            assertTrue(body.contains("pynapli_db_pool_waiting_threads 1"), body)
            assertTrue(body.contains("pynapli_realtime_subscribers{stream=\"messages\"} 42"), body)
            assertTrue(body.contains("pynapli_http_responses_total{status=\"4xx\"} 2"), "the two refused scrapes are counted: $body")
        }
}
