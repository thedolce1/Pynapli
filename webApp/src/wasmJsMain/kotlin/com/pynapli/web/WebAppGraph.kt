package com.pynapli.web

import com.pynapli.app.AppDeepLinkStateHolder
import com.pynapli.app.PynapliAppGraph
import com.pynapli.app.PynapliCommonGraph
import com.pynapli.app.appsettings.ApiNotificationPreferencesGateway
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.LocalAppSettingsRepository
import com.pynapli.app.appsettings.ServerBackedAppSettingsRepository
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.grid.NoOpGridSnapshotStore
import com.pynapli.app.onboarding.ApiOnboardingProfileRepository
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.store.UnavailableStorePurchaseGateway
import com.pynapli.app.swipe.NoOpSwipeActionQueueStore
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionInvalidationBus
import com.pynapli.shared.auth.SessionMutationLock
import com.pynapli.shared.auth.StoredSessionRefresher
import com.pynapli.shared.core.config.PynapliEnvironment
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.network.NoAppCheckTokenProvider
import com.pynapli.shared.network.SessionRefresherHolder
import com.pynapli.shared.network.webRealtimeStreams
import com.pynapli.shared.settings.RetiredLocationConsentKeys
import com.pynapli.web.auth.LocalStorageSessionStore
import com.pynapli.web.platform.BrowserLocalStorage
import com.pynapli.web.platform.onOtherTabStorageChange
import com.pynapli.web.settings.LocalStorageAppSettingsStore
import com.pynapli.web.settings.LocalStorageOnboardingProgressStore
import com.pynapli.web.settings.SessionStoragePendingSignupStore
import com.pynapli.web.settings.SessionStorageUnderageStopStore
import com.pynapli.web.settings.localStorageGridFilterStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.random.Random

/**
 * Web mirror of `AndroidAppGraph`: builds the shared state-holder set over browser
 * adapters. The web is a full companion to the phone app (many members use it on tablets and
 * desktops), so it ships every surface, Playing Tonight and Psync included. Deliberately absent
 * (WEB_PWA_GAMEPLAN.md): push registration (Web Push is post-v1), billing (Store is read-only),
 * and the swipe/grid local caches (NoOp stores until a localStorage snapshot store earns its
 * place in W2/W4). Realtime streams authenticate over WebSockets with a first frame since
 * browsers cannot set WS headers (W3); presence/activity/messages start from `Main.kt`, and the
 * Psync stream connects only while the Psync overlay is open.
 */
class WebAppGraph {
    private val sessionStore = LocalStorageSessionStore()
    private val pynapliConfig = WebRuntimeConfig.pynapliConfig

    // See AndroidAppGraph: the holder breaks the client <-> refresher construction cycle so an
    // expired access token is refreshed rather than surfacing as a surprise sign-out.
    private val sessionRefresher = SessionRefresherHolder()

    // Terminal mid-session invalidation (revoked session / suspended account) routes back to sign-in.
    private val sessionInvalidationBus = SessionInvalidationBus()

    // One lock for this store, shared by the refresher and AuthRepository: sign-out waits for a
    // refresh in flight rather than being overwritten by it.
    private val sessionLock = SessionMutationLock()
    private val apiClient =
        KtorPynapliApiClient(
            config = pynapliConfig,
            accessTokenProvider = sessionStore,
            appCheckTokenProvider = NoAppCheckTokenProvider,
            sessionRefresher = sessionRefresher,
        ).also { client ->
            sessionRefresher.delegate =
                StoredSessionRefresher(
                    authApi = client,
                    sessionStore = sessionStore,
                    invalidationBus = sessionInvalidationBus,
                    lock = sessionLock,
                    // Tabs share the localStorage session but not the refresh mutex: give a tab that
                    // lost a refresh race time to see the winning tab's saved session.
                    settleAttempts = 5,
                    settleDelayMillis = 300,
                )
        }
    private val authRepository =
        AuthRepository(
            apiClient = apiClient,
            sessionStore = sessionStore,
            sessionLock = sessionLock,
        )

    val deepLinkStateHolder = AppDeepLinkStateHolder()

    val authStateHolder =
        AuthStateHolder(
            authRepository = authRepository,
            platform = "web",
            appVersion = "0.1.0",
            deviceId = stableWebInstallId(),
            sessionInvalidations = sessionInvalidationBus.events,
        )

    val appSettingsStateHolder =
        AppSettingsStateHolder(
            repository =
                ServerBackedAppSettingsRepository(
                    localRepository =
                        LocalAppSettingsRepository(
                            store = LocalStorageAppSettingsStore(),
                        ),
                    notificationPreferencesGateway = ApiNotificationPreferencesGateway(apiClient),
                    timezoneProvider = { TimeZone.currentSystemDefault().id },
                    remoteSyncEnabled = { authStateHolder.state.value.authenticated },
                ),
        )

    val onboardingStateHolder =
        OnboardingStateHolder(
            authStateHolder = authStateHolder,
            profileRepository = ApiOnboardingProfileRepository(apiClient),
            progressStore = LocalStorageOnboardingProgressStore(),
            // sessionStorage: gone when the tab closes, so a shared computer never reopens it.
            pendingSignupStore = SessionStoragePendingSignupStore(),
            // The 18+ stop sticks for the tab's session (sessionStorage, like the pending sign-up).
            underageStopStore = SessionStorageUnderageStopStore(),
        )

    /** All realtime WebSocket streams, sharing one client and connection pool. */
    private val realtimeStreams = webRealtimeStreams(pynapliConfig.websocketBaseUrl, sessionStore)

    /**
     * Everything downstream of the API client, built once in commonMain and shared with
     * AndroidAppGraph. This block used to be ~110 lines duplicated here; keeping two copies is what
     * let the web fall behind Android.
     */
    private val common =
        PynapliCommonGraph(
            apiClient = apiClient,
            // NoOp stores until a localStorage snapshot store earns its place (WEB_PWA_GAMEPLAN W2/W4).
            gridSnapshotStore = NoOpGridSnapshotStore,
            // Filter choices only, kept across browser sessions; sign-out clears them.
            gridFilterStore = localStorageGridFilterStore(),
            swipeQueueStore = NoOpSwipeActionQueueStore,
            // Store is founder-locked read-only on web.
            storePurchaseGateway = UnavailableStorePurchaseGateway(),
            // First-frame-auth sockets: browsers cannot set WebSocket headers (W3).
            presenceStreamClient =
                realtimeStreams.presence,
            activityStreamClient =
                realtimeStreams.activity,
            messageStreamClient =
                realtimeStreams.message,
        )

    /** The object the web entry point hands to `PynapliApp`. */
    val pynapliAppGraph: PynapliAppGraph =
        common.appGraph(
            authStateHolder = authStateHolder,
            appSettingsStateHolder = appSettingsStateHolder,
            deepLinkStateHolder = deepLinkStateHolder,
            onboardingStateHolder = onboardingStateHolder,
            psyncStreamClient = realtimeStreams.psync,
        )

    init {
        // The retired location-sharing consent record held account ids; nothing reads it now.
        RetiredLocationConsentKeys.webStorageKeys.forEach(BrowserLocalStorage::remove)
    }

    /** Starts grid presence, activity and messages realtime; see [PynapliCommonGraph.startRealtime]. */
    fun startRealtime(scope: CoroutineScope): List<Job> = common.startRealtime(scope)

    /** Refetches every surface after the app was away; see [PynapliCommonGraph.onAppForegrounded]. */
    suspend fun onAppForegrounded() = common.onAppForegrounded(signedIn = authStateHolder.state.value.authenticated)

    /**
     * Tabs share one localStorage session. When another tab signs out, or out and back in as someone
     * else, this tab must end its own session rather than carry on with the other account's tokens
     * under its own account's cached state (AuthStateHolder.onStoredSessionChanged decides). Call once.
     */
    fun followSessionChangesFromOtherTabs(scope: CoroutineScope) {
        onOtherTabStorageChange(LocalStorageSessionStore.SESSION_KEY) {
            scope.launch { authStateHolder.onStoredSessionChanged(sessionStore.currentSession()) }
        }
    }

    val devLoginCredentials: DevLoginCredentials? =
        if (pynapliConfig.environment == PynapliEnvironment.Local) {
            DevLoginCredentials(
                email = "dev@pynapli.local",
                password = "PynapliDevPass123!",
            )
        } else {
            null
        }

    val testerDiagnosticLabel: String? = WebRuntimeConfig.testerDiagnosticLabel

    private companion object {
        private const val INSTALL_ID_KEY = "pynapli.install.id.v1"

        /**
         * Per-browser random identifier (account-farm signal parity with Android's
         * `stableInstallId`) — NOT a fingerprint; a random value persisted in
         * localStorage that resets when site data is cleared.
         */
        private fun stableWebInstallId(): String {
            BrowserLocalStorage.get(INSTALL_ID_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
            val id = buildString { repeat(32) { append(HEX_CHARS[Random.nextInt(HEX_CHARS.length)]) } }
            BrowserLocalStorage.set(INSTALL_ID_KEY, id)
            return id
        }

        private const val HEX_CHARS = "0123456789abcdef"
    }
}
