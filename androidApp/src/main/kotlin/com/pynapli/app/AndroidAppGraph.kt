package com.pynapli.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.ApiNotificationPreferencesGateway
import com.pynapli.app.appsettings.LocalAppSettingsRepository
import com.pynapli.app.appsettings.ServerBackedAppSettingsRepository
import com.pynapli.app.activity.ApiActivityFeedRepository
import com.pynapli.app.activity.ActivityRealtimeCoordinator
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
import com.pynapli.app.grid.TonightAvailabilityStateHolder
import com.pynapli.app.location.ApiLocationUpdateRepository
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.media.ApiMediaAlbumsRepository
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.messages.ApiMessagesRepository
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.notifications.AndroidFirebasePushTokenProvider
import com.pynapli.app.notifications.AndroidPushRegistrationStore
import com.pynapli.app.notifications.ApiPushDeviceRegistrationRepository
import com.pynapli.app.notifications.PushRegistrationCoordinator
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
import com.pynapli.app.swipe.ApiTopPicksRepository
import com.pynapli.app.swipe.DebugFaultInjectingSwipeRepository
import com.pynapli.app.swipe.SavedSwipeFilterStateHolder
import com.pynapli.app.swipe.SqlDelightSwipeActionQueueStore
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.app.swipe.TopPicksStateHolder
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SqlDelightSessionStore
import com.pynapli.shared.core.config.PynapliConfigs
import com.pynapli.shared.network.KtorMediaByteUploader
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.network.buildAndroidActivityStreamClient
import com.pynapli.shared.network.buildAndroidPresenceStreamClient
import com.pynapli.shared.settings.SqlDelightAppSettingsStore
import com.pynapli.shared.storage.AndroidPynapliDatabaseFactory
import java.util.Locale
import java.util.TimeZone

class AndroidAppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val database = AndroidPynapliDatabaseFactory(context.applicationContext).createDatabase()
    private val sessionStore = SqlDelightSessionStore(database)
    private val apiClient = KtorPynapliApiClient(
        config = PynapliConfigs.LocalAndroidEmulator,
        accessTokenProvider = sessionStore
    )
    private val authRepository = AuthRepository(
        apiClient = apiClient,
        sessionStore = sessionStore
    )
    private val playBillingPurchaseGateway = AndroidPlayBillingPurchaseGateway(
        context = appContext,
        apiClient = apiClient,
        packageNameProvider = { appContext.packageName }
    )
    private val storePurchaseGateway: StorePurchaseGateway =
        if (DebugQaFlags.isDebuggable(appContext)) {
            DevStorePurchaseGateway(apiClient)
        } else {
            playBillingPurchaseGateway
        }

    val deepLinkStateHolder = AppDeepLinkStateHolder()

    val pushRegistrationCoordinator = PushRegistrationCoordinator(
        tokenProvider = AndroidFirebasePushTokenProvider(appContext),
        repository = ApiPushDeviceRegistrationRepository(apiClient),
        registrationStore = AndroidPushRegistrationStore(appContext),
        platform = "android",
        appVersion = "0.1.0",
        localeProvider = { Locale.getDefault().toLanguageTag() },
        timezoneProvider = { TimeZone.getDefault().id },
        notificationsAllowed = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
    )

    val authStateHolder = AuthStateHolder(
        authRepository = authRepository,
        platform = "android",
        appVersion = "0.1.0",
        beforeLogout = { pushRegistrationCoordinator.unregisterCurrentDevice() }
    )

    val appSettingsStateHolder = AppSettingsStateHolder(
        repository = ServerBackedAppSettingsRepository(
            localRepository = LocalAppSettingsRepository(
                store = SqlDelightAppSettingsStore(database)
            ),
            notificationPreferencesGateway = ApiNotificationPreferencesGateway(apiClient),
            timezoneProvider = { TimeZone.getDefault().id },
            remoteSyncEnabled = { authStateHolder.state.value.authenticated }
        )
    )

    val gridStateHolder = GridStateHolder(
        repository = ApiGridFeedRepository(apiClient)
    )

    val savedGridFilterStateHolder = SavedGridFilterStateHolder(
        repository = ApiSavedGridFilterRepository(apiClient)
    )

    val savedSwipeFilterStateHolder = SavedSwipeFilterStateHolder(
        repository = ApiSavedSwipeFilterRepository(apiClient)
    )

    val topPicksStateHolder = TopPicksStateHolder(
        repository = ApiTopPicksRepository(apiClient)
    )

    val travelLocationStateHolder = com.pynapli.app.location.TravelLocationStateHolder(
        repository = com.pynapli.app.location.ApiTravelLocationRepository(apiClient)
    )

    val tonightAvailabilityStateHolder = TonightAvailabilityStateHolder(
        repository = ApiTonightAvailabilityRepository(apiClient)
    )

    val gridPresenceStreamCoordinator = GridPresenceStreamCoordinator(
        client = buildAndroidPresenceStreamClient(
            websocketBaseUrl = PynapliConfigs.LocalAndroidEmulator.websocketBaseUrl,
            tokenProvider = sessionStore
        ),
        gridStateHolder = gridStateHolder
    )

    val profileDetailStateHolder = ProfileDetailStateHolder(
        repository = ApiPublicProfileRepository(apiClient)
    )

    val conversationStateHolder = ConversationStateHolder(
        repository = ApiConversationRepository(apiClient)
    )

    val messagesStateHolder = MessagesStateHolder(
        repository = ApiMessagesRepository(apiClient)
    )

    val activityStateHolder = ActivityStateHolder(
        repository = ApiActivityFeedRepository(apiClient)
    )

    val activityRealtimeCoordinator = ActivityRealtimeCoordinator(
        client = buildAndroidActivityStreamClient(
            websocketBaseUrl = PynapliConfigs.LocalAndroidEmulator.websocketBaseUrl,
            tokenProvider = sessionStore
        ),
        activityStateHolder = activityStateHolder
    )

    val locationStateHolder = LocationStateHolder(
        repository = ApiLocationUpdateRepository(apiClient)
    )

    val mediaAlbumsStateHolder = MediaAlbumsStateHolder(
        repository = ApiMediaAlbumsRepository(
            apiClient = apiClient,
            mediaByteUploader = KtorMediaByteUploader()
        )
    )

    val profileHubStateHolder = ProfileHubStateHolder(
        repository = ApiProfileHubRepository(apiClient)
    )

    val profileLinksStateHolder = ProfileLinksStateHolder(
        repository = ApiProfileLinksRepository(apiClient)
    )

    val swipeStateHolder = SwipeStateHolder(
        repository = DebugFaultInjectingSwipeRepository(
            context = appContext,
            delegate = ApiSwipeRepository(apiClient)
        ),
        queueStore = SqlDelightSwipeActionQueueStore(database)
    )

    val storeStateHolder = StoreStateHolder(
        repository = ApiStoreRepository(apiClient),
        purchaseGateway = storePurchaseGateway
    )

    fun attachBillingActivity(activity: Activity) {
        playBillingPurchaseGateway.attachActivity(activity)
    }

    fun detachBillingActivity(activity: Activity) {
        playBillingPurchaseGateway.detachActivity(activity)
    }

    val devLoginCredentials = DevLoginCredentials(
        email = "dev@pynapli.local",
        password = "PynapliDevPass123!"
    )
}
