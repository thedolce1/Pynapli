package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.testsupport.testServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminRolesTest {
    @Test
    fun enforcementOffTreatsEveryAdminAsSenior() {
        val config = config(roles = emptyMap())
        assertEquals(ADMIN_ROLE_SENIOR, resolveAdminRole(config, "anyone"))
        requireSeniorAdmin(config, "anyone") // no roles configured -> no-op
    }

    @Test
    fun configuredSeniorIsAllowed() {
        val config = config(roles = mapOf("boss" to "senior"))
        assertEquals(ADMIN_ROLE_SENIOR, resolveAdminRole(config, "boss"))
        requireSeniorAdmin(config, "boss")
    }

    @Test
    fun configuredOperatorIsForbiddenFromSeniorActions() {
        val config = config(roles = mapOf("ops" to "operator"))
        assertEquals(ADMIN_ROLE_OPERATOR, resolveAdminRole(config, "ops"))
        assertFailsWith<ModerationException> { requireSeniorAdmin(config, "ops") }
    }

    @Test
    fun unmappedActorDefaultsToOperatorWhenRolesConfigured() {
        val config = config(roles = mapOf("boss" to "senior"))
        assertEquals(ADMIN_ROLE_OPERATOR, resolveAdminRole(config, "stranger"))
        assertFailsWith<ModerationException> { requireSeniorAdmin(config, "stranger") }
    }

    private fun config(roles: Map<String, String>): ServerConfig =
        testServerConfig().copy(
            adminApiToken = "test-admin-token",
            adminRoles = roles,
        )
}
