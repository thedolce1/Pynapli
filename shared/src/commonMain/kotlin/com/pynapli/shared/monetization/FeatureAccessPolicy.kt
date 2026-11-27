package com.pynapli.shared.monetization

enum class PynapliFeature(
    val requiresPaidEntitlement: Boolean,
) {
    BlockUser(false),
    UnblockUser(false),
    ListBlocks(false),
    HideProfile(false),
    UnhideProfile(false),
    ListHiddenProfiles(false),
    SubmitReport(false),
    ListReports(false),
    SupportContact(false),
    SafetyCenter(false),
    AgeVerification(false),

    // Every filter is free (founder decision 2, 2026-11-26); saved filter presets are the paid part.
    GridSavedFilterPresets(true),
    SwipeSavedFilterPresets(true),
    ActivityDeepReveal(true),
    PineappleLike(true),
    Boost(true),
}

object FeatureAccessPolicy {
    val alwaysFreeSafetyFeatures: Set<PynapliFeature> =
        setOf(
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
            PynapliFeature.AgeVerification,
        )

    fun requiresPaidEntitlement(feature: PynapliFeature): Boolean = feature.requiresPaidEntitlement

    fun requireAlwaysFreeSafetyFeature(feature: PynapliFeature) {
        check(feature in alwaysFreeSafetyFeatures && !requiresPaidEntitlement(feature)) {
            "${feature.name} must remain available without a paid entitlement."
        }
    }
}
