package com.pynapli.server.routing

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.dependencies.ServerDependencies
import com.pynapli.server.module
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRoutesTest {
    @Test
    fun healthReturnsOk() = testApplication {
        application {
            module(ServerDependencies.test(testConfig()))
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<String>().contains("pynapli-api"))
    }

    @Test
    fun deepHealthReturnsOkWhenNoDependencyProbesAreConfigured() = testApplication {
        application {
            module(ServerDependencies.test(testConfig()))
        }

        val response = client.get("/health/deep")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<String>().contains("\"dependencies\":[]"))
    }

    private fun testConfig(): ServerConfig = ServerConfig(
        environment = "test",
        port = 8080,
        databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
        databaseUser = "pynapli",
        databasePassword = "pynapli_dev_password",
        redisUrl = "redis://localhost:6379"
    )
}
