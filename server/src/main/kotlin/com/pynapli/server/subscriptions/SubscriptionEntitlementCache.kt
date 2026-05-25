package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class SubscriptionEntitlementCache(
    private val ttl: Duration,
    private val maxSize: Int
) {
    private val entries = ConcurrentHashMap<UUID, CachedEntitlement>()

    fun get(userId: UUID, now: Instant): SubscriptionEntitlementDto? {
        val cached = entries[userId] ?: return null
        if (cached.expiresAt > now) return cached.entitlement
        entries.remove(userId, cached)
        return null
    }

    fun put(userId: UUID, entitlement: SubscriptionEntitlementDto, now: Instant) {
        if (ttl <= Duration.ZERO || maxSize <= 0) return
        trim(now)
        entries[userId] = CachedEntitlement(
            entitlement = entitlement,
            expiresAt = now.plus(ttl)
        )
    }

    fun invalidate(userId: UUID) {
        entries.remove(userId)
    }

    private fun trim(now: Instant) {
        if (entries.size < maxSize) return
        entries.entries.removeIf { it.value.expiresAt <= now }
        if (entries.size < maxSize) return
        entries.keys.firstOrNull()?.let { entries.remove(it) }
    }

    private data class CachedEntitlement(
        val entitlement: SubscriptionEntitlementDto,
        val expiresAt: Instant
    )
}
