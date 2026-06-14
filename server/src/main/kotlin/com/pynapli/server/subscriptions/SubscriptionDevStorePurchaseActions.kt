package com.pynapli.server.subscriptions

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import com.pynapli.shared.network.dto.SubscriptionTier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class SubscriptionDevStorePurchaseActions(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
    private val rateLimitService: RateLimitService?,
    private val invalidateEntitlement: (UUID) -> Unit,
    private val getEntitlement: suspend (UUID) -> SubscriptionEntitlementDto,
    private val getInventory: suspend (UUID) -> SubscriptionInventoryResponseDto,
) {
    suspend fun recordDevStorePurchase(
        userId: UUID,
        request: SubmitDevStorePurchaseRequestDto,
    ): StorePurchaseResponseDto {
        rateLimitService.enforceSubscriptionRateLimit(userId, RateLimitedAction.STORE_PURCHASE)
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

    private companion object {
        private val DEV_SUBSCRIPTION_PERIOD: Duration = Duration.ofDays(30)
        private val DEV_INVENTORY_PERIOD: Duration = Duration.ofDays(90)
        private const val DEV_INVENTORY_GRANTED = "inventory_granted"
    }
}
