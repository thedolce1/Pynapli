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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.pynapli.app.ads.AdPlacement
import com.pynapli.app.ads.AdPlacementPolicy
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.grid.toUi
import com.pynapli.app.profile.ProfileDetailActionMode
import com.pynapli.app.profile.ProfileDetailSheet
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.launch

@Composable
fun SwipeScreen(
    stateHolder: SwipeStateHolder,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    viewerUserId: String?,
    distanceUnit: String = AppSettings.UNIT_METRIC,
    matchNotificationsEnabled: Boolean = true,
    notificationDetail: String = AppSettings.NOTIFICATION_DISCREET,
    onOpenStore: () -> Unit = {},
    savedSwipeFilterStateHolder: SavedSwipeFilterStateHolder? = null,
    topPicksStateHolder: TopPicksStateHolder? = null,
    travelLocationStateHolder: com.pynapli.app.location.TravelLocationStateHolder? = null,
    modifier: Modifier = Modifier
) {
    val state by stateHolder.state.collectAsState()
    val profileState by profileDetailStateHolder.state.collectAsState()
    val savedSwipeFiltersState by savedSwipeFilterStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(SavedSwipeFiltersUiState()) }
    val topPicksState by topPicksStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(TopPicksUiState()) }
    val travelLocationState by travelLocationStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(com.pynapli.app.location.TravelLocationUiState()) }
    val scope = rememberCoroutineScope()
    var requestedAction by remember { mutableStateOf<String?>(null) }
    var selectedProfileCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var profileActionMode by remember { mutableStateOf(ProfileDetailActionMode.SwipeUnmatched) }
    var introNoteCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var introNoteText by remember { mutableStateOf("") }
    var linkedPartnerPreviewCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var filtersSheetVisible by remember { mutableStateOf(false) }
    var travelSheetVisible by remember { mutableStateOf(false) }
    val platformContext = LocalPlatformContext.current
    val swipeImagePrefetchUrls = remember(state.cards) { planSwipeImagePrefetch(state.cards) }

    LaunchedEffect(stateHolder, viewerUserId) {
        stateHolder.load(ownerUserId = viewerUserId)
    }

    LaunchedEffect(stateHolder, state.cards.size, state.nextCursor) {
        stateHolder.loadMoreIfNeeded()
    }

    LaunchedEffect(topPicksStateHolder, viewerUserId) {
        topPicksStateHolder?.load()
    }

    LaunchedEffect(travelLocationStateHolder, viewerUserId) {
        travelLocationStateHolder?.load()
    }

    LaunchedEffect(swipeImagePrefetchUrls) {
        if (swipeImagePrefetchUrls.isEmpty()) return@LaunchedEffect
        val imageLoader = runCatching { SingletonImageLoader.get(platformContext) }.getOrNull() ?: return@LaunchedEffect
        swipeImagePrefetchUrls.forEach { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(platformContext)
                    .data(url)
                    .build()
            )
        }
    }

    LaunchedEffect(state.pendingMatch, matchNotificationsEnabled) {
        if (!matchNotificationsEnabled && state.pendingMatch != null) {
            stateHolder.dismissMatch()
        }
    }

    LaunchedEffect(profileState.blockedTargetUserId) {
        val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(blockedId)
        selectedProfileCard = null
        profileActionMode = ProfileDetailActionMode.SwipeUnmatched
        profileDetailStateHolder.dismiss()
    }

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
            travelCityName = travelLocationState.current?.city,
            onOpenFilters = { filtersSheetVisible = true },
            onOpenTravel = { travelSheetVisible = true }
        )
        Spacer(Modifier.height(10.dp))
        SwipeFilterRow(
            filters = state.filters,
            onFilterSelected = { filter -> scope.launch { stateHolder.selectFilter(filter) } }
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
                onPickSelected = { pickCard ->
                    scope.launch { profileDetailStateHolder.open(pickCard.toProfileCardUi()) }
                }
            )
        }
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isLoading && state.cards.isEmpty() -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.errorMessage != null && state.cards.isEmpty() && state.nextCursor == null -> {
                    SwipeError(
                        message = state.errorMessage ?: "Could not load swipe deck.",
                        onRetry = { scope.launch { stateHolder.load() } }
                    )
                }
                state.cards.isEmpty() && state.nextCursor != null -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                !state.hasMore -> {
                    EmptyDeck(
                        filtersActive = state.swipeFilters.isAnyActive,
                        onRefresh = { scope.launch { stateHolder.load() } },
                        onOpenFilters = { filtersSheetVisible = true }
                    )
                }
                else -> {
                    SwipeCardStack(
                        cards = state.cards,
                        enabled = !state.actionQueueFull,
                        requestedAction = requestedAction,
                        distanceUnit = distanceUnit,
                        onActionConsumed = { requestedAction = null },
                        onOpenProfile = { card ->
                            profileActionMode = ProfileDetailActionMode.SwipeUnmatched
                            selectedProfileCard = card
                            scope.launch {
                                profileDetailStateHolder.open(card.toProfileCardUi())
                            }
                        },
                        onLinkedPartnerTap = { card ->
                            linkedPartnerPreviewCard = card
                        },
                        onSwiped = { card, action ->
                            scope.launch { stateHolder.submitAction(card, action) }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (state.errorMessage != null && state.cards.isNotEmpty()) {
            SwipeInlineError(message = state.errorMessage ?: "")
            Spacer(Modifier.height(8.dp))
        }
        if (state.pendingActionCount > 0) {
            SwipePendingSync(
                count = state.pendingActionCount,
                onRetry = { scope.launch { stateHolder.retryPendingActions() } }
            )
            Spacer(Modifier.height(8.dp))
        }

        SwipeActions(
            actionEnabled = state.hasMore && !state.actionQueueFull,
            rewindEnabled = !state.isSubmitting && !state.isRewinding,
            isSubmitting = false,
            onRewind = { scope.launch { stateHolder.rewind() } },
            onPass = { requestedAction = SwipeAction.PASS },
            onIntroNote = {
                state.topCard?.let { card ->
                    introNoteCard = card
                    introNoteText = ""
                }
            },
            onPineappleLike = { requestedAction = SwipeAction.PINEAPPLE_LIKE },
            onLike = { requestedAction = SwipeAction.LIKE }
        )
    }

    if (matchNotificationsEnabled) state.pendingMatch?.let { match ->
        MatchOverlay(
            match = match,
            showDetails = notificationDetail == AppSettings.NOTIFICATION_STANDARD,
            onDismiss = stateHolder::dismissMatch,
            onMessage = {
                stateHolder.dismissMatch()
                scope.launch {
                    conversationStateHolder.openSwipeMatch(
                        match = match,
                        viewerUserId = viewerUserId
                    )
                }
            }
        )
    }

    if (profileState.visible && (selectedProfileCard != null || profileActionMode == ProfileDetailActionMode.Grid)) {
        val dismissProfile = {
            selectedProfileCard = null
            profileActionMode = ProfileDetailActionMode.SwipeUnmatched
            profileDetailStateHolder.dismiss()
        }

        ProfileDetailSheet(
            state = profileState,
            onDismiss = dismissProfile,
            onMessage = {
                val card = profileState.selectedCard
                if (card != null) {
                    dismissProfile()
                    scope.launch {
                        conversationStateHolder.openDirect(
                            card = card,
                            viewerUserId = viewerUserId
                        )
                    }
                }
            },
            onSpark = {
                scope.launch { profileDetailStateHolder.sendSpark() }
            },
            onRequestAlbum = {
                scope.launch { profileDetailStateHolder.requestAlbumAccess() }
            },
            onViewPrivateAlbum = {
                scope.launch { profileDetailStateHolder.loadPrivateAlbum() }
            },
            onBlock = { reason ->
                scope.launch { profileDetailStateHolder.blockUser(reason) }
            },
            onReport = { category, note ->
                scope.launch { profileDetailStateHolder.submitReport(category, note) }
            },
            actionMode = profileActionMode,
            isSwipeSubmitting = state.isSubmitting,
            onSwipePass = {
                val card = selectedProfileCard
                if (card != null) {
                    dismissProfile()
                    scope.launch { stateHolder.submitAction(card, SwipeAction.PASS) }
                }
            },
            onSwipeLike = {
                val card = selectedProfileCard
                if (card != null) {
                    dismissProfile()
                    scope.launch { stateHolder.submitAction(card, SwipeAction.LIKE) }
                }
            },
            onOpenLinkedPartner = { partner ->
                selectedProfileCard = null
                profileActionMode = ProfileDetailActionMode.Grid
                scope.launch { profileDetailStateHolder.openLinkedPartner(partner) }
            },
            distanceUnit = distanceUnit
        )
    }

    linkedPartnerPreviewCard?.let { card ->
        val partner = card.linkedPartner
        if (partner == null) {
            linkedPartnerPreviewCard = null
        } else {
            LinkedPartnerPreviewDialog(
                cardDisplayName = card.displayName,
                partnerDisplayName = partner.displayName,
                partnerThumbnailUrl = partner.thumbnailUrl,
                partnerOnline = partner.online,
                onDismiss = { linkedPartnerPreviewCard = null },
                onViewProfile = {
                    linkedPartnerPreviewCard = null
                    selectedProfileCard = null
                    profileActionMode = ProfileDetailActionMode.Grid
                    scope.launch { profileDetailStateHolder.openLinkedPartner(partner.toUi()) }
                }
            )
        }
    }

    if (filtersSheetVisible) {
        LaunchedEffect(savedSwipeFilterStateHolder) {
            savedSwipeFilterStateHolder?.load()
        }
        SwipeFiltersSheet(
            initial = state.swipeFilters,
            onDismiss = { filtersSheetVisible = false },
            onApply = { applied ->
                filtersSheetVisible = false
                scope.launch { stateHolder.applyFilters(applied) }
            },
            onClearAll = {
                filtersSheetVisible = false
                scope.launch { stateHolder.applyFilters(SwipeFilters.defaults) }
            },
            onOpenStore = onOpenStore,
            savedState = savedSwipeFiltersState,
            onSavePreset = { name, filters ->
                scope.launch { savedSwipeFilterStateHolder?.save(name, filters) }
            },
            onApplySavedPreset = { dto ->
                filtersSheetVisible = false
                val filters = SwipeFilters.fromSwipeDeckRequest(dto.payload)
                scope.launch { stateHolder.applyFilters(filters) }
            },
            onDeleteSavedPreset = { id ->
                scope.launch { savedSwipeFilterStateHolder?.delete(id) }
            }
        )
    }

    if (travelSheetVisible) {
        TravelModeSheet(
            state = travelLocationState,
            onDismiss = { travelSheetVisible = false },
            onPickCity = { preset ->
                scope.launch {
                    travelLocationStateHolder?.setActive(preset)
                    // Refresh the deck so the new travel location takes effect
                    // immediately rather than waiting for the next manual reload.
                    stateHolder.load(ownerUserId = viewerUserId)
                    topPicksStateHolder?.load()
                }
                travelSheetVisible = false
            },
            onClear = {
                scope.launch {
                    travelLocationStateHolder?.clear()
                    stateHolder.load(ownerUserId = viewerUserId)
                    topPicksStateHolder?.load()
                }
                travelSheetVisible = false
            },
            onOpenStore = {
                travelSheetVisible = false
                onOpenStore()
            }
        )
    }

    introNoteCard?.let { card ->
        IntroNoteDialog(
            displayName = card.displayName,
            noteText = introNoteText,
            isSubmitting = state.isSubmitting,
            onNoteTextChanged = { text -> introNoteText = text.take(INTRO_NOTE_INPUT_MAX_LENGTH) },
            onDismiss = {
                if (!state.isSubmitting) {
                    introNoteCard = null
                    introNoteText = ""
                }
            },
            onSend = {
                scope.launch {
                    stateHolder.submitIntroNote(card, introNoteText)
                    introNoteCard = null
                    introNoteText = ""
                }
            }
        )
    }
}

internal object SwipeTestTags {
    const val ROOT = "swipe-root"
    const val TOP_CARD = "swipe-top-card"
    const val CARD_PREFIX = "swipe-card-"
    const val ONLINE_DOT = "swipe-card-online-dot"
    const val VERIFIED_BADGE = "swipe-card-verified-badge"
    const val VISITING_PILL = "swipe-card-visiting-pill"
    const val LINKED_PARTNER_BADGE = "swipe-card-linked-partner-badge"
    const val LINKED_PARTNER_PREVIEW_DIALOG = "swipe-linked-partner-preview-dialog"
    const val LINKED_PARTNER_PREVIEW_VIEW_PROFILE = "swipe-linked-partner-preview-view-profile"
    const val LINKED_PARTNER_PREVIEW_ONLINE = "swipe-linked-partner-preview-online"
    const val SHORT_BIO = "swipe-card-short-bio"
    const val TAGS_ROW = "swipe-card-tags-row"
    const val PHOTO_CAROUSEL = "swipe-card-photo-carousel"
    const val PHOTO_CAROUSEL_INDICATORS = "swipe-card-photo-carousel-indicators"
    const val REWIND_ACTION = "swipe-action-rewind"
    const val PASS_ACTION = "swipe-action-pass"
    const val INTRO_NOTE_ACTION = "swipe-action-intro-note"
    const val PINEAPPLE_LIKE_ACTION = "swipe-action-pineapple-like"
    const val LIKE_ACTION = "swipe-action-like"
}
