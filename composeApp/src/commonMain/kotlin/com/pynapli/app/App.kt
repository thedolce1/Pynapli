package com.pynapli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import com.pynapli.app.activity.likeBackEventIdOrNull
import com.pynapli.app.activity.toActivityProfileCardUi
import com.pynapli.app.auth.AuthScreen
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.PhotoCropOverlay
import com.pynapli.app.onboarding.OnboardingFlowScreen
import com.pynapli.app.onboarding.OnboardingStep
import com.pynapli.app.perf.PynapliPerfMarks
import com.pynapli.app.store.StorePaywallFocus
import com.pynapli.app.store.StorePaywallSheet
import com.pynapli.app.store.toStorePartnerLink
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
        // The plans/store sheet and the paywall that opened it; null when closed. There is no Store
        // tab (2026-11-17): every upsell opens this sheet.
        var storePaywallFocus by remember { mutableStateOf<StorePaywallFocus?>(null) }
        val openStore: (StorePaywallFocus) -> Unit = { focus -> storePaywallFocus = focus }
        // Built per signed-in viewer so session mapping (isInitiator/partner) is correct.
        val psyncStateHolder =
            remember(authState.session?.userId, graph.psyncRepository) {
                graph.psyncRepository?.let { repo ->
                    com.pynapli.app.psync
                        .PsyncStateHolder(repo, authState.session?.userId)
                }
            }
        var activeNotification by remember { mutableStateOf<InAppNotificationUi?>(null) }
        // Signed out, session died, or a cold start whose stored session is gone: forget this
        // account's Grid (in memory and the cold-start cache) and Favorites. Waits for the session
        // check so a cold start with a live session keeps its instant cached Grid.
        // Blocked, hidden or reported anywhere: the Grid and Favorites (kept across tabs) drop them.
        LaunchedEffect(graph.profileDetailStateHolder) {
            graph.profileDetailStateHolder.removedProfiles.collect { userId ->
                // Favorites first: the Grid's removal may wait on a backfill page (plan G2-4).
                graph.favoritesStateHolder?.removeCardById(userId)
                graph.gridStateHolder.removeCardById(userId)
            }
        }
        // A plan change (a purchase or restore in the Store, or a lapse picked up by the Profile Hub on
        // resume) reloads the Grid, so cards locked for the old plan unlock or lock (Section 3). Each
        // source only emits when its own fetch answered, so the newest emission is the current plan.
        LaunchedEffect(graph.gridStateHolder, graph.storeStateHolder, graph.profileHubStateHolder) {
            merge(
                graph.storeStateHolder.state
                    .map { it.entitlement?.tier }
                    .distinctUntilChanged(),
                graph.profileHubStateHolder.state
                    .map { it.entitlement?.tier }
                    .distinctUntilChanged(),
            ).collect { tier -> graph.gridStateHolder.onPlanTierObserved(tier) }
        }
        LaunchedEffect(authState.isCheckingSession, authState.session == null) {
            if (!authState.isCheckingSession && authState.session == null) {
                graph.gridStateHolder.clearForSignOut()
                graph.favoritesStateHolder?.clear()
                // And its location: the next account sends its own first fix (Section 1, B2).
                graph.locationStateHolder.reset()
                // And its Travel state: the next account must not see (or be ranked from) A's city.
                graph.travelLocationStateHolder?.reset()
            }
        }
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
            matchCelebrationStateHolder = graph.matchCelebrationStateHolder,
            activeNotification = activeNotification,
            onNotification = { activeNotification = it },
            onSelectTab = { selectedTab = it },
            onClearOverlays = {
                profileSurface = ProfileSurface.None
                tonightVisible = false
                psyncVisible = false
                storePaywallFocus = null
                // Signed out: never show this account's verification state to the next one.
                graph.photoVerificationStateHolder?.reset()
                // Nor its linked sign-in methods.
                graph.signInMethodsStateHolder?.reset()
            },
            onOpenProfileHub = { profileSurface = ProfileSurface.Hub },
            onOpenPartnerPanel = {
                profileSurface = ProfileSurface.Hub
                graph.profileHubStateHolder.openPartner()
            },
            onOpenTonight = { tonightVisible = true },
            onOpenPsync = { psyncVisible = true },
            onOpenStore = { openStore(StorePaywallFocus.Plans) },
            onOpenActivityProfile = { event ->
                scope.launch {
                    graph.profileDetailStateHolder.open(
                        event.toActivityProfileCardUi(),
                        likeBackEventId = event.likeBackEventIdOrNull(),
                    )
                }
            },
        )

        // Report the canonical route for the current navigation state. Composed before
        // the early returns below so it fires for auth/onboarding states too; skipped
        // while the session restore is settling so the address bar never flashes /login.
        if (platform.onNavigationRouteChanged != null && !authState.isCheckingSession) {
            val onboardingForRoute =
                graph.onboardingStateHolder
                    ?.state
                    ?.collectAsState()
                    ?.value
            val onboardingActiveForRoute = onboardingForRoute?.active == true
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
                    storeOpen = storePaywallFocus != null,
                    // One history entry per sign-up step on the web (B6).
                    onboardingStep = onboardingForRoute?.step ?: OnboardingStep.Dob,
                )
            LaunchedEffect(route) { platform.onNavigationRouteChanged(route) }
        }

        // Once the session is settled, re-enter an interrupted signup stepper and
        // decide this session's add-a-photo nudge (ONBOARDING_AUTH_SPEC). Signed out, a
        // sign-up still waiting for its emailed code reopens on the code step.
        LaunchedEffect(authState.isCheckingSession, authState.authenticated, graph.onboardingStateHolder) {
            if (!authState.isCheckingSession) {
                if (authState.authenticated) {
                    graph.onboardingStateHolder?.onSessionReady()
                } else {
                    graph.onboardingStateHolder?.resumePendingSignupIfNeeded()
                }
            }
        }

        // Which "Continue with ..." buttons the signed-out screens show (server flag AND platform).
        LaunchedEffect(authState.isCheckingSession, authState.authenticated) {
            if (!authState.isCheckingSession && !authState.authenticated) {
                graph.authStateHolder.loadSocialSignInConfig()
            }
        }

        if (authState.isCheckingSession) {
            // The same splash the boot stage drew, so nothing moves at the swap (Section 1, B5).
            PynapliSplashContent()
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
                onConfirmDob = { scope.launch { graph.onboardingStateHolder.confirmDob() } },
                onBackToDob = graph.onboardingStateHolder::backToDob,
                onCancelToSignIn = { scope.launch { graph.onboardingStateHolder.cancelToSignIn() } },
                // Email moves on to the account step; Google / Apple finishes the sign-up here.
                onAcceptPolicies = { scope.launch { graph.onboardingStateHolder.acceptPoliciesAndContinue() } },
                onBackToPolicies = graph.onboardingStateHolder::backToPolicies,
                onEmailChanged = graph.onboardingStateHolder::updateEmail,
                onPasswordChanged = graph.onboardingStateHolder::updatePassword,
                onSubmitAccount = {
                    PynapliPerfMarks.markOnce(PynapliPerfMarks.ACCOUNT_SUBMIT)
                    scope.launch { graph.onboardingStateHolder.submitAccount() }
                },
                onSignupCodeChanged = { code ->
                    // The sixth digit submits on its own, once per entry (the holder decides when).
                    if (graph.onboardingStateHolder.updateSignupCode(code)) {
                        scope.launch { graph.onboardingStateHolder.verifySignupCode() }
                    }
                },
                onVerifySignupCode = { scope.launch { graph.onboardingStateHolder.verifySignupCode() } },
                onResendSignupCode = { scope.launch { graph.onboardingStateHolder.resendSignupCode() } },
                onUseDifferentEmail = { scope.launch { graph.onboardingStateHolder.useDifferentEmail() } },
                onStartSignupAgain = { scope.launch { graph.onboardingStateHolder.startSignupAgain() } },
                onShareLocation = platform.onUseCurrentLocation,
                onContinueFromLocation = graph.onboardingStateHolder::continueFromLocation,
                onDisplayNameChanged = graph.onboardingStateHolder::updateDisplayName,
                onPickPhoto = { platform.onPickMediaForAlbum(MediaAlbumKindUi.Profile) },
                onFinishProfile = { scope.launch { graph.onboardingStateHolder.finishProfile() } },
                onStepBack = { scope.launch { graph.onboardingStateHolder.stepBack() } },
                onShowBirthdayWheel = graph.onboardingStateHolder::showBirthdayWheel,
                onWheelDateChange = graph.onboardingStateHolder::selectWheelDate,
                onUseTypedBirthday = graph.onboardingStateHolder::useTypedBirthday,
                resendCooldownSecondsLeft = graph.onboardingStateHolder::resendCooldownSecondsLeft,
                testerDiagnosticLabel = platform.testerDiagnosticLabel,
            )
            // Composed after the stepper so the crop step draws above it; the main
            // shell hosts its own copy because this branch returns early.
            PhotoCropOverlay(graph.mediaAlbumsStateHolder, scope)
            return@PynapliTheme
        }

        // B3 (onboarding + first grid plan): until this session's sign-up resume is decided (a local
        // store read), hold a plain background instead of composing the main shell, which would show
        // (and load) the grid first. Plain on purpose: the splash itself is part B's (B5).
        if (onboardingState?.holdsMainSurfaces(authState.session?.userId) == true) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            return@PynapliTheme
        }

        if (!authState.authenticated) {
            AuthScreen(
                state = authState,
                onEmailChanged = graph.authStateHolder::updateEmail,
                onPasswordChanged = graph.authStateHolder::updatePassword,
                onModeSelected = graph.authStateHolder::switchMode,
                onSubmit = {
                    scope.launch { graph.authStateHolder.submit() }
                },
                onShowWelcome = graph.authStateHolder::showWelcome,
                onResetCodeChanged = graph.authStateHolder::updateResetCode,
                onResendResetCode = { scope.launch { graph.authStateHolder.requestPasswordReset() } },
                onAppealReasonChanged = graph.authStateHolder::updateAppealReason,
                onSubmitAppeal = { scope.launch { graph.authStateHolder.submitAccountAppeal() } },
                onDevLogin =
                    platform.devLoginCredentials?.let { credentials ->
                        { scope.launch { graph.authStateHolder.submitDevLogin(credentials) } }
                    },
                onStartCreateAccount =
                    graph.onboardingStateHolder?.let { holder ->
                        {
                            PynapliPerfMarks.markOnce(PynapliPerfMarks.CREATE_ACCOUNT_TAP)
                            holder.startCreateAccount()
                        }
                    },
                // Through the stepper when there is one: an unknown identity continues there.
                onContinueWithSocial = { provider ->
                    scope.launch {
                        val onboarding = graph.onboardingStateHolder
                        if (onboarding != null) onboarding.continueWithSocial(provider) else graph.authStateHolder.continueWithSocial(provider)
                    }
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
                // Read receipts, typing status and disappearing messages are plan perks.
                onOpenStore = { openStore(StorePaywallFocus.Plans) },
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
                appSettingsStateHolder = graph.appSettingsStateHolder,
                authStateHolder = graph.authStateHolder,
                savedGridFilterStateHolder = graph.savedGridFilterStateHolder,
                savedSwipeFilterStateHolder = graph.savedSwipeFilterStateHolder,
                travelLocationStateHolder = graph.travelLocationStateHolder,
                playingTonightStateHolder = graph.playingTonightStateHolder,
                onboardingStateHolder = graph.onboardingStateHolder,
                photoVerificationStateHolder = graph.photoVerificationStateHolder,
                contactBlocksStateHolder = graph.contactBlocksStateHolder,
                signInMethodsStateHolder = graph.signInMethodsStateHolder,
                favoritesStateHolder = graph.favoritesStateHolder,
                conversationActions = conversationActions,
                scope = scope,
                onProfileSurface = { profileSurface = it },
                onOpenStore = openStore,
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
                onPickVerificationSelfie = platform.onPickVerificationSelfie,
                onSetDiscreetIcon = platform.onSetDiscreetIcon,
                discreetIconSupported = platform.discreetIconSupported,
                swipeDeckCovered =
                    graph.matchCelebrationStateHolder
                        ?.state
                        ?.collectAsState()
                        ?.value
                        ?.current != null,
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
            lifestyleFiltersAllowed =
                graph.profileHubStateHolder.state
                    .collectAsState()
                    .value.me
                    ?.profile
                    ?.ageVerified == true,
            onTonightDismiss = { tonightVisible = false },
            psyncVisible = psyncVisible,
            onPsyncDismiss = { psyncVisible = false },
            // Psync tier limits: the sheet opens over the session, which stays where it was.
            onPsyncOpenStore = { openStore(StorePaywallFocus.Plans) },
            psyncTravelCityName =
                graph.travelLocationStateHolder
                    ?.state
                    ?.collectAsState()
                    ?.value
                    ?.current
                    ?.city,
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
            activityStateHolder = graph.activityStateHolder,
            conversationActions = conversationActions,
            onPickConversationPhoto = platform.onPickConversationPhoto,
            scope = scope,
            matchCelebrationStateHolder = graph.matchCelebrationStateHolder,
        )

        storePaywallFocus?.let { focus ->
            // Partner-benefit wording uses the links already loaded at sign-in; no extra fetch.
            val profileLinksState by graph.profileLinksStateHolder.state.collectAsState()
            StorePaywallSheet(
                stateHolder = graph.storeStateHolder,
                focus = focus,
                onDismiss = { storePaywallFocus = null },
                purchasesAvailable = platform.storePurchasesAvailable,
                partnerLink = profileLinksState.toStorePartnerLink(),
            )
        }

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
