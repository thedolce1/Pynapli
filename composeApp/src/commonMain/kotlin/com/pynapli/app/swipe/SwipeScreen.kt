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
import com.pynapli.app.grid.profileLinkActionFor
import com.pynapli.app.grid.toUi
import com.pynapli.app.match.MatchCelebrationScreen
import com.pynapli.app.match.MatchCelebrationUi
import com.pynapli.app.profile.ProfileDetailActionMode
import com.pynapli.app.profile.ProfileDetailSheet
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.store.StorePaywallFocus
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
    // Opens the plans/store sheet on the section this paywall sells.
    onOpenStore: (StorePaywallFocus) -> Unit = {},
    onOpenAgeVerification: () -> Unit = {},
    onOpenPsync: (() -> Unit)? = null,
    savedSwipeFilterStateHolder: SavedSwipeFilterStateHolder? = null,
    travelLocationStateHolder: com.pynapli.app.location.TravelLocationStateHolder? = null,
    modifier: Modifier = Modifier,
    profileLinksStateHolder: com.pynapli.app.profilelink.ProfileLinksStateHolder? = null,
    // The app-wide match moment is over the deck.
    deckCovered: Boolean = false,
) {
    val state by stateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(null) }
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
    // The Pineapple Like sheet (optional note) for the top card, and the note riding on the swipe-up
    // animation it starts, keyed to that card so a different card's swipe-up never picks it up.
    var pineappleSheetCard by remember { mutableStateOf<SwipeCardDto?>(null) }
    var pineappleNoteText by remember { mutableStateOf("") }
    var pendingPineappleNote by remember { mutableStateOf<Pair<String, String>?>(null) }
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
        onOpenStore = {
            onOpenStore(
                if (state.errorCode == "PINEAPPLE_LIKE_INVENTORY_REQUIRED") StorePaywallFocus.PineappleLikes else StorePaywallFocus.Plans,
            )
        },
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
        onSwiped = { card, action ->
            if (action == SwipeAction.PINEAPPLE_LIKE) {
                val note = pendingPineappleNote?.takeIf { it.first == card.userId }?.second
                pendingPineappleNote = null
                scope.launch { stateHolder.submitPineappleLike(card, note) }
            } else {
                scope.launch { stateHolder.submitAction(card, action) }
            }
        },
        onRetryPendingActions = { scope.launch { stateHolder.retryPendingActions() } },
        onRewind = { scope.launch { stateHolder.rewind() } },
        onPass = { requestedAction = SwipeAction.PASS },
        onPineappleLike = {
            state.topCard?.let { card ->
                pineappleSheetCard = card
                pineappleNoteText = ""
            }
        },
        onLike = { requestedAction = SwipeAction.LIKE },
        modifier = modifier,
        deckCovered =
            deckCovered ||
                (matchNotificationsEnabled && state.pendingMatch != null) ||
                profileState.visible ||
                pineappleSheetCard != null ||
                state.noteRefusedCard != null ||
                linkedPartnerPreviewCard != null ||
                filtersSheetVisible ||
                travelSheetVisible,
    )

    // Only when the app has no app-wide match moment wired (SwipeStateHolder without onMatch, e.g. a
    // standalone screen): the app-level MatchCelebrationScreen covers both people otherwise.
    if (matchNotificationsEnabled) {
        state.pendingMatch?.let { match ->
            MatchCelebrationScreen(
                celebration =
                    MatchCelebrationUi(
                        matchIds = listOf(match.matchId),
                        matchedUserId = match.matchedUserId,
                        matchedDisplayName = match.matchedDisplayName,
                        matchedPhotoUrl = match.matchedPhotoUrl,
                        viewerPhotoUrl = null,
                        createdAt = match.createdAt,
                    ),
                showNames = notificationDetail == AppSettings.NOTIFICATION_STANDARD,
                onKeepSwiping = stateHolder::dismissMatch,
                onSendMessage = {
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
            onReportAlbum = { albumId, category -> scope.launch { profileDetailStateHolder.submitAlbumReport(albumId, category) } },
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
            // Someone may find their partner in the deck: offer the link invite here too.
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
            onOpenStore = { onOpenStore(StorePaywallFocus.Plans) },
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
        LaunchedEffect(Unit) { travelLocationStateHolder?.loadTrips() }
        TravelModeSheet(
            state = travelLocationState,
            onDismiss = { travelSheetVisible = false },
            onSearchQueryChange = { query -> travelLocationStateHolder?.updateSearchQuery(query) },
            onRunSearch = {
                scope.launch { travelLocationStateHolder?.runSearch() }
            },
            onPickPlace = { place ->
                scope.launch {
                    // Close only once the trip is on: a refusal (plan, verification, region, switch
                    // limit) stays in the sheet with its reason instead of silently reloading home.
                    if (travelLocationStateHolder?.setActive(place) == true) {
                        travelSheetVisible = false
                        // Refresh the deck so the new travel location takes effect
                        // immediately rather than waiting for the next manual reload.
                        stateHolder.load(ownerUserId = viewerUserId)
                    }
                }
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
                // Travel mode is a Select perk.
                onOpenStore(StorePaywallFocus.Select)
            },
            onSelectPlace = { place -> travelLocationStateHolder?.selectPlace(place) },
            onPlanTrip = { place, startsOn, endsOn, announce ->
                scope.launch {
                    val zone =
                        kotlinx.datetime.TimeZone
                            .currentSystemDefault()
                            .id
                    if (travelLocationStateHolder?.planTrip(place, startsOn, endsOn, zone, announce) == true) {
                        // A trip starting today is already on: refresh the deck for the new place.
                        stateHolder.load(ownerUserId = viewerUserId)
                    }
                }
            },
            onCancelTrip = { tripId ->
                scope.launch {
                    travelLocationStateHolder?.cancelTrip(tripId)
                    stateHolder.load(ownerUserId = viewerUserId)
                }
            },
            onOpenAgeVerification = {
                travelSheetVisible = false
                onOpenAgeVerification()
            },
        )
    }

    pineappleSheetCard?.let { card ->
        PineappleLikeSheet(
            displayName = card.displayName,
            noteText = pineappleNoteText,
            notesRefused = false,
            isSubmitting = false,
            onNoteTextChanged = { text -> pineappleNoteText = text.take(PINEAPPLE_NOTE_INPUT_MAX_LENGTH) },
            onDismiss = {
                pineappleSheetCard = null
                pineappleNoteText = ""
            },
            onSend = {
                // Same swipe-up as the gesture; the note rides along to onSwiped for this card.
                pendingPineappleNote = card.userId to pineappleNoteText
                pineappleSheetCard = null
                pineappleNoteText = ""
                requestedAction = SwipeAction.PINEAPPLE_LIKE
            },
        )
    }

    // The recipient doesn't take notes: the card is back on top, and the same sheet offers the
    // Pineapple Like on its own. Dismissing leaves the card to decide on again.
    state.noteRefusedCard?.let { card ->
        PineappleLikeSheet(
            displayName = card.displayName,
            noteText = "",
            notesRefused = true,
            isSubmitting = state.isSubmitting,
            onNoteTextChanged = {},
            onDismiss = stateHolder::dismissNoteRefusal,
            onSend = { scope.launch { stateHolder.sendPineappleLikeWithoutNote() } },
        )
    }
}
