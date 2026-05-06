package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-05-06T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun returnsFreeEntitlementWhenNoPaidSourceExists() = runBlocking {
        val service = SubscriptionService(InMemorySubscriptionRepository(), clock)

        val entitlement = service.getEntitlement(uuid(1))

        assertEquals(SubscriptionTier.FREE, entitlement.tier)
        assertEquals(EntitlementSource.FREE, entitlement.source)
        assertTrue(entitlement.adSupported)
        assertFalse(entitlement.linkedPartnerIncluded)
    }

    @Test
    fun ownSubscriptionBeatsSameTierLinkedPartnerBenefit() = runBlocking {
        val user = uuid(10)
        val partner = uuid(11)
        val repository = InMemorySubscriptionRepository(
            own = mapOf(user to subscription(user, SubscriptionTier.SOCIAL)),
            linked = mapOf(user to linkedSubscription(partner, SubscriptionTier.SOCIAL))
        )
        val service = SubscriptionService(repository, clock)

        val entitlement = service.getEntitlement(user)

        assertEquals(SubscriptionTier.SOCIAL, entitlement.tier)
        assertEquals(EntitlementSource.OWN_SUBSCRIPTION, entitlement.source)
        assertEquals(user.toString(), entitlement.sourceUserId)
    }

    @Test
    fun higherLinkedPartnerBenefitBeatsLowerOwnSubscription() = runBlocking {
        val user = uuid(20)
        val partner = uuid(21)
        val repository = InMemorySubscriptionRepository(
            own = mapOf(user to subscription(user, SubscriptionTier.SOCIAL)),
            linked = mapOf(user to linkedSubscription(partner, SubscriptionTier.SELECT))
        )
        val service = SubscriptionService(repository, clock)

        val entitlement = service.getEntitlement(user)

        assertEquals(SubscriptionTier.SELECT, entitlement.tier)
        assertEquals(EntitlementSource.LINKED_PARTNER_BENEFIT, entitlement.source)
        assertEquals(partner.toString(), entitlement.linkedPartnerUserId)
    }

    @Test
    fun storedAdminEntitlementReturnsPaidTier() = runBlocking {
        val user = uuid(30)
        val repository = InMemorySubscriptionRepository(
            stored = mapOf(
                user to StoredEntitlementRecord(
                    entitlementId = uuid(300),
                    userId = user,
                    tier = SubscriptionTier.SELECT,
                    source = EntitlementSource.ADMIN,
                    sourceId = null,
                    startsAt = Instant.parse("2026-05-01T00:00:00Z"),
                    endsAt = null
                )
            )
        )
        val service = SubscriptionService(repository, clock)

        val entitlement = service.getEntitlement(user)

        assertEquals(SubscriptionTier.SELECT, entitlement.tier)
        assertEquals(EntitlementSource.ADMIN, entitlement.source)
    }

    @Test
    fun inventoryKeepsLaunchPricingAndLinkedPartnerBenefit() {
        val service = SubscriptionService(InMemorySubscriptionRepository(), clock)

        val tiers = service.getInventory().tiers

        assertEquals(listOf(SubscriptionTier.FREE, SubscriptionTier.SOCIAL, SubscriptionTier.SELECT), tiers.map { it.tier })
        assertEquals(0, tiers[0].monthlyPriceUsdCents)
        assertEquals(499, tiers[1].monthlyPriceUsdCents)
        assertEquals(999, tiers[2].monthlyPriceUsdCents)
        assertFalse(tiers[0].linkedPartnerIncluded)
        assertTrue(tiers[1].linkedPartnerIncluded)
        assertTrue(tiers[2].linkedPartnerIncluded)
    }

    private fun subscription(userId: UUID, tier: String): SubscriptionRecord =
        SubscriptionRecord(
            subscriptionId = UUID.randomUUID(),
            userId = userId,
            tier = tier,
            status = "active",
            currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
            currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z")
        )

    private fun linkedSubscription(partnerUserId: UUID, tier: String): LinkedPartnerSubscriptionRecord =
        LinkedPartnerSubscriptionRecord(
            subscription = subscription(partnerUserId, tier),
            linkedPartnerUserId = partnerUserId,
            partnerLinkId = UUID.randomUUID()
        )
}

private class InMemorySubscriptionRepository(
    private val own: Map<UUID, SubscriptionRecord> = emptyMap(),
    private val linked: Map<UUID, LinkedPartnerSubscriptionRecord> = emptyMap(),
    private val stored: Map<UUID, StoredEntitlementRecord> = emptyMap()
) : SubscriptionRepository {
    override suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord? = own[userId]

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant
    ): LinkedPartnerSubscriptionRecord? = linked[userId]

    override suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord? =
        stored[userId]

    override suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean = false
}

private fun uuid(value: Int): UUID =
    UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
