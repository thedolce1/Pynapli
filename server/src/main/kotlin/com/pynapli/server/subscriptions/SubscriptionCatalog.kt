package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto

internal object SubscriptionCatalog {
    fun inventory(): SubscriptionInventoryResponseDto =
        SubscriptionInventoryResponseDto(
            tiers = tiers(),
            premiumProducts = premiumProducts()
        )

    private fun tiers(): List<SubscriptionTierDto> =
        listOf(
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
                playStoreProductId = SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
                features = listOf(
                    "No ads",
                    "Expanded grid",
                    "More swipe likes",
                    "Rewind",
                    "More Activity reveal",
                    "Saved filters",
                    "Travel preview",
                    "Linked partner included"
                )
            ),
            SubscriptionTierDto(
                tier = SubscriptionTier.SELECT,
                displayName = "Pynapli Select",
                monthlyPriceUsdCents = 999,
                adSupported = false,
                linkedPartnerIncluded = true,
                playStoreProductId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                features = listOf(
                    "Full activity reveal",
                    "Advanced filters",
                    "Incognito",
                    "Travel mode",
                    "Intro Notes",
                    "Weekly Pineapple Likes",
                    "Monthly Boost",
                    "Daily curated Picks",
                    "Linked partner included"
                )
            )
        )

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
                features = listOf(
                    "30-minute visibility lift",
                    "Respects blocks, privacy, and filters",
                    "No guaranteed matches"
                )
            ),
            PremiumProductDto(
                productId = SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
                inventoryType = PremiumInventoryType.PINEAPPLE_LIKE,
                displayName = "Pineapple Likes",
                description = "Send a stronger swipe signal.",
                priceUsdCents = 399,
                quantity = 5,
                playStoreProductId = SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
                features = listOf(
                    "High-intent swipe signal",
                    "Prioritized in Activity",
                    "Dismissible by recipient"
                )
            ),
            PremiumProductDto(
                productId = SubscriptionProductId.INTRO_NOTES_THREE,
                inventoryType = PremiumInventoryType.INTRO_NOTE,
                displayName = "Intro Notes",
                description = "Send a short note before matching.",
                priceUsdCents = 299,
                quantity = 3,
                playStoreProductId = SubscriptionProductId.INTRO_NOTES_THREE,
                features = listOf(
                    "140 characters max",
                    "Does not open chat by itself",
                    "Subject to recipient controls"
                )
            )
        )
}
