package com.pynapli.app.ads

enum class AdPlacement {
    GridBetweenPages,
    SwipeBetweenBatches,
    StoreTab,
    NonSensitiveEmptyState,
    ActivityFeed,
    ProfileDetail,
    ChatThread,
    PrivateAlbumViewer,
    PartnerInvite,
    ReportFlow,
    BlockFlow,
    SafetyFlow,
    VerificationFlow
}

object AdPlacementPolicy {
    fun canShowAds(adSupported: Boolean, placement: AdPlacement): Boolean =
        adSupported && placement in allowedPlacements

    private val allowedPlacements = setOf(
        AdPlacement.GridBetweenPages,
        AdPlacement.SwipeBetweenBatches,
        AdPlacement.StoreTab,
        AdPlacement.NonSensitiveEmptyState
    )
}
