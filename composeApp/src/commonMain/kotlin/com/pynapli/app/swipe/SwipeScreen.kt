package com.pynapli.app.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    onOpenAgeVerification: () -> Unit = {},
    onOpenPsync: (() -> Unit)? = null,
    savedSwipeFilterStateHolder: SavedSwipeFilterStateHolder? = null,
    travelLocationStateHolder: com.pynapli.app.location.TravelLocationStateHolder? = null,
    modifier: Modifier = Modifier,
) {
    val state by stateHolder.state.collectAsState()
    val profileState by profileDetailStateHolder.state.collectAsState()
    val savedSwipeFiltersState by savedSwipeFilterStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(SavedSwipeFiltersUiState()) }
    val travelLocationState by travelLocationStateHolder?.state?.collectAsState()
        ?: remember {
            mutableStateOf(
                com.pynapli.app.location
                    .TravelLocationUiState(),
            )
        }
    val scope = rememberCoroutineScope()
    var requestedAction by remember { mutableStateOf<String?>(null) }
    var selectedProfileCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var profileActionMode by remember { mutableStateOf(ProfileDetailActionMode.SwipeUnmatched) }
    var introNoteCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var introNoteText by remember { mutableStateOf("") }
    var linkedPartnerPreviewCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var filtersSheetVisible by remember { mutableStateOf(false) }
    var travelSheetVisible by remember { mutableStateOf(false) }

    LaunchedEffect(stateHolder, viewerUserId) {
        stateHolder.load(ownerUserId = viewerUserId)
    }

    LaunchedEffect(stateHolder, state.cards.size, state.nextCursor) {
        stateHolder.loadMoreIfNeeded()
    }

    LaunchedEffect(travelLocationStateHolder, viewerUserId) {
        travelLocationStateHolder?.load()
    }

    SwipeImagePrefetchEffect(cards = state.cards)

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

    LaunchedEffect(profileState.hiddenTargetUserId) {
        val hiddenId = profileDetailStateHolder.consumeHiddenTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(hiddenId)
        selectedProfileCard = null
        profileActionMode = ProfileDetailActionMode.SwipeUnmatched
        profileDetailStateHolder.dismiss()
    }

    SwipeScreenContent(
        state = state,
        travelCityName = travelLocationState.current?.city,
        requestedAction = requestedAction,
        distanceUnit = distanceUnit,
        onOpenFilters = { filtersSheetVisible = true },
        onOpenTravel = { travelSheetVisible = true },
        onOpenStore = onOpenStore,
        onOpenPsync = onOpenPsync,
        onFilterSelected = { filter -> scope.launch { stateHolder.selectFilter(filter) } },
        onRetryDeck = { scope.launch { stateHolder.load() } },
        onActionConsumed = { requestedAction = null },
        onOpenProfile = { card ->
            profileActionMode = ProfileDetailActionMode.SwipeUnmatched
            selectedProfileCard = card
            scope.launch {
                profileDetailStateHolder.open(card.toProfileCardUi())
            }
        },
        onLinkedPartnerTap = { card -> linkedPartnerPreviewCard = card },
        onSwiped = { card, action -> scope.launch { stateHolder.submitAction(card, action) } },
        onRetryPendingActions = { scope.launch { stateHolder.retryPendingActions() } },
        onRewind = { scope.launch { stateHolder.rewind() } },
        onPass = { requestedAction = SwipeAction.PASS },
        onIntroNote = {
            state.topCard?.let { card ->
                introNoteCard = card
                introNoteText = ""
            }
        },
        onPineappleLike = { requestedAction = SwipeAction.PINEAPPLE_LIKE },
        onLike = { requestedAction = SwipeAction.LIKE },
        modifier = modifier,
    )

    if (matchNotificationsEnabled) {
        state.pendingMatch?.let { match ->
            MatchOverlay(
                match = match,
                showDetails = notificationDetail == AppSettings.NOTIFICATION_STANDARD,
                onDismiss = stateHolder::dismissMatch,
                onMessage = {
                    stateHolder.dismissMatch()
                    scope.launch {
                        conversationStateHolder.openSwipeMatch(
                            match = match,
                            viewerUserId = viewerUserId,
                        )
                    }
                },
            )
        }
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
                            viewerUserId = viewerUserId,
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
            onHide = {
                scope.launch { profileDetailStateHolder.hideProfile() }
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
            distanceUnit = distanceUnit,
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
                },
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
            onOpenAgeVerification = onOpenAgeVerification,
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
            },
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
                }
                travelSheetVisible = false
            },
            onClear = {
                scope.launch {
                    travelLocationStateHolder?.clear()
                    stateHolder.load(ownerUserId = viewerUserId)
                }
                travelSheetVisible = false
            },
            onOpenStore = {
                travelSheetVisible = false
                onOpenStore()
            },
            onOpenAgeVerification = {
                travelSheetVisible = false
                onOpenAgeVerification()
            },
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
            },
        )
    }
}
