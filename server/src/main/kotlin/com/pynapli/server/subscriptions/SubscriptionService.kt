package com.pynapli.server.subscriptions

import com.pynapli.server.safety.RateLimitService
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

class SubscriptionService(
    private val repository: SubscriptionRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val playStorePackageName: String = "com.pynapli.app",
    private val playStorePurchaseVerifier: PlayStorePurchaseVerifier = DisabledPlayStorePurchaseVerifier,
    private val playStoreSyncStaleAfter: Duration = Duration.ofHours(6),
    private val playStoreSyncRenewalLookahead: Duration = Duration.ofHours(24),
    entitlementCacheTtl: Duration = Duration.ofSeconds(5),
    entitlementCacheMaxSize: Int = 10_000,
    private val rateLimitService: RateLimitService? = null,
    private val productMetrics: com.pynapli.server.metrics.ProductMetrics? = null,
) {
    private val entitlementCache =
        SubscriptionEntitlementCache(
            ttl = entitlementCacheTtl,
            maxSize = entitlementCacheMaxSize,
        )
    private val playStorePurchaseApplier =
        PlayStoreSubscriptionPurchaseApplier(
            repository = repository,
            invalidateEntitlement = entitlementCache::invalidate,
        )
    private val playStoreRtdnProcessor =
        PlayStoreRtdnProcessor(
            repository = repository,
            clock = clock,
            playStorePackageName = playStorePackageName,
            playStorePurchaseVerifier = playStorePurchaseVerifier,
            purchaseApplier = playStorePurchaseApplier,
        )
    private val entitlementActions =
        SubscriptionEntitlementActions(
            repository = repository,
            clock = clock,
            entitlementCache = entitlementCache,
        )
    private val inventoryActions =
        SubscriptionInventoryActions(
            repository = repository,
            clock = clock,
            rateLimitService = rateLimitService,
        )
    private val devStorePurchaseActions =
        SubscriptionDevStorePurchaseActions(
            repository = repository,
            clock = clock,
            rateLimitService = rateLimitService,
            invalidateEntitlement = entitlementActions::invalidateEntitlement,
            getEntitlement = ::getEntitlement,
            getInventory = ::getInventory,
        )
    private val playStorePurchaseActions =
        SubscriptionPlayStorePurchaseActions(
            repository = repository,
            clock = clock,
            playStorePackageName = playStorePackageName,
            playStorePurchaseVerifier = playStorePurchaseVerifier,
            purchaseApplier = playStorePurchaseApplier,
            rateLimitService = rateLimitService,
            productMetrics = productMetrics,
            getEntitlement = ::getEntitlement,
        )
    private val playStoreProductPurchaseActions =
        SubscriptionPlayStoreProductPurchaseActions(
            repository = repository,
            clock = clock,
            playStorePackageName = playStorePackageName,
            playStorePurchaseVerifier = playStorePurchaseVerifier,
            rateLimitService = rateLimitService,
            getEntitlement = ::getEntitlement,
            getInventory = ::getInventory,
        )
    private val playStoreSyncActions =
        SubscriptionPlayStoreSyncActions(
            repository = repository,
            clock = clock,
            playStorePackageName = playStorePackageName,
            playStorePurchaseVerifier = playStorePurchaseVerifier,
            purchaseApplier = playStorePurchaseApplier,
            playStoreSyncStaleAfter = playStoreSyncStaleAfter,
            playStoreSyncRenewalLookahead = playStoreSyncRenewalLookahead,
        )

    suspend fun getEntitlement(userId: UUID): SubscriptionEntitlementDto = entitlementActions.getEntitlement(userId)

    suspend fun listStoredEntitlementsForUser(
        userIdText: String,
        limitText: String?,
    ): List<StoredEntitlementRecord> =
        entitlementActions.listStoredEntitlementsForUser(
            userIdText = userIdText,
            limitText = limitText,
        )

    suspend fun grantStoredEntitlement(
        userIdText: String,
        tierText: String?,
        sourceText: String?,
        startsAtText: String?,
        endsAtText: String?,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord =
        entitlementActions.grantStoredEntitlement(
            userIdText = userIdText,
            tierText = tierText,
            sourceText = sourceText,
            startsAtText = startsAtText,
            endsAtText = endsAtText,
            actorId = actorId,
            reason = reason,
        )

    suspend fun revokeStoredEntitlement(
        entitlementIdText: String,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord =
        entitlementActions.revokeStoredEntitlement(
            entitlementIdText = entitlementIdText,
            actorId = actorId,
            reason = reason,
        )

    fun getInventory(): SubscriptionInventoryResponseDto = SubscriptionCatalog.inventory()

    suspend fun getInventory(userId: UUID): SubscriptionInventoryResponseDto = inventoryActions.getInventory(userId)

    suspend fun recordDevStorePurchase(
        userId: UUID,
        request: SubmitDevStorePurchaseRequestDto,
    ): StorePurchaseResponseDto =
        devStorePurchaseActions.recordDevStorePurchase(
            userId = userId,
            request = request,
        )

    suspend fun recordPlayStoreSubscriptionPurchase(
        userId: UUID,
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): SubscriptionPurchaseSyncResponseDto =
        playStorePurchaseActions.recordPlayStoreSubscriptionPurchase(
            userId = userId,
            request = request,
        )

    suspend fun syncDuePlayStoreSubscriptions(limit: Int = PLAY_STORE_SYNC_DEFAULT_LIMIT): PlayStoreSubscriptionSyncSummary = playStoreSyncActions.syncDuePlayStoreSubscriptions(limit)

    suspend fun handlePlayStoreRealTimeDeveloperNotification(
        envelope: PlayStoreRtdnEnvelope,
    ): PlayStoreRtdnProcessingResult = playStoreRtdnProcessor.handle(envelope)

    suspend fun getPolicy(userId: UUID): EntitlementPolicy = entitlementActions.getPolicy(userId)

    suspend fun consumeIntroNoteInventory(userId: UUID): Boolean = inventoryActions.consumeIntroNoteInventory(userId)

    suspend fun consumePineappleLikeInventory(userId: UUID): Boolean = inventoryActions.consumePineappleLikeInventory(userId)

    /**
     * Variant of `consumePineappleLikeInventory` that returns the consumed
     * `premium_inventory.id`, so the caller (Swipe action recorder) can persist it
     * on the action row and the rewind path can refund the exact row via
     * `refundPineappleLikeInventory`.
     */
    suspend fun consumePineappleLikeInventoryReturningId(userId: UUID): UUID? = inventoryActions.consumePineappleLikeInventoryReturningId(userId)

    suspend fun refundPineappleLikeInventory(inventoryId: UUID): Boolean = inventoryActions.refundPineappleLikeInventory(inventoryId)

    suspend fun activateBoost(userId: UUID): BoostActivationDto = inventoryActions.activateBoost(userId)

    suspend fun recordPlayStoreProductPurchase(
        userId: UUID,
        request: com.pynapli.shared.network.dto.SubmitPlayStoreProductPurchaseRequestDto,
    ): com.pynapli.shared.network.dto.StorePurchaseResponseDto = playStoreProductPurchaseActions.recordProductPurchase(userId, request)

    private companion object {
        private const val PLAY_STORE_SYNC_DEFAULT_LIMIT = 100
    }
}
