package com.pynapli.shared.monetization

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureAccessPolicyTest {
    @Test
    fun safetyFeaturesNeverRequirePaidEntitlement() {
        val requiredSafetyFeatures = setOf(
            PynapliFeature.BlockUser,
            PynapliFeature.UnblockUser,
            PynapliFeature.ListBlocks,
            PynapliFeature.HideProfile,
            PynapliFeature.UnhideProfile,
            PynapliFeature.ListHiddenProfiles,
            PynapliFeature.SubmitReport,
            PynapliFeature.ListReports,
            PynapliFeature.SupportContact,
            PynapliFeature.SafetyCenter,
            PynapliFeature.AgeVerification
        )

        assertTrue(FeatureAccessPolicy.alwaysFreeSafetyFeatures.containsAll(requiredSafetyFeatures))
        requiredSafetyFeatures.forEach { feature ->
            assertFalse(
                FeatureAccessPolicy.requiresPaidEntitlement(feature),
                "$feature must remain free for every tier."
            )
            FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(feature)
        }
    }

    @Test
    fun monetizedFeaturesStayExplicitlyPaid() {
        val monetizedFeatures = setOf(
            PynapliFeature.GridAdvancedFilters,
            PynapliFeature.SwipeAdvancedFilters,
            PynapliFeature.ActivityDeepReveal,
            PynapliFeature.IntroNote,
            PynapliFeature.PineappleLike,
            PynapliFeature.Boost,
            PynapliFeature.RemoveAds
        )

        monetizedFeatures.forEach { feature ->
            assertTrue(
                FeatureAccessPolicy.requiresPaidEntitlement(feature),
                "$feature should stay behind entitlement or inventory policy."
            )
        }
    }
}
