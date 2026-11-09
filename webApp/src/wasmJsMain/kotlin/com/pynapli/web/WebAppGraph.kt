package com.pynapli.web

import com.pynapli.app.AppDeepLinkStateHolder
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
import com.pynapli.shared.auth.StoredSessionRefresher
import com.pynapli.shared.core.config.PynapliEnvironment
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.network.NoAppCheckTokenProvider
import com.pynapli.shared.network.SessionRefresherHolder
import com.pynapli.shared.network.buildWebActivityStreamClient
import com.pynapli.shared.network.buildWebMessageStreamClient
import com.pynapli.shared.network.buildWebPresenceStreamClient
import com.pynapli.web.auth.LocalStorageSessionStore
import com.pynapli.web.platform.BrowserLocalStorage
import com.pynapli.web.settings.LocalStorageAppSettingsStore
import com.pynapli.web.settings.LocalStorageOnboardingProgressStore
import kotlinx.datetime.TimeZone
import kotlin.random.Random

/**
 * Web mirror of `AndroidAppGraph`: builds the shared state-holder set over browser
 * adapters. Deliberately absent on web v1 (WEB_PWA_GAMEPLAN.md): push registration
 * (Web Push is post-v1), billing (Store is read-only), Playing Tonight + Psync surfaces
 * (v1.x), and the swipe/grid local caches (NoOp stores until a localStorage snapshot store
 * earns its place in W2/W4). Realtime stream coordinators (presence/activity/messages) ARE
 * wired here and started from `Main.kt` — they authenticate over WebSockets with a first
 * frame since browsers cannot set WS headers (W3, shipped).
 */
class WebAppGraph {
    private val sessionStore = LocalStorageSessionStore()
    private val pynapliConfig = WebRuntimeConfig.pynapliConfig

    // See AndroidAppGraph: the holder breaks the client <-> refresher construction cycle so an
    // expired access token is refreshed rather than surfacing as a surprise sign-out.
    private val sessionRefresher = SessionRefresherHolder()

    // Terminal mid-session invalidation (revoked session / suspended account) routes back to sign-in.
    private val sessionInvalidationBus = SessionInvalidationBus()
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
                )
        }
    private val authRepository =
        AuthRepository(
            apiClient = apiClient,
            sessionStore = sessionStore,
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
        )

    /**
     * Everything downstream of the API client, built once in commonMain and shared with
     * AndroidAppGraph. This block used to be ~110 lines duplicated here; keeping two copies is what
     * left Playing Tonight and Psync wired on Android only. Web now gets them for free — the
     * surfaces appear because the graph is shared, not because someone remembered.
     */
    private val common =
        PynapliCommonGraph(
            apiClient = apiClient,
            // NoOp stores until a localStorage snapshot store earns its place (WEB_PWA_GAMEPLAN W2/W4).
            gridSnapshotStore = NoOpGridSnapshotStore,
            swipeQueueStore = NoOpSwipeActionQueueStore,
            // Store is founder-locked read-only on web.
            storePurchaseGateway = UnavailableStorePurchaseGateway(),
            // First-frame-auth sockets: browsers cannot set WebSocket headers (W3).
            presenceStreamClient =
                buildWebPresenceStreamClient(
                    websocketBaseUrl = pynapliConfig.websocketBaseUrl,
                    tokenProvider = sessionStore,
                ),
            activityStreamClient =
                buildWebActivityStreamClient(
                    websocketBaseUrl = pynapliConfig.websocketBaseUrl,
                    tokenProvider = sessionStore,
                ),
            messageStreamClient =
                buildWebMessageStreamClient(
                    websocketBaseUrl = pynapliConfig.websocketBaseUrl,
                    tokenProvider = sessionStore,
                ),
        )

    val gridStateHolder get() = common.gridStateHolder
    val savedGridFilterStateHolder get() = common.savedGridFilterStateHolder
    val savedSwipeFilterStateHolder get() = common.savedSwipeFilterStateHolder
    val travelLocationStateHolder get() = common.travelLocationStateHolder
    val tonightAvailabilityStateHolder get() = common.tonightAvailabilityStateHolder
    val playingTonightStateHolder get() = common.playingTonightStateHolder
    val psyncRepository get() = common.psyncRepository
    val profileDetailStateHolder get() = common.profileDetailStateHolder
    val conversationStateHolder get() = common.conversationStateHolder
    val messagesStateHolder get() = common.messagesStateHolder
    val activityStateHolder get() = common.activityStateHolder
    val locationStateHolder get() = common.locationStateHolder
    val mediaAlbumsStateHolder get() = common.mediaAlbumsStateHolder
    val profileHubStateHolder get() = common.profileHubStateHolder
    val profileLinksStateHolder get() = common.profileLinksStateHolder
    val swipeStateHolder get() = common.swipeStateHolder
    val storeStateHolder get() = common.storeStateHolder
    val gridPresenceStreamCoordinator get() = common.gridPresenceStreamCoordinator
    val activityRealtimeCoordinator get() = common.activityRealtimeCoordinator
    val messagesRealtimeCoordinator get() = common.messagesRealtimeCoordinator

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
