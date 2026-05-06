package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SubscriptionService(
    private val repository: SubscriptionRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun getEntitlement(userId: UUID): SubscriptionEntitlementDto {
        val now = clock.instant()
        val own = repository.findBestActiveOwnSubscription(userId, now)?.toEntitlement()
        val linked = repository.findBestActiveLinkedPartnerSubscription(userId, now)?.toEntitlement()
        val stored = repository.findBestActiveStoredEntitlement(userId, now)?.toEntitlement()

        return listOfNotNull(own, stored, linked).maxWithOrNull(entitlementComparator()) ?: freeEntitlement()
    }

    fun getInventory(): SubscriptionInventoryResponseDto =
        SubscriptionInventoryResponseDto(
            tiers = listOf(
                SubscriptionTierDto(
                    tier = SubscriptionTier.FREE,
                    displayName = "Pynapli Free",
                    monthlyPriceUsdCents = 0,
                    adSupported = true,
                    linkedPartnerIncluded = false,
                    features = listOf(
                        "Basic grid",
                        "Basic swipe",
                        "Grid messaging",
                        "Partner linking",
                        "Private album basics",
                        "Block and report"
                    )
                ),
                SubscriptionTierDto(
                    tier = SubscriptionTier.SOCIAL,
                    displayName = "Pynapli Social",
                    monthlyPriceUsdCents = 499,
                    adSupported = false,
                    linkedPartnerIncluded = true,
                    features = listOf(
                        "No ads",
                        "Expanded grid",
                        "More swipe likes",
                        "Rewind",
                        "More activity reveal",
                        "Linked partner included"
                    )
                ),
                SubscriptionTierDto(
                    tier = SubscriptionTier.SELECT,
                    displayName = "Pynapli Select",
                    monthlyPriceUsdCents = 999,
                    adSupported = false,
                    linkedPartnerIncluded = true,
                    features = listOf(
                        "Full activity reveal",
                        "Advanced filters",
                        "Incognito",
                        "Travel mode",
                        "Intro Notes",
                        "Monthly Boost",
                        "Linked partner included"
                    )
                )
            )
        )

    suspend fun getPolicy(userId: UUID): EntitlementPolicy =
        getEntitlement(userId).toPolicy()

    suspend fun consumeIntroNoteInventory(userId: UUID): Boolean =
        repository.consumeIntroNoteInventory(userId, clock.instant())

    private fun SubscriptionRecord.toEntitlement(): SubscriptionEntitlementDto =
        paidEntitlement(
            tier = tier,
            source = EntitlementSource.OWN_SUBSCRIPTION,
            sourceSubscriptionId = subscriptionId,
            sourceUserId = userId,
            linkedPartnerUserId = null,
            startsAt = currentPeriodStart,
            endsAt = currentPeriodEnd
        )

    private fun LinkedPartnerSubscriptionRecord.toEntitlement(): SubscriptionEntitlementDto =
        paidEntitlement(
            tier = subscription.tier,
            source = EntitlementSource.LINKED_PARTNER_BENEFIT,
            sourceSubscriptionId = subscription.subscriptionId,
            sourceUserId = linkedPartnerUserId,
            linkedPartnerUserId = linkedPartnerUserId,
            startsAt = subscription.currentPeriodStart,
            endsAt = subscription.currentPeriodEnd
        )

    private fun StoredEntitlementRecord.toEntitlement(): SubscriptionEntitlementDto =
        paidEntitlement(
            tier = tier,
            source = source,
            sourceSubscriptionId = sourceId,
            sourceUserId = userId,
            linkedPartnerUserId = null,
            startsAt = startsAt,
            endsAt = endsAt
        )

    private fun paidEntitlement(
        tier: String,
        source: String,
        sourceSubscriptionId: UUID?,
        sourceUserId: UUID,
        linkedPartnerUserId: UUID?,
        startsAt: Instant,
        endsAt: Instant?
    ): SubscriptionEntitlementDto =
        SubscriptionEntitlementDto(
            tier = tier,
            source = source,
            displayName = displayNameFor(tier),
            adSupported = false,
            linkedPartnerIncluded = true,
            sourceSubscriptionId = sourceSubscriptionId?.toString(),
            sourceUserId = sourceUserId.toString(),
            linkedPartnerUserId = linkedPartnerUserId?.toString(),
            startsAt = startsAt.toString(),
            endsAt = endsAt?.toString()
        )

    private fun freeEntitlement(): SubscriptionEntitlementDto =
        SubscriptionEntitlementDto(
            tier = SubscriptionTier.FREE,
            source = EntitlementSource.FREE,
            displayName = "Pynapli Free",
            adSupported = true,
            linkedPartnerIncluded = false
        )

    private fun entitlementComparator(): Comparator<SubscriptionEntitlementDto> =
        compareBy<SubscriptionEntitlementDto> { tierRank(it.tier) }
            .thenBy { sourcePriority(it.source) }
            .thenBy { it.endsAt ?: "" }

    private fun sourcePriority(source: String): Int =
        when (source) {
            EntitlementSource.OWN_SUBSCRIPTION -> 3
            EntitlementSource.ADMIN -> 2
            EntitlementSource.PROMO -> 1
            EntitlementSource.LINKED_PARTNER_BENEFIT -> 0
            else -> -1
        }

    private fun tierRank(tier: String): Int =
        when (tier) {
            SubscriptionTier.SELECT -> 2
            SubscriptionTier.SOCIAL -> 1
            else -> 0
        }

    private fun displayNameFor(tier: String): String =
        when (tier) {
            SubscriptionTier.SELECT -> "Pynapli Select"
            SubscriptionTier.SOCIAL -> "Pynapli Social"
            else -> "Pynapli Free"
        }
}

data class EntitlementPolicy(
    val tier: String,
    val adSupported: Boolean,
    val activityRevealLimit: Int,
    val advancedFiltersAllowed: Boolean,
    val introNoteInventoryRequired: Boolean
) {
    val fullActivityReveal: Boolean
        get() = activityRevealLimit == Int.MAX_VALUE

    companion object {
        fun unrestricted(): EntitlementPolicy =
            EntitlementPolicy(
                tier = SubscriptionTier.SELECT,
                adSupported = false,
                activityRevealLimit = Int.MAX_VALUE,
                advancedFiltersAllowed = true,
                introNoteInventoryRequired = true
            )
    }
}

fun SubscriptionEntitlementDto.toPolicy(): EntitlementPolicy =
    when (tier) {
        SubscriptionTier.SELECT -> EntitlementPolicy(
            tier = tier,
            adSupported = adSupported,
            activityRevealLimit = Int.MAX_VALUE,
            advancedFiltersAllowed = true,
            introNoteInventoryRequired = true
        )
        SubscriptionTier.SOCIAL -> EntitlementPolicy(
            tier = tier,
            adSupported = adSupported,
            activityRevealLimit = 10,
            advancedFiltersAllowed = true,
            introNoteInventoryRequired = true
        )
        else -> EntitlementPolicy(
            tier = SubscriptionTier.FREE,
            adSupported = true,
            activityRevealLimit = 1,
            advancedFiltersAllowed = false,
            introNoteInventoryRequired = true
        )
    }
