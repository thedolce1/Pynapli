package com.pynapli.server.messaging

import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SubscriptionTier
import java.util.UUID

internal class MessagingFeatureAccess(
    private val subscriptionService: SubscriptionService?
) {
    suspend fun readReceiptsAllowed(
        userId: UUID,
        cache: MutableMap<UUID, Boolean> = mutableMapOf()
    ): Boolean =
        cache.getOrPut(userId) {
            val tier = subscriptionService?.getPolicy(userId)?.tier ?: SubscriptionTier.SELECT
            tier == SubscriptionTier.SOCIAL || tier == SubscriptionTier.SELECT
        }

    suspend fun typingIndicatorsAllowed(userId: UUID): Boolean {
        val tier = subscriptionService?.getPolicy(userId)?.tier ?: SubscriptionTier.SELECT
        return tier == SubscriptionTier.SELECT
    }
}
