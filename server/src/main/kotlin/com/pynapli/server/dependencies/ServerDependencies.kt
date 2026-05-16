package com.pynapli.server.dependencies

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.JdbcActivityRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.grid.JdbcGridViewerTrustRepository
import com.pynapli.server.grid.JdbcSavedGridFilterRepository
import com.pynapli.server.grid.SavedGridFilterService
import com.pynapli.server.location.JdbcLocationRepository
import com.pynapli.server.location.LocationService
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalDevMediaUploadSigner
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.LocalMediaUploadStorage
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.messaging.JdbcMessagingRepository
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.moderation.AdminReportService
import com.pynapli.server.moderation.AdminRiskService
import com.pynapli.server.moderation.JdbcAdminReportRepository
import com.pynapli.server.moderation.JdbcAdminRiskRepository
import com.pynapli.server.moderation.JdbcMessageModerationRepository
import com.pynapli.server.moderation.MessageModerationService
import com.pynapli.server.notifications.JdbcNotificationRepository
import com.pynapli.server.notifications.FirebaseAdminPushNotificationSender
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
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.profile.TonightAvailabilityService
import com.pynapli.server.profilelink.JdbcProfileLinkRepository
import com.pynapli.server.profilelink.ProfileLinkService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockService
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcReportRepository
import com.pynapli.server.safety.JdbcRiskRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RedisRateLimitRepository
import com.pynapli.server.safety.ReportService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.safety.RiskService
import com.pynapli.server.subscriptions.JdbcSubscriptionRepository
import com.pynapli.server.subscriptions.DisabledPlayStorePurchaseVerifier
import com.pynapli.server.subscriptions.GooglePlayStorePurchaseVerifier
import com.pynapli.server.subscriptions.PlayStoreSubscriptionSyncWorker
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.SwipeDeckTokenCodec
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.verification.AgeVerificationService
import com.pynapli.server.verification.JdbcAgeVerificationRepository
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    val presenceService: PresenceService?,
    val gridService: GridService?,
    val messagingService: MessagingService?,
    val mediaService: MediaService?,
    val messageModerationService: MessageModerationService?,
    val adminReportService: AdminReportService?,
    val adminRiskService: AdminRiskService?,
    val activityService: ActivityService?,
    val notificationService: NotificationService?,
    val notificationDispatchWorker: NotificationDispatchWorker?,
    val blockService: BlockService?,
    val reportService: ReportService?,
    val swipeService: SwipeService?,
    val subscriptionService: SubscriptionService?,
    val playStoreSubscriptionSyncWorker: PlayStoreSubscriptionSyncWorker?,
    val localMediaUploadStorage: LocalMediaUploadStorage?,
    val presenceBroadcaster: PresenceBroadcaster?,
    val presenceOfflineScanner: PresenceOfflineScanner?,
    val blockRepository: BlockRepository?,
    val savedGridFilterService: SavedGridFilterService?,
    val tonightAvailabilityService: TonightAvailabilityService?,
    val ageVerificationService: AgeVerificationService?,
    val requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
    private val profileActivityScope: CoroutineScope? = null,
    private val probes: List<DependencyProbe>
) : AutoCloseable {
    suspend fun checkAll() = probes.map { it.check() }

    override fun close() {
        runCatching { presenceOfflineScanner?.close() }
        runCatching { playStoreSubscriptionSyncWorker?.close() }
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
            val requestProfilerFactory = RequestProfilerFactory(
                enabled = config.apiProfilingEnabled,
                headerRequired = config.apiProfilingHeaderRequired,
                slowSpanThresholdMillis = config.apiProfilingSlowSpanThresholdMillis,
                poolSnapshotProvider = database::poolSnapshot
            )
            val postgres = PostgresDependencyProbe(database)
            val redis = RedisDependencyProbe(redisCache)
            val profileActivityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val authService = AuthService(JdbcAuthRepository(database.dataSource))
            val locationService = LocationService(JdbcLocationRepository(database.dataSource))
            val presenceRepository = RedisPresenceRepository(redisCache)
            val presenceBroadcaster = PresenceBroadcaster()
            val onlinePresenceTracker = OnlinePresenceTracker()
            val presenceService = PresenceService(
                repository = presenceRepository,
                broadcaster = presenceBroadcaster,
                onlineTracker = onlinePresenceTracker
            )
            val presenceOfflineScanner = PresenceOfflineScanner(
                tracker = onlinePresenceTracker,
                repository = presenceRepository,
                broadcaster = presenceBroadcaster
            )
            val mediaAssetUrlResolver: MediaAssetUrlResolver? =
                if (config.mediaStorageProvider.equals("local", ignoreCase = true)) {
                    LocalMediaAssetUrlResolver(config.mediaUploadBaseUrl)
                } else {
                    null
                }
            val localMediaUploadStorage = if (config.mediaStorageProvider.equals("local", ignoreCase = true)) {
                LocalMediaUploadStorage(Path.of(config.mediaLocalUploadDirectory))
            } else {
                null
            }
            val uploadSigner = if (config.mediaStorageProvider.equals("local", ignoreCase = true)) {
                LocalDevMediaUploadSigner(
                    storageBucket = config.mediaStorageBucket,
                    uploadBaseUrl = config.mediaUploadBaseUrl
                )
            } else {
                StaticMediaUploadSigner(
                    storageProvider = config.mediaStorageProvider,
                    storageBucket = config.mediaStorageBucket,
                    uploadBaseUrl = config.mediaUploadBaseUrl
                )
            }
            val blockRepository = JdbcBlockRepository(database.dataSource)
            val blockService = BlockService(blockRepository)
            val riskService = RiskService(JdbcRiskRepository(database.dataSource))
            val riskPolicyService = RiskPolicyService(riskService)
            val rateLimitService = RateLimitService(
                repository = RedisRateLimitRepository(redisCache),
                riskSignalPublisher = riskService
            )
            val reportService = ReportService(
                repository = JdbcReportRepository(database.dataSource),
                riskSignalPublisher = riskService,
                rateLimitService = rateLimitService
            )
            val notificationRepository = JdbcNotificationRepository(database.dataSource)
            val notificationService = NotificationService(notificationRepository)
            val notificationDispatchWorker = if (config.notificationDispatchEnabled) {
                val pushNotificationSender = createPushNotificationSender(config)
                NotificationDispatchWorker(
                    service = NotificationDispatchService(
                        repository = notificationRepository,
                        sender = pushNotificationSender,
                        maxAttempts = config.notificationMaxAttempts
                    ),
                    intervalMillis = config.notificationDispatchIntervalMillis,
                    batchSize = config.notificationDispatchBatchSize
                )
            } else {
                null
            }
            val mediaRepository = JdbcMediaRepository(database.dataSource)
            val mediaService = MediaService(
                repository = mediaRepository,
                uploadSigner = uploadSigner,
                devAutoApproveUploads = config.mediaDevAutoApproveUploads,
                blockRepository = blockRepository
            )
            val subscriptionService = SubscriptionService(
                repository = JdbcSubscriptionRepository(database.dataSource),
                playStorePackageName = config.playStorePackageName,
                playStorePurchaseVerifier = if (config.playStoreVerificationEnabled) {
                    GooglePlayStorePurchaseVerifier.fromConfig(config)
                } else {
                    DisabledPlayStorePurchaseVerifier
                },
                playStoreSyncStaleAfter = Duration.ofHours(config.playStoreSubscriptionSyncStaleAfterHours),
                playStoreSyncRenewalLookahead = Duration.ofHours(config.playStoreSubscriptionSyncRenewalLookaheadHours)
            )
            val playStoreSubscriptionSyncWorker =
                if (config.playStoreVerificationEnabled && config.playStoreSubscriptionSyncEnabled) {
                    PlayStoreSubscriptionSyncWorker(
                        service = subscriptionService,
                        intervalMillis = config.playStoreSubscriptionSyncIntervalMillis,
                        batchSize = config.playStoreSubscriptionSyncBatchSize
                    )
                } else {
                    null
                }
            val activityService = ActivityService(
                repository = JdbcActivityRepository(database.dataSource),
                mediaService = mediaService,
                mediaAssetUrlResolver = mediaAssetUrlResolver,
                blockRepository = blockRepository,
                notificationPublisher = notificationService,
                rateLimitService = rateLimitService,
                riskPolicyService = riskPolicyService,
                subscriptionService = subscriptionService
            )
            val profileService = ProfileService(
                repository = JdbcProfileRepository(database.dataSource),
                mediaAssetUrlResolver = mediaAssetUrlResolver,
                activityService = activityService,
                blockRepository = blockRepository,
                riskPolicyService = riskPolicyService,
                profileViewRecordScope = profileActivityScope,
                profileViewRecordConcurrency = 4
            )
            val profileLinkService = ProfileLinkService(
                repository = JdbcProfileLinkRepository(database.dataSource),
                mediaAssetUrlResolver = mediaAssetUrlResolver,
                blockRepository = blockRepository,
                notificationPublisher = notificationService
            )
            val gridService = GridService(
                repository = JdbcGridRepository(database.dataSource),
                cursorCodec = DiscoveryCursorCodec(config.discoveryCursorSecret),
                presenceService = presenceService,
                mediaAssetUrlResolver = mediaAssetUrlResolver,
                blockRepository = blockRepository,
                subscriptionService = subscriptionService,
                viewerTrustRepository = JdbcGridViewerTrustRepository(database.dataSource),
                rateLimitService = rateLimitService
            )
            val savedGridFilterService = SavedGridFilterService(
                repository = JdbcSavedGridFilterRepository(database.dataSource),
                subscriptionService = subscriptionService,
                viewerTrustRepository = JdbcGridViewerTrustRepository(database.dataSource)
            )
            val tonightAvailabilityService = TonightAvailabilityService(database.dataSource)
            val ageVerificationService = AgeVerificationService(
                JdbcAgeVerificationRepository(database.dataSource)
            )
            val messagingRepository = JdbcMessagingRepository(database.dataSource)
            val messagingService = MessagingService(
                repository = messagingRepository,
                blockRepository = blockRepository,
                notificationPublisher = notificationService,
                rateLimitService = rateLimitService,
                messageReviewRiskSignalPublisher = riskService,
                riskPolicyService = riskPolicyService
            )
            val messageModerationService = MessageModerationService(
                JdbcMessageModerationRepository(database.dataSource)
            )
            val adminReportService = AdminReportService(
                JdbcAdminReportRepository(database.dataSource)
            )
            val adminRiskService = AdminRiskService(
                JdbcAdminRiskRepository(database.dataSource)
            )
            val swipeService = SwipeService(
                repository = JdbcSwipeRepository(database.dataSource),
                mediaAssetUrlResolver = mediaAssetUrlResolver,
                blockRepository = blockRepository,
                notificationPublisher = notificationService,
                rateLimitService = rateLimitService,
                riskPolicyService = riskPolicyService,
                subscriptionService = subscriptionService,
                presenceService = presenceService,
                deckTokenCodec = SwipeDeckTokenCodec(config.swipeDeckTokenSecret)
            )
            return ServerDependencies(
                config = config,
                database = database,
                redisCache = redisCache,
                authService = authService,
                profileService = profileService,
                profileLinkService = profileLinkService,
                locationService = locationService,
                presenceService = presenceService,
                gridService = gridService,
                messagingService = messagingService,
                mediaService = mediaService,
                messageModerationService = messageModerationService,
                adminReportService = adminReportService,
                adminRiskService = adminRiskService,
                activityService = activityService,
                notificationService = notificationService,
                notificationDispatchWorker = notificationDispatchWorker,
                blockService = blockService,
                reportService = reportService,
                swipeService = swipeService,
                subscriptionService = subscriptionService,
                playStoreSubscriptionSyncWorker = playStoreSubscriptionSyncWorker,
                localMediaUploadStorage = localMediaUploadStorage,
                presenceBroadcaster = presenceBroadcaster,
                presenceOfflineScanner = presenceOfflineScanner,
                blockRepository = blockRepository,
                savedGridFilterService = savedGridFilterService,
                tonightAvailabilityService = tonightAvailabilityService,
                ageVerificationService = ageVerificationService,
                requestProfilerFactory = requestProfilerFactory,
                profileActivityScope = profileActivityScope,
                probes = listOf(postgres, redis)
            )
        }

        private fun createPushNotificationSender(config: ServerConfig): PushNotificationSender =
            when (config.notificationPushProvider.lowercase()) {
                "local" -> LocalLoggingPushNotificationSender()
                "firebase", "fcm" -> FirebaseAdminPushNotificationSender.fromConfig(config)
                else -> throw IllegalArgumentException(
                    "Unsupported notification push provider: ${config.notificationPushProvider}"
                )
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
                presenceService = null,
                gridService = null,
                messagingService = null,
                mediaService = null,
                messageModerationService = null,
                adminReportService = null,
                adminRiskService = null,
                activityService = null,
                notificationService = null,
                notificationDispatchWorker = null,
                blockService = null,
                reportService = null,
                swipeService = null,
                subscriptionService = null,
                playStoreSubscriptionSyncWorker = null,
                localMediaUploadStorage = null,
                presenceBroadcaster = null,
                presenceOfflineScanner = null,
                blockRepository = null,
                savedGridFilterService = null,
                tonightAvailabilityService = null,
                ageVerificationService = null,
                requestProfilerFactory = RequestProfilerFactory.disabled(),
                probes = emptyList()
            )
    }
}

fun Application.closeDependenciesOnStop(dependencies: ServerDependencies) {
    monitor.subscribe(ApplicationStopped) {
        dependencies.close()
    }
}
