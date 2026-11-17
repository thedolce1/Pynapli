package com.pynapli.app

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.activity.ActivityScreen
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.activity.toActivityProfileCardUi
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.conversation.ConversationScreen
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliEmptyState
import com.pynapli.app.design.PynapliTabIcons
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.closeOnEscape
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.grid.GridScreen
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.grid.SavedGridFilterStateHolder
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.location.TravelLocationStateHolder
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaAlbumsScreen
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.media.primaryOwnerProfilePhotoUrl
import com.pynapli.app.messages.MessagesScreen
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.onboarding.PhotoNudgeBanner
import com.pynapli.app.profile.ProfileDetailSheet
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilehub.ProfileHubScreen
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.store.StorePaywallFocus
import com.pynapli.app.swipe.SavedSwipeFilterStateHolder
import com.pynapli.app.swipe.SwipeScreen
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.app.tonight.PlayingTonightStateHolder
import com.pynapli.shared.network.dto.MatchDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Fixed inbox width in the Expanded two-pane messenger; the conversation takes the rest. */
private val MessagesInboxPaneWidth = 380.dp

/**
 * The primary in-tab content of [PynapliApp] (extracted from the App.kt shell): the profile-surface
 * dispatch (Hub / Media Albums) and the bottom-nav tab `when` (Grid / Messages / Activity / Swipe). Behavior-preserving — moved verbatim, with the shell's mutable navigation vars replaced by
 * setter callbacks ([onProfileSurface], [onOpenStore], [onOpenTonight], [onOpenPsync]). It collects
 * the per-screen UI state from the passed holders, so the shell no longer threads those values.
 */
@Composable
internal fun ColumnScope.PynapliMainContent(
    profileSurface: ProfileSurface,
    selectedTab: PynapliTab,
    gridStateHolder: GridStateHolder,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    messagesStateHolder: MessagesStateHolder,
    activityStateHolder: ActivityStateHolder,
    locationStateHolder: LocationStateHolder,
    mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    profileHubStateHolder: ProfileHubStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    swipeStateHolder: SwipeStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    authStateHolder: AuthStateHolder,
    savedGridFilterStateHolder: SavedGridFilterStateHolder?,
    savedSwipeFilterStateHolder: SavedSwipeFilterStateHolder?,
    travelLocationStateHolder: TravelLocationStateHolder?,
    playingTonightStateHolder: PlayingTonightStateHolder?,
    onboardingStateHolder: OnboardingStateHolder?,
    conversationActions: ConversationActions,
    scope: CoroutineScope,
    onProfileSurface: (ProfileSurface) -> Unit,
    // Opens the plans/store sheet on the section the paywall sells (there is no Store tab).
    onOpenStore: (StorePaywallFocus) -> Unit,
    onOpenAgeVerification: () -> Unit,
    onOpenTonight: () -> Unit,
    onOpenPsync: (() -> Unit)?,
    onOpenPsyncSession: ((String) -> Unit)? = null,
    onUseCurrentLocation: () -> Unit,
    onPickMediaForAlbum: (MediaAlbumKindUi) -> Unit,
    onPickConversationPhoto: () -> Unit,
    onSetDiscreetIcon: (Boolean) -> Unit,
    discreetIconSupported: Boolean = true,
) {
    // Pure dispatcher: it reads no state, so a state change only recomposes the child that reads it.
    // (A `when` branch is not its own recomposition scope; a composable function is.)
    when {
        profileSurface == ProfileSurface.Hub ->
            ProfileHubSurface(
                profileDetailStateHolder = profileDetailStateHolder,
                messagesStateHolder = messagesStateHolder,
                mediaAlbumsStateHolder = mediaAlbumsStateHolder,
                profileHubStateHolder = profileHubStateHolder,
                profileLinksStateHolder = profileLinksStateHolder,
                appSettingsStateHolder = appSettingsStateHolder,
                authStateHolder = authStateHolder,
                scope = scope,
                onProfileSurface = onProfileSurface,
                onOpenStore = { onOpenStore(StorePaywallFocus.Plans) },
                onSetDiscreetIcon = onSetDiscreetIcon,
                discreetIconSupported = discreetIconSupported,
                onOpenPsync = onOpenPsync,
            )
        profileSurface == ProfileSurface.MediaAlbums -> {
            MediaAlbumsScreen(
                stateHolder = mediaAlbumsStateHolder,
                profileLinksStateHolder = profileLinksStateHolder,
                onBack = { onProfileSurface(ProfileSurface.Hub) },
                onPickMedia = onPickMediaForAlbum,
                // Cap + center the album grid on wide windows so photo tiles keep a sane
                // size instead of three tiles stretched across a monitor (WEB_PWA_GAMEPLAN).
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = PynapliContentWidth.Feed)
                        .weight(1f)
                        .closeOnEscape { onProfileSurface(ProfileSurface.Hub) },
            )
        }
        else ->
            when (selectedTab) {
                PynapliTab.Grid ->
                    GridTabContent(
                        gridStateHolder = gridStateHolder,
                        profileDetailStateHolder = profileDetailStateHolder,
                        conversationStateHolder = conversationStateHolder,
                        locationStateHolder = locationStateHolder,
                        mediaAlbumsStateHolder = mediaAlbumsStateHolder,
                        profileHubStateHolder = profileHubStateHolder,
                        profileLinksStateHolder = profileLinksStateHolder,
                        appSettingsStateHolder = appSettingsStateHolder,
                        authStateHolder = authStateHolder,
                        savedGridFilterStateHolder = savedGridFilterStateHolder,
                        playingTonightStateHolder = playingTonightStateHolder,
                        onboardingStateHolder = onboardingStateHolder,
                        scope = scope,
                        onProfileSurface = onProfileSurface,
                        // Locked Grid filters and depth are plan perks.
                        onOpenStore = { onOpenStore(StorePaywallFocus.Plans) },
                        onOpenAgeVerification = onOpenAgeVerification,
                        onOpenTonight = onOpenTonight,
                        onUseCurrentLocation = onUseCurrentLocation,
                        travelLocationStateHolder = travelLocationStateHolder,
                    )
                PynapliTab.Messages ->
                    MessagesTabContent(
                        profileDetailStateHolder = profileDetailStateHolder,
                        conversationStateHolder = conversationStateHolder,
                        messagesStateHolder = messagesStateHolder,
                        mediaAlbumsStateHolder = mediaAlbumsStateHolder,
                        profileLinksStateHolder = profileLinksStateHolder,
                        authStateHolder = authStateHolder,
                        conversationActions = conversationActions,
                        scope = scope,
                        onProfileSurface = onProfileSurface,
                        onPickConversationPhoto = onPickConversationPhoto,
                    )
                PynapliTab.Activity ->
                    ActivityTabContent(
                        profileDetailStateHolder = profileDetailStateHolder,
                        conversationStateHolder = conversationStateHolder,
                        messagesStateHolder = messagesStateHolder,
                        activityStateHolder = activityStateHolder,
                        appSettingsStateHolder = appSettingsStateHolder,
                        authStateHolder = authStateHolder,
                        scope = scope,
                        // Likes You / Viewed You reveal is a plan perk.
                        onOpenStore = { onOpenStore(StorePaywallFocus.Plans) },
                        onOpenPsyncSession = onOpenPsyncSession,
                    )
                PynapliTab.Swipe ->
                    SwipeTabContent(
                        profileDetailStateHolder = profileDetailStateHolder,
                        conversationStateHolder = conversationStateHolder,
                        swipeStateHolder = swipeStateHolder,
                        appSettingsStateHolder = appSettingsStateHolder,
                        authStateHolder = authStateHolder,
                        savedSwipeFilterStateHolder = savedSwipeFilterStateHolder,
                        travelLocationStateHolder = travelLocationStateHolder,
                        onOpenStore = onOpenStore,
                        onOpenAgeVerification = onOpenAgeVerification,
                        onOpenPsync = onOpenPsync,
                        profileLinksStateHolder = profileLinksStateHolder,
                    )
            }
    }
}

/** Profile hub surface: collects only the hub's state, so other surfaces' updates don't recompose it. */
@Composable
private fun ColumnScope.ProfileHubSurface(
    profileDetailStateHolder: ProfileDetailStateHolder,
    messagesStateHolder: MessagesStateHolder,
    mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    profileHubStateHolder: ProfileHubStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    authStateHolder: AuthStateHolder,
    scope: CoroutineScope,
    onProfileSurface: (ProfileSurface) -> Unit,
    onOpenStore: () -> Unit,
    onSetDiscreetIcon: (Boolean) -> Unit,
    discreetIconSupported: Boolean,
    onOpenPsync: (() -> Unit)? = null,
) {
    val authState by authStateHolder.state.collectAsState()
    val messagesState by messagesStateHolder.state.collectAsState()
    val mediaAlbumsState by mediaAlbumsStateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder.state.collectAsState()
    val profileHubState by profileHubStateHolder.state.collectAsState()
    val profileDetailState by profileDetailStateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val profileImageUrl = mediaAlbumsState.primaryOwnerProfilePhotoUrl()
    val distanceUnit = appSettingsState.settings.distanceUnit

    ProfileHubScreen(
        stateHolder = profileHubStateHolder,
        appSettingsStateHolder = appSettingsStateHolder,
        mediaState = mediaAlbumsState,
        profileLinksState = profileLinksState,
        messagesState = messagesState,
        viewerUserId = authState.session?.userId,
        profileImageUrl = profileImageUrl,
        onBack = { onProfileSurface(ProfileSurface.None) },
        onOpenAlbums = { onProfileSurface(ProfileSurface.MediaAlbums) },
        onAcceptProfileLinkInvite = { linkId ->
            scope.launch {
                profileLinksStateHolder.acceptInvitation(linkId)
                profileHubStateHolder.load()
            }
        },
        onDeclineProfileLinkInvite = { linkId ->
            scope.launch {
                profileLinksStateHolder.declineInvitation(linkId)
            }
        },
        onUnlinkProfileLink = {
            scope.launch {
                profileLinksStateHolder.unlinkCurrent()
                profileHubStateHolder.load()
            }
        },
        onAcceptPartnerChatInvite = { conversationId ->
            scope.launch {
                messagesStateHolder.acceptPartnerInvite(conversationId)
            }
        },
        onDeclinePartnerChatInvite = { conversationId ->
            scope.launch {
                messagesStateHolder.declinePartnerInvite(conversationId, authState.session?.userId)
            }
        },
        onClearProfileLinkMessages = profileLinksStateHolder::clearMessages,
        onClearMessagesError = messagesStateHolder::clearError,
        onLogout = {
            onProfileSurface(ProfileSurface.None)
            scope.launch { authStateHolder.logout() }
        },
        // The store sheet opens over the hub, so closing it returns here.
        onOpenStore = onOpenStore,
        onPreviewProfile = {
            authState.session?.userId?.let { userId ->
                scope.launch {
                    profileDetailStateHolder.openOwnPreview(
                        userId = userId,
                        displayName = profileHubState.me?.profile?.displayName,
                        profilePhotoUrl = profileImageUrl,
                    )
                }
            }
        },
        onSetDiscreetIcon = onSetDiscreetIcon,
        discreetIconSupported = discreetIconSupported,
        onStartPsync =
            onOpenPsync?.let { open ->
                {
                    onProfileSurface(ProfileSurface.None)
                    open()
                }
            },
        // Cap + center the settings column on wide windows so it reads as an
        // intentional column instead of stretching edge-to-edge (WEB_PWA_GAMEPLAN).
        modifier =
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = PynapliContentWidth.Feed)
                .weight(1f)
                .closeOnEscape { onProfileSurface(ProfileSurface.None) },
    )

    // Own-profile preview ("Preview profile" row) opens the read-only public-profile peek over
    // the hub. The peek hides every outbound action when isOwnProfile, so the callbacks are no-ops.
    if (profileDetailState.isOwnProfile && profileDetailState.visible) {
        ProfileDetailSheet(
            state = profileDetailState,
            onDismiss = profileDetailStateHolder::dismiss,
            onMessage = {},
            onSpark = {},
            onRequestAlbum = {},
            onViewPrivateAlbum = {},
            onHide = {},
            onBlock = {},
            onReport = { _, _ -> },
            distanceUnit = distanceUnit,
        )
    }
}

/** Grid tab: its own recomposition scope, so conversation/messages updates elsewhere don't re-run it. */
@Composable
private fun ColumnScope.GridTabContent(
    gridStateHolder: GridStateHolder,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    locationStateHolder: LocationStateHolder,
    mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    profileHubStateHolder: ProfileHubStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    authStateHolder: AuthStateHolder,
    savedGridFilterStateHolder: SavedGridFilterStateHolder?,
    playingTonightStateHolder: PlayingTonightStateHolder?,
    onboardingStateHolder: OnboardingStateHolder?,
    scope: CoroutineScope,
    onProfileSurface: (ProfileSurface) -> Unit,
    onOpenStore: () -> Unit,
    onOpenAgeVerification: () -> Unit,
    onOpenTonight: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    travelLocationStateHolder: TravelLocationStateHolder? = null,
) {
    val authState by authStateHolder.state.collectAsState()
    val locationState by locationStateHolder.state.collectAsState()
    val mediaAlbumsState by mediaAlbumsStateHolder.state.collectAsState()
    val profileHubState by profileHubStateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val profileImageUrl = mediaAlbumsState.primaryOwnerProfilePhotoUrl()
    val distanceUnit = appSettingsState.settings.distanceUnit

    // Bounded add-a-photo nudge (ONBOARDING_AUTH_SPEC): only over the
    // grid, only while the profile has no photo, capped/dismissible via
    // the onboarding settings counters.
    val onboardingState = onboardingStateHolder?.state?.collectAsState()?.value
    val showPhotoNudge =
        onboardingState?.photoNudgeEligible == true &&
            !onboardingState.active &&
            profileImageUrl == null &&
            !mediaAlbumsState.isLoading
    if (onboardingStateHolder != null && showPhotoNudge) {
        PhotoNudgeBanner(
            onAddPhoto = { onProfileSurface(ProfileSurface.MediaAlbums) },
            onDismiss = { scope.launch { onboardingStateHolder.dismissPhotoNudge() } },
        )
        LaunchedEffect(onboardingStateHolder) {
            onboardingStateHolder.recordPhotoNudgeShown()
        }
    }
    GridScreen(
        // The banner already consumed the status-bar inset when shown;
        // without this the grid header would double-pad below it.
        modifier =
            if (showPhotoNudge) {
                Modifier
                    .weight(1f)
                    .consumeWindowInsets(WindowInsets.statusBars)
            } else {
                Modifier.weight(1f)
            },
        stateHolder = gridStateHolder,
        profileDetailStateHolder = profileDetailStateHolder,
        conversationStateHolder = conversationStateHolder,
        viewerUserId = authState.session?.userId,
        locationState = locationState,
        profileImageUrl = profileImageUrl,
        profileLinksStateHolder = profileLinksStateHolder,
        savedGridFilterStateHolder = savedGridFilterStateHolder,
        playingTonightStateHolder = playingTonightStateHolder,
        lifestyleFiltersAllowed = profileHubState.me?.profile?.ageVerified == true,
        distanceUnit = distanceUnit,
        onUseCurrentLocation = onUseCurrentLocation,
        onProfileIconClick = { onProfileSurface(ProfileSurface.Hub) },
        onOpenStore = onOpenStore,
        onOpenAgeVerification = onOpenAgeVerification,
        onOpenTonight = onOpenTonight,
        travelLocationStateHolder = travelLocationStateHolder,
    )
}

/** Messages tab: per-keystroke conversation draft updates recompose only this tab. */
@Composable
private fun ColumnScope.MessagesTabContent(
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    messagesStateHolder: MessagesStateHolder,
    mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    authStateHolder: AuthStateHolder,
    conversationActions: ConversationActions,
    scope: CoroutineScope,
    onProfileSurface: (ProfileSurface) -> Unit,
    onPickConversationPhoto: () -> Unit,
) {
    val authState by authStateHolder.state.collectAsState()
    val conversationState by conversationStateHolder.state.collectAsState()
    val mediaAlbumsState by mediaAlbumsStateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder.state.collectAsState()
    val profileImageUrl = mediaAlbumsState.primaryOwnerProfilePhotoUrl()

    val inboxPane: @Composable (Modifier) -> Unit = { paneModifier ->
        MessagesScreen(
            modifier = paneModifier,
            stateHolder = messagesStateHolder,
            viewerUserId = authState.session?.userId,
            profileImageUrl = profileImageUrl,
            onProfileIconClick = { onProfileSurface(ProfileSurface.Hub) },
            onThreadSelected = { conversation ->
                messagesStateHolder.markConversationReadLocal(conversation.conversationId)
                scope.launch {
                    conversationStateHolder.openConversation(
                        conversation = conversation,
                        viewerUserId = authState.session?.userId,
                    )
                }
            },
        )
    }
    val conversationPane: @Composable (Modifier) -> Unit = { paneModifier ->
        ConversationScreen(
            modifier = paneModifier,
            state = conversationState,
            onBack = conversationActions.dismiss,
            onProfileIconClick = { onProfileSurface(ProfileSurface.Hub) },
            onOpenParticipantProfile = { participant ->
                scope.launch {
                    profileDetailStateHolder.open(participant.toConversationProfileCardUi())
                }
            },
            onDraftChanged = conversationActions.onDraftChanged,
            onSend = conversationActions.onSend,
            onLoadOlder = conversationActions.onLoadOlder,
            linkedPartnerUserId = profileLinksState.current?.otherUserId,
            onInvitePartner = conversationActions.onInvitePartner,
            onAcceptInvite = conversationActions.onAcceptInvite,
            onDeclineInvite = conversationActions.onDeclineInvite,
            onLeaveConversation = conversationActions.onLeave,
            onDeleteMessage = conversationActions.onDeleteMessage,
            onReportMessage = conversationActions.onReportMessage,
            onOpenStore = conversationActions.onOpenStore,
            onSetExpiry = conversationActions.onSetExpiry,
            onRevealMedia = conversationActions.onRevealMedia,
            onPickPhoto = onPickConversationPhoto,
        )
    }

    if (currentPynapliWindowSizeClass() == PynapliWindowSizeClass.Expanded) {
        // Two-pane messenger (WEB_PWA_GAMEPLAN.md W3.1): the inbox (with its
        // New Matches rail) stays visible while a conversation is open.
        Row(modifier = Modifier.weight(1f)) {
            inboxPane(
                Modifier
                    .width(MessagesInboxPaneWidth)
                    .fillMaxHeight(),
            )
            VerticalDivider(thickness = 1.dp, color = PynapliColors.BorderSoft)
            if (conversationState.visible) {
                conversationPane(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            } else {
                PynapliEmptyState(
                    icon = PynapliTabIcons.Messages.unselected,
                    title = "Your messages",
                    subtitle = "Pick a conversation from the inbox to read it here.",
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                )
            }
        }
    } else if (conversationState.visible) {
        conversationPane(Modifier.weight(1f))
    } else {
        inboxPane(Modifier.weight(1f))
    }
}

/** Activity tab. */
@Composable
private fun ColumnScope.ActivityTabContent(
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    messagesStateHolder: MessagesStateHolder,
    activityStateHolder: ActivityStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    authStateHolder: AuthStateHolder,
    scope: CoroutineScope,
    onOpenStore: () -> Unit,
    onOpenPsyncSession: ((String) -> Unit)?,
) {
    val authState by authStateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val distanceUnit = appSettingsState.settings.distanceUnit
    val profileDetailState by profileDetailStateHolder.state.collectAsState()

    // Blocking or hiding someone from a profile opened here closes the sheet and refetches the feed,
    // so their cards leave Activity straight away.
    LaunchedEffect(profileDetailState.blockedTargetUserId, profileDetailState.hiddenTargetUserId) {
        val removed =
            profileDetailStateHolder.consumeBlockedTargetUserId()
                ?: profileDetailStateHolder.consumeHiddenTargetUserId()
                ?: return@LaunchedEffect
        if (removed.isNotEmpty()) {
            profileDetailStateHolder.dismiss()
            activityStateHolder.load()
        }
    }

    ActivityScreen(
        modifier = Modifier.weight(1f),
        stateHolder = activityStateHolder,
        distanceUnit = distanceUnit,
        onOpenStore = onOpenStore,
        onOpenMatch = { event ->
            scope.launch {
                conversationStateHolder.openSwipeMatch(
                    match =
                        MatchDto(
                            matchId = event.matchId ?: event.eventId,
                            matchedUserId = event.actorUserId,
                            matchedDisplayName = event.actorDisplayName,
                            matchedPhotoUrl = event.actorPhotoUrl,
                            createdAt = event.occurredAt,
                        ),
                    viewerUserId = authState.session?.userId,
                )
                messagesStateHolder.refreshSilently()
            }
        },
        onOpenPartnerChatInvite = { conversationId ->
            scope.launch {
                conversationStateHolder.openConversationById(
                    conversationId = conversationId,
                    viewerUserId = authState.session?.userId,
                    title = "Chat invite",
                )
                messagesStateHolder.refreshSilently()
            }
        },
        onOpenProfile = { event ->
            scope.launch { profileDetailStateHolder.open(event.toActivityProfileCardUi()) }
        },
        onOpenPsync = { sessionId -> onOpenPsyncSession?.invoke(sessionId) },
    )
}

/** Swipe tab. */
@Composable
private fun ColumnScope.SwipeTabContent(
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    swipeStateHolder: SwipeStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    authStateHolder: AuthStateHolder,
    savedSwipeFilterStateHolder: SavedSwipeFilterStateHolder?,
    travelLocationStateHolder: TravelLocationStateHolder?,
    onOpenStore: (StorePaywallFocus) -> Unit,
    onOpenAgeVerification: () -> Unit,
    onOpenPsync: (() -> Unit)?,
    profileLinksStateHolder: ProfileLinksStateHolder? = null,
) {
    val authState by authStateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val distanceUnit = appSettingsState.settings.distanceUnit

    SwipeScreen(
        modifier = Modifier.weight(1f),
        stateHolder = swipeStateHolder,
        profileDetailStateHolder = profileDetailStateHolder,
        conversationStateHolder = conversationStateHolder,
        viewerUserId = authState.session?.userId,
        distanceUnit = distanceUnit,
        matchNotificationsEnabled = appSettingsState.settings.matchNotifications,
        notificationDetail = appSettingsState.settings.notificationDetail,
        onOpenStore = onOpenStore,
        onOpenAgeVerification = onOpenAgeVerification,
        onOpenPsync = onOpenPsync,
        savedSwipeFilterStateHolder = savedSwipeFilterStateHolder,
        travelLocationStateHolder = travelLocationStateHolder,
        profileLinksStateHolder = profileLinksStateHolder,
    )
}
