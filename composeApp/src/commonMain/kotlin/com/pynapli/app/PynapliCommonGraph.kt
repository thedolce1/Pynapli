package com.pynapli.app

import com.pynapli.app.activity.ActivityRealtimeCoordinator
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.activity.ApiActivityFeedRepository
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.conversation.ApiConversationRepository
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.grid.ApiFavoritesRepository
import com.pynapli.app.grid.ApiGridFeedRepository
import com.pynapli.app.grid.ApiSavedGridFilterRepository
import com.pynapli.app.grid.FavoritesStateHolder
import com.pynapli.app.grid.GridPresenceStreamCoordinator
import com.pynapli.app.grid.GridSnapshotStore
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.grid.NoOpGridSnapshotStore
import com.pynapli.app.grid.SavedGridFilterStateHolder
import com.pynapli.app.location.ApiLocationUpdateRepository
import com.pynapli.app.location.ApiTravelLocationRepository
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.location.TravelLocationStateHolder
import com.pynapli.app.match.ApiMatchCelebrationRepository
import com.pynapli.app.match.MatchCelebrationStateHolder
import com.pynapli.app.media.ApiMediaAlbumsRepository
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.messages.ApiMessagesRepository
import com.pynapli.app.messages.MessagesRealtimeCoordinator
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.profile.ApiPublicProfileRepository
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilehub.ApiProfileHubRepository
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ApiProfileLinksRepository
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.psync.ApiPsyncRepository
import com.pynapli.app.store.ApiStoreRepository
import com.pynapli.app.store.StorePurchaseGateway
import com.pynapli.app.store.StoreStateHolder
import com.pynapli.app.swipe.ApiSavedSwipeFilterRepository
import com.pynapli.app.swipe.NoOpSwipeActionQueueStore
import com.pynapli.app.swipe.SavedSwipeFilterStateHolder
import com.pynapli.app.swipe.SwipeActionQueueStore
import com.pynapli.app.swipe.SwipeRepository
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.app.tonight.ApiTonightRepository
import com.pynapli.app.tonight.PlayingTonightStateHolder
import com.pynapli.app.verification.ApiPhotoVerificationRepository
import com.pynapli.app.verification.PhotoVerificationStateHolder
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.ActivityStreamClient
import com.pynapli.shared.network.KtorMediaByteUploader
import com.pynapli.shared.network.MessageStreamClient
import com.pynapli.shared.network.PresenceStreamClient
import com.pynapli.shared.network.PsyncStreamClient
import com.pynapli.shared.network.PynapliApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Every state holder that is built from the API client and nothing platform-specific.
 *
 * `AndroidAppGraph` and `WebAppGraph` used to construct these independently — 30 of ~35 dependencies
 * were written twice, identically. That is not a theoretical cost: the two drifted, and the git
 * history carries the correction ("realtime coordinators ARE wired on web"). Playing Tonight and
 * Psync were Android-only for the same reason — the web shell simply had not been updated, not
 * because a browser cannot do them.
 *
 * The seam is the constructor: anything a platform genuinely owns is passed in (its storage, its
 * purchase gateway, its WebSocket clients), and everything downstream of the API client is built
 * here once. A shell that forgets a platform piece now fails to compile instead of quietly shipping
 * a surface the other platform has.
 *
 * Deliberately NOT here: the session/auth chain (`sessionStore` -> `apiClient` ->
 * `StoredSessionRefresher` -> `AuthStateHolder`). Its inputs differ per platform in ways that are
 * real — App Check provider, device id, and Android's logout hook that unregisters the push device —
 * so it stays in each shell. Consolidating that is a separate change with a genuine design decision
 * in it, not mechanical de-duplication.
 */
class PynapliCommonGraph(
    apiClient: PynapliApiClient,
    /** Platform storage: SQLDelight on Android, absent on web until a localStorage snapshot earns it. */
    gridSnapshotStore: GridSnapshotStore = NoOpGridSnapshotStore,
    swipeQueueStore: SwipeActionQueueStore = NoOpSwipeActionQueueStore,
    /**
     * Android wraps the API repository with a debug fault injector for the connected swipe QA, so the
     * repository itself is a platform input rather than something built here.
     */
    swipeRepository: SwipeRepository =
        com.pynapli.app.swipe
            .ApiSwipeRepository(apiClient),
    /** Play Billing on Android; web passes an unavailable gateway (Store is read-only there). */
    storePurchaseGateway: StorePurchaseGateway,
    /**
     * Realtime convenience channels. Browsers cannot set WebSocket headers, so the clients are built
     * per platform (`buildAndroid*` / `buildWeb*`) and injected. Required rather than nullable: both
     * shells wire all three today, and making them optional would re-open the exact gap this class
     * exists to close — a platform silently missing a stream and nobody noticing.
     */
    presenceStreamClient: PresenceStreamClient,
    activityStreamClient: ActivityStreamClient,
    messageStreamClient: MessageStreamClient,
) {
    val gridStateHolder =
        GridStateHolder(
            repository = ApiGridFeedRepository(apiClient),
            snapshotStore = gridSnapshotStore,
        )

    val savedGridFilterStateHolder = SavedGridFilterStateHolder(ApiSavedGridFilterRepository(apiClient))

    val favoritesStateHolder = FavoritesStateHolder(ApiFavoritesRepository(apiClient))

    val savedSwipeFilterStateHolder = SavedSwipeFilterStateHolder(ApiSavedSwipeFilterRepository(apiClient))

    val travelLocationStateHolder = TravelLocationStateHolder(ApiTravelLocationRepository(apiClient))

    val playingTonightStateHolder = PlayingTonightStateHolder(ApiTonightRepository(apiClient))

    /** The per-viewer `PsyncStateHolder` is built in `PynapliApp` from this. */
    val psyncRepository = ApiPsyncRepository(apiClient)

    val profileDetailStateHolder = ProfileDetailStateHolder(ApiPublicProfileRepository(apiClient))

    val conversationStateHolder =
        ConversationStateHolder(ApiConversationRepository(apiClient, KtorMediaByteUploader()))

    val messagesStateHolder = MessagesStateHolder(ApiMessagesRepository(apiClient))

    val activityStateHolder = ActivityStateHolder(ApiActivityFeedRepository(apiClient))

    val locationStateHolder = LocationStateHolder(ApiLocationUpdateRepository(apiClient))

    val mediaAlbumsStateHolder =
        MediaAlbumsStateHolder(
            repository =
                ApiMediaAlbumsRepository(
                    apiClient = apiClient,
                    mediaByteUploader = KtorMediaByteUploader(),
                ),
        )

    val profileHubStateHolder = ProfileHubStateHolder(ApiProfileHubRepository(apiClient))

    val photoVerificationStateHolder =
        PhotoVerificationStateHolder(ApiPhotoVerificationRepository(apiClient, KtorMediaByteUploader()))

    val profileLinksStateHolder = ProfileLinksStateHolder(ApiProfileLinksRepository(apiClient))

    /** The app-wide "It's a match" moment, for both people in a match. */
    val matchCelebrationStateHolder = MatchCelebrationStateHolder(ApiMatchCelebrationRepository(apiClient))

    val swipeStateHolder =
        SwipeStateHolder(
            repository = swipeRepository,
            queueStore = swipeQueueStore,
            onMatch = matchCelebrationStateHolder::celebrate,
        )

    val storeStateHolder =
        StoreStateHolder(
            repository = ApiStoreRepository(apiClient),
            purchaseGateway = storePurchaseGateway,
        )

    /** Refetches canonical state when the app returns to the foreground; each shell calls [onAppForegrounded]. */
    val appResumeRefresher =
        AppResumeRefresher(
            refreshAll = {
                // Every refetch is silent: nothing flashes a spinner or an error over what the user
                // is reading, and a failure keeps the content on screen.
                coroutineScope {
                    launch { messagesStateHolder.refreshSilently() }
                    launch { conversationStateHolder.refreshOpenConversation() }
                    launch {
                        activityStateHolder.refreshSummarySilently()
                        activityStateHolder.refreshSilently()
                    }
                    launch { profileLinksStateHolder.refreshSilently() }
                    // Plan, age verification and export status can all change while away.
                    launch { profileHubStateHolder.refreshSilently() }
                    launch { playingTonightStateHolder.refreshIfOnScreen() }
                    // A match made while away shows its moment now (once, across devices).
                    launch { matchCelebrationStateHolder.refresh() }
                    // The Grid reorders only when the user asks or its results have expired, so a
                    // quick app switch never reshuffles the cards (composeApp/CLAUDE.md).
                    if (gridStateHolder.state.value.cards
                            .isNotEmpty() && gridStateHolder.resultsOlderThan(GridStateHolder.RESULTS_EXPIRE_AFTER)
                    ) {
                        launch { gridStateHolder.load(quiet = true) }
                    }
                }
            },
        )

    /** The app is visible again (Android onStart, iOS foreground, browser tab shown). */
    suspend fun onAppForegrounded(signedIn: Boolean) = appResumeRefresher.onForeground(signedIn)

    val gridPresenceStreamCoordinator = GridPresenceStreamCoordinator(presenceStreamClient, gridStateHolder)

    val activityRealtimeCoordinator =
        ActivityRealtimeCoordinator(activityStreamClient, activityStateHolder, onMatch = matchCelebrationStateHolder::refresh)

    val messagesRealtimeCoordinator =
        MessagesRealtimeCoordinator(
            client = messageStreamClient,
            messagesStateHolder = messagesStateHolder,
            conversationStateHolder = conversationStateHolder,
        )

    /**
     * The [PynapliAppGraph] for this shell: every common holder plus the few pieces each shell owns
     * (auth, settings, deep links, onboarding, the Psync stream). Built here once so the Android, iOS,
     * web and desktop shells don't each repeat a 22-argument mapping.
     *
     * Every shell ships Playing Tonight and Psync, so [psyncStreamClient] is required: a shell that
     * forgot it used to hide Psync silently (web did, until 2026-11-10).
     */
    fun appGraph(
        authStateHolder: AuthStateHolder,
        appSettingsStateHolder: AppSettingsStateHolder,
        deepLinkStateHolder: AppDeepLinkStateHolder? = null,
        onboardingStateHolder: OnboardingStateHolder? = null,
        psyncStreamClient: PsyncStreamClient,
    ): PynapliAppGraph =
        PynapliAppGraph(
            authStateHolder = authStateHolder,
            appSettingsStateHolder = appSettingsStateHolder,
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
            savedGridFilterStateHolder = savedGridFilterStateHolder,
            savedSwipeFilterStateHolder = savedSwipeFilterStateHolder,
            travelLocationStateHolder = travelLocationStateHolder,
            playingTonightStateHolder = playingTonightStateHolder,
            psyncRepository = psyncRepository,
            psyncStreamClient = psyncStreamClient,
            deepLinkStateHolder = deepLinkStateHolder,
            onboardingStateHolder = onboardingStateHolder,
            photoVerificationStateHolder = photoVerificationStateHolder,
            favoritesStateHolder = favoritesStateHolder,
            matchCelebrationStateHolder = matchCelebrationStateHolder,
        )

    /**
     * Starts the realtime coordinators (grid presence, activity, messages) in [scope] and returns their
     * jobs so the shell can cancel them when it goes to the background. Each coordinator reconnects on
     * its own; a cancellation is never swallowed (runCatchingCancellable).
     */
    fun startRealtime(scope: CoroutineScope): List<Job> =
        listOf(
            scope.launch { runCatchingCancellable { gridPresenceStreamCoordinator.run() } },
            scope.launch { runCatchingCancellable { activityRealtimeCoordinator.run() } },
            scope.launch { runCatchingCancellable { messagesRealtimeCoordinator.run() } },
        )
}
