package com.pynapli.server.subscriptions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionCatalogTest {
    // Clients render these from the tier's adSupported / linkedPartnerIncluded flags, so a feature
    // line with the same words shows twice on the Store's plan card.
    private val flagRows = setOf("No ads", "Ad-supported", "Linked partner included", "Single account entitlement")

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
}
