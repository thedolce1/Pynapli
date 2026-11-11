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
import androidx.compose.ui.text.font.FontWeight
import com.pynapli.app.auth.AuthScreen
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.PhotoCropOverlay
import com.pynapli.app.onboarding.OnboardingFlowScreen
import kotlinx.coroutines.launch

/**
 * Root of the shared Compose app.
 *
 * Takes two objects rather than the 29 individual parameters it used to: [PynapliAppGraph] for the
 * state holders and clients, [PynapliPlatform] for host capabilities and callbacks. See those types
 * for why. Adding a feature now means adding a property there, not widening this signature and both
 * platform shells with it.
 */
@Composable
fun PynapliApp(
    graph: PynapliAppGraph,
    platform: PynapliPlatform,
) {
    val appSettingsState by graph.appSettingsStateHolder.state.collectAsState()

    LaunchedEffect(graph.appSettingsStateHolder) {
        graph.appSettingsStateHolder.load()
    }

    PynapliTheme(themeMode = appSettingsState.settings.themeMode) {
        val authState by graph.authStateHolder.state.collectAsState()
        val conversationState by graph.conversationStateHolder.state.collectAsState()
        val messagesState by graph.messagesStateHolder.state.collectAsState()
        val activityState by graph.activityStateHolder.state.collectAsState()
        val profileDetailState by graph.profileDetailStateHolder.state.collectAsState()
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableStateOf(PynapliTab.Grid) }
        var profileSurface by remember { mutableStateOf(ProfileSurface.None) }
        var tonightVisible by remember { mutableStateOf(false) }
        var psyncVisible by remember { mutableStateOf(false) }
        // Built per signed-in viewer so session mapping (isInitiator/partner) is correct.
        val psyncStateHolder =
            remember(authState.session?.userId, graph.psyncRepository) {
                graph.psyncRepository?.let { repo ->
                    com.pynapli.app.psync
                        .PsyncStateHolder(repo, authState.session?.userId)
                }
            }
        var activeNotification by remember { mutableStateOf<InAppNotificationUi?>(null) }
        PynapliAppEffects(
            authStateHolder = graph.authStateHolder,
            appSettingsStateHolder = graph.appSettingsStateHolder,
            messagesStateHolder = graph.messagesStateHolder,
            activityStateHolder = graph.activityStateHolder,
            conversationStateHolder = graph.conversationStateHolder,
            mediaAlbumsStateHolder = graph.mediaAlbumsStateHolder,
            profileHubStateHolder = graph.profileHubStateHolder,
            profileLinksStateHolder = graph.profileLinksStateHolder,
            deepLinkStateHolder = graph.deepLinkStateHolder,
            activeNotification = activeNotification,
            onNotification = { activeNotification = it },
            onSelectTab = { selectedTab = it },
            onClearOverlays = {
                profileSurface = ProfileSurface.None
                tonightVisible = false
                psyncVisible = false
            },
            onOpenProfileHub = { profileSurface = ProfileSurface.Hub },
            onOpenTonight = { tonightVisible = true },
            onOpenPsync = { psyncVisible = true },
        )

        // Report the canonical route for the current navigation state. Composed before
        // the early returns below so it fires for auth/onboarding states too; skipped
        // while the session restore is settling so the address bar never flashes /login.
        if (platform.onNavigationRouteChanged != null && !authState.isCheckingSession) {
            val onboardingActiveForRoute =
                graph.onboardingStateHolder
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
                    tonightOpen = tonightVisible && graph.playingTonightStateHolder != null,
                    psyncOpen = psyncVisible && psyncStateHolder != null,
                )
            LaunchedEffect(route) { platform.onNavigationRouteChanged(route) }
        }

        // Once the session is settled, re-enter an interrupted signup stepper and
        // decide this session's add-a-photo nudge (ONBOARDING_AUTH_SPEC).
        LaunchedEffect(authState.isCheckingSession, authState.authenticated, graph.onboardingStateHolder) {
            if (!authState.isCheckingSession && authState.authenticated) {
                graph.onboardingStateHolder?.onSessionReady()
            }
        }

        if (authState.isCheckingSession) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Pynapli",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            return@PynapliTheme
        }

        // The signup stepper owns the screen while active: pre-auth steps (DOB,
        // underage stop, account) replace AuthScreen; post-auth steps (location,
        // name, photo) run before the main surfaces appear.
        val onboardingState =
            graph.onboardingStateHolder
                ?.state
                ?.collectAsState()
                ?.value
        if (graph.onboardingStateHolder != null && onboardingState != null && onboardingState.active) {
            val locationState by graph.locationStateHolder.state.collectAsState()
            val mediaAlbumsState by graph.mediaAlbumsStateHolder.state.collectAsState()
            OnboardingFlowScreen(
                state = onboardingState,
                locationState = locationState,
                mediaState = mediaAlbumsState,
                onDobDayChanged = graph.onboardingStateHolder::updateDobDay,
                onDobMonthChanged = graph.onboardingStateHolder::updateDobMonth,
                onDobYearChanged = graph.onboardingStateHolder::updateDobYear,
                onConfirmDob = graph.onboardingStateHolder::confirmDob,
                onBackToDob = graph.onboardingStateHolder::backToDob,
                onCancelToSignIn = graph.onboardingStateHolder::cancelToSignIn,
                onAcceptPolicies = graph.onboardingStateHolder::acceptPolicies,
                onBackToPolicies = graph.onboardingStateHolder::backToPolicies,
                onEmailChanged = graph.onboardingStateHolder::updateEmail,
                onPasswordChanged = graph.onboardingStateHolder::updatePassword,
                onSubmitAccount = { scope.launch { graph.onboardingStateHolder.submitAccount() } },
                onShareLocation = platform.onUseCurrentLocation,
                onContinueFromLocation = graph.onboardingStateHolder::continueFromLocation,
                onDisplayNameChanged = graph.onboardingStateHolder::updateDisplayName,
                onPickPhoto = { platform.onPickMediaForAlbum(MediaAlbumKindUi.Profile) },
                onFinishProfile = { scope.launch { graph.onboardingStateHolder.finishProfile() } },
                testerDiagnosticLabel = platform.testerDiagnosticLabel,
            )
            // Composed after the stepper so the crop step draws above it; the main
            // shell hosts its own copy because this branch returns early.
            PhotoCropOverlay(graph.mediaAlbumsStateHolder, scope)
            return@PynapliTheme
        }

        if (!authState.authenticated) {
            AuthScreen(
                state = authState,
                onEmailChanged = graph.authStateHolder::updateEmail,
                onPasswordChanged = graph.authStateHolder::updatePassword,
                onBirthdateChanged = graph.authStateHolder::updateBirthdate,
                onAcceptedAgeAndTermsChanged = graph.authStateHolder::updateAcceptedAgeAndTerms,
                onModeSelected = graph.authStateHolder::switchMode,
                onSubmit = {
                    scope.launch { graph.authStateHolder.submit() }
                },
                onDevLogin =
                    platform.devLoginCredentials?.let { credentials ->
                        { scope.launch { graph.authStateHolder.submitDevLogin(credentials) } }
                    },
                onStartCreateAccount =
                    graph.onboardingStateHolder?.let { holder ->
                        { holder.startCreateAccount() }
                    },
                testerDiagnosticLabel = platform.testerDiagnosticLabel,
            )
            return@PynapliTheme
        }

        val conversationActions =
            rememberConversationActions(
                scope = scope,
                conversationStateHolder = graph.conversationStateHolder,
                messagesStateHolder = graph.messagesStateHolder,
            )

        LaunchedEffect(selectedTab, profileDetailState.blockedTargetUserId) {
            if (selectedTab != PynapliTab.Messages) return@LaunchedEffect
            val blockedId = graph.profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
            graph.profileDetailStateHolder.dismiss()
            if (conversationState.detail
                    ?.conversation
                    ?.participants
                    ?.any { participant -> participant.userId == blockedId } == true
            ) {
                conversationActions.dismiss()
            }
            graph.messagesStateHolder.refreshSilently()
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
                gridStateHolder = graph.gridStateHolder,
                profileDetailStateHolder = graph.profileDetailStateHolder,
                conversationStateHolder = graph.conversationStateHolder,
                messagesStateHolder = graph.messagesStateHolder,
                activityStateHolder = graph.activityStateHolder,
                locationStateHolder = graph.locationStateHolder,
                mediaAlbumsStateHolder = graph.mediaAlbumsStateHolder,
                profileHubStateHolder = graph.profileHubStateHolder,
                profileLinksStateHolder = graph.profileLinksStateHolder,
                swipeStateHolder = graph.swipeStateHolder,
                storeStateHolder = graph.storeStateHolder,
                appSettingsStateHolder = graph.appSettingsStateHolder,
                authStateHolder = graph.authStateHolder,
                savedGridFilterStateHolder = graph.savedGridFilterStateHolder,
                savedSwipeFilterStateHolder = graph.savedSwipeFilterStateHolder,
                travelLocationStateHolder = graph.travelLocationStateHolder,
                tonightAvailabilityStateHolder = graph.tonightAvailabilityStateHolder,
                onboardingStateHolder = graph.onboardingStateHolder,
                conversationActions = conversationActions,
                scope = scope,
                onProfileSurface = { profileSurface = it },
                onOpenStore = { selectedTab = PynapliTab.Store },
                onOpenAgeVerification = {
                    // Route a gated AGE_VERIFICATION_REQUIRED prompt into the existing
                    // Profile-Hub age-verification flow: show the Hub surface and open its dialog.
                    profileSurface = ProfileSurface.Hub
                    graph.profileHubStateHolder.openAgeVerification()
                },
                onOpenTonight = { tonightVisible = true },
                onOpenPsync = psyncStateHolder?.let { { psyncVisible = true } },
                // From an Activity card: open Psync on that exact session (an invite, a live deck,
                // or a past recap), not just whatever is current.
                onOpenPsyncSession =
                    psyncStateHolder?.let { holder ->
                        { sessionId: String ->
                            psyncVisible = true
                            scope.launch { holder.openSession(sessionId) }
                        }
                    },
                onUseCurrentLocation = platform.onUseCurrentLocation,
                onPickMediaForAlbum = platform.onPickMediaForAlbum,
                onPickConversationPhoto = platform.onPickConversationPhoto,
                onSetDiscreetIcon = platform.onSetDiscreetIcon,
                storePurchasesAvailable = platform.storePurchasesAvailable,
                discreetIconSupported = platform.discreetIconSupported,
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
                            scope.launch { graph.activityStateHolder.selectTab(tab) }
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
            onPsyncOpenStore = {
                psyncVisible = false
                selectedTab = PynapliTab.Store
            },
            onPsyncOpenProfileHub = {
                // Straight to the linked-partner panel, where linking is managed.
                psyncVisible = false
                profileSurface = ProfileSurface.Hub
                graph.profileHubStateHolder.openPartner()
            },
            selectedTab = selectedTab,
            playingTonightStateHolder = graph.playingTonightStateHolder,
            psyncStateHolder = psyncStateHolder,
            psyncStreamClient = graph.psyncStreamClient,
            profileDetailStateHolder = graph.profileDetailStateHolder,
            conversationStateHolder = graph.conversationStateHolder,
            profileLinksStateHolder = graph.profileLinksStateHolder,
            authStateHolder = graph.authStateHolder,
            appSettingsStateHolder = graph.appSettingsStateHolder,
            conversationActions = conversationActions,
            onPickConversationPhoto = platform.onPickConversationPhoto,
            scope = scope,
        )

        // Square-crop step for a freshly picked profile photo; composed last so it
        // draws above the shell and every overlay.
        PhotoCropOverlay(graph.mediaAlbumsStateHolder, scope)
    }
}

internal enum class ProfileSurface {
    None,
    Hub,
    MediaAlbums,
}
