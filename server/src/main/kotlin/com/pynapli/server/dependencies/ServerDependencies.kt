package com.pynapli.server.dependencies

import com.pynapli.server.account.AccountDataExportWorker
import com.pynapli.server.account.AccountLifecycleService
import com.pynapli.server.activity.ActivityBroadcaster
import com.pynapli.server.activity.ActivityCursorCodec
import com.pynapli.server.activity.ActivityRetentionService
import com.pynapli.server.activity.ActivityRetentionWorker
import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.JdbcActivityRepository
import com.pynapli.server.activity.JdbcActivityRetentionRepository
import com.pynapli.server.admin.AdminDashboardService
import com.pynapli.server.admin.AdminMediaReviewService
import com.pynapli.server.admin.AdminPartnerLinkReviewService
import com.pynapli.server.admin.AdminRequestAuditRecorder
import com.pynapli.server.admin.AdminSubscriptionSupportService
import com.pynapli.server.admin.AdminUserLookupService
import com.pynapli.server.admin.JdbcAdminDashboardRepository
import com.pynapli.server.admin.JdbcAdminMediaReviewRepository
import com.pynapli.server.admin.JdbcAdminPartnerLinkReviewRepository
import com.pynapli.server.admin.JdbcAdminRequestAuditRecorder
import com.pynapli.server.admin.JdbcAdminSubscriptionSupportRepository
import com.pynapli.server.admin.JdbcAdminUserLookupRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.PasswordRecoveryService
import com.pynapli.server.auth.SignedInDevicesService
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.config.startupSummary
import com.pynapli.server.config.validateForStartup
import com.pynapli.server.db.PostgresJobLock
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.SavedGridFilterService
import com.pynapli.server.location.LocationService
import com.pynapli.server.location.TravelLocationService
import com.pynapli.server.location.TravelPlaceSearchService
import com.pynapli.server.media.JdbcMediaCleanupRepository
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalMediaUploadStorage
import com.pynapli.server.media.MediaCleanupService
import com.pynapli.server.media.MediaCleanupWorker
import com.pynapli.server.media.MediaService
import com.pynapli.server.messaging.MessageBroadcaster
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.metrics.ProductMetrics
import com.pynapli.server.moderation.AdminAppealService
import com.pynapli.server.moderation.AdminReportService
import com.pynapli.server.moderation.AdminRiskService
import com.pynapli.server.moderation.MessageModerationService
import com.pynapli.server.notifications.FirebaseAdminPushNotificationSender
import com.pynapli.server.notifications.LocalLoggingPushNotificationSender
import com.pynapli.server.notifications.NotificationDispatchWorker
import com.pynapli.server.notifications.NotificationService
import com.pynapli.server.notifications.PushNotificationSender
import com.pynapli.server.presence.PresenceBroadcaster
import com.pynapli.server.presence.PresenceOfflineScanner
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.profile.ProfileService
import com.pynapli.server.profilelink.JdbcProfileLinkRepository
import com.pynapli.server.profilelink.ProfileLinkService
import com.pynapli.server.profiling.RequestProfilerFactory
import com.pynapli.server.psync.PsyncSessionExpiryWorker
import com.pynapli.server.psync.PsyncSessionService
import com.pynapli.server.retention.DataRetentionService
import com.pynapli.server.retention.DataRetentionWorker
import com.pynapli.server.safety.AppealService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockService
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.HiddenProfileService
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.ReportService
import com.pynapli.server.subscriptions.PlayStoreSubscriptionSyncWorker
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.swipe.SavedSwipeFilterService
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.tonight.TonightFeedService
import com.pynapli.server.tonight.TonightSessionExpiryWorker
import com.pynapli.server.tonight.TonightSessionService
import com.pynapli.server.verification.AgeVerificationService
import com.pynapli.server.verification.JdbcAgeVerificationRepository
import com.pynapli.server.verification.JdbcPhotoVerificationRepository
import com.pynapli.server.verification.PhotoVerificationRetentionWorker
import com.pynapli.server.verification.PhotoVerificationService
import com.pynapli.server.verification.RedirectAgeVerificationProvider
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

class ServerDependencies(
    val config: ServerConfig,
    val database: Database,
    private val redisCache: RedisCache,
    val authService: AuthService,
    val passwordRecoveryService: PasswordRecoveryService? = null,
    val signedInDevicesService: SignedInDevicesService? = null,
    val emailSignupService: com.pynapli.server.auth.EmailSignupService? = null,
    val devSignupMailbox: com.pynapli.server.auth.LocalFileSignupMailer? = null,
    val profileService: ProfileService,
    val favoriteService: com.pynapli.server.favorites.FavoriteService? = null,
    val profileLinkService: ProfileLinkService,
    val adminUserLookupService: AdminUserLookupService,
    val adminPartnerLinkReviewService: AdminPartnerLinkReviewService,
    val adminSubscriptionSupportService: AdminSubscriptionSupportService,
    val adminDashboardService: AdminDashboardService,
    val adminMediaReviewService: AdminMediaReviewService,
    val adminRequestAuditRecorder: AdminRequestAuditRecorder?,
    val locationService: LocationService,
    val travelLocationService: TravelLocationService,
    val travelTripService: com.pynapli.server.location.TravelTripService? = null,
    val travelTripWorker: com.pynapli.server.location.TravelTripWorker? = null,
    val travelPlaceSearchService: TravelPlaceSearchService,
    val presenceService: PresenceService,
    val gridService: GridService,
    val messagingService: MessagingService,
    val mediaService: MediaService,
    val messageModerationService: MessageModerationService,
    val adminAppealService: AdminAppealService,
    val adminReportService: AdminReportService,
    val adminRiskService: AdminRiskService,
    val activityService: ActivityService,
    val activityRetentionWorker: ActivityRetentionWorker?,
    val dataRetentionWorker: DataRetentionWorker? = null,
    val rateLimitService: RateLimitService? = null,
    val mediaCleanupWorker: MediaCleanupWorker?,
    val accountDataExportWorker: AccountDataExportWorker?,
    val notificationService: NotificationService,
    val notificationDispatchWorker: NotificationDispatchWorker?,
    val productMetrics: ProductMetrics,
    val blockService: BlockService,
    val hiddenProfileService: HiddenProfileService,
    val appealService: AppealService,
    val reportService: ReportService,
    val swipeService: SwipeService,
    val subscriptionService: SubscriptionService,
    val playStoreSubscriptionSyncWorker: PlayStoreSubscriptionSyncWorker?,
    val localMediaUploadStorage: LocalMediaUploadStorage?,
    val activityBroadcaster: ActivityBroadcaster,
    val psyncBroadcaster: com.pynapli.server.psync.PsyncBroadcaster,
    val messageBroadcaster: MessageBroadcaster,
    val presenceBroadcaster: PresenceBroadcaster,
    val presenceOfflineScanner: PresenceOfflineScanner,
    val blockRepository: BlockRepository,
    val hiddenProfileRepository: HiddenProfileRepository,
    val savedGridFilterService: SavedGridFilterService,
    val savedSwipeFilterService: SavedSwipeFilterService,
    val matchCelebrationService: com.pynapli.server.swipe.MatchCelebrationService? = null,
    val tonightSessionService: TonightSessionService,
    val tonightFeedService: TonightFeedService,
    val tonightSessionExpiryWorker: TonightSessionExpiryWorker,
    val psyncSessionService: PsyncSessionService,
    val psyncSessionExpiryWorker: PsyncSessionExpiryWorker? = null,
    val matchSideEffectDrainWorker: com.pynapli.server.swipe.MatchSideEffectDrainWorker? = null,
    val psyncPresenceWatcher: com.pynapli.server.psync.PsyncPresenceWatcher? = null,
    val ageVerificationService: AgeVerificationService,
    val photoVerificationService: PhotoVerificationService,
    val photoVerificationRetentionWorker: PhotoVerificationRetentionWorker? = null,
    val accountLifecycleService: AccountLifecycleService,
    val requestProfilerFactory: RequestProfilerFactory = RequestProfilerFactory.disabled(),
    val contactBlockService: com.pynapli.server.contactblocks.ContactBlockService? = null,
    val contactBlockResolverWorker: com.pynapli.server.contactblocks.ContactBlockResolverWorker? = null,
    val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
    private val profileActivityScope: CoroutineScope,
    private val probes: List<DependencyProbe>,
) : AutoCloseable {
    suspend fun checkAll() = probes.map { it.check() }

    override fun close() {
        runCatching { presenceOfflineScanner.close() }
        runCatching { playStoreSubscriptionSyncWorker?.close() }
        runCatching { activityRetentionWorker?.close() }
        runCatching { dataRetentionWorker?.close() }
        runCatching { mediaCleanupWorker?.close() }
        runCatching { photoVerificationRetentionWorker?.close() }
        runCatching { accountDataExportWorker?.close() }
        runCatching { tonightSessionExpiryWorker.close() }
        runCatching { psyncSessionExpiryWorker?.close() }
        runCatching { matchSideEffectDrainWorker?.close() }
        runCatching { psyncPresenceWatcher?.close() }
        runCatching { travelTripWorker?.close() }
        runCatching { notificationDispatchWorker?.close() }
        runCatching { contactBlockResolverWorker?.close() }
        runCatching { profileActivityScope.cancel() }
        probes.asReversed().forEach { probe ->
            runCatching { probe.close() }
        }
        runCatching { redisCache.close() }
        runCatching { database.close() }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ServerDependencies::class.java)

        fun fromEnvironment(config: ServerConfig = ServerConfig.fromEnvironment()): ServerDependencies {
            config.validateForStartup()
            val startupSummary = config.startupSummary()
            logger.info(
                "Pynapli API startup: environment={} port={} mediaProvider={} moderation={} pushProvider={} appCheck={} cloudSql={} redis={}",
                startupSummary.environment,
                startupSummary.port,
                startupSummary.mediaStorageProvider,
                startupSummary.mediaModeration,
                startupSummary.notificationPushProvider,
                startupSummary.appCheckEnforced,
                startupSummary.cloudSqlConfigured,
                startupSummary.redisConfigured,
            )
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
            val core = buildCoreDependencies(config, database, redisCache, backgroundScope = profileActivityScope)
            val emailSignup =
                buildEmailSignupDependencies(
                    config = config,
                    database = database,
                    authService = core.authService,
                    rateLimitService = core.rateLimitService,
                    backgroundScope = profileActivityScope,
                    onAccountVerified = core.contactBlockSettling::atVerify,
                )
            val media =
                buildMediaDependencies(
                    config = config,
                    blockRepository = core.blockRepository,
                    riskService = core.riskService,
                    rateLimitService = core.rateLimitService,
                    dataSource = database.dataSource,
                    contactBlockVisibility = core.contactBlockVisibility,
                )
            val commerceTravel =
                buildCommerceTravelDependencies(
                    config = config,
                    dataSource = database.dataSource,
                    travelLocationRepository = core.travelLocationRepository,
                    rateLimitService = core.rateLimitService,
                    productMetrics = core.productMetrics,
                    riskService = core.riskService,
                    riskPolicyService = core.riskPolicyService,
                )
            val activityService =
                ActivityService(
                    repository = JdbcActivityRepository(database.dataSource, contactBlockTokens = core.contactBlockTokens),
                    mediaService = media.mediaService,
                    mediaAssetUrlResolver = media.mediaAssetUrlResolver,
                    blockRepository = core.blockRepository,
                    notificationPublisher = core.notificationService,
                    rateLimitService = core.rateLimitService,
                    riskPolicyService = core.riskPolicyService,
                    subscriptionService = commerceTravel.subscriptionService,
                    activityBroadcaster = core.activityBroadcaster,
                    cursorCodec = ActivityCursorCodec(config.activityCursorSecret),
                    contactBlockVisibility = core.contactBlockVisibility,
                )
            val activityRetentionWorker =
                if (config.activityRetentionEnabled) {
                    ActivityRetentionWorker(
                        service =
                            ActivityRetentionService(
                                repository = JdbcActivityRetentionRepository(database.dataSource),
                            ),
                        intervalMillis = config.activityRetentionIntervalMillis,
                        jobLock = PostgresJobLock(database.dataSource),
                    )
                } else {
                    null
                }
            val dataRetentionWorker =
                if (config.dataRetentionEnabled) {
                    DataRetentionWorker(
                        service =
                            DataRetentionService(
                                dataSource = database.dataSource,
                                expiredMessageGrace = java.time.Duration.ofDays(config.expiredMessageGraceDays),
                            ),
                        intervalMillis = config.dataRetentionIntervalMillis,
                        jobLock = PostgresJobLock(database.dataSource),
                    )
                } else {
                    null
                }
            val mediaCleanupWorker =
                if (config.mediaCleanupEnabled) {
                    MediaCleanupWorker(
                        service = MediaCleanupService(JdbcMediaCleanupRepository(database.dataSource)),
                        intervalMillis = config.mediaCleanupIntervalMillis,
                        jobLock = PostgresJobLock(database.dataSource),
                    )
                } else {
                    null
                }
            val favoriteService =
                com.pynapli.server.favorites.FavoriteService(
                    repository =
                        com.pynapli.server.favorites
                            .JdbcFavoriteRepository(database.dataSource),
                    cursorCodec =
                        com.pynapli.server.discovery
                            .DiscoveryCursorCodec(config.discoveryCursorSecret),
                    presenceService = core.presenceService,
                    mediaAssetUrlResolver = media.mediaAssetUrlResolver,
                    blockRepository = core.blockRepository,
                    rateLimitService = core.rateLimitService,
                    contactBlockVisibility = core.contactBlockVisibility,
                )
            val profileService =
                ProfileService(
                    lastActiveRepository =
                        com.pynapli.server.presence
                            .JdbcLastActiveRepository(database.dataSource),
                    favoriteService = favoriteService,
                    repository = JdbcProfileRepository(database.dataSource),
                    bioScanner = com.pynapli.server.profile.ContactAndSolicitationBioScanner,
                    mediaAssetUrlResolver = media.mediaAssetUrlResolver,
                    activityService = activityService,
                    blockRepository = core.blockRepository,
                    riskPolicyService = core.riskPolicyService,
                    presenceService = core.presenceService,
                    rateLimitService = core.rateLimitService,
                    profileViewRecordScope = profileActivityScope,
                    profileViewRecordConcurrency = 4,
                    contactBlockVisibility = core.contactBlockVisibility,
                )
            val profileLinkService =
                ProfileLinkService(
                    repository = JdbcProfileLinkRepository(database.dataSource),
                    mediaAssetUrlResolver = media.mediaAssetUrlResolver,
                    blockRepository = core.blockRepository,
                    notificationPublisher = core.notificationService,
                    riskPolicyService = core.riskPolicyService,
                    contactBlockVisibility = core.contactBlockVisibility,
                )
            val adminUserLookupService =
                AdminUserLookupService(
                    profileRepository = JdbcProfileRepository(database.dataSource),
                    repository = JdbcAdminUserLookupRepository(database.dataSource),
                    entitlementLookup = { userId -> commerceTravel.subscriptionService.getEntitlement(userId) },
                    partnerLinkLookup = { userId -> profileLinkService.getSummaryForAdmin(userId) },
                )
            val adminPartnerLinkReviewService =
                AdminPartnerLinkReviewService(
                    repository = JdbcAdminPartnerLinkReviewRepository(database.dataSource),
                )
            val adminSubscriptionSupportService =
                AdminSubscriptionSupportService(
                    repository = JdbcAdminSubscriptionSupportRepository(database.dataSource),
                    entitlementLookup = { userId -> commerceTravel.subscriptionService.getEntitlement(userId) },
                )
            val adminDashboardService =
                AdminDashboardService(
                    repository = JdbcAdminDashboardRepository(database.dataSource),
                )
            val adminMediaReviewService =
                AdminMediaReviewService(
                    repository = JdbcAdminMediaReviewRepository(database.dataSource),
                    mediaRepository = JdbcMediaRepository(database.dataSource),
                    mediaAssetUrlResolver = media.mediaAssetUrlResolver,
                    notificationPublisher = core.notificationService,
                )
            val adminRequestAuditRecorder =
                config.adminApiToken?.let { JdbcAdminRequestAuditRecorder(database.dataSource) }
            val grid =
                buildGridDependencies(
                    config = config,
                    dataSource = database.dataSource,
                    redisCache = redisCache,
                    presenceService = core.presenceService,
                    mediaAssetUrlResolver = media.mediaAssetUrlResolver,
                    blockRepository = core.blockRepository,
                    hiddenProfileRepository = core.hiddenProfileRepository,
                    subscriptionService = commerceTravel.subscriptionService,
                    rateLimitService = core.rateLimitService,
                    contactBlockVisibility = core.contactBlockVisibility,
                )
            val tonight =
                buildTonightDependencies(
                    dataSource = database.dataSource,
                    subscriptionService = commerceTravel.subscriptionService,
                    notificationService = core.notificationService,
                    presenceService = core.presenceService,
                    mediaAssetUrlResolver = media.mediaAssetUrlResolver,
                    blockRepository = core.blockRepository,
                    hiddenProfileRepository = core.hiddenProfileRepository,
                    riskPolicyService = core.riskPolicyService,
                    contactBlockVisibility = core.contactBlockVisibility,
                )
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
                    rateLimitService = core.rateLimitService,
                    // Successful verification lowers account risk (TRUST_AND_SAFETY_SPEC).
                    verificationRiskSignals = core.riskService,
                )
            val photoVerificationService =
                PhotoVerificationService(
                    repository = JdbcPhotoVerificationRepository(database.dataSource),
                    selfieMedia = media.selfieMediaGateway,
                    faceDetector = media.selfieFaceDetector,
                    rateLimitService = core.rateLimitService,
                    notificationPublisher = core.notificationService,
                    // Approval lowers account risk once (TRUST_AND_SAFETY_SPEC).
                    verificationRiskSignals = core.riskService,
                )
            // Selfie retention runs with the media cleanup sweep (same switch and interval).
            val photoVerificationRetentionWorker =
                if (config.mediaCleanupEnabled) {
                    PhotoVerificationRetentionWorker(
                        service = photoVerificationService,
                        intervalMillis = config.mediaCleanupIntervalMillis,
                        jobLock = PostgresJobLock(database.dataSource),
                    )
                } else {
                    null
                }
            val messaging =
                buildMessagingDependencies(
                    dataSource = database.dataSource,
                    blockRepository = core.blockRepository,
                    notificationService = core.notificationService,
                    messageBroadcaster = core.messageBroadcaster,
                    rateLimitService = core.rateLimitService,
                    riskService = core.riskService,
                    riskPolicyService = core.riskPolicyService,
                    subscriptionService = commerceTravel.subscriptionService,
                    activityService = activityService,
                    messageMediaGateway = media.messageMediaGateway,
                    productMetrics = core.productMetrics,
                    travelLocationRepository = core.travelLocationRepository,
                    contactBlockTokens = core.contactBlockTokens,
                    contactBlockVisibility = core.contactBlockVisibility,
                )
            val swipe =
                buildSwipeDependencies(
                    config = config,
                    dataSource = database.dataSource,
                    mediaAssetUrlResolver = media.mediaAssetUrlResolver,
                    blockRepository = core.blockRepository,
                    hiddenProfileRepository = core.hiddenProfileRepository,
                    notificationService = core.notificationService,
                    rateLimitService = core.rateLimitService,
                    riskPolicyService = core.riskPolicyService,
                    subscriptionService = commerceTravel.subscriptionService,
                    presenceService = core.presenceService,
                    travelLocationRepository = core.travelLocationRepository,
                    activityService = activityService,
                    psyncBroadcaster = core.psyncBroadcaster,
                    productMetrics = core.productMetrics,
                    matchConversationOpener = messaging.messagingService.matchConversationOpener,
                    contactBlockVisibility = core.contactBlockVisibility,
                )
            return ServerDependencies(
                config = config,
                database = database,
                redisCache = redisCache,
                authService = core.authService,
                passwordRecoveryService = core.passwordRecoveryService,
                signedInDevicesService = core.signedInDevicesService,
                emailSignupService = emailSignup.service,
                devSignupMailbox = emailSignup.devMailbox,
                profileService = profileService,
                favoriteService = favoriteService,
                profileLinkService = profileLinkService,
                adminUserLookupService = adminUserLookupService,
                adminPartnerLinkReviewService = adminPartnerLinkReviewService,
                adminSubscriptionSupportService = adminSubscriptionSupportService,
                adminDashboardService = adminDashboardService,
                adminMediaReviewService = adminMediaReviewService,
                adminRequestAuditRecorder = adminRequestAuditRecorder,
                locationService = core.locationService,
                travelLocationService = commerceTravel.travelLocationService,
                travelTripService = commerceTravel.travelTripService,
                travelTripWorker =
                    com.pynapli.server.location
                        .TravelTripWorker(commerceTravel.travelTripService),
                travelPlaceSearchService = commerceTravel.travelPlaceSearchService,
                presenceService = core.presenceService,
                gridService = grid.gridService,
                messagingService = messaging.messagingService,
                mediaService = media.mediaService,
                messageModerationService = messaging.messageModerationService,
                adminAppealService = core.adminAppealService,
                adminReportService = messaging.adminReportService,
                adminRiskService = messaging.adminRiskService,
                activityService = activityService,
                activityRetentionWorker = activityRetentionWorker,
                dataRetentionWorker = dataRetentionWorker,
                rateLimitService = core.rateLimitService,
                mediaCleanupWorker = mediaCleanupWorker,
                accountDataExportWorker = core.accountDataExportWorker,
                notificationService = core.notificationService,
                notificationDispatchWorker = core.notificationDispatchWorker,
                productMetrics = core.productMetrics,
                blockService = core.blockService,
                hiddenProfileService = core.hiddenProfileService,
                appealService = core.appealService,
                reportService = core.reportService,
                swipeService = swipe.swipeService,
                subscriptionService = commerceTravel.subscriptionService,
                playStoreSubscriptionSyncWorker = commerceTravel.playStoreSubscriptionSyncWorker,
                localMediaUploadStorage = media.localMediaUploadStorage,
                activityBroadcaster = core.activityBroadcaster,
                psyncBroadcaster = core.psyncBroadcaster,
                messageBroadcaster = core.messageBroadcaster,
                presenceBroadcaster = core.presenceBroadcaster,
                presenceOfflineScanner = core.presenceOfflineScanner,
                blockRepository = core.blockRepository,
                hiddenProfileRepository = core.hiddenProfileRepository,
                savedGridFilterService = grid.savedGridFilterService,
                savedSwipeFilterService = grid.savedSwipeFilterService,
                matchCelebrationService = swipe.matchCelebrationService,
                tonightSessionService = tonight.tonightSessionService,
                tonightFeedService = tonight.tonightFeedService,
                tonightSessionExpiryWorker = tonight.tonightSessionExpiryWorker,
                psyncSessionService = swipe.psyncSessionService,
                psyncSessionExpiryWorker = swipe.psyncSessionExpiryWorker,
                matchSideEffectDrainWorker = swipe.matchSideEffectDrainWorker,
                psyncPresenceWatcher =
                    com.pynapli.server.psync.PsyncPresenceWatcher(
                        dataSource = database.dataSource,
                        liveness =
                            com.pynapli.server.psync
                                .RedisPsyncLiveness(redisCache),
                        broadcaster = core.psyncBroadcaster,
                    ),
                ageVerificationService = ageVerificationService,
                photoVerificationService = photoVerificationService,
                photoVerificationRetentionWorker = photoVerificationRetentionWorker,
                accountLifecycleService = core.accountLifecycleService,
                requestProfilerFactory = requestProfilerFactory,
                contactBlockService = core.contactBlockService,
                contactBlockResolverWorker = core.contactBlockResolverWorker,
                contactBlockVisibility = core.contactBlockVisibility,
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
    }
}

fun Application.closeDependenciesOnStop(dependencies: ServerDependencies) {
    monitor.subscribe(ApplicationStopped) {
        dependencies.close()
    }
}
