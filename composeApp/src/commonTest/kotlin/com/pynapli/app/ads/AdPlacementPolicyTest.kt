package com.pynapli.app.ads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdPlacementPolicyTest {
    @Test
    fun freeTierCanShowAdsOnlyOnApprovedDiscoverySurfaces() {
        val allowed = setOf(
            AdPlacement.GridBetweenPages,
            AdPlacement.SwipeBetweenBatches,
            AdPlacement.StoreTab,
            AdPlacement.NonSensitiveEmptyState
        )

        AdPlacement.entries.forEach { placement ->
            val canShow = AdPlacementPolicy.canShowAds(adSupported = true, placement = placement)

            if (placement in allowed) {
                assertTrue(canShow, "$placement should allow free-tier ads.")
            } else {
                assertFalse(canShow, "$placement should not allow ads.")
            }
        }
    }

    @Test
    fun paidTierNeverShowsAdsEvenOnApprovedSurfaces() {
        AdPlacement.entries.forEach { placement ->
            assertFalse(
                AdPlacementPolicy.canShowAds(adSupported = false, placement = placement),
                "$placement should not show ads when entitlement removes ads."
            )
        }
    }

    @Test
    fun sensitiveSurfacesNeverShowAds() {
        val sensitivePlacements = listOf(
            AdPlacement.ActivityFeed,
            AdPlacement.ProfileDetail,
            AdPlacement.ChatThread,
            AdPlacement.PrivateAlbumViewer,
            AdPlacement.PartnerInvite,
            AdPlacement.ReportFlow,
            AdPlacement.BlockFlow,
            AdPlacement.SafetyFlow,
            AdPlacement.VerificationFlow
        )

        sensitivePlacements.forEach { placement ->
            assertFalse(AdPlacementPolicy.canShowAds(adSupported = true, placement = placement))
        }
    }
}
