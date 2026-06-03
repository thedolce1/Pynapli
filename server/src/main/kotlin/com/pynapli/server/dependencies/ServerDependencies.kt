package com.pynapli.server.dependencies

import com.google.auth.oauth2.ServiceAccountCredentials
import com.pynapli.server.account.AccountLifecycleService
import com.pynapli.server.account.JdbcAccountLifecycleRepository
import com.pynapli.server.activity.ActivityBroadcaster
import com.pynapli.server.activity.ActivityCursorCodec
import com.pynapli.server.activity.ActivityRetentionService
import com.pynapli.server.activity.ActivityRetentionWorker
import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.JdbcActivityRepository
import com.pynapli.server.activity.JdbcActivityRetentionRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.grid.JdbcGridViewerTrustRepository
import com.pynapli.server.grid.JdbcSavedGridFilterRepository
import com.pynapli.server.grid.RedisGridCandidateRankCache
import com.pynapli.server.grid.SavedGridFilterService
import com.pynapli.server.location.JdbcLocationRepository
import com.pynapli.server.location.JdbcTravelLocationRepository
import com.pynapli.server.location.LocationService
import com.pynapli.server.location.TravelLocationService
import com.pynapli.server.media.AutoApproveMediaModerationScanner
import com.pynapli.server.media.CdnMediaAssetUrlResolver
import com.pynapli.server.media.CdnSignedUrlFactory
import com.pynapli.server.media.CloudVisionMediaModerationScanner
import com.pynapli.server.media.GcsV4MediaAssetUrlResolver
import com.pynapli.server.media.GcsV4MediaUploadSigner
import com.pynapli.server.media.GcsV4SignedUrlFactory
import com.pynapli.server.media.JdbcMediaCleanupRepository
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalDevMediaUploadSigner
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.LocalMediaUploadStorage
import com.pynapli.server.media.ManualReviewMediaModerationScanner
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaCleanupService
import com.pynapli.server.media.MediaCleanupWorker
import com.pynapli.server.media.MediaModerationScanner
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.messaging.JdbcMessagingRepository
import com.pynapli.server.messaging.MessageBroadcaster
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.moderation.AdminReportService
import com.pynapli.server.moderation.AdminRiskService
import com.pynapli.server.moderation.JdbcAdminReportRepository
import com.pynapli.server.moderation.JdbcAdminRiskRepository
import com.pynapli.server.moderation.JdbcMessageModerationRepository
import com.pynapli.server.moderation.MessageModerationService
import com.pynapli.server.notifications.FirebaseAdminPushNotificationSender
import com.pynapli.server.notifications.JdbcNotificationRepository
import com.pynapli.server.notifications.LocalLoggingPushNotificationSender
import com.pynapli.server.notifications.NotificationDispatchService
import com.pynapli.server.notifications.NotificationDispatchWorker
import com.pynapli.server.notifications.NotificationService
import com.pynapli.server.notifications.PushNotificationSender
import com.pynapli.server.presence.OnlinePresenceTracker
import com.pynapli.server.presence.PresenceBroadcaster
import com.pynapli.server.presence.PresenceOfflineScanner
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.RedisPresenceRepository
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.profile.TonightAvailabilityService
import com.pynapli.server.profilelink.JdbcProfileLinkRepository
import com.pynapli.server.profilelink.ProfileLinkService
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockService
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.HiddenProfileService
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.safety.JdbcReportRepository
import com.pynapli.server.safety.JdbcRiskRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RedisRateLimitRepository
import com.pynapli.server.safety.ReportService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.safety.RiskService
import com.pynapli.server.subscriptions.DisabledPlayStorePurchaseVerifier
import com.pynapli.server.subscriptions.GooglePlayStorePurchaseVerifier
import com.pynapli.server.subscriptions.JdbcSubscriptionRepository
import com.pynapli.server.subscriptions.PlayStoreSubscriptionSyncWorker
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.swipe.JdbcSavedSwipeFilterRepository
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.JdbcTopPicksRepository
import com.pynapli.server.swipe.SavedSwipeFilterService
import com.pynapli.server.swipe.SwipeDeckTokenCodec
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.swipe.TopPicksService
import com.pynapli.server.tonight.JdbcTonightFeedRepository
import com.pynapli.server.tonight.JdbcTonightSessionRepository
import com.pynapli.server.tonight.TonightFeedService
import com.pynapli.server.tonight.TonightSessionExpiryWorker
import com.pynapli.server.tonight.TonightSessionService
import com.pynapli.server.verification.AgeVerificationService
import com.pynapli.server.verification.JdbcAgeVerificationRepository
import com.pynapli.server.verification.RedirectAgeVerificationProvider
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration

class ServerDependencies(
    val config: ServerConfig,
    val database: Database?,
    private val redisCache: RedisCache?,
    val authService: AuthService?,
    val profileService: ProfileService?,
    val profileLinkService: ProfileLinkService?,
    val locationService: LocationService?,
    val travelLocationService: TravelLocationService?,
    val presenceService: PresenceService?,
    val gridService: GridService?,
    val messagingService: MessagingService?,
    val mediaService: MediaService?,
    val messageModerationService: MessageModerationService?,
    val adminReportService: AdminReportService?,
    val adminRiskService: AdminRiskService?,
    val activityService: ActivityService?,
    val activityRetentionWorker: ActivityRetentionWorker?,
    val mediaCleanupWorker: MediaCleanupWorker?,
    val notificationService: NotificationService?,
    val notificationDispatchWorker: NotificationDispatchWorker?,
    val blockService: BlockService?,
    val hiddenProfileService: HiddenProfileService?,
    val reportService: ReportService?,
    val swipeService: SwipeService?,
    val subscriptionService: SubscriptionService?,
    val playStoreSubscriptionSyncWorker: PlayStoreSubscriptionSyncWorker?,
    val localMediaUploadStorage: LocalMediaUploadStorage?,
    val activityBroadcaster: ActivityBroadcaster?,
    val messageBroadcaster: MessageBroadcaster?,
    val presenceBroadcaster: PresenceBroadcaster?,
    val presenceOfflineScanner: PresenceOfflineScanner?,
    val blockRepository: BlockRepository?,
    val hiddenProfileRepository: HiddenProfileRepository?,
    val savedGridFilterService: SavedGridFilterService?,
    val savedSwipeFilterService: SavedSwipeFilterService?,
    val topPicksService: TopPicksService?,
    val tonightAvailabilityService: TonightAvailabilityService?,
    val tonightSessionService: TonightSessionService?,
    val tonightFeedService: TonightFeedService?,
    val tonightSessionExpiryWorker: TonightSessionExpiryWorker?,
    val ageVerificationService: AgeVerificationService?,
    val accountLifecycleService: AccountLifecycleService?,
    val requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
    private val profileActivityScope: CoroutineScope? = null,
    private val probes: List<DependencyProbe>,
) : AutoCloseable {
    suspend fun checkAll() = probes.map { it.check() }

    override fun close() {
        runCatching { presenceOfflineScanner?.close() }
        runCatching { playStoreSubscriptionSyncWorker?.close() }
        runCatching { activityRetentionWorker?.close() }
        runCatching { mediaCleanupWorker?.close() }
        runCatching { tonightSessionExpiryWorker?.close() }
        runCatching { notificationDispatchWorker?.close() }
        runCatching { profileActivityScope?.cancel() }
        probes.asReversed().forEach { probe ->
            runCatching { probe.close() }
        }
        runCatching { redisCache?.close() }
        runCatching { database?.close() }
    }

    companion object {
        fun fromEnvironment(config: ServerConfig = ServerConfig.fromEnvironment()): ServerDependencies {
            val database = Database(config)
            val redisCache = RedisCache(config.redisUrl)
            val requestProfilerFactory =
                RequestProfilerFactory(
                    enabled = config.apiProfilingEnabled,
                    headerRequired = config.apiProfilingHeaderRequired,
                    slowSpanThresholdMillis = config.apiProfilingSlowSpanThresholdMillis,
                    poolSnapshotProvider = database::poolSnapshot,
                )
            val postgres = PostgresDependencyProbe(database)
            val redis = RedisDependencyProbe(redisCache)
            val profileActivityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val authService = AuthService(JdbcAuthRepository(database.dataSource))
            val accountLifecycleService =
                AccountLifecycleService(
                    JdbcAccountLifecycleRepository(database.dataSource),
                )
            val locationService = LocationService(JdbcLocationRepository(database.dataSource))
            val travelLocationRepository = JdbcTravelLocationRepository(database.dataSource)
            val presenceRepository = RedisPresenceRepository(redisCache)
            val presenceBroadcaster = PresenceBroadcaster()
            val onlinePresenceTracker = OnlinePresenceTracker()
            val presenceService =
                PresenceService(
                    repository = presenceRepository,
                    broadcaster = presenceBroadcaster,
                    onlineTracker = onlinePresenceTracker,
                )
            val presenceOfflineScanner =
                PresenceOfflineScanner(
                    tracker = onlinePresenceTracker,
                    repository = presenceRepository,
                    broadcaster = presenceBroadcaster,
                )
            // Media signing credentials, loaded once and shared by the GCS signer and the Cloud
            // Vision moderation scanner (both ride the same service account).
            val mediaSigningCredentials =
                if (config.mediaStorageProvider.equals("gcs", ignoreCase = true) ||
                    config.mediaModerationProvider.equals("cloud_vision", ignoreCase = true)
                ) {
                    loadMediaSigningCredentials(config)
                } else {
                    null
                }
            // One signing factory shared by the upload signer and the download resolver (gcs only).
            val gcsSignedUrlFactory =
                if (config.mediaStorageProvider.equals("gcs", ignoreCase = true)) {
                    GcsV4SignedUrlFactory(
                        credentials = mediaSigningCredentials!!,
                        region = config.mediaGcsSigningRegion,
                    )
                } else {
                    null
                }
            // CDN signed-URL factory for the "cdn" delivery path (gcs storage only). Built only when
            // the CDN base URL + key name + key are all configured; otherwise delivery falls back to
            // direct GCS V4 signed URLs below.
            val cdnSignedUrlFactory =
                if (config.mediaStorageProvider.equals("gcs", ignoreCase = true) &&
                    config.mediaDeliveryProvider.equals("cdn", ignoreCase = true) &&
                    !config.mediaCdnBaseUrl.isNullOrBlank() &&
                    !config.mediaCdnSigningKeyName.isNullOrBlank() &&
                    !config.mediaCdnSigningKeyBase64Url.isNullOrBlank()
                ) {
                    CdnSignedUrlFactory(
                        keyName = config.mediaCdnSigningKeyName,
                        base64UrlKey = config.mediaCdnSigningKeyBase64Url,
                    )
                } else {
                    null
                }
            val mediaAssetUrlResolver: MediaAssetUrlResolver? =
                when (config.mediaStorageProvider.lowercase()) {
                    "local" -> LocalMediaAssetUrlResolver(config.mediaUploadBaseUrl)
                    "gcs" ->
                        if (cdnSignedUrlFactory != null) {
                            CdnMediaAssetUrlResolver(
                                cdnBaseUrl = config.mediaCdnBaseUrl!!,
                                signedUrlFactory = cdnSignedUrlFactory,
                            )
                        } else {
                            GcsV4MediaAssetUrlResolver(
                                storageBucket = config.mediaStorageBucket,
                                signedUrlFactory = gcsSignedUrlFactory!!,
                            )
                        }

                    else -> null
                }
            val localMediaUploadStorage =
                if (config.mediaStorageProvider.equals("local", ignoreCase = true)) {
                    LocalMediaUploadStorage(Path.of(config.mediaLocalUploadDirectory))
                } else {
                    null
                }
            val uploadSigner =
                when (config.mediaStorageProvider.lowercase()) {
                    "local" ->
                        LocalDevMediaUploadSigner(
                            storageBucket = config.mediaStorageBucket,
                            uploadBaseUrl = config.mediaUploadBaseUrl,
                        )

                    "gcs" ->
                        GcsV4MediaUploadSigner(
                            storageBucket = config.mediaStorageBucket,
                            signedUrlFactory = gcsSignedUrlFactory!!,
                        )

                    else ->
                        StaticMediaUploadSigner(
                            storageProvider = config.mediaStorageProvider,
                            storageBucket = config.mediaStorageBucket,
                            uploadBaseUrl = config.mediaUploadBaseUrl,
                        )
                }
            val blockRepository = JdbcBlockRepository(database.dataSource)
            val blockService = BlockService(blockRepository)
            val hiddenProfileRepository = JdbcHiddenProfileRepository(database.dataSource)
            val hiddenProfileService = HiddenProfileService(hiddenProfileRepository)
            val riskService = RiskService(JdbcRiskRepository(database.dataSource))
            val riskPolicyService = RiskPolicyService(riskService)
            val rateLimitService =
                RateLimitService(
                    repository = RedisRateLimitRepository(redisCache),
                    riskSignalPublisher = riskService,
                )
            val reportService =
                ReportService(
                    repository = JdbcReportRepository(database.dataSource),
                    riskSignalPublisher = riskService,
                    rateLimitService = rateLimitService,
                    supportEmail = config.supportEmail,
                )
            val notificationRepository = JdbcNotificationRepository(database.dataSource)
            val notificationService = NotificationService(notificationRepository)
            val activityBroadcaster = ActivityBroadcaster()
            val messageBroadcaster = MessageBroadcaster()
            val notificationDispatchWorker =
                if (config.notificationDispatchEnabled) {
                    val pushNotificationSender = createPushNotificationSender(config)
                    NotificationDispatchWorker(
                        service =
                            NotificationDispatchService(
                                repository = notificationRepository,
                                sender = pushNotificationSender,
                                maxAttempts = config.notificationMaxAttempts,
                            ),
                        intervalMillis = config.notificationDispatchIntervalMillis,
                        batchSize = config.notificationDispatchBatchSize,
                    )
                } else {
                    null
                }
            val mediaRepository = JdbcMediaRepository(database.dataSource)
            val mediaService =
                MediaService(
                    repository = mediaRepository,
                    uploadSigner = uploadSigner,
                    blockRepository = blockRepository,
                    mediaAssetUrlResolver = mediaAssetUrlResolver,
                    moderationScanner = createMediaModerationScanner(config, mediaSigningCredentials),
                )
            val subscriptionService =
                SubscriptionService(
                    repository = JdbcSubscriptionRepository(database.dataSource),
                    playStorePackageName = config.playStorePackageName,
                    playStorePurchaseVerifier =
                        if (config.playStoreVerificationEnabled) {
                            GooglePlayStorePurchaseVerifier.fromConfig(config)
                        } else {
                            DisabledPlayStorePurchaseVerifier
                        },
                    playStoreSyncStaleAfter = Duration.ofHours(config.playStoreSubscriptionSyncStaleAfterHours),
                    playStoreSyncRenewalLookahead =
                        Duration.ofHours(config.playStoreSubscriptionSyncRenewalLookaheadHours),
                )
            val travelLocationService =
                TravelLocationService(
                    repository = travelLocationRepository,
                    subscriptionService = subscriptionService,
                    // Production default is EmptyTravelRegionPolicy (no destinations
                    // require extra verification). Ops swaps via DI when the region
                    // safety review is in place — see TravelRegionPolicy.
                    viewerTrustRepository = JdbcGridViewerTrustRepository(database.dataSource),
                )
            val playStoreSubscriptionSyncWorker =
                if (config.playStoreVerificationEnabled && config.playStoreSubscriptionSyncEnabled) {
                    PlayStoreSubscriptionSyncWorker(
                        service = subscriptionService,
                        intervalMillis = config.playStoreSubscriptionSyncIntervalMillis,
                        batchSize = config.playStoreSubscriptionSyncBatchSize,
                    )
                } else {
                    null
                }
            val activityService =
                ActivityService(
                    repository = JdbcActivityRepository(database.dataSource),
                    mediaService = mediaService,
                    mediaAssetUrlResolver = mediaAssetUrlResolver,
                    blockRepository = blockRepository,
                    notificationPublisher = notificationService,
                    rateLimitService = rateLimitService,
                    riskPolicyService = riskPolicyService,
                    subscriptionService = subscriptionService,
                    activityBroadcaster = activityBroadcaster,
                    cursorCodec = ActivityCursorCodec(config.activityCursorSecret),
                )
            val activityRetentionWorker =
                if (config.activityRetentionEnabled) {
                    ActivityRetentionWorker(
                        service =
                            ActivityRetentionService(
                                repository = JdbcActivityRetentionRepository(database.dataSource),
                            ),
                        intervalMillis = config.activityRetentionIntervalMillis,
                    )
                } else {
                    null
                }
            val mediaCleanupWorker =
                if (config.mediaCleanupEnabled) {
                    MediaCleanupWorker(
                        service = MediaCleanupService(JdbcMediaCleanupRepository(database.dataSource)),
                        intervalMillis = config.mediaCleanupIntervalMillis,
                    )
                } else {
                    null
                }
            val profileService =
                ProfileService(
                    repository = JdbcProfileRepository(database.dataSource),
                    mediaAssetUrlResolver = mediaAssetUrlResolver,
                    activityService = activityService,
                    blockRepository = blockRepository,
                    riskPolicyService = riskPolicyService,
                    profileViewRecordScope = profileActivityScope,
                    profileViewRecordConcurrency = 4,
                )
            val profileLinkService =
                ProfileLinkService(
                    repository = JdbcProfileLinkRepository(database.dataSource),
                    mediaAssetUrlResolver = mediaAssetUrlResolver,
                    blockRepository = blockRepository,
                    notificationPublisher = notificationService,
                )
            val gridService =
                GridService(
                    repository =
                        JdbcGridRepository(
                            dataSource = database.dataSource,
                            // Share the ranked candidate window across instances via Redis
                            // (GRID_IMPLEMENTATION_SPEC candidate cache). The cached window is
                            // exclusion-free, so per-viewer safety is unchanged; the cache fails
                            // open to direct queries if Redis is unavailable. Single-instance /
                            // tests use the in-memory default baked into JdbcGridRepository.
                            candidateRankCache = RedisGridCandidateRankCache(redisCache, Duration.ofSeconds(5)),
                        ),
                    cursorCodec = DiscoveryCursorCodec(config.discoveryCursorSecret),
                    presenceService = presenceService,
                    mediaAssetUrlResolver = mediaAssetUrlResolver,
                    blockRepository = blockRepository,
                    hiddenProfileRepository = hiddenProfileRepository,
                    subscriptionService = subscriptionService,
                    viewerTrustRepository = JdbcGridViewerTrustRepository(database.dataSource),
                    rateLimitService = rateLimitService,
                )
            val savedGridFilterService =
                SavedGridFilterService(
                    repository = JdbcSavedGridFilterRepository(database.dataSource),
                    subscriptionService = subscriptionService,
                    viewerTrustRepository = JdbcGridViewerTrustRepository(database.dataSource),
                )
            val savedSwipeFilterService =
                SavedSwipeFilterService(
                    repository = JdbcSavedSwipeFilterRepository(database.dataSource),
                    subscriptionService = subscriptionService,
                    viewerTrustRepository = JdbcGridViewerTrustRepository(database.dataSource),
                )
            val tonightAvailabilityService = TonightAvailabilityService(database.dataSource)
            val tonightSessionService =
                TonightSessionService(
                    repository = JdbcTonightSessionRepository(database.dataSource),
                    subscriptionService = subscriptionService,
                )
            val tonightFeedService =
                TonightFeedService(
                    repository = JdbcTonightFeedRepository(database.dataSource),
                    presenceService = presenceService,
                    mediaAssetUrlResolver = mediaAssetUrlResolver,
                    blockRepository = blockRepository,
                    hiddenProfileRepository = hiddenProfileRepository,
                )
            // Hygiene sweep so elapsed sessions move to 'expired' (reads already filter on
            // expires_at, so this is not load-bearing for correctness). Default every 5 minutes.
            val tonightSessionExpiryWorker = TonightSessionExpiryWorker(tonightSessionService, intervalMillis = 5 * 60 * 1000L)
            // Provider-hosted age verification is opt-in: built only when both the provider name and
            // its redirect base URL are configured. Otherwise the service keeps manual-review-only
            // behavior.
            val ageVerificationProvider =
                if (!config.ageVerificationProvider.isNullOrBlank() &&
                    !config.ageVerificationProviderRedirectBaseUrl.isNullOrBlank()
                ) {
                    RedirectAgeVerificationProvider(
                        providerName = config.ageVerificationProvider,
                        label = config.ageVerificationProviderLabel,
                        redirectBaseUrl = config.ageVerificationProviderRedirectBaseUrl,
                    )
                } else {
                    null
                }
            val ageVerificationService =
                AgeVerificationService(
                    repository = JdbcAgeVerificationRepository(database.dataSource),
                    provider = ageVerificationProvider,
                )
            val messagingRepository = JdbcMessagingRepository(database.dataSource)
            val messagingService =
                MessagingService(
                    repository = messagingRepository,
                    blockRepository = blockRepository,
                    notificationPublisher = notificationService,
                    messageBroadcaster = messageBroadcaster,
                    rateLimitService = rateLimitService,
                    messageReviewRiskSignalPublisher = riskService,
                    riskPolicyService = riskPolicyService,
                    subscriptionService = subscriptionService,
                    activityService = activityService,
                )
            val messageModerationService =
                MessageModerationService(
                    JdbcMessageModerationRepository(database.dataSource),
                )
            val adminReportService =
                AdminReportService(
                    JdbcAdminReportRepository(database.dataSource),
                )
            val adminRiskService =
                AdminRiskService(
                    JdbcAdminRiskRepository(database.dataSource),
                )
            val swipeRepository = JdbcSwipeRepository(database.dataSource)
            val swipeService =
                SwipeService(
                    repository = swipeRepository,
                    mediaAssetUrlResolver = mediaAssetUrlResolver,
                    blockRepository = blockRepository,
                    notificationPublisher = notificationService,
                    rateLimitService = rateLimitService,
                    riskPolicyService = riskPolicyService,
                    subscriptionService = subscriptionService,
                    presenceService = presenceService,
                    travelLocationRepository = travelLocationRepository,
                    deckTokenCodec = SwipeDeckTokenCodec(config.swipeDeckTokenSecret),
                    activityService = activityService,
                )
            val topPicksService =
                TopPicksService(
                    topPicksRepository = JdbcTopPicksRepository(database.dataSource),
                    swipeRepository = swipeRepository,
                    subscriptionService = subscriptionService,
                    presenceService = presenceService,
                    mediaAssetUrlResolver = mediaAssetUrlResolver,
                    deckTokenCodec = SwipeDeckTokenCodec(config.swipeDeckTokenSecret),
                )
            return ServerDependencies(
                config = config,
                database = database,
                redisCache = redisCache,
                authService = authService,
                profileService = profileService,
                profileLinkService = profileLinkService,
                locationService = locationService,
                travelLocationService = travelLocationService,
                presenceService = presenceService,
                gridService = gridService,
                messagingService = messagingService,
                mediaService = mediaService,
                messageModerationService = messageModerationService,
                adminReportService = adminReportService,
                adminRiskService = adminRiskService,
                activityService = activityService,
                activityRetentionWorker = activityRetentionWorker,
                mediaCleanupWorker = mediaCleanupWorker,
                notificationService = notificationService,
                notificationDispatchWorker = notificationDispatchWorker,
                blockService = blockService,
                hiddenProfileService = hiddenProfileService,
                reportService = reportService,
                swipeService = swipeService,
                subscriptionService = subscriptionService,
                playStoreSubscriptionSyncWorker = playStoreSubscriptionSyncWorker,
                localMediaUploadStorage = localMediaUploadStorage,
                activityBroadcaster = activityBroadcaster,
                messageBroadcaster = messageBroadcaster,
                presenceBroadcaster = presenceBroadcaster,
                presenceOfflineScanner = presenceOfflineScanner,
                blockRepository = blockRepository,
                hiddenProfileRepository = hiddenProfileRepository,
                savedGridFilterService = savedGridFilterService,
                savedSwipeFilterService = savedSwipeFilterService,
                topPicksService = topPicksService,
                tonightAvailabilityService = tonightAvailabilityService,
                tonightSessionService = tonightSessionService,
                tonightFeedService = tonightFeedService,
                tonightSessionExpiryWorker = tonightSessionExpiryWorker,
                ageVerificationService = ageVerificationService,
                accountLifecycleService = accountLifecycleService,
                requestProfilerFactory = requestProfilerFactory,
                profileActivityScope = profileActivityScope,
                probes = listOf(postgres, redis),
            )
        }

        private fun createPushNotificationSender(config: ServerConfig): PushNotificationSender =
            when (config.notificationPushProvider.lowercase()) {
                "local" -> LocalLoggingPushNotificationSender()
                "firebase", "fcm" -> FirebaseAdminPushNotificationSender.fromConfig(config)
                else -> throw IllegalArgumentException(
                    "Unsupported notification push provider: ${config.notificationPushProvider}",
                )
            }

        // GCS V4 signing needs a service-account private key. Prefer a media-specific service
        // account (least privilege: storage object access only); fall back to the Firebase service
        // account so a single-credential setup also works.
        private fun loadMediaSigningCredentials(config: ServerConfig): ServiceAccountCredentials {
            val json = config.mediaGcsSigningServiceAccountJson ?: config.firebaseServiceAccountJson
            if (json != null) {
                return ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
                    .use(ServiceAccountCredentials::fromStream)
            }
            val path = config.mediaGcsSigningServiceAccountPath ?: config.firebaseServiceAccountPath
            if (path != null) {
                return FileInputStream(path).use(ServiceAccountCredentials::fromStream)
            }
            throw IllegalStateException(
                "GCS media storage requires PYNAPLI_MEDIA_GCS_SIGNING_SA_JSON/PATH " +
                    "(or a Firebase service account) to sign upload URLs.",
            )
        }

        // Upload-complete moderation: dev auto-approve wins first, then the configured provider.
        // "cloud_vision" needs the media signing credentials; if they're absent we fall back to
        // manual review rather than silently auto-approving (fail-safe).
        private fun createMediaModerationScanner(
            config: ServerConfig,
            mediaSigningCredentials: ServiceAccountCredentials?,
        ): MediaModerationScanner =
            when {
                config.mediaDevAutoApproveUploads -> AutoApproveMediaModerationScanner()
                config.mediaModerationProvider.equals("cloud_vision", ignoreCase = true) &&
                    mediaSigningCredentials != null ->
                    CloudVisionMediaModerationScanner(
                        credentials = mediaSigningCredentials,
                        endpoint = config.mediaModerationVisionEndpoint,
                    )

                else -> ManualReviewMediaModerationScanner()
            }

        fun test(config: ServerConfig = ServerConfig.fromEnvironment()): ServerDependencies =
            ServerDependencies(
                config = config,
                database = null,
                redisCache = null,
                authService = null,
                profileService = null,
                profileLinkService = null,
                locationService = null,
                travelLocationService = null,
                presenceService = null,
                gridService = null,
                messagingService = null,
                mediaService = null,
                messageModerationService = null,
                adminReportService = null,
                adminRiskService = null,
                activityService = null,
                activityRetentionWorker = null,
                mediaCleanupWorker = null,
                notificationService = null,
                notificationDispatchWorker = null,
                blockService = null,
                hiddenProfileService = null,
                reportService = null,
                swipeService = null,
                subscriptionService = null,
                playStoreSubscriptionSyncWorker = null,
                localMediaUploadStorage = null,
                activityBroadcaster = null,
                messageBroadcaster = null,
                presenceBroadcaster = null,
                presenceOfflineScanner = null,
                blockRepository = null,
                hiddenProfileRepository = null,
                savedGridFilterService = null,
                savedSwipeFilterService = null,
                topPicksService = null,
                tonightAvailabilityService = null,
                tonightSessionService = null,
                tonightFeedService = null,
                tonightSessionExpiryWorker = null,
                ageVerificationService = null,
                accountLifecycleService = null,
                requestProfilerFactory = RequestProfilerFactory.disabled(),
                probes = emptyList(),
            )
    }
}

fun Application.closeDependenciesOnStop(dependencies: ServerDependencies) {
    monitor.subscribe(ApplicationStopped) {
        dependencies.close()
    }
}
