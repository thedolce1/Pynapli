package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequireRoutableServicesTest {
    /**
     * The health-route fixture nulls every service deliberately so it can run without a database.
     * It must stay startable, otherwise this guard breaks the one test that depends on that shape.
     */
    @Test
    fun testFixtureIsExemptSoHealthRoutesStillStartWithoutADatabase() {
        ServerDependencies.test(localConfig()).requireRoutableServices()
    }

    /**
     * The regression this guards: every configureXxxRoutes returns early on a null service, so a
     * wiring mistake produced a server that booted clean and answered 404 on everything rather than
     * failing. Startup must refuse instead, and name what is missing.
     *
     * `isTestFixture = false` is the point of the test — same empty shape, but claiming to be a real
     * deployment.
     */
    @Test
    fun aMissingServiceStopsStartupAndIsNamed() {
        val wiredAsProduction = ServerDependencies.test(localConfig(), isTestFixture = false)

        val error = assertFailsWith<IllegalStateException> { wiredAsProduction.requireRoutableServices() }
        val message = error.message.orEmpty()

        assertTrue(message.contains("silently 404"), "the message must explain the consequence: $message")
        assertTrue(message.contains("gridService"), "the message must name what is missing: $message")
        assertTrue(message.contains("authService"), "the message must name what is missing: $message")
        assertTrue(message.contains("database"), "the message must name what is missing: $message")
    }

    private fun localConfig(): ServerConfig =
        ServerConfig(
            environment = "local",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_dev",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
        )
}
