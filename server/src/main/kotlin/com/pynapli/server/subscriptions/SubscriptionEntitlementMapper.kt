package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import java.time.Instant
import java.util.UUID

internal object SubscriptionEntitlementMapper {
    fun activeToDto(record: ActiveEntitlementRecord): SubscriptionEntitlementDto =
        paidEntitlement(
            tier = record.tier,
            source = record.source,
            sourceSubscriptionId = record.sourceSubscriptionId,
            sourceUserId = record.sourceUserId,
            linkedPartnerUserId = record.linkedPartnerUserId,
            startsAt = record.startsAt,
            endsAt = record.endsAt,
        )

    fun free(): SubscriptionEntitlementDto =
        SubscriptionEntitlementDto(
            tier = SubscriptionTier.FREE,
            source = EntitlementSource.FREE,
            displayName = displayNameFor(SubscriptionTier.FREE),
            linkedPartnerIncluded = false,
        )

    private fun paidEntitlement(
        tier: String,
        source: String,
        sourceSubscriptionId: UUID?,
        sourceUserId: UUID,
        linkedPartnerUserId: UUID?,
        startsAt: Instant,
        endsAt: Instant?,
    ): SubscriptionEntitlementDto =
        SubscriptionEntitlementDto(
            tier = tier,
            source = source,
            displayName = displayNameFor(tier),
            linkedPartnerIncluded = true,
            sourceSubscriptionId = sourceSubscriptionId?.toString(),
            sourceUserId = sourceUserId.toString(),
            linkedPartnerUserId = linkedPartnerUserId?.toString(),
            startsAt = startsAt.toString(),
            endsAt = endsAt?.toString(),
        )

    private fun displayNameFor(tier: String): String =
        when (tier) {
            SubscriptionTier.SELECT -> "Pynapli Select"
            SubscriptionTier.SOCIAL -> "Pynapli Social"
            else -> "Pynapli Free"
        }
}
