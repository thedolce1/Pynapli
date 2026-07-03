package com.pynapli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pynapli.app.activity.ActivityScreen
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthScreen
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.conversation.ConversationScreen
import com.pynapli.app.conversation.ConversationSheet
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.grid.GridScreen
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaAlbumsScreen
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.media.PhotoCropOverlay
import com.pynapli.app.media.primaryOwnerProfilePhotoUrl
import com.pynapli.app.messages.MessagesScreen
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.onboarding.OnboardingFlowScreen
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilehub.ProfileHubScreen
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.store.StoreScreen
import com.pynapli.app.store.StoreStateHolder
import com.pynapli.app.swipe.SwipeScreen
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.shared.network.dto.MatchDto
import kotlinx.coroutines.launch

@Composable
fun PynapliApp(
    authStateHolder: AuthStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
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
    savedGridFilterStateHolder: com.pynapli.app.grid.SavedGridFilterStateHolder? = null,
    savedSwipeFilterStateHolder: com.pynapli.app.swipe.SavedSwipeFilterStateHolder? = null,
    travelLocationStateHolder: com.pynapli.app.location.TravelLocationStateHolder? = null,
    tonightAvailabilityStateHolder: com.pynapli.app.grid.TonightAvailabilityStateHolder? = null,
    playingTonightStateHolder: com.pynapli.app.tonight.PlayingTonightStateHolder? = null,
    psyncRepository: com.pynapli.app.psync.PsyncRepository? = null,
    psyncStreamClient: com.pynapli.shared.network.PsyncStreamClient? = null,
    deepLinkStateHolder: AppDeepLinkStateHolder? = null,
    onboardingStateHolder: OnboardingStateHolder? = null,
    onUseCurrentLocation: () -> Unit,
    onPickMediaForAlbum: (MediaAlbumKindUi) -> Unit = {},
    onPickConversationPhoto: () -> Unit = {},
    onSetDiscreetIcon: (Boolean) -> Unit = {},
    devLoginCredentials: DevLoginCredentials? = null,
    testerDiagnosticLabel: String? = null,
    // Browser URL sync (WEB_PWA_GAMEPLAN.md W1.2): the web shell mirrors the canonical
    // route into the address bar; other platforms leave this null.
    onNavigationRouteChanged: ((String) -> Unit)? = null,
) {
    val appSettingsState by appSettingsStateHolder.state.collectAsState()

    LaunchedEffect(appSettingsStateHolder) {
        appSettingsStateHolder.load()
    }

    PynapliTheme(themeMode = appSettingsState.settings.themeMode) {
        val authState by authStateHolder.state.collectAsState()
        val conversationState by conversationStateHolder.state.collectAsState()
        val messagesState by messagesStateHolder.state.collectAsState()
        val activityState by activityStateHolder.state.collectAsState()
        val profileDetailState by profileDetailStateHolder.state.collectAsState()
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableStateOf(PynapliTab.Grid) }
        var profileSurface by remember { mutableStateOf(ProfileSurface.None) }
        var tonightVisible by remember { mutableStateOf(false) }
        var psyncVisible by remember { mutableStateOf(false) }
        // Built per signed-in viewer so session mapping (isInitiator/partner) is correct.
        val psyncStateHolder =
            remember(authState.session?.userId, psyncRepository) {
                psyncRepository?.let { repo ->
                    com.pynapli.app.psync
                        .PsyncStateHolder(repo, authState.session?.userId)
                }
            }
        var activeNotification by remember { mutableStateOf<InAppNotificationUi?>(null) }
        PynapliAppEffects(
            authStateHolder = authStateHolder,
            appSettingsStateHolder = appSettingsStateHolder,
            messagesStateHolder = messagesStateHolder,
            activityStateHolder = activityStateHolder,
            conversationStateHolder = conversationStateHolder,
            mediaAlbumsStateHolder = mediaAlbumsStateHolder,
            profileHubStateHolder = profileHubStateHolder,
            profileLinksStateHolder = profileLinksStateHolder,
            deepLinkStateHolder = deepLinkStateHolder,
            activeNotification = activeNotification,
            onNotification = { activeNotification = it },
            onSelectTab = { selectedTab = it },
            onClearProfileSurface = { profileSurface = ProfileSurface.None },
            onOpenProfileHub = { profileSurface = ProfileSurface.Hub },
        )

        // Report the canonical route for the current navigation state. Composed before
        // the early returns below so it fires for auth/onboarding states too; skipped
        // while the session restore is settling so the address bar never flashes /login.
        if (onNavigationRouteChanged != null && !authState.isCheckingSession) {
            val onboardingActiveForRoute =
                onboardingStateHolder
                    ?.state
                    ?.collectAsState()
                    ?.value
                    ?.active == true
            val openConversationId =
                if (conversationState.visible) {
                    conversationState.detail
                        ?.conversation
                        ?.conversationId
                } else {
                    null
                }
            val route =
                pynapliRouteFor(
                    authenticated = authState.authenticated,
                    onboardingActive = onboardingActiveForRoute,
                    tab = selectedTab,
                    profileSurface = profileSurface,
                    conversationId = openConversationId,
                )
            LaunchedEffect(route) { onNavigationRouteChanged(route) }
        }

        // Once the session is settled, re-enter an interrupted signup stepper and
        // decide this session's add-a-photo nudge (ONBOARDING_AUTH_SPEC).
        LaunchedEffect(authState.isCheckingSession, authState.authenticated, onboardingStateHolder) {
            if (!authState.isCheckingSession && authState.authenticated) {
                onboardingStateHolder?.onSessionReady()
            }
        }

        if (authState.isCheckingSession) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
            ) {
                Text(
                    text = "Pynapli",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            return@PynapliTheme
        }

        // The signup stepper owns the screen while active: pre-auth steps (DOB,
        // underage stop, account) replace AuthScreen; post-auth steps (location,
        // name, photo) run before the main surfaces appear.
        val onboardingState = onboardingStateHolder?.state?.collectAsState()?.value
        if (onboardingStateHolder != null && onboardingState != null && onboardingState.active) {
            val locationState by locationStateHolder.state.collectAsState()
            val mediaAlbumsState by mediaAlbumsStateHolder.state.collectAsState()
            OnboardingFlowScreen(
                state = onboardingState,
                locationState = locationState,
                mediaState = mediaAlbumsState,
                onDobDayChanged = onboardingStateHolder::updateDobDay,
                onDobMonthChanged = onboardingStateHolder::updateDobMonth,
                onDobYearChanged = onboardingStateHolder::updateDobYear,
                onConfirmDob = onboardingStateHolder::confirmDob,
                onBackToDob = onboardingStateHolder::backToDob,
                onCancelToSignIn = onboardingStateHolder::cancelToSignIn,
                onAcceptPolicies = onboardingStateHolder::acceptPolicies,
                onBackToPolicies = onboardingStateHolder::backToPolicies,
                onEmailChanged = onboardingStateHolder::updateEmail,
                onPasswordChanged = onboardingStateHolder::updatePassword,
                onSubmitAccount = { scope.launch { onboardingStateHolder.submitAccount() } },
                onShareLocation = onUseCurrentLocation,
                onContinueFromLocation = onboardingStateHolder::continueFromLocation,
                onDisplayNameChanged = onboardingStateHolder::updateDisplayName,
                onPickPhoto = { onPickMediaForAlbum(MediaAlbumKindUi.Profile) },
                onFinishProfile = { scope.launch { onboardingStateHolder.finishProfile() } },
                testerDiagnosticLabel = testerDiagnosticLabel,
            )
            // Composed after the stepper so the crop step draws above it; the main
            // shell hosts its own copy because this branch returns early.
            PhotoCropOverlay(mediaAlbumsStateHolder, scope)
            return@PynapliTheme
        }

        if (!authState.authenticated) {
            AuthScreen(
                state = authState,
                onEmailChanged = authStateHolder::updateEmail,
                onPasswordChanged = authStateHolder::updatePassword,
                onBirthdateChanged = authStateHolder::updateBirthdate,
                onAcceptedAgeAndTermsChanged = authStateHolder::updateAcceptedAgeAndTerms,
                onModeSelected = authStateHolder::switchMode,
                onSubmit = {
                    scope.launch { authStateHolder.submit() }
                },
                onDevLogin =
                    devLoginCredentials?.let { credentials ->
                        { scope.launch { authStateHolder.submitDevLogin(credentials) } }
                    },
                onStartCreateAccount =
                    onboardingStateHolder?.let { holder ->
                        { holder.startCreateAccount() }
                    },
                testerDiagnosticLabel = testerDiagnosticLabel,
            )
            return@PynapliTheme
        }

        val conversationActions =
            rememberConversationActions(
                scope = scope,
                conversationStateHolder = conversationStateHolder,
                messagesStateHolder = messagesStateHolder,
            )

        LaunchedEffect(selectedTab, profileDetailState.blockedTargetUserId) {
            if (selectedTab != PynapliTab.Messages) return@LaunchedEffect
            val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
            profileDetailStateHolder.dismiss()
            if (conversationState.detail
                    ?.conversation
                    ?.participants
                    ?.any { participant -> participant.userId == blockedId } == true
            ) {
                conversationActions.dismiss()
            }
            messagesStateHolder.refreshSilently()
        }

        // Adaptive shell (WEB_PWA_GAMEPLAN.md): Compact keeps the proven phone layout
        // untouched (bottom bar); Medium+ swaps it for a navigation rail so wide
        // windows (web/desktop/tablet) never render a stretched phone chrome.
        val windowSizeClass = currentPynapliWindowSizeClass()
        val onNavTabSelected: (PynapliTab) -> Unit = { tab ->
            if (selectedTab == PynapliTab.Messages && tab != PynapliTab.Messages && conversationState.visible) {
                conversationActions.dismiss()
            }
            profileSurface = ProfileSurface.None
            selectedTab = tab
        }
        val mainContent: @Composable ColumnScope.() -> Unit = {
            PynapliMainContent(
                profileSurface = profileSurface,
                selectedTab = selectedTab,
                gridStateHolder = gridStateHolder,
                profileDetailStateHolder = profileDetailStateHolder,
                conversationStateHolder = conversationStateHolder,
                messagesStateHolder = messagesStateHolder,
                activityStateHolder = activityStateHolder,
                locationStateHolder = locationStateHolder,
                mediaAlbumsStateHolder = mediaAlbumsStateHolder,
                profileHubStateHolder = profileHubStateHolder,
                profileLinksStateHolder = profileLinksStateHolder,
                swipeStateHolder = swipeStateHolder,
                storeStateHolder = storeStateHolder,
                appSettingsStateHolder = appSettingsStateHolder,
                authStateHolder = authStateHolder,
                savedGridFilterStateHolder = savedGridFilterStateHolder,
                savedSwipeFilterStateHolder = savedSwipeFilterStateHolder,
                travelLocationStateHolder = travelLocationStateHolder,
                tonightAvailabilityStateHolder = tonightAvailabilityStateHolder,
                onboardingStateHolder = onboardingStateHolder,
                conversationActions = conversationActions,
                scope = scope,
                onProfileSurface = { profileSurface = it },
                onOpenStore = { selectedTab = PynapliTab.Store },
                onOpenAgeVerification = {
                    // Route a gated AGE_VERIFICATION_REQUIRED prompt into the existing
                    // Profile-Hub age-verification flow: show the Hub surface and open its dialog.
                    profileSurface = ProfileSurface.Hub
                    profileHubStateHolder.openAgeVerification()
                },
                onOpenTonight = { tonightVisible = true },
                onOpenPsync = psyncStateHolder?.let { { psyncVisible = true } },
                onUseCurrentLocation = onUseCurrentLocation,
                onPickMediaForAlbum = onPickMediaForAlbum,
                onPickConversationPhoto = onPickConversationPhoto,
                onSetDiscreetIcon = onSetDiscreetIcon,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            if (windowSizeClass == PynapliWindowSizeClass.Compact) {
                Column(modifier = Modifier.fillMaxSize()) {
                    mainContent()

                    PynapliBottomNavigation(
                        selectedTab = selectedTab,
                        unreadMessages = messagesState.unreadCount,
                        activityBadgeCount = activityState.activityBadgeCount,
                        onTabSelected = onNavTabSelected,
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    PynapliNavigationRail(
                        selectedTab = selectedTab,
                        unreadMessages = messagesState.unreadCount,
                        activityBadgeCount = activityState.activityBadgeCount,
                        onTabSelected = onNavTabSelected,
                    )
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    ) {
                        mainContent()
                    }
                }
            }

            activeNotification?.let { notification ->
                InAppNotificationBanner(
                    notification = notification,
                    onClick = {
                        profileSurface = ProfileSurface.None
                        selectedTab = notification.targetTab
                        notification.activityTab?.let { tab ->
                            scope.launch { activityStateHolder.selectTab(tab) }
                        }
                        activeNotification = null
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        PynapliOverlays(
            tonightVisible = tonightVisible,
            onTonightDismiss = { tonightVisible = false },
            psyncVisible = psyncVisible,
            onPsyncDismiss = { psyncVisible = false },
            selectedTab = selectedTab,
            playingTonightStateHolder = playingTonightStateHolder,
            psyncStateHolder = psyncStateHolder,
            psyncStreamClient = psyncStreamClient,
            profileDetailStateHolder = profileDetailStateHolder,
            conversationStateHolder = conversationStateHolder,
            profileLinksStateHolder = profileLinksStateHolder,
            authStateHolder = authStateHolder,
            appSettingsStateHolder = appSettingsStateHolder,
            conversationActions = conversationActions,
            onPickConversationPhoto = onPickConversationPhoto,
            scope = scope,
        )

        // Square-crop step for a freshly picked profile photo; composed last so it
        // draws above the shell and every overlay.
        PhotoCropOverlay(mediaAlbumsStateHolder, scope)
    }
}

internal enum class ProfileSurface {
    None,
    Hub,
    MediaAlbums,
}
