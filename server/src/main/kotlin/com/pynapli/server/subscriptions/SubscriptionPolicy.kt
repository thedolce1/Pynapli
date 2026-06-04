package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier

data class EntitlementPolicy(
    val tier: String,
    val adSupported: Boolean,
    val activityRevealLimit: Int,
    val advancedFiltersAllowed: Boolean,
    val introNoteInventoryRequired: Boolean,
    val gridPageLimit: Int,
    val gridResultDepthLimit: Int,
    // PSYNC_SPEC Monetization: Free has a limited deck size; Social/Select get larger decks.
    val psyncMaxDeckSize: Int,
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
                introNoteInventoryRequired = true,
                gridPageLimit = 120,
                gridResultDepthLimit = Int.MAX_VALUE,
                psyncMaxDeckSize = PSYNC_MAX_DECK_SIZE,
            )

        // PSYNC_SPEC deck-size caps per tier (overall hard cap is 50; see psync_sessions CHECK).
        const val PSYNC_MAX_DECK_SIZE = 50
        const val PSYNC_SOCIAL_DECK_SIZE = 25
        const val PSYNC_FREE_DECK_SIZE = 10
    }
}

fun SubscriptionEntitlementDto.toPolicy(): EntitlementPolicy =
    when (tier) {
        SubscriptionTier.SELECT ->
            EntitlementPolicy(
                tier = tier,
                adSupported = adSupported,
                activityRevealLimit = Int.MAX_VALUE,
                advancedFiltersAllowed = true,
                introNoteInventoryRequired = true,
                gridPageLimit = 120,
                gridResultDepthLimit = Int.MAX_VALUE,
                psyncMaxDeckSize = EntitlementPolicy.PSYNC_MAX_DECK_SIZE,
            )
        SubscriptionTier.SOCIAL ->
            EntitlementPolicy(
                tier = tier,
                adSupported = adSupported,
                activityRevealLimit = 10,
                advancedFiltersAllowed = true,
                introNoteInventoryRequired = true,
                gridPageLimit = 90,
                gridResultDepthLimit = 600,
                psyncMaxDeckSize = EntitlementPolicy.PSYNC_SOCIAL_DECK_SIZE,
            )
        else ->
            EntitlementPolicy(
                tier = SubscriptionTier.FREE,
                adSupported = true,
                activityRevealLimit = 1,
                advancedFiltersAllowed = false,
                introNoteInventoryRequired = true,
                gridPageLimit = 60,
                gridResultDepthLimit = 60,
                psyncMaxDeckSize = EntitlementPolicy.PSYNC_FREE_DECK_SIZE,
            )
    }
