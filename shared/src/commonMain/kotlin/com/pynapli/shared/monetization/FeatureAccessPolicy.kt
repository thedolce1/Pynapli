package com.pynapli.shared.monetization

enum class PynapliFeature(val requiresPaidEntitlement: Boolean) {
    BlockUser(false),
    UnblockUser(false),
    ListBlocks(false),
    HideProfile(false),
    UnhideProfile(false),
    ListHiddenProfiles(false),
    SubmitReport(false),
    SafetyCenter(false),
    AgeVerification(false),
    GridAdvancedFilters(true),
    SwipeAdvancedFilters(true),
    ActivityDeepReveal(true),
    IntroNote(true),
    PineappleLike(true),
    Boost(true),
    RemoveAds(true)
}

object FeatureAccessPolicy {
    val alwaysFreeSafetyFeatures: Set<PynapliFeature> = setOf(
        PynapliFeature.BlockUser,
        PynapliFeature.UnblockUser,
        PynapliFeature.ListBlocks,
        PynapliFeature.HideProfile,
        PynapliFeature.UnhideProfile,
        PynapliFeature.ListHiddenProfiles,
        PynapliFeature.SubmitReport,
        PynapliFeature.SafetyCenter,
        PynapliFeature.AgeVerification
    )

    fun requiresPaidEntitlement(feature: PynapliFeature): Boolean =
        feature.requiresPaidEntitlement

    fun requireAlwaysFreeSafetyFeature(feature: PynapliFeature) {
        check(feature in alwaysFreeSafetyFeatures && !requiresPaidEntitlement(feature)) {
            "${feature.name} must remain available without a paid entitlement."
        }
    }
}
