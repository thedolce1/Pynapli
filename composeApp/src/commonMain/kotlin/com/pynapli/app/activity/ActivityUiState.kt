package com.pynapli.app.activity

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.ActivityEventDto

@Immutable
data class ActivityUiState(
    // Likes the viewer has answered from Activity (event ids), so the button shows it's done.
    val likedBackEventIds: Set<String> = emptySet(),
    val items: List<ActivityEventDto> = emptyList(),
    val selectedTab: ActivityTabUi = ActivityTabUi.Flirts,
    val summary: ActivitySummaryUi = ActivitySummaryUi.Empty,
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val entitlementTier: String? = null,
    val revealLimit: Int? = null,
    val gatedCount: Int = 0,
    val pendingAlbumActionEventId: String? = null,
    val pendingDismissEventId: String? = null,
    val focusedEventId: String? = null,
    val actionFeedback: String? = null,
    val errorMessage: String? = null,
) {
    val visibleItems: List<ActivityEventDto>
        get() = items

    val activityBadgeCount: Int
        get() = summary.badgeCount
}

@Immutable
data class ActivitySummaryUi(
    val badgeCount: Int,
    val profileViewCount: Int,
    val flirtCount: Int,
    val likeCount: Int,
    val matchCount: Int,
    val albumRequestCount: Int,
    val partnerInviteCount: Int,
    val latestBadgeOccurredAt: String?,
) {
    companion object {
        val Empty =
            ActivitySummaryUi(
                badgeCount = 0,
                profileViewCount = 0,
                flirtCount = 0,
                likeCount = 0,
                matchCount = 0,
                albumRequestCount = 0,
                partnerInviteCount = 0,
                latestBadgeOccurredAt = null,
            )
    }
}

enum class ActivityTabUi(
    val label: String,
    val apiValue: String,
) {
    // Mobile Activity surface: Flirts (unified inbound interest — taps + swipe-likes + pineapples,
    // with their notes — and the premium reveal money surface), Views, and Requests (album-access +
    // partner/psync/tonight invites). Matches live in the Messages new-matches rail.
    Flirts("Flirts", "flirts"),
    Views("Views", "views"),
    Requests("Requests", "requests"),
}

/**
 * Flirts and Views render as a 2-up profile grid (FOMO teaser). Requests keeps action
 * rows (album review / invite CTAs), so it stays the list layout.
 */
internal fun ActivityTabUi.isFomoGridTab(): Boolean = this == ActivityTabUi.Flirts || this == ActivityTabUi.Views

@Immutable
data class ActivityFeedUi(
    val items: List<ActivityEventDto>,
    val nextCursor: String? = null,
    val entitlementTier: String? = null,
    val revealLimit: Int? = null,
    val gatedCount: Int = 0,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 30
    }
}
