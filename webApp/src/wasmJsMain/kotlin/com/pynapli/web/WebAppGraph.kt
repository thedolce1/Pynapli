package com.pynapli.web

import com.pynapli.app.AppDeepLinkStateHolder
import com.pynapli.app.activity.ActivityRealtimeCoordinator
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.activity.ApiActivityFeedRepository
import com.pynapli.app.appsettings.ApiNotificationPreferencesGateway
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.LocalAppSettingsRepository
import com.pynapli.app.appsettings.ServerBackedAppSettingsRepository
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.conversation.ApiConversationRepository
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.grid.ApiGridFeedRepository
import com.pynapli.app.grid.ApiSavedGridFilterRepository
import com.pynapli.app.grid.ApiTonightAvailabilityRepository
import com.pynapli.app.grid.GridPresenceStreamCoordinator
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.grid.NoOpGridSnapshotStore
import com.pynapli.app.grid.SavedGridFilterStateHolder
import com.pynapli.app.grid.TonightAvailabilityStateHolder
import com.pynapli.app.location.ApiLocationUpdateRepository
import com.pynapli.app.location.ApiTravelLocationRepository
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.location.TravelLocationStateHolder
import com.pynapli.app.media.ApiMediaAlbumsRepository
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.messages.ApiMessagesRepository
import com.pynapli.app.messages.MessagesRealtimeCoordinator
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.onboarding.ApiOnboardingProfileRepository
import com.pynapli.app.onboarding.OnboardingStateHolder
import com.pynapli.app.profile.ApiPublicProfileRepository
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilehub.ApiProfileHubRepository
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ApiProfileLinksRepository
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.store.ApiStoreRepository
import com.pynapli.app.store.StoreStateHolder
import com.pynapli.app.store.UnavailableStorePurchaseGateway
import com.pynapli.app.swipe.ApiSavedSwipeFilterRepository
import com.pynapli.app.swipe.ApiSwipeRepository
import com.pynapli.app.swipe.NoOpSwipeActionQueueStore
import com.pynapli.app.swipe.SavedSwipeFilterStateHolder
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.core.config.PynapliEnvironment
import com.pynapli.shared.network.KtorMediaByteUploader
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.network.NoAppCheckTokenProvider
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
 * (Web Push is post-v1), billing (Store is read-only), realtime stream coordinators
 * (W3), Playing Tonight + Psync surfaces (v1.x), and the swipe/grid local caches
 * (NoOp stores until a localStorage snapshot store earns its place in W2/W4).
 */
class WebAppGraph {
    private val sessionStore = LocalStorageSessionStore()
    private val pynapliConfig = WebRuntimeConfig.pynapliConfig
    private val apiClient =
        KtorPynapliApiClient(
            config = pynapliConfig,
            accessTokenProvider = sessionStore,
            appCheckTokenProvider = NoAppCheckTokenProvider,
        )
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

    val gridStateHolder =
        GridStateHolder(
            repository = ApiGridFeedRepository(apiClient),
            snapshotStore = NoOpGridSnapshotStore,
        )

    val savedGridFilterStateHolder =
        SavedGridFilterStateHolder(
            repository = ApiSavedGridFilterRepository(apiClient),
        )

    val savedSwipeFilterStateHolder =
        SavedSwipeFilterStateHolder(
            repository = ApiSavedSwipeFilterRepository(apiClient),
        )

    val travelLocationStateHolder =
        TravelLocationStateHolder(
            repository = ApiTravelLocationRepository(apiClient),
        )

    val tonightAvailabilityStateHolder =
        TonightAvailabilityStateHolder(
            repository = ApiTonightAvailabilityRepository(apiClient),
        )

    val profileDetailStateHolder =
        ProfileDetailStateHolder(
            repository = ApiPublicProfileRepository(apiClient),
        )

    val conversationStateHolder =
        ConversationStateHolder(
            repository = ApiConversationRepository(apiClient, KtorMediaByteUploader()),
        )

    val messagesStateHolder =
        MessagesStateHolder(
            repository = ApiMessagesRepository(apiClient),
        )

    val activityStateHolder =
        ActivityStateHolder(
            repository = ApiActivityFeedRepository(apiClient),
        )

    val locationStateHolder =
        LocationStateHolder(
            repository = ApiLocationUpdateRepository(apiClient),
        )

    val mediaAlbumsStateHolder =
        MediaAlbumsStateHolder(
            repository =
                ApiMediaAlbumsRepository(
                    apiClient = apiClient,
                    mediaByteUploader = KtorMediaByteUploader(),
                ),
        )

    val profileHubStateHolder =
        ProfileHubStateHolder(
            repository = ApiProfileHubRepository(apiClient),
        )

    val profileLinksStateHolder =
        ProfileLinksStateHolder(
            repository = ApiProfileLinksRepository(apiClient),
        )

    val swipeStateHolder =
        SwipeStateHolder(
            repository = ApiSwipeRepository(apiClient),
            queueStore = NoOpSwipeActionQueueStore,
        )

    val storeStateHolder =
        StoreStateHolder(
            repository = ApiStoreRepository(apiClient),
            purchaseGateway = UnavailableStorePurchaseGateway(),
        )

    // Realtime convenience streams (WEB_PWA_GAMEPLAN.md W3.2), same coordinators as
    // Android over first-frame-auth web sockets. The resilient clients idle with
    // backoff until a session token exists, so these run for the whole page lifetime.
    val gridPresenceStreamCoordinator =
        GridPresenceStreamCoordinator(
            client =
                buildWebPresenceStreamClient(
                    websocketBaseUrl = pynapliConfig.websocketBaseUrl,
                    tokenProvider = sessionStore,
                ),
            gridStateHolder = gridStateHolder,
        )

    val activityRealtimeCoordinator =
        ActivityRealtimeCoordinator(
            client =
                buildWebActivityStreamClient(
                    websocketBaseUrl = pynapliConfig.websocketBaseUrl,
                    tokenProvider = sessionStore,
                ),
            activityStateHolder = activityStateHolder,
        )

    val messagesRealtimeCoordinator =
        MessagesRealtimeCoordinator(
            client =
                buildWebMessageStreamClient(
                    websocketBaseUrl = pynapliConfig.websocketBaseUrl,
                    tokenProvider = sessionStore,
                ),
            messagesStateHolder = messagesStateHolder,
            conversationStateHolder = conversationStateHolder,
        )

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
