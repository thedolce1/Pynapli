package com.pynapli.app.grid

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.profile.ProfileDetailSheet
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.shared.settings.AppSettings
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
    onOpenStore: () -> Unit = {},
    onOpenAgeVerification: () -> Unit = {},
    onOpenTonight: () -> Unit = {},
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
        stateHolder.primeFromCacheThenLoad()
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
        onRefresh = { scope.launch { stateHolder.refresh() } },
        onProfileSelected = { card ->
            scope.launch { profileDetailStateHolder.open(card) }
        },
        onUseCurrentLocation = onUseCurrentLocation,
        onProfileIconClick = onProfileIconClick,
        onOpenStore = onOpenStore,
        onOpenAgeVerification = onOpenAgeVerification,
        onOpenTonight = onOpenTonight,
        modifier = modifier,
    )

    LaunchedEffect(profileState.blockedTargetUserId) {
        val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(blockedId)
        profileDetailStateHolder.dismiss()
    }

    LaunchedEffect(profileState.hiddenTargetUserId) {
        val hiddenId = profileDetailStateHolder.consumeHiddenTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(hiddenId)
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
                            viewerUserId = viewerUserId,
                        )
                    }
                }
            },
            onBlock = { reason ->
                scope.launch { profileDetailStateHolder.blockUser(reason) }
            },
            onHide = {
                scope.launch { profileDetailStateHolder.hideProfile() }
            },
            onReport = { category, note ->
                scope.launch { profileDetailStateHolder.submitReport(category, note) }
            },
            profileLinkAction =
                profileLinksState?.profileLinkActionFor(
                    targetUserId = profileState.profile?.userId ?: profileState.selectedCard?.id,
                    viewerUserId = viewerUserId,
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
            distanceUnit = distanceUnit,
        )
    }
}
