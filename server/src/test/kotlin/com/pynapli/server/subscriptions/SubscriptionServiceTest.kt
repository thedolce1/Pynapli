package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
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
        assertEquals(SubscriptionProductId.PLAY_SOCIAL_MONTHLY, tiers[1].playStoreProductId)
        assertEquals(SubscriptionProductId.PLAY_SELECT_MONTHLY, tiers[2].playStoreProductId)
    }

    @Test
    fun playStorePurchaseSyncRecordsPendingPurchaseWithoutGrantingEntitlement() = runBlocking {
        val user = uuid(40)
        val repository = InMemorySubscriptionRepository()
        val service = SubscriptionService(repository, clock)

        val response = service.recordPlayStoreSubscriptionPurchase(
            userId = user,
            request = SubmitPlayStoreSubscriptionPurchaseRequestDto(
                productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                purchaseToken = "purchase-token-00000000000000000001",
                packageName = "com.pynapli.app"
            )
        )

        assertEquals(SubscriptionPurchaseSyncStatus.PENDING_VERIFICATION, response.status)
        assertEquals(SubscriptionTier.FREE, response.entitlement.tier)
        assertEquals(SubscriptionTier.SELECT, repository.pendingPurchases.single().tier)
        assertEquals("pending", repository.pendingPurchases.single().status)
    }

    @Test
    fun verifiedPlayStorePurchaseGrantsEntitlement() = runBlocking {
        val user = uuid(41)
        val repository = InMemorySubscriptionRepository()
        val service = SubscriptionService(
            repository = repository,
            clock = clock,
            playStorePurchaseVerifier = FakePlayStorePurchaseVerifier(
                PlayStorePurchaseVerificationResult.Verified(
                    VerifiedPlayStoreSubscriptionPurchase(
                        productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                        purchaseToken = "purchase-token-00000000000000000002",
                        latestOrderId = "GPA.0000-0000-0000-00000",
                        subscriptionState = "SUBSCRIPTION_STATE_ACTIVE",
                        acknowledgementState = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                        currentPeriodStart = Instant.parse("2026-05-06T12:00:00Z"),
                        currentPeriodEnd = Instant.parse("2026-06-06T12:00:00Z")
                    )
                )
            )
        )

        val response = service.recordPlayStoreSubscriptionPurchase(
            userId = user,
            request = SubmitPlayStoreSubscriptionPurchaseRequestDto(
                productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                purchaseToken = "purchase-token-00000000000000000002",
                packageName = "com.pynapli.app"
            )
        )

        assertEquals(SubscriptionPurchaseSyncStatus.ACTIVE, response.status)
        assertEquals(SubscriptionTier.SELECT, response.entitlement.tier)
        assertEquals(EntitlementSource.OWN_SUBSCRIPTION, response.entitlement.source)
        assertEquals("active", repository.verifiedPurchases.single().status)
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
    val pendingPurchases = mutableListOf<SubscriptionRecord>()
    val verifiedPurchases = mutableListOf<SubscriptionRecord>()

    override suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord? =
        (listOfNotNull(own[userId]) + verifiedPurchases.filter {
            it.userId == userId &&
                it.status in setOf("active", "grace") &&
                it.currentPeriodStart <= now &&
                it.currentPeriodEnd > now
        }).maxWithOrNull(
                compareBy<SubscriptionRecord> { if (it.tier == SubscriptionTier.SELECT) 2 else 1 }
                    .thenBy { it.currentPeriodEnd }
            )

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant
    ): LinkedPartnerSubscriptionRecord? = linked[userId]

    override suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord? =
        stored[userId]

    override suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean = false

    override suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant
    ): SubscriptionRecord {
        val record = SubscriptionRecord(
            subscriptionId = UUID.randomUUID(),
            userId = userId,
            tier = tier,
            status = "pending",
            currentPeriodStart = now,
            currentPeriodEnd = Instant.parse("2026-05-09T12:00:00Z")
        )
        pendingPurchases += record
        return record
    }

    override suspend fun upsertVerifiedPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        orderId: String?,
        tier: String,
        status: String,
        externalStatus: String,
        acknowledgementState: String?,
        acknowledgedAt: Instant?,
        currentPeriodStart: Instant,
        currentPeriodEnd: Instant,
        verifiedAt: Instant
    ): SubscriptionRecord {
        val record = SubscriptionRecord(
            subscriptionId = UUID.randomUUID(),
            userId = userId,
            tier = tier,
            status = status,
            currentPeriodStart = currentPeriodStart,
            currentPeriodEnd = currentPeriodEnd
        )
        verifiedPurchases += record
        return record
    }
}

private class FakePlayStorePurchaseVerifier(
    private val result: PlayStorePurchaseVerificationResult
) : PlayStorePurchaseVerifier {
    override suspend fun verifySubscriptionPurchase(
        packageName: String,
        productId: String,
        purchaseToken: String
    ): PlayStorePurchaseVerificationResult = result
}

private fun uuid(value: Int): UUID =
    UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
