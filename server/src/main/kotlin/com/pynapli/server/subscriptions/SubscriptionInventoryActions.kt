package com.pynapli.server.subscriptions

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.PremiumInventoryBalanceDto
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

internal class SubscriptionInventoryActions(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
    private val rateLimitService: RateLimitService?,
    // The cached entitlement (SubscriptionEntitlementCache, 5 s): decides what the plan includes.
    private val getEntitlement: suspend (UUID) -> SubscriptionEntitlementDto,
) {
    /** What [userId]'s current plan includes of [inventoryType] (0 on Free). */
    suspend fun includedAllowance(
        userId: UUID,
        inventoryType: String,
    ): IncludedAllowance = IncludedAllowancePlan.forEntitlement(getEntitlement(userId), inventoryType)

    suspend fun getInventory(userId: UUID): SubscriptionInventoryResponseDto {
        val entitlement = getEntitlement(userId)
        val now = clock.instant()
        // Balances include the plan's included units: grant this period's (once) before reading.
        repository.reconcileIncludedAllowances(
            userId = userId,
            allowances =
                IncludedAllowancePlan.INCLUDED_TYPES.map { type ->
                    IncludedAllowancePlan.forEntitlement(entitlement, type)
                },
            now = now,
        )
        return SubscriptionCatalog.inventory().copy(
            balances = repository.listPremiumInventoryBalances(userId, now).map { it.toDto() },
            activeBoostEndsAt = repository.findActiveBoost(userId, now)?.endsAt?.toString(),
        )
    }

    suspend fun consumePineappleLikeInventory(userId: UUID): Boolean = consumePineappleLikeInventoryReturningId(userId) != null

    // Psync spends here: included Pineapple Likes first, as in the Swipe deck.
    suspend fun consumePineappleLikeInventoryReturningId(userId: UUID): UUID? {
        val allowance = includedAllowance(userId, PremiumInventoryType.PINEAPPLE_LIKE)
        return repository.consumePineappleLikeInventoryReturningId(userId, clock.instant(), allowance)
    }

    suspend fun refundPineappleLikeInventory(inventoryId: UUID): Boolean = repository.refundInventoryById(inventoryId, clock.instant())

    suspend fun activateBoost(userId: UUID): BoostActivationDto {
        rateLimitService.enforceSubscriptionRateLimit(userId, RateLimitedAction.BOOST_ACTIVATE)
        val allowance = includedAllowance(userId, PremiumInventoryType.BOOST)
        val result =
            repository.activateBoost(
                userId = userId,
                now = clock.instant(),
                duration = BOOST_DURATION,
                includedAllowance = allowance,
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

    private fun PremiumInventoryBalanceRecord.toDto(): PremiumInventoryBalanceDto =
        PremiumInventoryBalanceDto(
            inventoryType = inventoryType,
            quantity = quantity,
            nextExpiresAt = nextExpiresAt?.toString(),
        )

    private companion object {
        private val BOOST_DURATION: Duration = Duration.ofMinutes(30)
    }
}
