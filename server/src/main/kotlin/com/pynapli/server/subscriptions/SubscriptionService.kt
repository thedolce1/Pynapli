package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumInventoryBalanceDto
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import com.pynapli.shared.network.dto.SubscriptionTier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SubscriptionService(
    private val repository: SubscriptionRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val playStorePackageName: String = "com.pynapli.app",
    private val playStorePurchaseVerifier: PlayStorePurchaseVerifier = DisabledPlayStorePurchaseVerifier,
    private val playStoreSyncStaleAfter: Duration = Duration.ofHours(6),
    private val playStoreSyncRenewalLookahead: Duration = Duration.ofHours(24),
    private val entitlementCacheTtl: Duration = Duration.ofSeconds(5),
    private val entitlementCacheMaxSize: Int = 10_000,
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

    suspend fun getEntitlement(userId: UUID): SubscriptionEntitlementDto {
        val now = clock.instant()
        entitlementCache.get(userId, now)?.let { return it }

        val entitlement =
            repository
                .findBestActiveEntitlement(userId, now)
                ?.let { SubscriptionEntitlementMapper.activeToDto(it) }
                ?: SubscriptionEntitlementMapper.free()
        entitlementCache.put(userId, entitlement, now)
        return entitlement
    }

    suspend fun listStoredEntitlementsForUser(
        userIdText: String,
        limitText: String?,
    ): List<StoredEntitlementRecord> {
        val userId = userIdText.toUuidOrThrow("INVALID_ENTITLEMENT_USER")
        val limit = limitText?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        return repository.listStoredEntitlementsForUser(userId, limit)
    }

    suspend fun grantStoredEntitlement(
        userIdText: String,
        tierText: String?,
        sourceText: String?,
        startsAtText: String?,
        endsAtText: String?,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord {
        val userId = userIdText.toUuidOrThrow("INVALID_ENTITLEMENT_USER")
        val tier =
            tierText
                ?.trim()
                ?.lowercase()
                ?.takeIf { it in ADMIN_GRANTABLE_TIERS }
                ?: throw SubscriptionException("INVALID_ENTITLEMENT_TIER")
        val source = sourceText?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: EntitlementSource.ADMIN
        if (source !in ADMIN_GRANTABLE_SOURCES) throw SubscriptionException("INVALID_ENTITLEMENT_SOURCE")
        val startsAt = startsAtText?.toInstantOrThrow("INVALID_ENTITLEMENT_PERIOD") ?: clock.instant()
        val endsAt = endsAtText?.toInstantOrThrow("INVALID_ENTITLEMENT_PERIOD")
        if (endsAt != null && endsAt <= startsAt) throw SubscriptionException("INVALID_ENTITLEMENT_PERIOD")
        val normalizedActorId = actorId.validatedAdminActor()
        val normalizedReason = reason.validatedAdminReason()

        val record =
            repository.grantStoredEntitlement(
                userId = userId,
                tier = tier,
                source = source,
                startsAt = startsAt,
                endsAt = endsAt,
                actorId = normalizedActorId,
                reason = normalizedReason,
            ) ?: throw SubscriptionException("INVALID_ENTITLEMENT_USER")
        invalidateEntitlement(userId)
        return record
    }

    suspend fun revokeStoredEntitlement(
        entitlementIdText: String,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord {
        val entitlementId = entitlementIdText.toUuidOrThrow("ENTITLEMENT_NOT_FOUND")
        val record =
            repository.revokeStoredEntitlement(
                entitlementId = entitlementId,
                actorId = actorId.validatedAdminActor(),
                reason = reason.validatedAdminReason(),
            ) ?: throw SubscriptionException("ENTITLEMENT_NOT_FOUND")
        invalidateEntitlement(record.userId)
        return record
    }

    fun getInventory(): SubscriptionInventoryResponseDto = SubscriptionCatalog.inventory()

    suspend fun getInventory(userId: UUID): SubscriptionInventoryResponseDto {
        val now = clock.instant()
        return getInventory().copy(
            balances = repository.listPremiumInventoryBalances(userId, now).map { it.toDto() },
        )
    }

    suspend fun recordDevStorePurchase(
        userId: UUID,
        request: SubmitDevStorePurchaseRequestDto,
    ): StorePurchaseResponseDto {
        val productId = request.productId.trim()
        if (productId.isEmpty()) throw SubscriptionException("INVALID_STORE_PRODUCT")
        val now = clock.instant()
        val status =
            when (productId) {
                SubscriptionProductId.PLAY_SOCIAL_MONTHLY -> {
                    repository.upsertDevSubscriptionPurchase(
                        userId = userId,
                        productId = productId,
                        tier = SubscriptionTier.SOCIAL,
                        startsAt = now,
                        endsAt = now.plus(DEV_SUBSCRIPTION_PERIOD),
                    )
                    invalidateEntitlement(userId)
                    SubscriptionPurchaseSyncStatus.ACTIVE
                }

                SubscriptionProductId.PLAY_SELECT_MONTHLY -> {
                    repository.upsertDevSubscriptionPurchase(
                        userId = userId,
                        productId = productId,
                        tier = SubscriptionTier.SELECT,
                        startsAt = now,
                        endsAt = now.plus(DEV_SUBSCRIPTION_PERIOD),
                    )
                    invalidateEntitlement(userId)
                    SubscriptionPurchaseSyncStatus.ACTIVE
                }

                SubscriptionProductId.BOOST_SINGLE -> {
                    grantDevPremiumProduct(userId, PremiumInventoryType.BOOST, quantity = 1, now = now)
                    DEV_INVENTORY_GRANTED
                }

                SubscriptionProductId.PINEAPPLE_LIKES_FIVE -> {
                    grantDevPremiumProduct(userId, PremiumInventoryType.PINEAPPLE_LIKE, quantity = 5, now = now)
                    DEV_INVENTORY_GRANTED
                }

                SubscriptionProductId.INTRO_NOTES_THREE -> {
                    grantDevPremiumProduct(userId, PremiumInventoryType.INTRO_NOTE, quantity = 3, now = now)
                    DEV_INVENTORY_GRANTED
                }

                else -> throw SubscriptionException("INVALID_STORE_PRODUCT")
            }

        return StorePurchaseResponseDto(
            status = status,
            entitlement = getEntitlement(userId),
            inventory = getInventory(userId),
        )
    }

    suspend fun recordPlayStoreSubscriptionPurchase(
        userId: UUID,
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): SubscriptionPurchaseSyncResponseDto {
        val productId = request.productId.trim()
        val purchaseToken = request.purchaseToken.trim()
        val packageName = request.packageName.trim()
        if (productId.isEmpty()) throw SubscriptionException("INVALID_SUBSCRIPTION_PRODUCT")
        if (purchaseToken.length !in 16..4096) throw SubscriptionException("INVALID_PURCHASE_TOKEN")
        if (packageName != playStorePackageName) throw SubscriptionException("INVALID_PURCHASE_PACKAGE")

        val tier = playStoreTierForProduct(productId) ?: throw SubscriptionException("INVALID_SUBSCRIPTION_PRODUCT")
        val now = clock.instant()
        return when (
            val verification =
                playStorePurchaseVerifier.verifySubscriptionPurchase(
                    packageName = packageName,
                    productId = productId,
                    purchaseToken = purchaseToken,
                )
        ) {
            is PlayStorePurchaseVerificationResult.Invalid -> throw SubscriptionException(verification.code)
            PlayStorePurchaseVerificationResult.VerificationUnavailable -> {
                repository.upsertPendingPlayStoreSubscriptionPurchase(
                    userId = userId,
                    productId = productId,
                    purchaseToken = purchaseToken,
                    tier = tier,
                    now = now,
                )
                pendingVerificationResponse(userId)
            }
            is PlayStorePurchaseVerificationResult.Verified -> {
                val subscription =
                    playStorePurchaseApplier.applyVerifiedPurchase(
                        userId = userId,
                        tier = tier,
                        purchase = verification.purchase,
                        verifiedAt = now,
                    )

                if (subscription.status in ACTIVE_PLAY_STORE_SUBSCRIPTION_STATUSES) {
                    SubscriptionPurchaseSyncResponseDto(
                        status = SubscriptionPurchaseSyncStatus.ACTIVE,
                        entitlement = getEntitlement(userId),
                    )
                } else {
                    pendingVerificationResponse(userId)
                }
            }
        }
    }

    suspend fun syncDuePlayStoreSubscriptions(limit: Int = PLAY_STORE_SYNC_DEFAULT_LIMIT): PlayStoreSubscriptionSyncSummary {
        val now = clock.instant()
        val normalizedLimit = limit.coerceIn(1, PLAY_STORE_SYNC_MAX_LIMIT)
        val candidates =
            repository.listPlayStoreSubscriptionsDueForVerification(
                staleBefore = now.minus(playStoreSyncStaleAfter),
                renewalWindowEnd = now.plus(playStoreSyncRenewalLookahead),
                limit = normalizedLimit,
            )
        var verified = 0
        var active = 0
        var grace = 0
        var pending = 0
        var expired = 0
        var revoked = 0
        var invalid = 0
        var unavailable = 0

        candidates.forEach { candidate ->
            when (
                val verification =
                    playStorePurchaseVerifier.verifySubscriptionPurchase(
                        packageName = playStorePackageName,
                        productId = candidate.productId,
                        purchaseToken = candidate.purchaseToken,
                    )
            ) {
                is PlayStorePurchaseVerificationResult.Invalid -> {
                    invalid++
                    revoked++
                    playStorePurchaseApplier.revokeStoredPurchase(
                        stored = candidate,
                        externalStatus = verification.code,
                        verifiedAt = now,
                    )
                }
                PlayStorePurchaseVerificationResult.VerificationUnavailable -> unavailable++
                is PlayStorePurchaseVerificationResult.Verified -> {
                    verified++
                    val subscription =
                        playStorePurchaseApplier.applyVerifiedPurchase(
                            userId = candidate.userId,
                            tier = candidate.tier,
                            purchase = verification.purchase,
                            verifiedAt = now,
                        )
                    when (subscription.status) {
                        "active" -> active++
                        "grace" -> grace++
                        "expired" -> expired++
                        "revoked" -> revoked++
                        else -> pending++
                    }
                }
            }
        }

        return PlayStoreSubscriptionSyncSummary(
            scanned = candidates.size,
            verified = verified,
            active = active,
            grace = grace,
            pending = pending,
            expired = expired,
            revoked = revoked,
            invalid = invalid,
            unavailable = unavailable,
        )
    }

    suspend fun handlePlayStoreRealTimeDeveloperNotification(
        envelope: PlayStoreRtdnEnvelope,
    ): PlayStoreRtdnProcessingResult = playStoreRtdnProcessor.handle(envelope)

    suspend fun getPolicy(userId: UUID): EntitlementPolicy = getEntitlement(userId).toPolicy()

    suspend fun consumeIntroNoteInventory(userId: UUID): Boolean = repository.consumeIntroNoteInventory(userId, clock.instant())

    suspend fun consumePineappleLikeInventory(userId: UUID): Boolean = repository.consumePineappleLikeInventory(userId, clock.instant())

    /**
     * Variant of `consumePineappleLikeInventory` that returns the consumed
     * `premium_inventory.id`, so the caller (Swipe action recorder) can persist it
     * on the action row and the rewind path can refund the exact row via
     * `refundPineappleLikeInventory`.
     */
    suspend fun consumePineappleLikeInventoryReturningId(userId: UUID): UUID? = repository.consumePineappleLikeInventoryReturningId(userId, clock.instant())

    suspend fun refundPineappleLikeInventory(inventoryId: UUID): Boolean = repository.refundInventoryById(inventoryId, clock.instant())

    suspend fun activateBoost(userId: UUID): BoostActivationDto {
        val result =
            repository.activateBoost(
                userId = userId,
                now = clock.instant(),
                duration = BOOST_DURATION,
            ) ?: throw SubscriptionException("BOOST_INVENTORY_REQUIRED")
        val activation = result.activation
        return BoostActivationDto(
            activationId = activation.activationId.toString(),
            boostType = activation.boostType,
            startsAt = activation.startsAt.toString(),
            endsAt = activation.endsAt.toString(),
            alreadyActive = !result.consumedInventory,
        )
    }

    private suspend fun grantDevPremiumProduct(
        userId: UUID,
        inventoryType: String,
        quantity: Int,
        now: Instant,
    ) {
        repository.grantPremiumInventory(
            userId = userId,
            inventoryType = inventoryType,
            quantity = quantity,
            source = "dev",
            expiresAt = now.plus(DEV_INVENTORY_PERIOD),
            now = now,
        ) ?: throw SubscriptionException("INVALID_STORE_PRODUCT")
    }

    private fun PremiumInventoryBalanceRecord.toDto(): PremiumInventoryBalanceDto =
        PremiumInventoryBalanceDto(
            inventoryType = inventoryType,
            quantity = quantity,
            nextExpiresAt = nextExpiresAt?.toString(),
        )

    private suspend fun pendingVerificationResponse(userId: UUID): SubscriptionPurchaseSyncResponseDto =
        SubscriptionPurchaseSyncResponseDto(
            status = SubscriptionPurchaseSyncStatus.PENDING_VERIFICATION,
            entitlement = getEntitlement(userId),
        )

    private fun invalidateEntitlement(userId: UUID) {
        entitlementCache.invalidate(userId)
    }

    companion object {
        private val ADMIN_GRANTABLE_TIERS = setOf(SubscriptionTier.SOCIAL, SubscriptionTier.SELECT)
        private val ADMIN_GRANTABLE_SOURCES = setOf(EntitlementSource.ADMIN, EntitlementSource.PROMO)
        private val BOOST_DURATION: Duration = Duration.ofMinutes(30)
        private val DEV_SUBSCRIPTION_PERIOD: Duration = Duration.ofDays(30)
        private val DEV_INVENTORY_PERIOD: Duration = Duration.ofDays(90)
        private const val DEV_INVENTORY_GRANTED = "inventory_granted"
        private const val PLAY_STORE_SYNC_DEFAULT_LIMIT = 100
        private const val PLAY_STORE_SYNC_MAX_LIMIT = 500
    }
}

class SubscriptionException(
    val code: String,
) : RuntimeException(code)

data class PlayStoreSubscriptionSyncSummary(
    val scanned: Int,
    val verified: Int,
    val active: Int,
    val grace: Int,
    val pending: Int,
    val expired: Int,
    val revoked: Int,
    val invalid: Int,
    val unavailable: Int,
)

private fun String.toUuidOrThrow(code: String): UUID =
    runCatching { UUID.fromString(trim()) }
        .getOrElse { throw SubscriptionException(code) }

private fun String.toInstantOrThrow(code: String): Instant =
    runCatching { Instant.parse(trim()) }
        .getOrElse { throw SubscriptionException(code) }

private fun String.validatedAdminActor(): String =
    trim().takeIf { it.length in 2..120 }
        ?: throw SubscriptionException("INVALID_ADMIN_ACTOR")

private fun String.validatedAdminReason(): String =
    trim().takeIf { it.length in 2..500 }
        ?: throw SubscriptionException("ADMIN_REASON_REQUIRED")
