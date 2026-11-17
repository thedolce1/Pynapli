package com.pynapli.server.testsupport

import com.pynapli.server.safety.RateLimitCounter
import com.pynapli.server.safety.RateLimitRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.subscriptions.LinkedPartnerSubscriptionRecord
import com.pynapli.server.subscriptions.StoredEntitlementRecord
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionRepository
import java.time.Instant
import java.util.UUID

/** Rate limiter whose every counter is already past any limit, so each checked action is denied. */
internal fun denyingRateLimitService(): RateLimitService =
    RateLimitService(
        object : RateLimitRepository {
            override suspend fun increment(
                key: String,
                windowSeconds: Long,
            ): RateLimitCounter = RateLimitCounter(count = Long.MAX_VALUE, ttlSeconds = windowSeconds)

            override suspend fun current(key: String): RateLimitCounter = RateLimitCounter(count = Long.MAX_VALUE, ttlSeconds = 60)
        },
    )

/** Subscription fake: [own] maps users to their active subscription; no partner or stored entitlements. */
internal class StaticSubscriptionRepository(
    private val own: Map<UUID, SubscriptionRecord> = emptyMap(),
) : SubscriptionRepository {
    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? = own[userId]

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
