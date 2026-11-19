package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionCatalogTest {
    // Clients render these from the tier's linkedPartnerIncluded flag, so a feature line with the
    // same words shows twice on the Store's plan card.
    // "Covers your linked partner too" is the in-app wording since 2026-11-18; the older label stays
    // listed so neither can creep back in as a feature line.
    private val flagRows = setOf("Covers your linked partner too", "Linked partner included", "Single account entitlement")

    @Test
    fun featureListsNeverRepeatWhatTheFlagsSay() {
        SubscriptionCatalog.inventory().tiers.forEach { tier ->
            val repeated = tier.features.filter { it in flagRows }
            assertTrue(repeated.isEmpty(), "${tier.displayName} repeats flag rows in features: $repeated")
        }
    }

    @Test
    fun noTierListsTheSameFeatureTwice() {
        SubscriptionCatalog.inventory().tiers.forEach { tier ->
            assertEquals(tier.features.distinct(), tier.features, "${tier.displayName} lists a feature twice")
        }
    }

    // The plan cards state the numbers the server grants (MONETIZATION_SPEC "Included allowances"),
    // not a vague "Weekly Pineapple Likes" / "Monthly Boost".
    @Test
    fun planCardsStateTheIncludedAllowancesTheServerGrants() {
        val tiers = SubscriptionCatalog.inventory().tiers.associateBy { it.tier }
        val social = tiers.getValue(SubscriptionTier.SOCIAL).features
        val select = tiers.getValue(SubscriptionTier.SELECT).features

        assertTrue("1 Pineapple Like every week" in social, "Social card: $social")
        assertTrue("5 Pineapple Likes every week" in select, "Select card: $select")
        assertTrue("1 Boost every month" in select, "Select card: $select")
        assertEquals(1, IncludedAllowancePlan.quantityFor(SubscriptionTier.SOCIAL, PremiumInventoryType.PINEAPPLE_LIKE))
        assertEquals(5, IncludedAllowancePlan.quantityFor(SubscriptionTier.SELECT, PremiumInventoryType.PINEAPPLE_LIKE))
        assertEquals(1, IncludedAllowancePlan.quantityFor(SubscriptionTier.SELECT, PremiumInventoryType.BOOST))
        assertEquals(0, IncludedAllowancePlan.quantityFor(SubscriptionTier.SOCIAL, PremiumInventoryType.BOOST))
        assertEquals(0, IncludedAllowancePlan.quantityFor(SubscriptionTier.FREE, PremiumInventoryType.PINEAPPLE_LIKE))
        SubscriptionCatalog.inventory().tiers.forEach { tier ->
            val vague = tier.features.filter { it == "Weekly Pineapple Likes" || it == "Monthly Boost" }
            assertTrue(vague.isEmpty(), "${tier.displayName}: $vague")
        }
    }
}
