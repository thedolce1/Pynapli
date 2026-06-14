package com.pynapli.server.dependencies

import com.pynapli.server.account.AccountLifecycleService
import com.pynapli.server.account.JdbcAccountLifecycleRepository
import com.pynapli.server.activity.ActivityBroadcaster
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.location.JdbcLocationRepository
import com.pynapli.server.location.JdbcTravelLocationRepository
import com.pynapli.server.location.LocationService
import com.pynapli.server.location.TravelLocationRepository
import com.pynapli.server.messaging.MessageBroadcaster
import com.pynapli.server.moderation.AdminAppealService
import com.pynapli.server.moderation.JdbcAdminAppealRepository
import com.pynapli.server.notifications.FirebaseAdminPushNotificationSender
import com.pynapli.server.notifications.JdbcNotificationRepository
import com.pynapli.server.notifications.LocalLoggingPushNotificationSender
import com.pynapli.server.notifications.NotificationDispatchService
import com.pynapli.server.notifications.NotificationDispatchWorker
import com.pynapli.server.notifications.NotificationRepository
import com.pynapli.server.notifications.NotificationService
import com.pynapli.server.notifications.PushNotificationSender
import com.pynapli.server.presence.OnlinePresenceTracker
import com.pynapli.server.presence.PresenceBroadcaster
import com.pynapli.server.presence.PresenceOfflineScanner
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.presence.RedisPresenceRepository
import com.pynapli.server.psync.PsyncBroadcaster
import com.pynapli.server.safety.AppealService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockService
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.HiddenProfileService
import com.pynapli.server.safety.JdbcAppealRepository
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.safety.JdbcHiddenProfileRepository
import com.pynapli.server.safety.JdbcReportRepository
import com.pynapli.server.safety.JdbcRiskRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RedisRateLimitRepository
import com.pynapli.server.safety.ReportService
import com.pynapli.server.safety.RiskFalsePositiveMetrics
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.safety.RiskService

internal class CoreDependencies(
    val accountLifecycleService: AccountLifecycleService,
    val travelLocationRepository: TravelLocationRepository,
    val presenceService: PresenceService,
    val presenceBroadcaster: PresenceBroadcaster,
    val presenceOfflineScanner: PresenceOfflineScanner,
    val blockRepository: BlockRepository,
    val blockService: BlockService,
    val hiddenProfileRepository: HiddenProfileRepository,
    val hiddenProfileService: HiddenProfileService,
    val riskService: RiskService,
    val riskPolicyService: RiskPolicyService,
    val locationService: LocationService,
    val rateLimitService: RateLimitService,
    val authService: AuthService,
    val reportService: ReportService,
    val appealService: AppealService,
    val adminAppealService: AdminAppealService,
    val notificationRepository: NotificationRepository,
    val notificationService: NotificationService,
    val activityBroadcaster: ActivityBroadcaster,
    val psyncBroadcaster: PsyncBroadcaster,
    val messageBroadcaster: MessageBroadcaster,
    val notificationDispatchWorker: NotificationDispatchWorker?,
)

internal fun buildCoreDependencies(
    config: ServerConfig,
    database: Database,
    redisCache: RedisCache,
): CoreDependencies {
    val accountLifecycleService =
        AccountLifecycleService(
            JdbcAccountLifecycleRepository(database.dataSource),
        )
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
    val blockRepository = JdbcBlockRepository(database.dataSource)
    val blockService = BlockService(blockRepository)
    val hiddenProfileRepository = JdbcHiddenProfileRepository(database.dataSource)
    val hiddenProfileService = HiddenProfileService(hiddenProfileRepository)
    val riskService = RiskService(JdbcRiskRepository(database.dataSource))
    val riskPolicyService = RiskPolicyService(riskService)
    val locationService =
        LocationService(
            repository = JdbcLocationRepository(database.dataSource),
            // Impossible-travel fixes feed the risk model (TRUST_AND_SAFETY_SPEC).
            locationRiskSignals = riskService,
        )
    val rateLimitService =
        RateLimitService(
            repository = RedisRateLimitRepository(redisCache),
            riskSignalPublisher = riskService,
        )
    val authService =
        AuthService(
            repository = JdbcAuthRepository(database.dataSource),
            rateLimitService = rateLimitService,
        )
    val reportService =
        ReportService(
            repository = JdbcReportRepository(database.dataSource),
            riskSignalPublisher = riskService,
            rateLimitService = rateLimitService,
            supportEmail = config.supportEmail,
        )
    val appealService = AppealService(JdbcAppealRepository(database.dataSource))
    val adminAppealService =
        AdminAppealService(
            repository = JdbcAdminAppealRepository(database.dataSource),
            // Appeal outcomes feed the false-positive rate (TRUST_AND_SAFETY_SPEC).
            falsePositiveMetrics = RiskFalsePositiveMetrics(),
        )
    val notificationRepository = JdbcNotificationRepository(database.dataSource)
    val notificationService = NotificationService(notificationRepository)
    val activityBroadcaster = ActivityBroadcaster()
    val psyncBroadcaster = PsyncBroadcaster()
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

    return CoreDependencies(
        accountLifecycleService = accountLifecycleService,
        travelLocationRepository = travelLocationRepository,
        presenceService = presenceService,
        presenceBroadcaster = presenceBroadcaster,
        presenceOfflineScanner = presenceOfflineScanner,
        blockRepository = blockRepository,
        blockService = blockService,
        hiddenProfileRepository = hiddenProfileRepository,
        hiddenProfileService = hiddenProfileService,
        riskService = riskService,
        riskPolicyService = riskPolicyService,
        locationService = locationService,
        rateLimitService = rateLimitService,
        authService = authService,
        reportService = reportService,
        appealService = appealService,
        adminAppealService = adminAppealService,
        notificationRepository = notificationRepository,
        notificationService = notificationService,
        activityBroadcaster = activityBroadcaster,
        psyncBroadcaster = psyncBroadcaster,
        messageBroadcaster = messageBroadcaster,
        notificationDispatchWorker = notificationDispatchWorker,
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
