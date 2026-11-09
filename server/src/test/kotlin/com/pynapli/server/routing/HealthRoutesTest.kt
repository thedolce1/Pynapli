package com.pynapli.server.routing

import com.pynapli.server.plugins.configureSerialization
import com.pynapli.shared.network.dto.DependencyHealthDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRoutesTest {
    @Test
    fun healthReturnsOk() =
        testApplication {
            application {
                configureSerialization()
                configureHealthRoutes("test") { emptyList() }
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.body<String>().contains("pynapli-api"))
        }

    @Test
    fun deepHealthReturnsOkWhenNoDependencyProbesAreConfigured() =
        testApplication {
            application {
                configureSerialization()
                configureHealthRoutes("test") { emptyList() }
            }

            val response = client.get("/health/deep")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.body<String>().contains("\"dependencies\":[]"))
        }

    @Test
    fun deepHealthIsUnavailableWhenAProbeFails() =
        testApplication {
            application {
                configureSerialization()
                configureHealthRoutes("test") {
                    listOf(
                        DependencyHealthDto(name = "postgres", status = "ok"),
                        DependencyHealthDto(name = "redis", status = "down", detail = "connection refused"),
                    )
                }
            }

            val response = client.get("/health/deep")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.body<String>().contains("\"status\":\"degraded\""))
        }
}
