package com.pynapli.app

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.pynapli.app.activity.ActivityScreen
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.activity.toActivityProfileCardUi
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.conversation.ConversationScreen
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.grid.GridScreen
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.grid.SavedGridFilterStateHolder
import com.pynapli.app.grid.TonightAvailabilityStateHolder
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
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilehub.ProfileHubScreen
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.store.StoreScreen
import com.pynapli.app.store.StoreStateHolder
import com.pynapli.app.swipe.SavedSwipeFilterStateHolder
import com.pynapli.app.swipe.SwipeScreen
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.shared.network.dto.MatchDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The primary in-tab content of [PynapliApp] (extracted from the App.kt shell): the profile-surface
 * dispatch (Hub / Media Albums) and the bottom-nav tab `when` (Grid / Messages / Activity / Swipe /
 * Store). Behavior-preserving — moved verbatim, with the shell's mutable navigation vars replaced by
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
    storeStateHolder: StoreStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    authStateHolder: AuthStateHolder,
    savedGridFilterStateHolder: SavedGridFilterStateHolder?,
    savedSwipeFilterStateHolder: SavedSwipeFilterStateHolder?,
    travelLocationStateHolder: TravelLocationStateHolder?,
    tonightAvailabilityStateHolder: TonightAvailabilityStateHolder?,
    onboardingStateHolder: OnboardingStateHolder?,
    conversationActions: ConversationActions,
    scope: CoroutineScope,
    onProfileSurface: (ProfileSurface) -> Unit,
    onOpenStore: () -> Unit,
    onOpenAgeVerification: () -> Unit,
    onOpenTonight: () -> Unit,
    onOpenPsync: (() -> Unit)?,
    onUseCurrentLocation: () -> Unit,
    onPickMediaForAlbum: (MediaAlbumKindUi) -> Unit,
    onPickConversationPhoto: () -> Unit,
    onSetDiscreetIcon: (Boolean) -> Unit,
) {
    val authState by authStateHolder.state.collectAsState()
    val locationState by locationStateHolder.state.collectAsState()
    val conversationState by conversationStateHolder.state.collectAsState()
    val messagesState by messagesStateHolder.state.collectAsState()
    val mediaAlbumsState by mediaAlbumsStateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder.state.collectAsState()
    val profileHubState by profileHubStateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()

    val profileImageUrl = mediaAlbumsState.primaryOwnerProfilePhotoUrl()
    val distanceUnit = appSettingsState.settings.distanceUnit

    if (profileSurface == ProfileSurface.Hub) {
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
            onOpenStore = {
                onProfileSurface(ProfileSurface.None)
                onOpenStore()
            },
            onSetDiscreetIcon = onSetDiscreetIcon,
            modifier = Modifier.weight(1f),
        )
    } else if (profileSurface == ProfileSurface.MediaAlbums) {
        MediaAlbumsScreen(
            stateHolder = mediaAlbumsStateHolder,
            profileLinksStateHolder = profileLinksStateHolder,
            onBack = { onProfileSurface(ProfileSurface.Hub) },
            onPickMedia = onPickMediaForAlbum,
            modifier = Modifier.weight(1f),
        )
    } else {
        when (selectedTab) {
            PynapliTab.Grid -> {
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
                    tonightAvailabilityStateHolder = tonightAvailabilityStateHolder,
                    lifestyleFiltersAllowed = profileHubState.me?.profile?.ageVerified == true,
                    distanceUnit = distanceUnit,
                    onUseCurrentLocation = onUseCurrentLocation,
                    onProfileIconClick = { onProfileSurface(ProfileSurface.Hub) },
                    onOpenStore = onOpenStore,
                    onOpenAgeVerification = onOpenAgeVerification,
                    onOpenTonight = onOpenTonight,
                )
            }

            PynapliTab.Messages -> {
                if (conversationState.visible) {
                    ConversationScreen(
                        modifier = Modifier.weight(1f),
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
                        onSetExpiry = conversationActions.onSetExpiry,
                        onRevealMedia = conversationActions.onRevealMedia,
                        onPickPhoto = onPickConversationPhoto,
                    )
                } else {
                    MessagesScreen(
                        modifier = Modifier.weight(1f),
                        stateHolder = messagesStateHolder,
                        viewerUserId = authState.session?.userId,
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
            }

            PynapliTab.Activity ->
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
                )

            PynapliTab.Swipe ->
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
                )

            PynapliTab.Store ->
                StoreScreen(
                    modifier = Modifier.weight(1f),
                    stateHolder = storeStateHolder,
                )
        }
    }
}
