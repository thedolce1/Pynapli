package com.pynapli.server.subscriptions

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.PremiumInventoryBalanceDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

internal class SubscriptionInventoryActions(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
    private val rateLimitService: RateLimitService?,
) {
    suspend fun getInventory(userId: UUID): SubscriptionInventoryResponseDto {
        val now = clock.instant()
        return SubscriptionCatalog.inventory().copy(
            balances = repository.listPremiumInventoryBalances(userId, now).map { it.toDto() },
            activeBoostEndsAt = repository.findActiveBoost(userId, now)?.endsAt?.toString(),
        )
    }

    suspend fun consumePineappleLikeInventory(userId: UUID): Boolean = repository.consumePineappleLikeInventory(userId, clock.instant())

    suspend fun consumePineappleLikeInventoryReturningId(userId: UUID): UUID? = repository.consumePineappleLikeInventoryReturningId(userId, clock.instant())

    suspend fun refundPineappleLikeInventory(inventoryId: UUID): Boolean = repository.refundInventoryById(inventoryId, clock.instant())

    suspend fun activateBoost(userId: UUID): BoostActivationDto {
        rateLimitService.enforceSubscriptionRateLimit(userId, RateLimitedAction.BOOST_ACTIVATE)
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
