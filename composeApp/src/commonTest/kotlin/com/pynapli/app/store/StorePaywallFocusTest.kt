package com.pynapli.app.store

import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorePaywallFocusTest {
    private val state =
        StoreUiState(
            tiers =
                listOf(SubscriptionTier.FREE, SubscriptionTier.SOCIAL, SubscriptionTier.SELECT).map { tier ->
                    SubscriptionTierDto(tier = tier, displayName = tier, monthlyPriceUsdCents = 0, linkedPartnerIncluded = false, features = emptyList())
                },
            premiumProducts =
                listOf(PremiumInventoryType.BOOST, PremiumInventoryType.PINEAPPLE_LIKE).map { type ->
                    PremiumProductDto(
                        productId = "product-$type",
                        inventoryType = type,
                        displayName = type,
                        description = "",
                        priceUsdCents = 299,
                        quantity = 1,
                        features = emptyList(),
                    )
                },
        )

    // List order: current plan, plans title, Free, Social, Select, partner benefit, extras title,
    // Boost, Pineapple Likes. Web adds a "manage on mobile" row after the current plan.
    @Test
    fun eachPaywallLandsOnItsSectionOfTheStoreList() {
        assertEquals(1, storeListIndexFor(StorePaywallFocus.Plans, state, purchasesAvailable = true))
        assertEquals(4, storeListIndexFor(StorePaywallFocus.Select, state, purchasesAvailable = true))
        assertEquals(8, storeListIndexFor(StorePaywallFocus.PineappleLikes, state, purchasesAvailable = true))
        assertEquals(9, storeListIndexFor(StorePaywallFocus.PineappleLikes, state, purchasesAvailable = false))
    }

    @Test
    fun aFocusWithNothingToShowStaysAtTheTop() {
        assertNull(storeListIndexFor(StorePaywallFocus.PineappleLikes, state.copy(premiumProducts = emptyList()), purchasesAvailable = true))
        assertNull(storeListIndexFor(StorePaywallFocus.Plans, StoreUiState(), purchasesAvailable = true))
    }
}
