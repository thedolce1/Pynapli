package com.pynapli.app

import com.pynapli.app.appsettings.ApiNotificationPreferencesGateway
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.LocalAppSettingsRepository
import com.pynapli.app.appsettings.ServerBackedAppSettingsRepository
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.grid.SqlDelightGridSnapshotStore
import com.pynapli.app.onboarding.ApiOnboardingProfileRepository
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.store.UnavailableStorePurchaseGateway
import com.pynapli.app.swipe.ApiSwipeRepository
import com.pynapli.app.swipe.SqlDelightSwipeActionQueueStore
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.KeychainSessionTokenCipher
import com.pynapli.shared.auth.SessionInvalidationBus
import com.pynapli.shared.auth.SqlDelightSessionStore
import com.pynapli.shared.auth.StoredSessionRefresher
import com.pynapli.shared.auth.createPlatformSessionTokenCipher
import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.network.NoAppCheckTokenProvider
import com.pynapli.shared.network.SessionRefresherHolder
import com.pynapli.shared.network.iosRealtimeStreams
import com.pynapli.shared.settings.SqlDelightAppSettingsStore
import com.pynapli.shared.settings.SqlDelightOnboardingProgressStore
import com.pynapli.shared.settings.SqlDelightPendingSignupStore
import com.pynapli.shared.storage.IosPynapliDatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import platform.Foundation.NSBundle

/**
 * iOS mirror of `AndroidAppGraph` / `WebAppGraph`: the session-auth chain plus the platform seam
 * that [PynapliCommonGraph] needs. Everything downstream of the API client comes from the common
 * graph, so this file stays small by design — that is the whole point of `PynapliCommonGraph`.
 *
 * STATUS — written on Windows and type-checked by `compileKotlinIosSimulatorArm64`, but never LINKED
 * or RUN: Kotlin/Native can build an iOS klib on a non-Mac host, while producing a framework needs
 * macOS. Treat this as "the compiler agrees the wiring is consistent", not as working code. See
 * docs/IOS_BRINGUP_PLAN.md.
 *
 * TWO THINGS ARE DELIBERATELY NOT DONE HERE, both requiring a Mac:
 *
 *  1. [sessionStore] uses the platform cipher, which on iOS is still a pass-through — so session
 *     tokens are stored UNENCRYPTED. That is the blocker in step 3 of the plan and must close before
 *     any build reaches a user. It is not fixed here because cryptographic code that compiles but
 *     was never executed is worse than a documented gap: it looks solved.
 *  2. Push registration and StoreKit. The Store ships read-only first, exactly as web does.
 */
class IosAppGraph(
    private val pynapliConfig: PynapliConfig,
    /**
     * Stable per-install identifier feeding the account-farm risk signal, supplied by the Swift
     * shell. A random UUID persisted in the Keychain, NOT `identifierForVendor` or any hardware id —
     * matching the choice already documented for Android in `AndroidInstallId.kt` and enforced by
     * Play policy there. Passed in rather than built here because the persistence is platform code.
     */
    installId: String,
    /**
     * Keychain access, supplied by the Swift shell — the same seam [installId] uses, and for the same
     * reason: this is platform code that belongs where it can be compiled and run.
     *
     * Leave null ONLY for a throwaway dev build. Null means the session falls back to
     * [createPlatformSessionTokenCipher]'s pass-through and **tokens are stored in plaintext**, which
     * is the open blocker in docs/IOS_BRINGUP_PLAN.md step 3. Supply it before any build reaches a
     * user; see [KeychainSessionTokenCipher] for exactly what the Swift side must do.
     */
    keychain: IosKeychainBridge? = null,
) {
    private val database = IosPynapliDatabaseFactory().createDatabase()
    private val sessionStore =
        SqlDelightSessionStore(
            database = database,
            cipher =
                keychain?.let {
                    KeychainSessionTokenCipher(read = it.read, write = it.write, delete = it.delete)
                } ?: createPlatformSessionTokenCipher(),
        )

    // Same construction cycle as the other shells: the HTTP client needs a refresher to recover from
    // the access-token TTL, and the refresher needs the client to call /v1/auth/refresh.
    private val sessionRefresher = SessionRefresherHolder()
    private val sessionInvalidationBus = SessionInvalidationBus()

    private val apiClient =
        KtorPynapliApiClient(
            config = pynapliConfig,
            accessTokenProvider = sessionStore,
            // App Check on Apple uses DeviceCheck/App Attest via the Firebase Apple SDK, which is
            // Swift-side and lands with the Firebase step of the plan.
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

    private val authRepository = AuthRepository(apiClient = apiClient, sessionStore = sessionStore)

    val deepLinkStateHolder = AppDeepLinkStateHolder()

    val authStateHolder =
        AuthStateHolder(
            authRepository = authRepository,
            platform = "ios",
            appVersion = bundleVersionName(),
            deviceId = installId,
            // No beforeLogout hook yet: Android uses it to unregister the push device, which lands
            // with the APNs step of the plan.
            sessionInvalidations = sessionInvalidationBus.events,
        )

    val appSettingsStateHolder =
        AppSettingsStateHolder(
            repository =
                ServerBackedAppSettingsRepository(
                    localRepository = LocalAppSettingsRepository(store = SqlDelightAppSettingsStore(database)),
                    notificationPreferencesGateway = ApiNotificationPreferencesGateway(apiClient),
                    timezoneProvider = { TimeZone.currentSystemDefault().id },
                    remoteSyncEnabled = { authStateHolder.state.value.authenticated },
                ),
        )

    val onboardingStateHolder =
        OnboardingStateHolder(
            authStateHolder = authStateHolder,
            profileRepository = ApiOnboardingProfileRepository(apiClient),
            progressStore = SqlDelightOnboardingProgressStore(database),
            // The platform default cipher (pass-through on iOS until the Keychain phase, like the
            // session default); never the shell's Keychain cipher, whose single slot is the session's.
            pendingSignupStore = SqlDelightPendingSignupStore(database),
        )

    /** All realtime WebSocket streams, sharing one client and connection pool. */
    private val realtimeStreams = iosRealtimeStreams(pynapliConfig.websocketBaseUrl, sessionStore)

    private val common =
        PynapliCommonGraph(
            apiClient = apiClient,
            gridSnapshotStore = SqlDelightGridSnapshotStore(database),
            swipeQueueStore = SqlDelightSwipeActionQueueStore(database),
            swipeRepository = ApiSwipeRepository(apiClient),
            // Read-only Store for the first iOS release; StoreKit 2 is a later slice.
            storePurchaseGateway = UnavailableStorePurchaseGateway(),
            presenceStreamClient =
                realtimeStreams.presence,
            activityStreamClient =
                realtimeStreams.activity,
            messageStreamClient =
                realtimeStreams.message,
        )

    /** Psync realtime channel; the per-viewer holder is built inside `PynapliApp`. */
    val psyncStreamClient = realtimeStreams.psync

    /** The single object the Swift shell hands to `PynapliApp`. */
    val pynapliAppGraph: PynapliAppGraph =
        common.appGraph(
            authStateHolder = authStateHolder,
            appSettingsStateHolder = appSettingsStateHolder,
            deepLinkStateHolder = deepLinkStateHolder,
            onboardingStateHolder = onboardingStateHolder,
            psyncStreamClient = psyncStreamClient,
        )

    private val realtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var realtimeJobs: List<Job> = emptyList()

    /**
     * Start the realtime convenience streams. Call from `applicationDidBecomeActive` (or SwiftUI
     * `scenePhase == .active`).
     *
     * This exists as an explicit pair with [stopRealtime] because Android does not leave it to
     * chance: `scripts/android-location-policy-check.ps1` FAILS THE BUILD if `MainActivity.onStop`
     * does not cancel the presence stream, the activity stream, and any pending location request.
     * iOS has no equivalent gate, so the requirement is encoded in the API shape instead — sockets
     * left running in the background burn battery and reconnect-churn on resume.
     *
     * NOTE — this duplicates a framework feature, knowingly. Compose Multiplatform provides a common
     * `LifecycleOwner` that already maps `didBecomeActive`/`willResignActive` to `ON_RESUME`/`ON_PAUSE`,
     * so this could be observed in shared code with no Swift hook at all. It is hand-rolled because
     * the project declares no `androidx.lifecycle` dependency and Android drives the same transitions
     * from `MainActivity.onStop`; adopting the common lifecycle is a slice across both shells, not an
     * iOS detail. See "Lifecycle" in docs/IOS_BRINGUP_PLAN.md step 2.
     *
     * Idempotent, and main-thread only: iOS lifecycle callbacks are delivered on the main thread and
     * [realtimeJobs] is not synchronised.
     */
    fun startRealtime() {
        if (realtimeJobs.isNotEmpty()) return
        realtimeJobs = common.startRealtime(realtimeScope)
        // Coming back from the background: the sockets were down, so refetch what they missed.
        realtimeScope.launch { common.onAppForegrounded(signedIn = authStateHolder.state.value.authenticated) }
    }

    /**
     * Stop them again. Call from `applicationDidEnterBackground` (or `scenePhase != .active`).
     *
     * HTTP stays canonical, so nothing is lost by dropping the sockets — the surfaces refetch on
     * resume. That is the same contract Android relies on in `MainActivity.onStop`.
     *
     * Web deliberately does NOT do this: its `visibilitychange` handler only refetches when the tab
     * becomes visible again, and the three coordinators run for the life of the page. A browser tab
     * is not a backgrounded app — there is no battery gate and no OS teardown — so the socket stays.
     */
    fun stopRealtime() {
        realtimeJobs.forEach { it.cancel() }
        realtimeJobs = emptyList()
    }
}

/** The app's marketing version (Xcode MARKETING_VERSION), sent with auth requests. */
private fun bundleVersionName(): String = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.0.0"
