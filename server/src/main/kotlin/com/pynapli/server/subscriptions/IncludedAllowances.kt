package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import java.time.Duration
import java.time.Instant

/**
 * What a member's plan includes of one inventory type right now (MONETIZATION_SPEC "Included
 * allowances"). [quantity] is what the current tier is entitled to per period: 0 means "not entitled
 * now" (Free, lapsed, unlinked from a paying partner), which withholds any unused included units of
 * the current period instead of granting.
 *
 * [entitlementStartedAt] only matters the first time a person is ever granted anything: it becomes
 * their allowance anchor (see V116), clamped to now. After that the anchor never moves.
 */
data class IncludedAllowance(
    val inventoryType: String,
    val tier: String,
    val quantity: Int,
    val period: Duration,
    val entitlementStartedAt: Instant?,
)

/** One period of an allowance cycle: [start] inclusive, [end] exclusive. */
data class IncludedAllowancePeriod(
    val start: Instant,
    val end: Instant,
)

internal object IncludedAllowancePlan {
    val WEEK: Duration = Duration.ofDays(7)

    // "Monthly" is a 30-day window: simple, the same length for everyone, and never shorter than a
    // calendar month by more than a day.
    val MONTH: Duration = Duration.ofDays(30)

    const val SOCIAL_WEEKLY_PINEAPPLE_LIKES = 1
    const val SELECT_WEEKLY_PINEAPPLE_LIKES = 5
    const val SELECT_MONTHLY_BOOSTS = 1

    /** The inventory types a plan can include, in the order they are reconciled. */
    val INCLUDED_TYPES: List<String> = listOf(PremiumInventoryType.PINEAPPLE_LIKE, PremiumInventoryType.BOOST)

    fun periodFor(inventoryType: String): Duration =
        when (inventoryType) {
            PremiumInventoryType.BOOST -> MONTH
            else -> WEEK
        }

    /** Units of [inventoryType] [tier] includes per period. Social's Boosts are a discount, not a grant. */
    fun quantityFor(
        tier: String,
        inventoryType: String,
    ): Int =
        when (inventoryType) {
            PremiumInventoryType.PINEAPPLE_LIKE ->
                when (tier) {
                    SubscriptionTier.SELECT -> SELECT_WEEKLY_PINEAPPLE_LIKES
                    SubscriptionTier.SOCIAL -> SOCIAL_WEEKLY_PINEAPPLE_LIKES
                    else -> 0
                }
            PremiumInventoryType.BOOST -> if (tier == SubscriptionTier.SELECT) SELECT_MONTHLY_BOOSTS else 0
            else -> 0
        }

    /**
     * The allowance for whoever holds [entitlement] (own subscription, linked-partner benefit, promo
     * or admin grant alike: every paid source gets its tier's allowance, each person their own).
     */
    fun forEntitlement(
        entitlement: SubscriptionEntitlementDto,
        inventoryType: String,
    ): IncludedAllowance =
        IncludedAllowance(
            inventoryType = inventoryType,
            tier = entitlement.tier,
            quantity = quantityFor(entitlement.tier, inventoryType),
            period = periodFor(inventoryType),
            entitlementStartedAt = entitlement.startsAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )

    /**
     * The period of a cycle anchored at [anchor] that contains [now]: windows of [length] laid end to
     * end from the anchor. Deterministic for a given anchor, so every request in the same period
     * computes the same key.
     */
    fun periodContaining(
        anchor: Instant,
        length: Duration,
        now: Instant,
    ): IncludedAllowancePeriod {
        val index = Math.floorDiv(Duration.between(anchor, now).toMillis(), length.toMillis())
        val start = anchor.plus(length.multipliedBy(index))
        return IncludedAllowancePeriod(start = start, end = start.plus(length))
    }
}
