package com.pynapli.server.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConfigTest {
    @Test
    fun serverPortPrefersExplicitPynapliPort() {
        val port =
            serverPortFromEnvironment(
                mapOf(
                    "PYNAPLI_SERVER_PORT" to "9090",
                    "PORT" to "8081",
                ),
            )

        assertEquals(9090, port)
    }

    @Test
    fun serverPortFallsBackToCloudRunPort() {
        val port = serverPortFromEnvironment(mapOf("PORT" to "8081"))

        assertEquals(8081, port)
    }

    @Test
    fun serverPortFallsBackToLocalDefaultWhenValuesAreMissingOrInvalid() {
        val port =
            serverPortFromEnvironment(
                mapOf(
                    "PYNAPLI_SERVER_PORT" to "invalid",
                    "PORT" to "also-invalid",
                ),
            )

        assertEquals(8080, port)
    }
}
