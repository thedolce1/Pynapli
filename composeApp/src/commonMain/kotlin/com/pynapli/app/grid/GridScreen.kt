package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.pynapli.shared.distance.formatDistanceForUnit
import com.pynapli.shared.settings.AppSettings
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    GridScreenContent(
        state = state,
        locationState = locationState,
        profileImageUrl = profileImageUrl,
        distanceUnit = distanceUnit,
        gridState = gridState,
        onQuickFilterToggled = { id -> scope.launch { stateHolder.toggleQuickFilter(id) } },
        onApplyFilters = { filters -> scope.launch { stateHolder.applyFilters(filters) } },
        onClearAllFilters = { scope.launch { stateHolder.clearAllFilters() } },
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
    locationState: LocationUiState,
    profileImageUrl: String?,
    distanceUnit: String,
    gridState: LazyGridState,
    onQuickFilterToggled: (QuickFilterId) -> Unit,
    onApplyFilters: (GridFilters) -> Unit,
    onClearAllFilters: () -> Unit,
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
            onDismiss = { sheetVisible = false },
            onApply = { applied ->
                sheetVisible = false
                onApplyFilters(applied)
            },
            onClearAll = {
                sheetVisible = false
                onClearAllFilters()
            }
        )
    }
}

@Composable
private fun GridHeader(
    locationLabel: String,
    profileImageUrl: String?,
    onProfileIconClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4E342E), Color(0xFFC39A49))
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onProfileIconClick),
            contentAlignment = Alignment.Center
        ) {
            if (profileImageUrl != null) {
                AsyncImage(
                    model = profileImageUrl,
                    contentDescription = "Profile",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Profile",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            OnlineDot(
                modifier = Modifier.align(Alignment.BottomEnd),
                size = 10
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = locationLabel,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = {},
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = "Filters",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun QuickFilterRow(
    filters: GridFilters,
    onToggled: (QuickFilterId) -> Unit,
    onOpenSheet: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(GridFilterTags.QUICK_ROW),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "filters-button") {
            FiltersOpenChip(activeCount = filters.activeCount, onClick = onOpenSheet)
        }
        item(key = QuickFilterId.Online.name) {
            QuickToggleChip(
                label = "Online",
                active = filters.online,
                onClick = { onToggled(QuickFilterId.Online) }
            )
        }
        item(key = "nearby-radius") {
            NearbyRadiusChip(radiusMeters = filters.radiusMeters, onClick = onOpenSheet)
        }
        item(key = QuickFilterId.Singles.name) {
            QuickToggleChip(
                label = "Singles",
                active = filters.singles,
                onClick = { onToggled(QuickFilterId.Singles) }
            )
        }
        item(key = QuickFilterId.Linked.name) {
            QuickToggleChip(
                label = "Linked",
                active = filters.linked,
                onClick = { onToggled(QuickFilterId.Linked) }
            )
        }
        item(key = QuickFilterId.Verified.name) {
            QuickToggleChip(
                label = "Verified",
                active = filters.verified,
                onClick = { onToggled(QuickFilterId.Verified) }
            )
        }
        item(key = QuickFilterId.Tonight.name) {
            QuickToggleChip(
                label = "Tonight",
                active = filters.tonightAvailable,
                onClick = { onToggled(QuickFilterId.Tonight) }
            )
        }
    }
}

@Composable
private fun FiltersOpenChip(activeCount: Int, onClick: () -> Unit) {
    val label = if (activeCount == 0) "Filters" else "Filters · $activeCount"
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.primary
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.testTag(GridFilterTags.OPEN_SHEET)
    )
}

@Composable
private fun QuickToggleChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            labelColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        )
    )
}

@Composable
private fun NearbyRadiusChip(radiusMeters: Int, onClick: () -> Unit) {
    val km = (radiusMeters + 500) / 1_000
    val label = "≤ ${km} km"
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.primary
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        )
    )
}

internal object GridFilterTags {
    const val QUICK_ROW: String = "grid-quick-filter-row"
    const val OPEN_SHEET: String = "grid-filters-open"
    const val SHEET: String = "grid-filters-sheet"
    const val APPLY: String = "grid-filters-apply"
    const val CLEAR_ALL: String = "grid-filters-clear-all"
}

@Composable
private fun GridPaywallRow(
    message: String,
    onOpenStore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpenStore) {
            Text(text = "Social", maxLines = 1)
        }
    }
}

@Composable
private fun GridAdSlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Sponsored",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
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
private fun GridProfileTile(
    card: GridProfileCardUi,
    distanceUnit: String,
    onClick: () -> Unit
) {
    val distanceLabel = formatDistanceForUnit(
        distanceMeters = card.distanceMeters,
        fallbackLabel = card.distanceLabel,
        distanceUnit = distanceUnit
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .background(card.tone.backgroundBrush())
    ) {
        card.thumbnailUrl?.let { thumbnailUrl ->
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.52f to Color.Transparent,
                            1.0f to Color(0xD9000000)
                        )
                    )
                )
        )

        if (card.linkedPartner != null) {
            LinkedPartnerBadge(
                thumbnailUrl = card.linkedPartner.thumbnailUrl,
                color = card.linkedPartnerTone.badgeColor(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (card.online) {
                OnlineDot(size = 8)
            }

            Text(
                text = card.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    color = Color(0xFFD7D7D7),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun OnlineDot(
    modifier: Modifier = Modifier,
    size: Int
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0xFF13E58A))
            .border(1.dp, Color(0xFF050505), CircleShape)
    )
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

@Composable
private fun LinkedPartnerBadge(
    thumbnailUrl: String?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (thumbnailUrl == null) color else Color(0xCC111111))
            .border(1.dp, Color(0xD9FFFFFF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = "Linked partner",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Linked partner",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

private fun GridCardTone.backgroundBrush(): Brush = when (this) {
    GridCardTone.Warm -> Brush.linearGradient(listOf(Color(0xFF2A2A2A), Color(0xFF7D5B3F)))
    GridCardTone.Slate -> Brush.linearGradient(listOf(Color(0xFF263238), Color(0xFF607D8B)))
    GridCardTone.Rust -> Brush.linearGradient(listOf(Color(0xFF1E1B1B), Color(0xFFB85C38)))
    GridCardTone.DeepGreen -> Brush.linearGradient(listOf(Color(0xFF252525), Color(0xFF455A40)))
    GridCardTone.Bronze -> Brush.linearGradient(listOf(Color(0xFF2B2622), Color(0xFF9A6B38)))
    GridCardTone.Graphite -> Brush.linearGradient(listOf(Color(0xFF202124), Color(0xFF6B7280)))
    GridCardTone.Teal -> Brush.linearGradient(listOf(Color(0xFF171717), Color(0xFF355C5B)))
    GridCardTone.Rose -> Brush.linearGradient(listOf(Color(0xFF2E2524), Color(0xFF884C5E)))
    GridCardTone.Cocoa -> Brush.linearGradient(listOf(Color(0xFF1C1B1A), Color(0xFF6D5D4F)))
    GridCardTone.Moss -> Brush.linearGradient(listOf(Color(0xFF1F2421), Color(0xFF526E48)))
    GridCardTone.Stone -> Brush.linearGradient(listOf(Color(0xFF212121), Color(0xFF7B6257)))
    GridCardTone.Midnight -> Brush.linearGradient(listOf(Color(0xFF1A202C), Color(0xFF57606A)))
}

private fun LinkedPartnerTone?.badgeColor(): Color = when (this) {
    LinkedPartnerTone.Violet -> Color(0xFF8B5CF6)
    LinkedPartnerTone.Gold -> Color(0xFFC39A49)
    LinkedPartnerTone.Pink -> Color(0xFFE85D75)
    LinkedPartnerTone.Teal -> Color(0xFF4DB6AC)
    LinkedPartnerTone.Sun -> Color(0xFFFFC928)
    null -> Color(0xFFC39A49)
}
