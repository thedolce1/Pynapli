package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.ads.AdPlacement
import com.pynapli.app.ads.AdPlacementPolicy
import com.pynapli.shared.network.dto.SwipeCardDto

@Composable
internal fun SwipeScreenContent(
    state: SwipeUiState,
    topPicksState: TopPicksUiState,
    travelCityName: String?,
    requestedAction: String?,
    distanceUnit: String,
    onOpenFilters: () -> Unit,
    onOpenTravel: () -> Unit,
    onOpenStore: () -> Unit,
    onFilterSelected: (SwipeFilterType) -> Unit,
    onTopPickSelected: (SwipeCardDto) -> Unit,
    onRetryDeck: () -> Unit,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onLinkedPartnerTap: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit,
    onRetryPendingActions: () -> Unit,
    onRewind: () -> Unit,
    onPass: () -> Unit,
    onIntroNote: () -> Unit,
    onPineappleLike: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(SwipeTestTags.ROOT)
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        SwipeHeader(
            activeFilterCount = state.swipeFilters.activeCount,
            travelCityName = travelCityName,
            onOpenFilters = onOpenFilters,
            onOpenTravel = onOpenTravel
        )
        Spacer(Modifier.height(10.dp))
        SwipeFilterRow(
            filters = state.filters,
            onFilterSelected = onFilterSelected
        )
        state.paywallMessage?.let { message ->
            SwipePaywallRow(
                message = message,
                onOpenStore = onOpenStore
            )
        }
        if (AdPlacementPolicy.canShowAds(state.adSupported, AdPlacement.SwipeBetweenBatches)) {
            SwipeAdSlot()
        }
        if (topPicksState.items.isNotEmpty()) {
            TopPicksRow(
                state = topPicksState,
                onPickSelected = onTopPickSelected
            )
        }
        Spacer(Modifier.height(10.dp))

        SwipeDeckArea(
            state = state,
            requestedAction = requestedAction,
            distanceUnit = distanceUnit,
            onRetryDeck = onRetryDeck,
            onOpenFilters = onOpenFilters,
            onActionConsumed = onActionConsumed,
            onOpenProfile = onOpenProfile,
            onLinkedPartnerTap = onLinkedPartnerTap,
            onSwiped = onSwiped,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(Modifier.height(16.dp))
        if (state.errorMessage != null && state.cards.isNotEmpty()) {
            SwipeInlineError(message = state.errorMessage)
            Spacer(Modifier.height(8.dp))
        }
        if (state.pendingActionCount > 0) {
            SwipePendingSync(
                count = state.pendingActionCount,
                onRetry = onRetryPendingActions
            )
            Spacer(Modifier.height(8.dp))
        }

        SwipeActions(
            actionEnabled = state.hasMore && !state.actionQueueFull,
            rewindEnabled = !state.isSubmitting && !state.isRewinding,
            isSubmitting = false,
            onRewind = onRewind,
            onPass = onPass,
            onIntroNote = onIntroNote,
            onPineappleLike = onPineappleLike,
            onLike = onLike
        )
    }
}

@Composable
private fun SwipeDeckArea(
    state: SwipeUiState,
    requestedAction: String?,
    distanceUnit: String,
    onRetryDeck: () -> Unit,
    onOpenFilters: () -> Unit,
    onActionConsumed: () -> Unit,
    onOpenProfile: (SwipeCardDto) -> Unit,
    onLinkedPartnerTap: (SwipeCardDto) -> Unit,
    onSwiped: (SwipeCardDto, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        when {
            state.isLoading && state.cards.isEmpty() -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            state.errorMessage != null && state.cards.isEmpty() && state.nextCursor == null -> {
                SwipeError(
                    message = state.errorMessage,
                    onRetry = onRetryDeck
                )
            }
            state.cards.isEmpty() && state.nextCursor != null -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            !state.hasMore -> {
                EmptyDeck(
                    filtersActive = state.swipeFilters.isAnyActive,
                    onRefresh = onRetryDeck,
                    onOpenFilters = onOpenFilters
                )
            }
            else -> {
                SwipeCardStack(
                    cards = state.cards,
                    enabled = !state.actionQueueFull,
                    requestedAction = requestedAction,
                    distanceUnit = distanceUnit,
                    onActionConsumed = onActionConsumed,
                    onOpenProfile = onOpenProfile,
                    onLinkedPartnerTap = onLinkedPartnerTap,
                    onSwiped = onSwiped
                )
            }
        }
    }
}
