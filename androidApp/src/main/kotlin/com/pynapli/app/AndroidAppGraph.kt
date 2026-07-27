package com.pynapli.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.pynapli.app.activity.ActivityRealtimeCoordinator
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.activity.ApiActivityFeedRepository
import com.pynapli.app.appcheck.AndroidFirebaseAppCheckTokenProvider
import com.pynapli.app.appsettings.ApiNotificationPreferencesGateway
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.LocalAppSettingsRepository
import com.pynapli.app.appsettings.ServerBackedAppSettingsRepository
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.billing.AndroidPlayBillingPurchaseGateway
import com.pynapli.app.conversation.ApiConversationRepository
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.debug.DebugQaFlags
import com.pynapli.app.grid.ApiGridFeedRepository
import com.pynapli.app.grid.ApiSavedGridFilterRepository
import com.pynapli.app.grid.ApiTonightAvailabilityRepository
import com.pynapli.app.grid.GridPresenceStreamCoordinator
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.grid.SavedGridFilterStateHolder
import com.pynapli.app.grid.SqlDelightGridSnapshotStore
import com.pynapli.app.grid.TonightAvailabilityStateHolder
import com.pynapli.app.location.ApiLocationUpdateRepository
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.media.ApiMediaAlbumsRepository
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.messages.ApiMessagesRepository
import com.pynapli.app.messages.MessagesRealtimeCoordinator
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.notifications.AndroidFirebasePushTokenProvider
import com.pynapli.app.notifications.AndroidPushRegistrationStore
import com.pynapli.app.notifications.ApiPushDeviceRegistrationRepository
import com.pynapli.app.notifications.PushRegistrationCoordinator
import com.pynapli.app.onboarding.ApiOnboardingProfileRepository
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.profile.ApiPublicProfileRepository
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilehub.ApiProfileHubRepository
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ApiProfileLinksRepository
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.store.ApiStoreRepository
import com.pynapli.app.store.DevStorePurchaseGateway
import com.pynapli.app.store.StorePurchaseGateway
import com.pynapli.app.store.StoreStateHolder
import com.pynapli.app.swipe.ApiSavedSwipeFilterRepository
import com.pynapli.app.swipe.ApiSwipeRepository
import com.pynapli.app.swipe.DebugFaultInjectingSwipeRepository
import com.pynapli.app.swipe.SavedSwipeFilterStateHolder
import com.pynapli.app.swipe.SqlDelightSwipeActionQueueStore
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SessionInvalidationBus
import com.pynapli.shared.auth.SqlDelightSessionStore
import com.pynapli.shared.auth.StoredSessionRefresher
import com.pynapli.shared.network.KtorMediaByteUploader
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.network.SessionRefresherHolder
import com.pynapli.shared.network.buildAndroidActivityStreamClient
import com.pynapli.shared.network.buildAndroidMessageStreamClient
import com.pynapli.shared.network.buildAndroidPresenceStreamClient
import com.pynapli.shared.network.buildAndroidPsyncStreamClient
import com.pynapli.shared.settings.SqlDelightAppSettingsStore
import com.pynapli.shared.settings.SqlDelightOnboardingProgressStore
import com.pynapli.shared.storage.AndroidPynapliDatabaseFactory
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
            appVersion = "0.1.0",
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
            appVersion = "0.1.0",
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

    // Realtime convenience channel for an open Psync session (refetch on partner-side changes).
    // Not part of the common graph: the per-viewer PsyncStateHolder is built in PynapliApp, so the
    // shell only needs to hand the client through.
    val psyncStreamClient =
        buildAndroidPsyncStreamClient(
            websocketBaseUrl = pynapliConfig.websocketBaseUrl,
            tokenProvider = sessionStore,
        )

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
                buildAndroidPresenceStreamClient(
                    websocketBaseUrl = pynapliConfig.websocketBaseUrl,
                    tokenProvider = sessionStore,
                ),
            activityStreamClient =
                buildAndroidActivityStreamClient(
                    websocketBaseUrl = pynapliConfig.websocketBaseUrl,
                    tokenProvider = sessionStore,
                ),
            messageStreamClient =
                buildAndroidMessageStreamClient(
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

    fun attachBillingActivity(activity: Activity) {
        playBillingPurchaseGateway.attachActivity(activity)
    }

    fun detachBillingActivity(activity: Activity) {
        playBillingPurchaseGateway.detachActivity(activity)
    }

    val devLoginCredentials =
        DevLoginCredentials(
            email = "dev@pynapli.local",
            password = "PynapliDevPass123!",
        )

    val testerDiagnosticLabel: String? = AndroidRuntimeConfig.testerDiagnosticLabel
}
