package com.pynapli.server.subscriptions

import com.pynapli.server.testsupport.denyingRateLimitService
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-05-06T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun returnsFreeEntitlementWhenNoPaidSourceExists() =
        runBlocking {
            val service = SubscriptionService(InMemorySubscriptionRepository(), clock)

            val entitlement = service.getEntitlement(uuid(1))

            assertEquals(SubscriptionTier.FREE, entitlement.tier)
            assertEquals(EntitlementSource.FREE, entitlement.source)
            assertFalse(entitlement.linkedPartnerIncluded)
        }

    @Test
    fun ownSubscriptionBeatsSameTierLinkedPartnerBenefit() =
        runBlocking {
            val user = uuid(10)
            val partner = uuid(11)
            val repository =
                InMemorySubscriptionRepository(
                    own = mapOf(user to subscription(user, SubscriptionTier.SOCIAL)),
                    linked = mapOf(user to linkedSubscription(partner, SubscriptionTier.SOCIAL)),
                )
            val service = SubscriptionService(repository, clock)

            val entitlement = service.getEntitlement(user)

            assertEquals(SubscriptionTier.SOCIAL, entitlement.tier)
            assertEquals(EntitlementSource.OWN_SUBSCRIPTION, entitlement.source)
            assertEquals(user.toString(), entitlement.sourceUserId)
        }

    @Test
    fun higherLinkedPartnerBenefitBeatsLowerOwnSubscription() =
        runBlocking {
            val user = uuid(20)
            val partner = uuid(21)
            val repository =
                InMemorySubscriptionRepository(
                    own = mapOf(user to subscription(user, SubscriptionTier.SOCIAL)),
                    linked = mapOf(user to linkedSubscription(partner, SubscriptionTier.SELECT)),
                )
            val service = SubscriptionService(repository, clock)

            val entitlement = service.getEntitlement(user)

            assertEquals(SubscriptionTier.SELECT, entitlement.tier)
            assertEquals(EntitlementSource.LINKED_PARTNER_BENEFIT, entitlement.source)
            assertEquals(partner.toString(), entitlement.linkedPartnerUserId)
        }

    @Test
    fun storedAdminEntitlementReturnsPaidTier() =
        runBlocking {
            val user = uuid(30)
            val repository =
                InMemorySubscriptionRepository(
                    stored =
                        mapOf(
                            user to
                                StoredEntitlementRecord(
                                    entitlementId = uuid(300),
                                    userId = user,
                                    tier = SubscriptionTier.SELECT,
                                    source = EntitlementSource.ADMIN,
                                    sourceId = null,
                                    startsAt = Instant.parse("2026-05-01T00:00:00Z"),
                                    endsAt = null,
                                ),
                        ),
                )
            val service = SubscriptionService(repository, clock)

            val entitlement = service.getEntitlement(user)

            assertEquals(SubscriptionTier.SELECT, entitlement.tier)
            assertEquals(EntitlementSource.ADMIN, entitlement.source)
        }

    @Test
    fun getEntitlementUsesShortLivedCache() =
        runBlocking {
            val user = uuid(301)
            val repository =
                CountingActiveEntitlementRepository(
                    ActiveEntitlementRecord(
                        tier = SubscriptionTier.SOCIAL,
                        source = EntitlementSource.OWN_SUBSCRIPTION,
                        sourceSubscriptionId = uuid(3010),
                        sourceUserId = user,
                        linkedPartnerUserId = null,
                        startsAt = Instant.parse("2026-05-01T00:00:00Z"),
                        endsAt = Instant.parse("2026-06-01T00:00:00Z"),
                    ),
                )
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    entitlementCacheTtl = Duration.ofSeconds(5),
                )

            assertEquals(SubscriptionTier.SOCIAL, service.getEntitlement(user).tier)
            assertEquals(SubscriptionTier.SOCIAL, service.getEntitlement(user).tier)

            assertEquals(1, repository.lookupCount)
        }

    @Test
    fun adminGrantCreatesStoredEntitlementAndUpdatesPolicy() =
        runBlocking {
            val user = uuid(31)
            val repository = InMemorySubscriptionRepository(validUsers = setOf(user))
            val service = SubscriptionService(repository, clock)

            val record =
                service.grantStoredEntitlement(
                    userIdText = user.toString(),
                    tierText = SubscriptionTier.SELECT,
                    sourceText = EntitlementSource.ADMIN,
                    startsAtText = "2026-05-06T12:00:00Z",
                    endsAtText = "2026-06-06T12:00:00Z",
                    actorId = "billing-ops",
                    reason = "launch support",
                )
            val entitlement = service.getEntitlement(user)

            assertEquals(SubscriptionTier.SELECT, record.tier)
            assertEquals(EntitlementSource.ADMIN, record.source)
            assertEquals(SubscriptionTier.SELECT, entitlement.tier)
            assertEquals(EntitlementSource.ADMIN, entitlement.source)
        }

    @Test
    fun adminGrantInvalidatesCachedFreeEntitlement() =
        runBlocking {
            val user = uuid(310)
            val repository = InMemorySubscriptionRepository(validUsers = setOf(user))
            val service = SubscriptionService(repository, clock)

            assertEquals(SubscriptionTier.FREE, service.getEntitlement(user).tier)
            service.grantStoredEntitlement(
                userIdText = user.toString(),
                tierText = SubscriptionTier.SELECT,
                sourceText = EntitlementSource.ADMIN,
                startsAtText = "2026-05-06T12:00:00Z",
                endsAtText = "2026-06-06T12:00:00Z",
                actorId = "billing-ops",
                reason = "launch support",
            )

            val entitlement = service.getEntitlement(user)

            assertEquals(SubscriptionTier.SELECT, entitlement.tier)
            assertEquals(EntitlementSource.ADMIN, entitlement.source)
        }

    @Test
    fun adminRevokeRemovesStoredEntitlementFromPolicy() =
        runBlocking {
            val user = uuid(32)
            val repository = InMemorySubscriptionRepository(validUsers = setOf(user))
            val service = SubscriptionService(repository, clock)
            val record =
                service.grantStoredEntitlement(
                    userIdText = user.toString(),
                    tierText = SubscriptionTier.SOCIAL,
                    sourceText = EntitlementSource.PROMO,
                    startsAtText = null,
                    endsAtText = "2026-06-06T12:00:00Z",
                    actorId = "billing-ops",
                    reason = "promo grant",
                )

            val revoked =
                service.revokeStoredEntitlement(
                    entitlementIdText = record.entitlementId.toString(),
                    actorId = "billing-ops",
                    reason = "promo ended",
                )
            val entitlement = service.getEntitlement(user)

            assertEquals("revoked", revoked.status)
            assertEquals(SubscriptionTier.FREE, entitlement.tier)
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
        assertEquals(
            listOf(
                SubscriptionProductId.BOOST_SINGLE,
                SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
                SubscriptionProductId.INTRO_NOTES_THREE,
            ),
            service.getInventory().premiumProducts.map { it.productId },
        )
    }

    @Test
    fun devStoreSubscriptionPurchaseGrantsEntitlementAndInvalidatesCache() =
        runBlocking {
            val user = uuid(35)
            val repository = InMemorySubscriptionRepository()
            val service = SubscriptionService(repository, clock)

            assertEquals(SubscriptionTier.FREE, service.getEntitlement(user).tier)
            val response =
                service.recordDevStorePurchase(
                    userId = user,
                    request = SubmitDevStorePurchaseRequestDto(SubscriptionProductId.PLAY_SELECT_MONTHLY),
                )

            assertEquals(SubscriptionPurchaseSyncStatus.ACTIVE, response.status)
            assertEquals(SubscriptionTier.SELECT, response.entitlement.tier)
            assertEquals(EntitlementSource.OWN_SUBSCRIPTION, response.entitlement.source)
            assertEquals(SubscriptionTier.SELECT, repository.verifiedPurchases.single().tier)
        }

    @Test
    fun devStorePurchaseRejectsRateLimitedUserBeforeGrantingEntitlement() =
        runBlocking {
            val user = uuid(351)
            val repository = InMemorySubscriptionRepository()
            val service = SubscriptionService(repository, clock, rateLimitService = denyingRateLimitService())

            val error =
                assertFailsWith<SubscriptionException> {
                    service.recordDevStorePurchase(
                        userId = user,
                        request = SubmitDevStorePurchaseRequestDto(SubscriptionProductId.PLAY_SELECT_MONTHLY),
                    )
                }

            assertEquals("RATE_LIMITED", error.code)
            assertEquals(0, repository.verifiedPurchases.size)
        }

    @Test
    fun devStoreProductPurchaseGrantsInventoryBalance() =
        runBlocking {
            val user = uuid(36)
            val repository = InMemorySubscriptionRepository()
            val service = SubscriptionService(repository, clock)

            val response =
                service.recordDevStorePurchase(
                    userId = user,
                    request = SubmitDevStorePurchaseRequestDto(SubscriptionProductId.PINEAPPLE_LIKES_FIVE),
                )

            assertEquals("inventory_granted", response.status)
            val balance = response.inventory.balances.single()
            assertEquals(PremiumInventoryType.PINEAPPLE_LIKE, balance.inventoryType)
            assertEquals(5, balance.quantity)
        }

    @Test
    fun playStorePurchaseSyncRecordsPendingPurchaseWithoutGrantingEntitlement() =
        runBlocking {
            val user = uuid(40)
            val repository = InMemorySubscriptionRepository()
            val service = SubscriptionService(repository, clock)

            val response =
                service.recordPlayStoreSubscriptionPurchase(
                    userId = user,
                    request =
                        SubmitPlayStoreSubscriptionPurchaseRequestDto(
                            productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                            purchaseToken = "purchase-token-00000000000000000001",
                            packageName = "com.pynapli.app",
                        ),
                )

            assertEquals(SubscriptionPurchaseSyncStatus.PENDING_VERIFICATION, response.status)
            assertEquals(SubscriptionTier.FREE, response.entitlement.tier)
            assertEquals(SubscriptionTier.SELECT, repository.pendingPurchases.single().tier)
            assertEquals("pending", repository.pendingPurchases.single().status)
        }

    @Test
    fun verifiedPlayStorePurchaseGrantsEntitlement() =
        runBlocking {
            val user = uuid(41)
            val repository = InMemorySubscriptionRepository()
            val metrics =
                com.pynapli.server.metrics
                    .ProductMetrics()
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    playStorePurchaseVerifier =
                        FakePlayStorePurchaseVerifier(
                            PlayStorePurchaseVerificationResult.Verified(
                                VerifiedPlayStoreSubscriptionPurchase(
                                    productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                                    purchaseToken = "purchase-token-00000000000000000002",
                                    latestOrderId = "GPA.0000-0000-0000-00000",
                                    subscriptionState = "SUBSCRIPTION_STATE_ACTIVE",
                                    acknowledgementState = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                                    currentPeriodStart = Instant.parse("2026-05-06T12:00:00Z"),
                                    currentPeriodEnd = Instant.parse("2026-06-06T12:00:00Z"),
                                ),
                            ),
                        ),
                    productMetrics = metrics,
                )

            val response =
                service.recordPlayStoreSubscriptionPurchase(
                    userId = user,
                    request =
                        SubmitPlayStoreSubscriptionPurchaseRequestDto(
                            productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                            purchaseToken = "purchase-token-00000000000000000002",
                            packageName = "com.pynapli.app",
                        ),
                )

            assertEquals(SubscriptionPurchaseSyncStatus.ACTIVE, response.status)
            assertEquals(SubscriptionTier.SELECT, response.entitlement.tier)
            assertEquals(EntitlementSource.OWN_SUBSCRIPTION, response.entitlement.source)
            assertEquals("active", repository.verifiedPurchases.single().status)
            // Authoritative monetization KPI recorded on the user-initiated verified purchase.
            assertEquals(1, metrics.countFor(com.pynapli.server.metrics.ProductMetrics.PURCHASE_VERIFIED))
        }

    @Test
    fun sandboxVerifierGrantsEntitlementThroughRealSubmitPath() =
        runBlocking {
            // Proves the actual SandboxPlayStorePurchaseVerifier (not a fake) drives the real
            // submit -> verify -> grant path end-to-end, which is the whole point of the sandbox:
            // exercising Play Billing receipt validation without Google Play Developer API creds.
            val user = uuid(45)
            val repository = InMemorySubscriptionRepository()
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    playStorePurchaseVerifier = SandboxPlayStorePurchaseVerifier(clock = clock),
                )

            val response =
                service.recordPlayStoreSubscriptionPurchase(
                    userId = user,
                    request =
                        SubmitPlayStoreSubscriptionPurchaseRequestDto(
                            productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                            purchaseToken = "sandbox-select-00000000000000000045",
                            packageName = "com.pynapli.app",
                        ),
                )

            assertEquals(SubscriptionPurchaseSyncStatus.ACTIVE, response.status)
            assertEquals(SubscriptionTier.SELECT, response.entitlement.tier)
            assertEquals(EntitlementSource.OWN_SUBSCRIPTION, response.entitlement.source)
            assertEquals("active", repository.verifiedPurchases.single().status)
        }

    @Test
    fun playStoreSyncRefreshesDueSubscriptionTokens() =
        runBlocking {
            val user = uuid(42)
            val repository =
                InMemorySubscriptionRepository(
                    dueCandidates =
                        listOf(
                            PlayStoreSubscriptionVerificationCandidate(
                                userId = user,
                                productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                                purchaseToken = "purchase-token-00000000000000000003",
                                tier = SubscriptionTier.SELECT,
                                status = "active",
                                currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                                currentPeriodEnd = Instant.parse("2026-05-07T00:00:00Z"),
                            ),
                        ),
                )
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    playStorePurchaseVerifier =
                        FakePlayStorePurchaseVerifier(
                            PlayStorePurchaseVerificationResult.Verified(
                                VerifiedPlayStoreSubscriptionPurchase(
                                    productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                                    purchaseToken = "purchase-token-00000000000000000003",
                                    latestOrderId = "GPA.0000-0000-0000-00003",
                                    subscriptionState = "SUBSCRIPTION_STATE_ACTIVE",
                                    acknowledgementState = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                                    currentPeriodStart = Instant.parse("2026-05-06T12:00:00Z"),
                                    currentPeriodEnd = Instant.parse("2026-06-06T12:00:00Z"),
                                ),
                            ),
                        ),
                )

            val summary = service.syncDuePlayStoreSubscriptions()

            assertEquals(1, summary.scanned)
            assertEquals(1, summary.verified)
            assertEquals(1, summary.active)
            assertEquals("active", repository.verifiedPurchases.single().status)
            assertEquals(SubscriptionTier.SELECT, repository.verifiedPurchases.single().tier)
        }

    @Test
    fun playStoreSyncRevokesInvalidStoredTokens() =
        runBlocking {
            val user = uuid(43)
            val repository =
                InMemorySubscriptionRepository(
                    dueCandidates =
                        listOf(
                            PlayStoreSubscriptionVerificationCandidate(
                                userId = user,
                                productId = SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
                                purchaseToken = "purchase-token-00000000000000000004",
                                tier = SubscriptionTier.SOCIAL,
                                status = "active",
                                currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                                currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z"),
                            ),
                        ),
                )
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    playStorePurchaseVerifier =
                        FakePlayStorePurchaseVerifier(
                            PlayStorePurchaseVerificationResult.Invalid("INVALID_PURCHASE_TOKEN"),
                        ),
                )

            val summary = service.syncDuePlayStoreSubscriptions()

            assertEquals(1, summary.scanned)
            assertEquals(1, summary.invalid)
            assertEquals(1, summary.revoked)
            assertEquals("revoked", repository.verifiedPurchases.single().status)
        }

    @Test
    fun rtdnSubscriptionNotificationRefreshesLinkedPurchaseToken() =
        runBlocking {
            val user = uuid(44)
            val repository =
                InMemorySubscriptionRepository(
                    linkedPurchase =
                        PlayStoreSubscriptionVerificationCandidate(
                            userId = user,
                            productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                            purchaseToken = "purchase-token-00000000000000000005",
                            tier = SubscriptionTier.SELECT,
                            status = "active",
                            currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                            currentPeriodEnd = Instant.parse("2026-05-07T00:00:00Z"),
                        ),
                )
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    playStorePurchaseVerifier =
                        FakePlayStorePurchaseVerifier(
                            PlayStorePurchaseVerificationResult.Verified(
                                VerifiedPlayStoreSubscriptionPurchase(
                                    productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                                    purchaseToken = "purchase-token-00000000000000000005",
                                    latestOrderId = "GPA.0000-0000-0000-00005",
                                    subscriptionState = "SUBSCRIPTION_STATE_ACTIVE",
                                    acknowledgementState = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                                    currentPeriodStart = Instant.parse("2026-05-06T12:00:00Z"),
                                    currentPeriodEnd = Instant.parse("2026-06-06T12:00:00Z"),
                                ),
                            ),
                        ),
                )

            val result =
                service.handlePlayStoreRealTimeDeveloperNotification(
                    subscriptionRtdnEnvelope(
                        messageId = "rtdn-message-1",
                        purchaseToken = "purchase-token-00000000000000000005",
                    ),
                )

            assertEquals("processed", result.status)
            assertEquals("active", result.subscriptionStatus)
            assertEquals(1, repository.events.size)
            assertEquals("processed", repository.eventStatuses["rtdn-message-1"])
            assertEquals("active", repository.verifiedPurchases.single().status)
        }

    @Test
    fun playStoreSyncInvalidRevocationInvalidatesEntitlementCache() =
        runBlocking {
            // Without invalidation, a user whose subscription is voided/revoked by Play Store
            // would keep seeing their cached paid tier (and the policy that comes with it)
            // for up to the cache TTL — a small window of "lying entitlement". Verify the
            // sync path's Invalid branch clears the cache so the next lookup reflects reality.
            val user = uuid(50)
            val repository =
                MutableEntitlementRepository(
                    user = user,
                    initialEntitlement = activeEntitlement(user, SubscriptionTier.SELECT),
                    dueCandidates =
                        listOf(
                            PlayStoreSubscriptionVerificationCandidate(
                                userId = user,
                                productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                                purchaseToken = "purchase-token-00000000000000000050",
                                tier = SubscriptionTier.SELECT,
                                status = "active",
                                currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                                currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z"),
                            ),
                        ),
                )
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    entitlementCacheTtl = Duration.ofSeconds(30),
                    playStorePurchaseVerifier =
                        FakePlayStorePurchaseVerifier(
                            PlayStorePurchaseVerificationResult.Invalid("INVALID_PURCHASE_TOKEN"),
                        ),
                )

            assertEquals(SubscriptionTier.SELECT, service.getEntitlement(user).tier)
            assertEquals(1, repository.lookupCount)

            service.syncDuePlayStoreSubscriptions()
            assertEquals(SubscriptionTier.FREE, service.getEntitlement(user).tier)
            assertEquals(2, repository.lookupCount)
        }

    @Test
    fun rtdnInvalidRevocationInvalidatesEntitlementCache() =
        runBlocking {
            // RTDN Invalid branch (Play Store reports the purchase as invalid out-of-band)
            // must drop the cache so the user falls back to FREE on the next lookup.
            val user = uuid(51)
            val repository =
                MutableEntitlementRepository(
                    user = user,
                    initialEntitlement = activeEntitlement(user, SubscriptionTier.SOCIAL),
                    linkedPurchase =
                        PlayStoreSubscriptionVerificationCandidate(
                            userId = user,
                            productId = SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
                            purchaseToken = "purchase-token-00000000000000000051",
                            tier = SubscriptionTier.SOCIAL,
                            status = "active",
                            currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                            currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z"),
                        ),
                )
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    entitlementCacheTtl = Duration.ofSeconds(30),
                    playStorePurchaseVerifier =
                        FakePlayStorePurchaseVerifier(
                            PlayStorePurchaseVerificationResult.Invalid("REVOKED_BY_PUBLISHER"),
                        ),
                )

            assertEquals(SubscriptionTier.SOCIAL, service.getEntitlement(user).tier)
            assertEquals(1, repository.lookupCount)

            service.handlePlayStoreRealTimeDeveloperNotification(
                subscriptionRtdnEnvelope(
                    messageId = "rtdn-message-revoke-51",
                    purchaseToken = "purchase-token-00000000000000000051",
                ),
            )
            assertEquals(SubscriptionTier.FREE, service.getEntitlement(user).tier)
            assertEquals(2, repository.lookupCount)
        }

    @Test
    fun rtdnVoidedPurchaseInvalidatesEntitlementCache() =
        runBlocking {
            // Voided-purchase RTDN (refund) is the third entrypoint that flips a stored
            // subscription to revoked. Cache must drop or the refunded user keeps paid
            // features until the TTL expires.
            val user = uuid(52)
            val repository =
                MutableEntitlementRepository(
                    user = user,
                    initialEntitlement = activeEntitlement(user, SubscriptionTier.SELECT),
                    linkedPurchase =
                        PlayStoreSubscriptionVerificationCandidate(
                            userId = user,
                            productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                            purchaseToken = "purchase-token-00000000000000000052",
                            tier = SubscriptionTier.SELECT,
                            status = "active",
                            currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                            currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z"),
                        ),
                )
            val service =
                SubscriptionService(
                    repository = repository,
                    clock = clock,
                    entitlementCacheTtl = Duration.ofSeconds(30),
                )

            assertEquals(SubscriptionTier.SELECT, service.getEntitlement(user).tier)
            assertEquals(1, repository.lookupCount)

            service.handlePlayStoreRealTimeDeveloperNotification(
                voidedPurchaseRtdnEnvelope(
                    messageId = "rtdn-message-void-52",
                    purchaseToken = "purchase-token-00000000000000000052",
                ),
            )
            assertEquals(SubscriptionTier.FREE, service.getEntitlement(user).tier)
            assertEquals(2, repository.lookupCount)
        }

    @Test
    fun rtdnDuplicateMessageDoesNotReprocessPurchase() =
        runBlocking {
            val repository =
                InMemorySubscriptionRepository(
                    duplicateMessageIds = setOf("rtdn-message-duplicate"),
                )
            val service = SubscriptionService(repository = repository, clock = clock)

            val result =
                service.handlePlayStoreRealTimeDeveloperNotification(
                    subscriptionRtdnEnvelope(
                        messageId = "rtdn-message-duplicate",
                        purchaseToken = "purchase-token-00000000000000000006",
                    ),
                )

            assertEquals("duplicate", result.status)
            assertEquals(0, repository.verifiedPurchases.size)
        }

    private fun subscription(
        userId: UUID,
        tier: String,
    ): SubscriptionRecord =
        SubscriptionRecord(
            subscriptionId = UUID.randomUUID(),
            userId = userId,
            tier = tier,
            status = "active",
            currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
            currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z"),
        )

    private fun linkedSubscription(
        partnerUserId: UUID,
        tier: String,
    ): LinkedPartnerSubscriptionRecord =
        LinkedPartnerSubscriptionRecord(
            subscription = subscription(partnerUserId, tier),
            linkedPartnerUserId = partnerUserId,
            partnerLinkId = UUID.randomUUID(),
        )
}

private class InMemorySubscriptionRepository(
    private val own: Map<UUID, SubscriptionRecord> = emptyMap(),
    private val linked: Map<UUID, LinkedPartnerSubscriptionRecord> = emptyMap(),
    private val stored: Map<UUID, StoredEntitlementRecord> = emptyMap(),
    private val dueCandidates: List<PlayStoreSubscriptionVerificationCandidate> = emptyList(),
    private val linkedPurchase: PlayStoreSubscriptionVerificationCandidate? = null,
    private val duplicateMessageIds: Set<String> = emptySet(),
    private val validUsers: Set<UUID> = emptySet(),
) : SubscriptionRepository {
    val pendingPurchases = mutableListOf<SubscriptionRecord>()
    val verifiedPurchases = mutableListOf<SubscriptionRecord>()
    private val inventoryEntries = mutableListOf<InventoryEntry>()
    private val storedEntitlements = stored.toMutableMap()
    val events = mutableListOf<PlayStoreRtdnEventInsert>()
    val eventStatuses = mutableMapOf<String, String>()

    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? =
        (
            listOfNotNull(own[userId]) +
                verifiedPurchases.filter {
                    it.userId == userId &&
                        it.status in setOf("active", "grace") &&
                        it.currentPeriodStart <= now &&
                        it.currentPeriodEnd > now
                }
        ).maxWithOrNull(
            compareBy<SubscriptionRecord> { if (it.tier == SubscriptionTier.SELECT) 2 else 1 }
                .thenBy { it.currentPeriodEnd },
        )

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? = linked[userId]

    override suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? =
        storedEntitlements.values
            .filter {
                it.userId == userId &&
                    it.status == "active" &&
                    it.startsAt <= now &&
                    (it.endsAt == null || it.endsAt > now)
            }.maxWithOrNull(
                compareBy<StoredEntitlementRecord> { if (it.tier == SubscriptionTier.SELECT) 2 else 1 }
                    .thenBy { it.endsAt ?: Instant.MAX },
            )

    override suspend fun listStoredEntitlementsForUser(
        userId: UUID,
        limit: Int,
    ): List<StoredEntitlementRecord> =
        storedEntitlements.values
            .filter { it.userId == userId }
            .sortedByDescending { it.startsAt }
            .take(limit)

    override suspend fun grantStoredEntitlement(
        userId: UUID,
        tier: String,
        source: String,
        startsAt: Instant,
        endsAt: Instant?,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? {
        if (userId !in validUsers) return null
        val record =
            StoredEntitlementRecord(
                entitlementId = UUID.randomUUID(),
                userId = userId,
                tier = tier,
                source = source,
                sourceId = null,
                startsAt = startsAt,
                endsAt = endsAt,
                status = "active",
            )
        storedEntitlements[record.entitlementId] = record
        return record
    }

    override suspend fun revokeStoredEntitlement(
        entitlementId: UUID,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? {
        val current = storedEntitlements[entitlementId]?.takeIf { it.status == "active" } ?: return null
        val revoked = current.copy(status = "revoked")
        storedEntitlements[entitlementId] = revoked
        return revoked
    }

    override suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = false

    override suspend fun listPremiumInventoryBalances(
        userId: UUID,
        now: Instant,
    ): List<PremiumInventoryBalanceRecord> =
        inventoryEntries
            .filter { entry ->
                entry.userId == userId &&
                    entry.quantity > 0 &&
                    (entry.expiresAt == null || entry.expiresAt > now)
            }.groupBy { it.inventoryType }
            .map { (inventoryType, entries) ->
                PremiumInventoryBalanceRecord(
                    inventoryType = inventoryType,
                    quantity = entries.sumOf { it.quantity },
                    nextExpiresAt = entries.mapNotNull { it.expiresAt }.minOrNull(),
                )
            }.sortedBy { it.inventoryType }

    override suspend fun grantPremiumInventory(
        userId: UUID,
        inventoryType: String,
        quantity: Int,
        source: String,
        expiresAt: Instant?,
        now: Instant,
    ): PremiumInventoryBalanceRecord? {
        inventoryEntries +=
            InventoryEntry(
                userId = userId,
                inventoryType = inventoryType,
                quantity = quantity,
                expiresAt = expiresAt,
            )
        return listPremiumInventoryBalances(userId, now).firstOrNull { it.inventoryType == inventoryType }
    }

    override suspend fun upsertDevSubscriptionPurchase(
        userId: UUID,
        productId: String,
        tier: String,
        startsAt: Instant,
        endsAt: Instant,
    ): SubscriptionRecord {
        verifiedPurchases.removeAll { it.userId == userId && it.status == "active" }
        val record =
            SubscriptionRecord(
                subscriptionId = UUID.randomUUID(),
                userId = userId,
                tier = tier,
                status = "active",
                currentPeriodStart = startsAt,
                currentPeriodEnd = endsAt,
            )
        verifiedPurchases += record
        return record
    }

    override suspend fun listPlayStoreSubscriptionsDueForVerification(
        staleBefore: Instant,
        renewalWindowEnd: Instant,
        limit: Int,
    ): List<PlayStoreSubscriptionVerificationCandidate> = dueCandidates.take(limit)

    override suspend fun findPlayStoreSubscriptionByPurchaseToken(
        purchaseToken: String,
    ): PlayStoreSubscriptionVerificationCandidate? = linkedPurchase?.takeIf { it.purchaseToken == purchaseToken }

    override suspend fun insertPlayStoreRtdnEvent(event: PlayStoreRtdnEventInsert): Boolean {
        if (event.messageId in duplicateMessageIds) return false
        events += event
        return true
    }

    override suspend fun updatePlayStoreRtdnEventStatus(
        messageId: String,
        processingStatus: String,
        errorCode: String?,
    ) {
        eventStatuses[messageId] = processingStatus
    }

    override suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant,
    ): SubscriptionRecord {
        val record =
            SubscriptionRecord(
                subscriptionId = UUID.randomUUID(),
                userId = userId,
                tier = tier,
                status = "pending",
                currentPeriodStart = now,
                currentPeriodEnd = Instant.parse("2026-05-09T12:00:00Z"),
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
        verifiedAt: Instant,
    ): SubscriptionRecord {
        val record =
            SubscriptionRecord(
                subscriptionId = UUID.randomUUID(),
                userId = userId,
                tier = tier,
                status = status,
                currentPeriodStart = currentPeriodStart,
                currentPeriodEnd = currentPeriodEnd,
            )
        verifiedPurchases += record
        return record
    }

    private data class InventoryEntry(
        val userId: UUID,
        val inventoryType: String,
        val quantity: Int,
        val expiresAt: Instant?,
    )
}

private class CountingActiveEntitlementRepository(
    private val entitlement: ActiveEntitlementRecord?,
) : SubscriptionRepository {
    var lookupCount: Int = 0
        private set

    override suspend fun findBestActiveEntitlement(
        userId: UUID,
        now: Instant,
    ): ActiveEntitlementRecord? {
        lookupCount += 1
        return entitlement?.takeIf { it.sourceUserId == userId }
    }

    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? = null

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? = null

    override suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? = null

    override suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = false
}

private class FakePlayStorePurchaseVerifier(
    private val result: PlayStorePurchaseVerificationResult,
) : PlayStorePurchaseVerifier {
    override suspend fun verifySubscriptionPurchase(
        packageName: String,
        productId: String,
        purchaseToken: String,
    ): PlayStorePurchaseVerificationResult = result

    override suspend fun verifySubscriptionPurchaseByToken(
        packageName: String,
        purchaseToken: String,
    ): PlayStorePurchaseVerificationResult = result
}

private fun subscriptionRtdnEnvelope(
    messageId: String,
    purchaseToken: String,
    productId: String = SubscriptionProductId.PLAY_SELECT_MONTHLY,
): PlayStoreRtdnEnvelope =
    PlayStoreRtdnEnvelope(
        messageId = messageId,
        rawPayload = "{}",
        notification =
            PlayStoreDeveloperNotificationDto(
                packageName = "com.pynapli.app",
                subscriptionNotification =
                    PlayStoreSubscriptionNotificationDto(
                        version = "1.0",
                        notificationType = 2,
                        purchaseToken = purchaseToken,
                        subscriptionId = productId,
                    ),
            ),
    )

private fun voidedPurchaseRtdnEnvelope(
    messageId: String,
    purchaseToken: String,
): PlayStoreRtdnEnvelope =
    PlayStoreRtdnEnvelope(
        messageId = messageId,
        rawPayload = "{}",
        notification =
            PlayStoreDeveloperNotificationDto(
                packageName = "com.pynapli.app",
                voidedPurchaseNotification =
                    PlayStoreVoidedPurchaseNotificationDto(
                        purchaseToken = purchaseToken,
                        orderId = "GPA.0000-0000-0000-9999",
                        productType = 1,
                        refundType = 1,
                    ),
            ),
    )

private fun activeEntitlement(
    userId: UUID,
    tier: String,
): ActiveEntitlementRecord =
    ActiveEntitlementRecord(
        tier = tier,
        source = EntitlementSource.OWN_SUBSCRIPTION,
        sourceSubscriptionId = UUID.randomUUID(),
        sourceUserId = userId,
        linkedPartnerUserId = null,
        startsAt = Instant.parse("2026-05-01T00:00:00Z"),
        endsAt = Instant.parse("2026-06-01T00:00:00Z"),
    )

private class MutableEntitlementRepository(
    private val user: UUID,
    private val initialEntitlement: ActiveEntitlementRecord,
    private val dueCandidates: List<PlayStoreSubscriptionVerificationCandidate> = emptyList(),
    private val linkedPurchase: PlayStoreSubscriptionVerificationCandidate? = null,
) : SubscriptionRepository {
    var lookupCount: Int = 0
        private set
    private var revoked: Boolean = false

    override suspend fun findBestActiveEntitlement(
        userId: UUID,
        now: Instant,
    ): ActiveEntitlementRecord? {
        lookupCount += 1
        if (revoked) return null
        return initialEntitlement.takeIf { it.sourceUserId == userId }
    }

    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? = null

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? = null

    override suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? = null

    override suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = false

    override suspend fun listPlayStoreSubscriptionsDueForVerification(
        staleBefore: Instant,
        renewalWindowEnd: Instant,
        limit: Int,
    ): List<PlayStoreSubscriptionVerificationCandidate> = dueCandidates.take(limit)

    override suspend fun findPlayStoreSubscriptionByPurchaseToken(
        purchaseToken: String,
    ): PlayStoreSubscriptionVerificationCandidate? = linkedPurchase?.takeIf { it.purchaseToken == purchaseToken }

    override suspend fun insertPlayStoreRtdnEvent(event: PlayStoreRtdnEventInsert): Boolean = true

    override suspend fun updatePlayStoreRtdnEventStatus(
        messageId: String,
        processingStatus: String,
        errorCode: String?,
    ) = Unit

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
        verifiedAt: Instant,
    ): SubscriptionRecord {
        // Mirror the production DB semantics: an "revoked" upsert removes the
        // record from the user's active-entitlement view.
        if (status == "revoked" && userId == user) revoked = true
        return SubscriptionRecord(
            subscriptionId = UUID.randomUUID(),
            userId = userId,
            tier = tier,
            status = status,
            currentPeriodStart = currentPeriodStart,
            currentPeriodEnd = currentPeriodEnd,
        )
    }

    override suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant,
    ): SubscriptionRecord =
        SubscriptionRecord(
            subscriptionId = UUID.randomUUID(),
            userId = userId,
            tier = tier,
            status = "pending",
            currentPeriodStart = now,
            currentPeriodEnd = now.plusSeconds(3_600),
        )
}

private fun uuid(value: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
