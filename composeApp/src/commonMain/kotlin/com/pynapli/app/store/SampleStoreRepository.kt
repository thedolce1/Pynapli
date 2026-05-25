package com.pynapli.app.store

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumInventoryBalanceDto
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto

class SampleStoreRepository : StoreRepository {
    override suspend fun loadStore(): AppResult<StoreUi> =
        AppResult.Success(
            StoreUi(
                tiers = listOf(
                    SubscriptionTierDto(
                        tier = SubscriptionTier.FREE,
                        displayName = "Free",
                        monthlyPriceUsdCents = 0,
                        adSupported = true,
                        linkedPartnerIncluded = false,
                        features = listOf("Grid browsing", "Swipe matching", "Messages after match")
                    ),
                    SubscriptionTierDto(
                        tier = SubscriptionTier.SOCIAL,
                        displayName = "Social",
                        monthlyPriceUsdCents = 499,
                        adSupported = false,
                        linkedPartnerIncluded = true,
                        playStoreProductId = SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
                        features = listOf("No ads", "Advanced filters", "More Activity reveals")
                    ),
                    SubscriptionTierDto(
                        tier = SubscriptionTier.SELECT,
                        displayName = "Select",
                        monthlyPriceUsdCents = 999,
                        adSupported = false,
                        linkedPartnerIncluded = true,
                        playStoreProductId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                        features = listOf("Intro Notes", "Deep Activity reveals", "Priority discovery")
                    )
                ),
                premiumProducts = listOf(
                    PremiumProductDto(
                        productId = SubscriptionProductId.BOOST_SINGLE,
                        inventoryType = PremiumInventoryType.BOOST,
                        displayName = "Boost",
                        description = "Increase visibility for 30 minutes.",
                        priceUsdCents = 299,
                        quantity = 1,
                        features = listOf("Visibility lift", "No guaranteed matches")
                    ),
                    PremiumProductDto(
                        productId = SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
                        inventoryType = PremiumInventoryType.PINEAPPLE_LIKE,
                        displayName = "Pineapple Likes",
                        description = "Send a stronger swipe signal.",
                        priceUsdCents = 399,
                        quantity = 5,
                        features = listOf("High-intent signal", "Prioritized in Activity")
                    ),
                    PremiumProductDto(
                        productId = SubscriptionProductId.INTRO_NOTES_THREE,
                        inventoryType = PremiumInventoryType.INTRO_NOTE,
                        displayName = "Intro Notes",
                        description = "Send a short note before matching.",
                        priceUsdCents = 299,
                        quantity = 3,
                        features = listOf("140 characters max", "Recipient controls still apply")
                    )
                ),
                balances = listOf(
                    PremiumInventoryBalanceDto(inventoryType = PremiumInventoryType.BOOST, quantity = 0),
                    PremiumInventoryBalanceDto(inventoryType = PremiumInventoryType.PINEAPPLE_LIKE, quantity = 1),
                    PremiumInventoryBalanceDto(inventoryType = PremiumInventoryType.INTRO_NOTE, quantity = 0)
                ),
                entitlement = SubscriptionEntitlementDto(
                    tier = SubscriptionTier.FREE,
                    source = EntitlementSource.FREE,
                    displayName = "Free",
                    adSupported = true,
                    linkedPartnerIncluded = false
                )
            )
        )
}
