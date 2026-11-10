package com.pynapli.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.pynapli.app.appcheck.AndroidFirebaseAppCheckTokenProvider
import com.pynapli.app.appsettings.ApiNotificationPreferencesGateway
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.LocalAppSettingsRepository
import com.pynapli.app.appsettings.ServerBackedAppSettingsRepository
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.billing.AndroidPlayBillingPurchaseGateway
import com.pynapli.app.debug.DebugQaFlags
import com.pynapli.app.grid.SqlDelightGridSnapshotStore
import com.pynapli.app.notifications.AndroidFirebasePushTokenProvider
import com.pynapli.app.notifications.AndroidPushRegistrationStore
import com.pynapli.app.notifications.ApiPushDeviceRegistrationRepository
import com.pynapli.app.notifications.PushRegistrationCoordinator
import com.pynapli.app.onboarding.ApiOnboardingProfileRepository
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.store.DevStorePurchaseGateway
import com.pynapli.app.store.StorePurchaseGateway
import com.pynapli.app.swipe.ApiSwipeRepository
import com.pynapli.app.swipe.DebugFaultInjectingSwipeRepository
import com.pynapli.app.swipe.SqlDelightSwipeActionQueueStore
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionInvalidationBus
import com.pynapli.shared.auth.SqlDelightSessionStore
import com.pynapli.shared.auth.StoredSessionRefresher
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.network.SessionRefresherHolder
import com.pynapli.shared.network.androidRealtimeStreams
import com.pynapli.shared.settings.SqlDelightAppSettingsStore
import com.pynapli.shared.settings.SqlDelightOnboardingProgressStore
import com.pynapli.shared.storage.AndroidPynapliDatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.Locale
import java.util.TimeZone

class AndroidAppGraph(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val database = AndroidPynapliDatabaseFactory(context.applicationContext).createDatabase()
    private val sessionStore = SqlDelightSessionStore(database)
    private val pynapliConfig = AndroidRuntimeConfig.pynapliConfig

    // The HTTP client needs a refresher to recover from the 15-minute access-token TTL, but the
    // refresher needs the client to call /v1/auth/refresh. The holder breaks that cycle: install it
    // now, point it at the real implementation once the client exists.
    private val sessionRefresher = SessionRefresherHolder()

    // Terminal mid-session invalidation (revoked session / suspended account) flows from the
    // refresher to the auth state holder through this bus, dropping the app back to sign-in.
    private val sessionInvalidationBus = SessionInvalidationBus()
    private val apiClient =
        KtorPynapliApiClient(
            config = pynapliConfig,
            accessTokenProvider = sessionStore,
            appCheckTokenProvider = AndroidFirebaseAppCheckTokenProvider(appContext),
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
    private val playBillingPurchaseGateway =
        AndroidPlayBillingPurchaseGateway(
            context = appContext,
            apiClient = apiClient,
            packageNameProvider = { appContext.packageName },
        )
    private val storePurchaseGateway: StorePurchaseGateway =
        if (DebugQaFlags.isDebuggable(appContext)) {
            DevStorePurchaseGateway(apiClient)
        } else {
            playBillingPurchaseGateway
        }

    val deepLinkStateHolder = AppDeepLinkStateHolder()

    val pushRegistrationCoordinator =
        PushRegistrationCoordinator(
            tokenProvider = AndroidFirebasePushTokenProvider(appContext),
            repository = ApiPushDeviceRegistrationRepository(apiClient),
            registrationStore = AndroidPushRegistrationStore(appContext),
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            localeProvider = { Locale.getDefault().toLanguageTag() },
            timezoneProvider = { TimeZone.getDefault().id },
            notificationsAllowed = {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )

    val authStateHolder =
        AuthStateHolder(
            authRepository = authRepository,
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            deviceId = stableInstallId(appContext),
            beforeLogout = { pushRegistrationCoordinator.unregisterCurrentDevice() },
            sessionInvalidations = sessionInvalidationBus.events,
        )

    val appSettingsStateHolder =
        AppSettingsStateHolder(
            repository =
                ServerBackedAppSettingsRepository(
                    localRepository =
                        LocalAppSettingsRepository(
                            store = SqlDelightAppSettingsStore(database),
                        ),
                    notificationPreferencesGateway = ApiNotificationPreferencesGateway(apiClient),
                    timezoneProvider = { TimeZone.getDefault().id },
                    remoteSyncEnabled = { authStateHolder.state.value.authenticated },
                ),
        )

    val onboardingStateHolder =
        OnboardingStateHolder(
            authStateHolder = authStateHolder,
            profileRepository = ApiOnboardingProfileRepository(apiClient),
            // Dedicated store: onboarding progress must not share the AppSettings
            // blob that the server-backed settings sync read-merge-saves.
            progressStore = SqlDelightOnboardingProgressStore(database),
        )

    /** All realtime WebSocket streams, sharing one client and connection pool. */
    private val realtimeStreams = androidRealtimeStreams(pynapliConfig.websocketBaseUrl, sessionStore)

    // Realtime convenience channel for an open Psync session (refetch on partner-side changes).
    // Not part of the common graph: the per-viewer PsyncStateHolder is built in PynapliApp, so the
    // shell only needs to hand the client through.
    val psyncStreamClient =
        realtimeStreams.psync

    /**
     * Everything downstream of the API client, built once in commonMain. This block used to be
     * ~140 lines duplicated in WebAppGraph; the two drifted (Playing Tonight and Psync were
     * Android-only purely because the web shell had not been updated). Only genuinely
     * platform-owned pieces are passed in below.
     */
    private val common =
        PynapliCommonGraph(
            apiClient = apiClient,
            gridSnapshotStore = SqlDelightGridSnapshotStore(database),
            swipeQueueStore = SqlDelightSwipeActionQueueStore(database),
            // Debug builds inject swipe failures for the connected QA (android-swipe-queue-qa.ps1).
            swipeRepository =
                DebugFaultInjectingSwipeRepository(
                    context = appContext,
                    delegate = ApiSwipeRepository(apiClient),
                ),
            storePurchaseGateway = storePurchaseGateway,
            presenceStreamClient =
                realtimeStreams.presence,
            activityStreamClient =
                realtimeStreams.activity,
            messageStreamClient =
                realtimeStreams.message,
        )

    /** The object MainActivity hands to `PynapliApp`: every common holder plus this shell's pieces. */
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

    fun attachBillingActivity(activity: Activity) {
        playBillingPurchaseGateway.attachActivity(activity)
    }

    fun detachBillingActivity(activity: Activity) {
        playBillingPurchaseGateway.detachActivity(activity)
    }

    /** Non-null only in debug builds (per-build-type `DevLoginDefaults.kt`). */
    val devLoginCredentials: DevLoginCredentials? = devLoginDefaults()

    val testerDiagnosticLabel: String? = AndroidRuntimeConfig.testerDiagnosticLabel
}
