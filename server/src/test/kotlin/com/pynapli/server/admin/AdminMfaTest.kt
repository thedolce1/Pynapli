package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminMfaTest {
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val now = 1_700_000_000L

    @Test
    fun offIsAlwaysSatisfied() {
        assertTrue(adminMfaSatisfied(config(enforced = false), "ops", code = null, nowSeconds = now))
    }

    @Test
    fun enforcedRequiresAValidCodeForAConfiguredActor() {
        val config = config(enforced = true, secrets = mapOf("ops" to secret))
        val code = AdminTotp.generate(secret, now)
        assertTrue(adminMfaSatisfied(config, "ops", code, now))
        assertFalse(adminMfaSatisfied(config, "ops", code = null, nowSeconds = now)) // no code presented
        assertFalse(adminMfaSatisfied(config, "ops", "000000", now)) // wrong code
        assertFalse(adminMfaSatisfied(config, "stranger", code, now)) // actor has no secret
    }

    private fun config(
        enforced: Boolean,
        secrets: Map<String, String> = emptyMap(),
    ): ServerConfig =
        ServerConfig(
            environment = "test",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
            adminApiToken = "test-admin-token",
            adminMfaEnforced = enforced,
            adminMfaSecrets = secrets,
        )
}
