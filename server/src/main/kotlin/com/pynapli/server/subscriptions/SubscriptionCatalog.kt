package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto

/**
 * The plan catalog the Store renders. A tier's [SubscriptionTierDto.features] lists what the plan
 * adds; it must NOT repeat what the structured flags already say. Every client renders
 * `linkedPartnerIncluded` ("Linked partner included" / "Single account entitlement") as a row of
 * its own, so repeating it here showed it twice (Social listed it twice until 2026-11-10).
 * SubscriptionCatalogTest pins this.
 */
internal object SubscriptionCatalog {
    fun inventory(): SubscriptionInventoryResponseDto =
        SubscriptionInventoryResponseDto(
            tiers = tiers(),
            premiumProducts = premiumProducts(),
        )

    private fun tiers(): List<SubscriptionTierDto> =
        listOf(
            SubscriptionTierDto(
                tier = SubscriptionTier.FREE,
                displayName = "Pynapli Free",
                monthlyPriceUsdCents = 0,
                linkedPartnerIncluded = false,
                features =
                    listOf(
                        "Basic grid",
                        "Basic swipe",
                        "Grid messaging",
                        "Partner linking",
                        "Private album basics",
                        "Block and report",
                    ),
            ),
            SubscriptionTierDto(
                tier = SubscriptionTier.SOCIAL,
                displayName = "Pynapli Social",
                monthlyPriceUsdCents = 499,
                linkedPartnerIncluded = true,
                playStoreProductId = SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
                features =
                    listOf(
                        "Expanded grid",
                        "More swipe likes",
                        "Rewind",
                        "More Activity reveal",
                        "Saved filters",
                        "Travel preview",
                        includedPineappleLikesLine(IncludedAllowancePlan.SOCIAL_WEEKLY_PINEAPPLE_LIKES),
                    ),
            ),
            SubscriptionTierDto(
                tier = SubscriptionTier.SELECT,
                displayName = "Pynapli Select",
                monthlyPriceUsdCents = 999,
                linkedPartnerIncluded = true,
                playStoreProductId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                features =
                    listOf(
                        "Full activity reveal",
                        "Incognito",
                        "Travel mode",
                        includedPineappleLikesLine(IncludedAllowancePlan.SELECT_WEEKLY_PINEAPPLE_LIKES),
                        includedBoostsLine(IncludedAllowancePlan.SELECT_MONTHLY_BOOSTS),
                    ),
            ),
        )

    // The plan card states what the server grants (IncludedAllowancePlan), from the same constants.
    internal fun includedPineappleLikesLine(perWeek: Int): String = if (perWeek == 1) "1 Pineapple Like every week" else "$perWeek Pineapple Likes every week"

    internal fun includedBoostsLine(perMonth: Int): String = if (perMonth == 1) "1 Boost every month" else "$perMonth Boosts every month"

    private fun premiumProducts(): List<PremiumProductDto> =
        listOf(
            PremiumProductDto(
                productId = SubscriptionProductId.BOOST_SINGLE,
                inventoryType = PremiumInventoryType.BOOST,
                displayName = "Boost",
                description = "Increase visibility for 30 minutes.",
                priceUsdCents = 299,
                quantity = 1,
                playStoreProductId = SubscriptionProductId.BOOST_SINGLE,
                features =
                    listOf(
                        "30-minute visibility lift",
                        "Respects blocks, privacy, and filters",
                        "No guaranteed matches",
                    ),
            ),
            PremiumProductDto(
                productId = SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
                inventoryType = PremiumInventoryType.PINEAPPLE_LIKE,
                displayName = "Pineapple Likes",
                description = "Stand out, with an optional note.",
                priceUsdCents = 399,
                quantity = 5,
                playStoreProductId = SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
                features =
                    listOf(
                        "High-intent swipe signal",
                        "Add a note up to 140 characters",
                        "Prioritized in Activity",
                        "Dismissible by recipient",
                    ),
            ),
        )
}
