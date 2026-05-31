package com.pynapli.server.safety

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskPolicyServiceTest {
    @Test
    fun cleanAndFrictionAccountsCanPerformSocialActions() =
        runBlocking {
            assertFalse(riskPolicy("clean").isSocialActionRestricted(UUID.randomUUID()))
            assertFalse(riskPolicy("watch").isSocialActionRestricted(UUID.randomUUID()))
            assertFalse(riskPolicy("friction").isSocialActionRestricted(UUID.randomUUID()))
        }

    @Test
    fun quarantineAndTerminateAccountsAreSocialActionRestricted() =
        runBlocking {
            assertTrue(riskPolicy("quarantine").isSocialActionRestricted(UUID.randomUUID()))
            assertTrue(riskPolicy("terminate").isSocialActionRestricted(UUID.randomUUID()))
        }

    @Test
    fun quarantineAndTerminateAccountsAreHiddenFromDiscovery() =
        runBlocking {
            assertFalse(riskPolicy("friction").shouldHideFromDiscovery(UUID.randomUUID()))
            assertTrue(riskPolicy("quarantine").shouldHideFromDiscovery(UUID.randomUUID()))
            assertTrue(riskPolicy("terminate").shouldHideFromDiscovery(UUID.randomUUID()))
        }

    @Test
    fun missingRiskStateIsAllowed() =
        runBlocking {
            val service = RiskPolicyService { null }

            assertFalse(service.isSocialActionRestricted(UUID.randomUUID()))
            assertFalse(service.shouldHideFromDiscovery(UUID.randomUUID()))
        }
}

private fun riskPolicy(tier: String): RiskPolicyService =
    RiskPolicyService { userId ->
        AccountRiskStateRecord(
            userId = userId,
            score = 70,
            tier = tier,
            lastEventAt = Instant.parse("2026-04-28T18:00:00Z"),
            lastReviewedAt = null,
            updatedAt = Instant.parse("2026-04-28T18:00:00Z"),
        )
    }
