package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import java.time.Clock
import java.util.UUID

internal class SubscriptionEntitlementActions(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
    private val entitlementCache: SubscriptionEntitlementCache,
) {
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

    suspend fun getPolicy(userId: UUID): EntitlementPolicy = getEntitlement(userId).toPolicy()

    fun invalidateEntitlement(userId: UUID) {
        entitlementCache.invalidate(userId)
    }

    private companion object {
        private val ADMIN_GRANTABLE_TIERS = setOf(SubscriptionTier.SOCIAL, SubscriptionTier.SELECT)
        private val ADMIN_GRANTABLE_SOURCES = setOf(EntitlementSource.ADMIN, EntitlementSource.PROMO)
    }
}
