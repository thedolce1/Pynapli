package com.pynapli.desktop

import com.pynapli.app.AppDeepLinkStateHolder
import com.pynapli.app.PynapliAppGraph
import com.pynapli.app.PynapliCommonGraph
import com.pynapli.app.appsettings.ApiNotificationPreferencesGateway
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.LocalAppSettingsRepository
import com.pynapli.app.appsettings.ServerBackedAppSettingsRepository
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.auth.UnavailableSocialSignInProvider
import com.pynapli.app.grid.AccountBoundGridFilterStore
import com.pynapli.app.grid.NoOpGridSnapshotStore
import com.pynapli.app.onboarding.ApiOnboardingProfileRepository
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.store.UnavailableStorePurchaseGateway
import com.pynapli.app.swipe.ApiSwipeRepository
import com.pynapli.app.swipe.NoOpSwipeActionQueueStore
import com.pynapli.desktop.auth.DesktopSessionStore
import com.pynapli.desktop.platform.DesktopStorage
import com.pynapli.desktop.settings.DesktopAppSettingsStore
import com.pynapli.desktop.settings.DesktopOnboardingProgressStore
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionInvalidationBus
import com.pynapli.shared.auth.SessionMutationLock
import com.pynapli.shared.auth.StoredSessionRefresher
import com.pynapli.shared.core.config.PynapliEnvironment
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.network.NoAppCheckTokenProvider
import com.pynapli.shared.network.SessionRefresherHolder
import com.pynapli.shared.network.jvmRealtimeStreams
import com.pynapli.shared.settings.InMemoryPendingSignupStore
import com.pynapli.shared.settings.InMemoryUnderageStopStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.datetime.TimeZone
import kotlin.random.Random

/**
 * Desktop mirror of `AndroidAppGraph` / `WebAppGraph` / `IosAppGraph`.
 *
 * The point of this shell is UI iteration speed on a Windows host: it renders the exact
 * `:composeApp` screens iOS will render, with near-instant rebuilds and no emulator. Everything
 * downstream of the API client comes from [PynapliCommonGraph], so — as on web — surfaces appear
 * here because the graph is shared, not because someone remembered to wire them.
 *
 * Deliberately absent, matching web v1: push registration, billing (Store is read-only via
 * [UnavailableStorePurchaseGateway]), and the grid/swipe local caches (NoOp stores — a desktop
 * snapshot cache would be testing a store no mobile shell uses).
 *
 * NOT a shippable target. See `DesktopRuntimeConfig` for the local-only guard and
 * `DesktopSessionStore` for the plaintext-token caveat.
 */
class DesktopAppGraph {
    private val pynapliConfig = DesktopRuntimeConfig.pynapliConfig
    private val sessionStore = DesktopSessionStore()

    // Same construction cycle as every other shell: the client needs a refresher to survive the
    // access-token TTL, and the refresher needs the client to call /v1/auth/refresh.
    private val sessionRefresher = SessionRefresherHolder()
    private val sessionInvalidationBus = SessionInvalidationBus()

    // One lock for this store, shared by the refresher and AuthRepository: sign-out waits for a
    // refresh in flight rather than being overwritten by it.
    private val sessionLock = SessionMutationLock()

    private val apiClient =
        KtorPynapliApiClient(
            config = pynapliConfig,
            accessTokenProvider = sessionStore,
            // App Check has no desktop attestation provider, and this shell never targets prod.
            appCheckTokenProvider = NoAppCheckTokenProvider,
            sessionRefresher = sessionRefresher,
        ).also { client ->
            sessionRefresher.delegate =
                StoredSessionRefresher(
                    authApi = client,
                    sessionStore = sessionStore,
                    invalidationBus = sessionInvalidationBus,
                    lock = sessionLock,
                )
        }

    private val authRepository = AuthRepository(apiClient = apiClient, sessionStore = sessionStore, sessionLock = sessionLock)

    val deepLinkStateHolder = AppDeepLinkStateHolder()

    val authStateHolder =
        AuthStateHolder(
            authRepository = authRepository,
            platform = "desktop",
            appVersion = "0.1.0",
            deviceId = stableDesktopInstallId(),
            sessionInvalidations = sessionInvalidationBus.events,
            // Dev-only shell: no provider integration, so the social buttons never show here.
            socialSignInProvider = UnavailableSocialSignInProvider,
        )

    val appSettingsStateHolder =
        AppSettingsStateHolder(
            repository =
                ServerBackedAppSettingsRepository(
                    localRepository = LocalAppSettingsRepository(store = DesktopAppSettingsStore()),
                    notificationPreferencesGateway = ApiNotificationPreferencesGateway(apiClient),
                    timezoneProvider = { TimeZone.currentSystemDefault().id },
                    remoteSyncEnabled = { authStateHolder.state.value.authenticated },
                ),
        )

    val onboardingStateHolder =
        OnboardingStateHolder(
            authStateHolder = authStateHolder,
            profileRepository = ApiOnboardingProfileRepository(apiClient),
            progressStore = DesktopOnboardingProgressStore(),
            // Dev-only shell: memory only (a restart before the code starts the sign-up over).
            pendingSignupStore = InMemoryPendingSignupStore(),
            underageStopStore = InMemoryUnderageStopStore(),
        )

    /** All realtime WebSocket streams, sharing one client and connection pool. */
    private val realtimeStreams = jvmRealtimeStreams(pynapliConfig.websocketBaseUrl, sessionStore)

    private val common =
        PynapliCommonGraph(
            apiClient = apiClient,
            gridSnapshotStore = NoOpGridSnapshotStore,
            // No SQLDelight on desktop (see DesktopStorage): the filters go in a plain file.
            gridFilterStore =
                AccountBoundGridFilterStore(
                    currentUserId = {
                        authStateHolder.state.value.session
                            ?.userId
                    },
                    read = { DesktopStorage.get(GRID_FILTERS_KEY) },
                    write = { value -> DesktopStorage.set(GRID_FILTERS_KEY, value) },
                    remove = { DesktopStorage.remove(GRID_FILTERS_KEY) },
                ),
            swipeQueueStore = NoOpSwipeActionQueueStore,
            swipeRepository = ApiSwipeRepository(apiClient),
            storePurchaseGateway = UnavailableStorePurchaseGateway(),
            presenceStreamClient =
                realtimeStreams.presence,
            activityStreamClient =
                realtimeStreams.activity,
            messageStreamClient =
                realtimeStreams.message,
            socialSignInProvider = UnavailableSocialSignInProvider,
        )

    /** Psync realtime channel; the per-viewer holder is built inside `PynapliApp`. */
    val psyncStreamClient = realtimeStreams.psync

    /** The single object the desktop entry point hands to `PynapliApp`. */
    val pynapliAppGraph: PynapliAppGraph =
        common.appGraph(
            authStateHolder = authStateHolder,
            appSettingsStateHolder = appSettingsStateHolder,
            deepLinkStateHolder = deepLinkStateHolder,
            onboardingStateHolder = onboardingStateHolder,
            psyncStreamClient = psyncStreamClient,
        )

    /** Starts grid presence, activity and messages realtime; see [PynapliCommonGraph.startRealtime]. */
    fun startRealtime(scope: CoroutineScope): List<Job> = common.startRealtime(scope)

    /** Matches the web shell: the dev-login shortcut appears only against a local server. */
    val devLoginCredentials: DevLoginCredentials? =
        if (pynapliConfig.environment == PynapliEnvironment.Local) {
            DevLoginCredentials(
                email = "dev@pynapli.local",
                password = "PynapliDevPass123!",
            )
        } else {
            null
        }

    private companion object {
        private const val INSTALL_ID_KEY = "install-id-v1"
        private const val GRID_FILTERS_KEY = "grid-filters-v1"
        private const val HEX_CHARS = "0123456789abcdef"

        /**
         * Per-machine random identifier for the account-farm risk signal — parity with Android's
         * `stableInstallId` and web's `stableWebInstallId`. A random value, NOT a hardware id or
         * MAC address; it resets when the `.pynapli` directory is cleared.
         */
        private fun stableDesktopInstallId(): String {
            DesktopStorage.get(INSTALL_ID_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
            val id = buildString { repeat(32) { append(HEX_CHARS[Random.nextInt(HEX_CHARS.length)]) } }
            DesktopStorage.set(INSTALL_ID_KEY, id)
            return id
        }
    }
}
