package com.pynapli.app.activity

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliErrorState
import com.pynapli.app.design.PynapliGridSkeleton
import com.pynapli.app.design.PynapliListSkeleton
import com.pynapli.app.design.PynapliMaxWidthColumn
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.pynapliEntrance
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.launch

@Composable
fun ActivityScreen(
    modifier: Modifier = Modifier,
    stateHolder: ActivityStateHolder,
    distanceUnit: String = AppSettings.UNIT_METRIC,
    onOpenStore: () -> Unit = {},
    onOpenMatch: (ActivityEventDto) -> Unit = {},
    onOpenPartnerChatInvite: (String) -> Unit = {},
    onOpenProfile: (ActivityEventDto) -> Unit = {},
) {
    val state by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var expandedAlbumReviewEventId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    ActivityScreenContent(
        state = state,
        onTabSelected = { tab -> scope.launch { stateHolder.selectTab(tab) } },
        onOpenProfile = onOpenProfile,
        onAlbumRequestDecision = { eventId, approve ->
            expandedAlbumReviewEventId = null
            scope.launch { stateHolder.respondToAlbumRequest(eventId, approve) }
        },
        expandedAlbumReviewEventId = expandedAlbumReviewEventId,
        onReviewAlbumRequest = { eventId -> expandedAlbumReviewEventId = eventId },
        onDismissEvent = { eventId -> scope.launch { stateHolder.dismissActivityEvent(eventId) } },
        onOpenMatch = onOpenMatch,
        onOpenPartnerChatInvite = onOpenPartnerChatInvite,
        onLoadMore = { scope.launch { stateHolder.loadMore() } },
        onRefresh = { scope.launch { stateHolder.load() } },
        onOpenStore = onOpenStore,
        distanceUnit = distanceUnit,
        modifier = modifier,
    )
}

@Composable
private fun ActivityScreenContent(
    state: ActivityUiState,
    onTabSelected: (ActivityTabUi) -> Unit,
    onOpenProfile: (ActivityEventDto) -> Unit,
    onAlbumRequestDecision: (String, Boolean) -> Unit,
    expandedAlbumReviewEventId: String?,
    onReviewAlbumRequest: (String) -> Unit,
    onDismissEvent: (String) -> Unit,
    onOpenMatch: (ActivityEventDto) -> Unit,
    onOpenPartnerChatInvite: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenStore: () -> Unit,
    distanceUnit: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.focusedEventId, state.visibleItems, state.gatedCount) {
        val focusedEventId = state.focusedEventId ?: return@LaunchedEffect
        val index = state.visibleItems.indexOfFirst { item -> item.eventId == focusedEventId }
        if (index >= 0) {
            val gateOffset = if (state.gatedCount > 0) 1 else 0
            listState.animateScrollToItem(index + gateOffset)
        }
    }

    PynapliMaxWidthColumn(
        maxWidth = PynapliContentWidth.WideFeed,
        modifier = modifier,
    ) {
        ActivityHeader(
            loading = state.isLoading,
            onRefresh = onRefresh,
        )
        ActivityTabRow(
            selectedTab = state.selectedTab,
            onTabSelected = onTabSelected,
        )
        if (state.items.isNotEmpty()) {
            ActivityContextStrip(
                label = state.selectedTab.label,
                count = state.visibleItems.size + state.gatedCount,
            )
        }
        ActivityInlineMessage(
            message = state.actionFeedback ?: state.errorMessage?.takeIf { state.items.isNotEmpty() },
            isError = state.actionFeedback == null && state.errorMessage != null,
        )

        when {
            state.isLoading && state.items.isEmpty() -> {
                // Match the loading shape to the selected tab: FOMO tabs render a 2-up grid, the
                // Requests tab renders a list, so the skeleton dissolves into the real content.
                if (state.selectedTab.isFomoGridTab()) {
                    PynapliGridSkeleton(
                        columns = 2,
                        rows = 3,
                        modifier = Modifier.fillMaxSize(),
                        tileAspectRatio = 0.82f,
                        tileShape = PynapliShape.CardShape,
                        gap = 10.dp,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                    )
                } else {
                    PynapliListSkeleton(modifier = Modifier.fillMaxSize())
                }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                PynapliErrorState(
                    title = "Couldn't load activity",
                    message = state.errorMessage,
                    onRetry = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.items.isEmpty() ->
                EmptyActivityState(
                    label = state.selectedTab.emptyLabel(),
                    modifier = Modifier.fillMaxSize(),
                )

            state.visibleItems.isEmpty() ->
                EmptyActivityState(
                    label = state.selectedTab.emptyLabel(),
                    modifier = Modifier.fillMaxSize(),
                )

            state.selectedTab.isFomoGridTab() -> {
                ActivityFomoGrid(
                    items = state.visibleItems,
                    gatedCount = state.gatedCount,
                    adSupported = state.adSupported,
                    nextCursor = state.nextCursor,
                    isLoadingMore = state.isLoadingMore,
                    focusedEventId = state.focusedEventId,
                    pendingDismissEventId = state.pendingDismissEventId,
                    distanceUnit = distanceUnit,
                    // Matches open the conversation (like the Messages rail); Views/Flirts open the
                    // actor's profile.
                    onTileClick = { event ->
                        if (state.selectedTab == ActivityTabUi.Matches) {
                            onOpenMatch(event)
                        } else {
                            onOpenProfile(event)
                        }
                    },
                    onDismissEvent = onDismissEvent,
                    onOpenStore = onOpenStore,
                    onLoadMore = onLoadMore,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                var revealed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { revealed = true }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.gatedCount > 0) {
                        item(key = "activity-gate") {
                            ActivityGateRow(
                                gatedCount = state.gatedCount,
                                onOpenStore = onOpenStore,
                            )
                        }
                    }
                    itemsIndexed(
                        items = state.visibleItems,
                        key = { _, item -> item.eventId },
                    ) { index, item ->
                        ActivityRow(
                            item = item,
                            focused = state.focusedEventId == item.eventId,
                            pendingAction = state.pendingAlbumActionEventId == item.eventId,
                            albumReviewExpanded = expandedAlbumReviewEventId == item.eventId,
                            pendingDismiss = state.pendingDismissEventId == item.eventId,
                            distanceUnit = distanceUnit,
                            onReviewAlbumRequest = onReviewAlbumRequest,
                            onAlbumRequestDecision = onAlbumRequestDecision,
                            onOpenMatch = onOpenMatch,
                            onOpenPartnerChatInvite = onOpenPartnerChatInvite,
                            onDismissEvent = onDismissEvent,
                            modifier = Modifier.pynapliEntrance(visible = revealed, index = index),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    }
                    if (state.nextCursor != null) {
                        item(key = "activity-load-more") {
                            ActivityLoadMoreRow(
                                loading = state.isLoadingMore,
                                onLoadMore = onLoadMore,
                            )
                        }
                    }
                    if (state.adSupported) {
                        item(key = "activity-ad-slot") {
                            ActivityAdSlot()
                        }
                    }
                }
            }
        }
    }
}
