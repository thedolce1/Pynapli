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
            )
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
            )
    }
