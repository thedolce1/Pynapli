package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier

data class EntitlementPolicy(
    val tier: String,
    val activityRevealLimit: Int,
    // Saved filter presets (Grid and Swipe). Since founder decision 2 (2026-11-26) no live filter is
    // paid, so this gates presets only; the name is kept for the entitlement payload.
    val advancedFiltersAllowed: Boolean,
    val gridPageLimit: Int,
    val gridResultDepthLimit: Int,
    // PSYNC_SPEC Monetization: Free has a limited deck size; Social/Select get larger decks.
    val psyncMaxDeckSize: Int,
    // PSYNC_SPEC Monetization: "Limited Psync sessions" on Free, more on Social. How many sessions
    // the user may START as initiator in a rolling 24 hours; an invite that never starts is free.
    val psyncDailySessionLimit: Int = Int.MAX_VALUE,
    // Section 3 paywall on tap: for a client that accepts locked cards, the Grid keeps paging past
    // [gridResultDepthLimit] with locked cards (no identity) up to this depth, the next tier's depth.
    // Equal to [gridResultDepthLimit] means nothing is ever locked. Never arithmetic on MAX_VALUE.
    val gridLockedPreviewDepth: Int = gridResultDepthLimit,
    // The plan that unlocks those locked cards; null when nothing is locked.
    val gridUnlockTier: String? = null,
) {
    val fullActivityReveal: Boolean
        get() = activityRevealLimit == Int.MAX_VALUE

    companion object {
        fun unrestricted(): EntitlementPolicy =
            EntitlementPolicy(
                tier = SubscriptionTier.SELECT,
                activityRevealLimit = Int.MAX_VALUE,
                advancedFiltersAllowed = true,
                gridPageLimit = 120,
                gridResultDepthLimit = Int.MAX_VALUE,
                psyncMaxDeckSize = PSYNC_MAX_DECK_SIZE,
            )

        // Grid result depth per tier (MONETIZATION_SPEC): Free 60, Social 600, Select unlimited. The
        // locked window past each depth ends at the next tier's depth (Free 61-600, Social 601-1200).
        const val GRID_FREE_DEPTH = 60
        const val GRID_SOCIAL_DEPTH = 600
        const val GRID_SOCIAL_LOCKED_PREVIEW_DEPTH = 1_200

        // PSYNC_SPEC deck-size caps per tier (overall hard cap is 50; see psync_sessions CHECK).
        const val PSYNC_MAX_DECK_SIZE = 50
        const val PSYNC_SOCIAL_DECK_SIZE = 25
        const val PSYNC_FREE_DECK_SIZE = 10

        // Started sessions per rolling 24h, per initiator. Select is unlimited.
        const val PSYNC_SOCIAL_DAILY_SESSIONS = 5
        const val PSYNC_FREE_DAILY_SESSIONS = 1
    }
}

fun SubscriptionEntitlementDto.toPolicy(): EntitlementPolicy =
    when (tier) {
        SubscriptionTier.SELECT ->
            EntitlementPolicy(
                tier = tier,
                activityRevealLimit = Int.MAX_VALUE,
                advancedFiltersAllowed = true,
                gridPageLimit = 120,
                gridResultDepthLimit = Int.MAX_VALUE,
                psyncMaxDeckSize = EntitlementPolicy.PSYNC_MAX_DECK_SIZE,
            )
        SubscriptionTier.SOCIAL ->
            EntitlementPolicy(
                tier = tier,
                activityRevealLimit = 10,
                advancedFiltersAllowed = true,
                gridPageLimit = 90,
                gridResultDepthLimit = EntitlementPolicy.GRID_SOCIAL_DEPTH,
                psyncMaxDeckSize = EntitlementPolicy.PSYNC_SOCIAL_DECK_SIZE,
                psyncDailySessionLimit = EntitlementPolicy.PSYNC_SOCIAL_DAILY_SESSIONS,
                gridLockedPreviewDepth = EntitlementPolicy.GRID_SOCIAL_LOCKED_PREVIEW_DEPTH,
                gridUnlockTier = SubscriptionTier.SELECT,
            )
        else ->
            EntitlementPolicy(
                tier = SubscriptionTier.FREE,
                activityRevealLimit = 1,
                advancedFiltersAllowed = false,
                gridPageLimit = 60,
                gridResultDepthLimit = EntitlementPolicy.GRID_FREE_DEPTH,
                psyncMaxDeckSize = EntitlementPolicy.PSYNC_FREE_DECK_SIZE,
                psyncDailySessionLimit = EntitlementPolicy.PSYNC_FREE_DAILY_SESSIONS,
                gridLockedPreviewDepth = EntitlementPolicy.GRID_SOCIAL_DEPTH,
                gridUnlockTier = SubscriptionTier.SOCIAL,
            )
    }
