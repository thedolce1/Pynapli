package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.conversation.SampleConversationRepository
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.profile.ProfileDetailSheet
import com.pynapli.app.profile.ProfileLinkActionState
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.profilelink.ProfileLinksUiState
import com.pynapli.shared.settings.AppSettings
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.launch

@Composable
fun GridScreen(
    modifier: Modifier = Modifier,
    stateHolder: GridStateHolder = rememberGridStateHolder(),
    profileDetailStateHolder: ProfileDetailStateHolder = rememberProfileDetailStateHolder(),
    conversationStateHolder: ConversationStateHolder = rememberConversationStateHolder(),
    viewerUserId: String? = null,
    locationState: LocationUiState = LocationUiState(),
    profileImageUrl: String? = null,
    profileLinksStateHolder: ProfileLinksStateHolder? = null,
    savedGridFilterStateHolder: SavedGridFilterStateHolder? = null,
    tonightAvailabilityStateHolder: TonightAvailabilityStateHolder? = null,
    lifestyleFiltersAllowed: Boolean = true,
    distanceUnit: String = AppSettings.UNIT_METRIC,
    gridState: LazyGridState = rememberLazyGridState(),
    onUseCurrentLocation: () -> Unit = {},
    onProfileIconClick: () -> Unit = {},
    onOpenStore: () -> Unit = {}
) {
    val state by stateHolder.state.collectAsState()
    val profileState by profileDetailStateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val savedFiltersState by savedGridFilterStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(SavedGridFiltersUiState()) }
    val tonightState by tonightAvailabilityStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(TonightAvailabilityUiState()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    LaunchedEffect(savedGridFilterStateHolder) {
        savedGridFilterStateHolder?.load()
    }

    LaunchedEffect(tonightAvailabilityStateHolder) {
        tonightAvailabilityStateHolder?.load()
    }

    GridScreenContent(
        state = state,
        savedFiltersState = savedFiltersState,
        tonightState = tonightState,
        lifestyleFiltersAllowed = lifestyleFiltersAllowed,
        locationState = locationState,
        profileImageUrl = profileImageUrl,
        distanceUnit = distanceUnit,
        gridState = gridState,
        onQuickFilterToggled = { id -> scope.launch { stateHolder.toggleQuickFilter(id) } },
        onApplyFilters = { filters -> scope.launch { stateHolder.applyFilters(filters) } },
        onClearAllFilters = { scope.launch { stateHolder.clearAllFilters() } },
        onSavePreset = { name, filters ->
            savedGridFilterStateHolder?.let { holder -> scope.launch { holder.save(name, filters) } }
        },
        onApplySavedPreset = { dto ->
            scope.launch { stateHolder.applyFilters(GridFilters.fromGridQueryRequest(dto.payload)) }
        },
        onDeleteSavedPreset = { id ->
            savedGridFilterStateHolder?.let { holder -> scope.launch { holder.delete(id) } }
        },
        onSetTonightAvailable = {
            tonightAvailabilityStateHolder?.let { holder -> scope.launch { holder.setAvailable() } }
        },
        onClearTonightAvailable = {
            tonightAvailabilityStateHolder?.let { holder -> scope.launch { holder.clear() } }
        },
        onLoadMore = { scope.launch { stateHolder.loadMore() } },
        onProfileSelected = { card ->
            scope.launch { profileDetailStateHolder.open(card) }
        },
        onUseCurrentLocation = onUseCurrentLocation,
        onProfileIconClick = onProfileIconClick,
        onOpenStore = onOpenStore,
        modifier = modifier
    )

    LaunchedEffect(profileState.blockedTargetUserId) {
        val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(blockedId)
        profileDetailStateHolder.dismiss()
    }

    if (profileState.visible) {
        ProfileDetailSheet(
            state = profileState,
            onDismiss = profileDetailStateHolder::dismiss,
            onSpark = {
                scope.launch { profileDetailStateHolder.sendSpark() }
            },
            onRequestAlbum = {
                scope.launch { profileDetailStateHolder.requestAlbumAccess() }
            },
            onViewPrivateAlbum = {
                scope.launch { profileDetailStateHolder.loadPrivateAlbum() }
            },
            onMessage = {
                val card = profileState.selectedCard
                if (card != null) {
                    profileDetailStateHolder.dismiss()
                    scope.launch {
                        conversationStateHolder.openDirect(
                            card = card,
                            viewerUserId = viewerUserId
                        )
                    }
                }
            },
            onBlock = { reason ->
                scope.launch { profileDetailStateHolder.blockUser(reason) }
            },
            onReport = { category, note ->
                scope.launch { profileDetailStateHolder.submitReport(category, note) }
            },
            profileLinkAction = profileLinksState?.profileLinkActionFor(
                targetUserId = profileState.profile?.userId ?: profileState.selectedCard?.id,
                viewerUserId = viewerUserId
            ),
            onInviteProfileLink = {
                val targetUserId = profileState.profile?.userId ?: profileState.selectedCard?.id
                if (targetUserId != null) {
                    scope.launch { profileLinksStateHolder?.createInvitation(targetUserId) }
                }
            },
            onOpenLinkedPartner = { partner ->
                scope.launch { profileDetailStateHolder.openLinkedPartner(partner) }
            },
            distanceUnit = distanceUnit
        )
    }

}

private fun ProfileLinksUiState.profileLinkActionFor(
    targetUserId: String?,
    viewerUserId: String?
): ProfileLinkActionState? {
    if (targetUserId == null || targetUserId == viewerUserId) return null
    val currentLink = current
    val pendingInvite = pendingInviteWith(targetUserId)
    val isSubmitting = targetUserId in submittingTargetUserIds
    val targetFeedback = feedbackMessage.takeIf { feedbackTargetUserId == targetUserId }
    val targetError = errorMessage.takeIf { errorTargetUserId == targetUserId }

    return when {
        currentLink?.otherUserId == targetUserId -> ProfileLinkActionState(
            label = "Linked",
            enabled = false,
            isSubmitting = false,
            feedbackMessage = targetFeedback,
            errorMessage = targetError
        )

        currentLink != null -> null

        pendingInvite != null && pendingInvite.direction == "outgoing" -> ProfileLinkActionState(
            label = "Invite sent",
            enabled = false,
            isSubmitting = false,
            feedbackMessage = targetFeedback,
            errorMessage = targetError
        )

        pendingInvite != null && pendingInvite.direction == "incoming" -> ProfileLinkActionState(
            label = "Review invite",
            enabled = false,
            isSubmitting = false,
            feedbackMessage = targetFeedback,
            errorMessage = targetError
        )

        else -> ProfileLinkActionState(
            label = if (isSubmitting) "Sending" else "Link",
            enabled = true,
            isSubmitting = isSubmitting,
            feedbackMessage = targetFeedback,
            errorMessage = targetError
        )
    }
}

@Composable
private fun rememberGridStateHolder(): GridStateHolder {
    return remember {
        GridStateHolder(
            repository = SampleGridFeedRepository()
        )
    }
}

@Composable
private fun rememberProfileDetailStateHolder(): ProfileDetailStateHolder {
    return remember {
        ProfileDetailStateHolder(
            repository = SamplePublicProfileRepository()
        )
    }
}

@Composable
private fun rememberConversationStateHolder(): ConversationStateHolder {
    return remember {
        ConversationStateHolder(
            repository = SampleConversationRepository()
        )
    }
}

@Composable
private fun GridScreenContent(
    state: GridUiState,
    savedFiltersState: SavedGridFiltersUiState,
    tonightState: TonightAvailabilityUiState,
    lifestyleFiltersAllowed: Boolean,
    locationState: LocationUiState,
    profileImageUrl: String?,
    distanceUnit: String,
    gridState: LazyGridState,
    onQuickFilterToggled: (QuickFilterId) -> Unit,
    onApplyFilters: (GridFilters) -> Unit,
    onClearAllFilters: () -> Unit,
    onSavePreset: (String, GridFilters) -> Unit,
    onApplySavedPreset: (com.pynapli.shared.network.dto.SavedGridFilterDto) -> Unit,
    onDeleteSavedPreset: (String) -> Unit,
    onSetTonightAvailable: () -> Unit,
    onClearTonightAvailable: () -> Unit,
    onLoadMore: () -> Unit,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onProfileIconClick: () -> Unit,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sheetVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        GridHeader(
            locationLabel = state.locationLabel,
            profileImageUrl = profileImageUrl,
            onProfileIconClick = onProfileIconClick
        )
        QuickFilterRow(
            filters = state.filters,
            onToggled = onQuickFilterToggled,
            onOpenSheet = { sheetVisible = true }
        )
        state.paywallMessage?.let { message ->
            GridPaywallRow(
                message = message,
                onOpenStore = onOpenStore
            )
        }
        if (state.adSupported) {
            GridAdSlot()
        }
        GridBody(
            state = state,
            locationState = locationState,
            distanceUnit = distanceUnit,
            gridState = gridState,
            onProfileSelected = onProfileSelected,
            onLoadMore = onLoadMore,
            onUseCurrentLocation = onUseCurrentLocation,
            modifier = Modifier.weight(1f)
        )
    }

    if (sheetVisible) {
        GridFiltersSheet(
            initial = state.filters,
            savedState = savedFiltersState,
            tonightState = tonightState,
            lifestyleFiltersAllowed = lifestyleFiltersAllowed,
            onDismiss = { sheetVisible = false },
            onApply = { applied ->
                sheetVisible = false
                onApplyFilters(applied)
            },
            onClearAll = {
                sheetVisible = false
                onClearAllFilters()
            },
            onSavePreset = onSavePreset,
            onApplySavedPreset = { dto ->
                sheetVisible = false
                val filters = GridFilters.fromGridQueryRequest(dto.payload)
                onApplyFilters(
                    if (lifestyleFiltersAllowed) filters else filters.copy(lifestyleTags = emptySet())
                )
            },
            onDeleteSavedPreset = onDeleteSavedPreset,
            onSetTonightAvailable = onSetTonightAvailable,
            onClearTonightAvailable = onClearTonightAvailable
        )
    }
}

@Composable
private fun ProfileGrid(
    cards: List<GridProfileCardUi>,
    distanceUnit: String,
    gridState: LazyGridState,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onLoadMore: () -> Unit,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    modifier: Modifier = Modifier
) {
    val shouldLoadMore by remember(cards.size, gridState, isLoadingMore, canLoadMore) {
        derivedStateOf {
            val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            canLoadMore && !isLoadingMore && cards.isNotEmpty() && lastVisibleIndex >= cards.lastIndex - 9
        }
    }

    LaunchedEffect(shouldLoadMore, cards.size, canLoadMore, isLoadingMore) {
        if (shouldLoadMore) onLoadMore()
    }

    val prefetchUrls by remember(cards, gridState) {
        derivedStateOf {
            val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            planThumbnailPrefetch(cards = cards, lastVisibleIndex = lastVisibleIndex)
        }
    }
    val platformContext = LocalPlatformContext.current
    LaunchedEffect(prefetchUrls) {
        if (prefetchUrls.isEmpty()) return@LaunchedEffect
        val imageLoader = runCatching { SingletonImageLoader.get(platformContext) }.getOrNull() ?: return@LaunchedEffect
        prefetchUrls.forEach { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(platformContext)
                    .data(url)
                    .build()
            )
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 1.dp, end = 1.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        items(
            items = cards,
            key = { card -> card.id },
            contentType = { "grid-card" }
        ) { card ->
            GridProfileTile(
                card = card,
                distanceUnit = distanceUnit,
                onClick = { onProfileSelected(card) }
            )
        }

        if (isLoadingMore) {
            item(
                key = "grid-loading-more",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "grid-loading"
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GridBody(
    state: GridUiState,
    locationState: LocationUiState,
    distanceUnit: String,
    gridState: LazyGridState,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onLoadMore: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isRefreshing && state.cards.isEmpty() -> {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        state.errorCode == "LOCATION_REQUIRED" && state.cards.isEmpty() -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Use current location to load nearby profiles.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onUseCurrentLocation,
                    enabled = !locationState.isUpdating,
                    modifier = Modifier.padding(top = 14.dp)
                ) {
                    Text(text = if (locationState.isUpdating) "Updating..." else "Use current location")
                }
                locationState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }

        state.errorMessage != null && state.cards.isEmpty() -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        state.cards.isEmpty() && state.filters.isAnyActive -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No one matches these filters right now.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Try widening distance or clearing one filter.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        else -> {
            ProfileGrid(
                cards = state.cards,
                distanceUnit = distanceUnit,
                gridState = gridState,
                onProfileSelected = onProfileSelected,
                onLoadMore = onLoadMore,
                canLoadMore = state.nextCursor != null,
                isLoadingMore = state.isLoadingMore,
                modifier = modifier
            )
        }
    }
}
