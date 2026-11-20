package com.pynapli.server.dependencies

import com.pynapli.server.account.AccountDataExportProcessor
import com.pynapli.server.account.AccountDataExportWorker
import com.pynapli.server.account.AccountLifecycleService
import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.account.JdbcAccountLifecycleRepository
import com.pynapli.server.activity.ActivityBroadcaster
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.JdbcDeviceFarmRepository
import com.pynapli.server.auth.JdbcPasswordRecoveryRepository
import com.pynapli.server.auth.JdbcSignedInDevicesRepository
import com.pynapli.server.auth.LocalFilePasswordResetMailer
import com.pynapli.server.auth.PasswordRecoveryService
import com.pynapli.server.auth.SignedInDevicesService
import com.pynapli.server.auth.UnconfiguredPasswordResetMailer
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.location.JdbcLocationRepository
import com.pynapli.server.location.JdbcTravelLocationRepository
import com.pynapli.server.location.LocationService
import com.pynapli.server.location.TravelLocationRepository
import com.pynapli.server.messaging.MessageBroadcaster
import com.pynapli.server.metrics.ProductMetrics
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
import com.pynapli.server.safety.JdbcReportEvidenceLookup
import com.pynapli.server.safety.JdbcReportRepository
import com.pynapli.server.safety.JdbcRiskRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RedisRateLimitRepository
import com.pynapli.server.safety.ReportService
import com.pynapli.server.safety.RiskFalsePositiveMetrics
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.safety.RiskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class CoreDependencies(
    val accountLifecycleService: AccountLifecycleService,
    val accountDataExportWorker: AccountDataExportWorker?,
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
    val productMetrics: ProductMetrics,
    val authService: AuthService,
    val passwordRecoveryService: PasswordRecoveryService,
    val signedInDevicesService: SignedInDevicesService,
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
    // The server's background scope (cancelled on shutdown) for work that runs after a response.
    backgroundScope: CoroutineScope,
): CoreDependencies {
    val accountLifecycleRepository =
        JdbcAccountLifecycleRepository(database.dataSource, metInPersonAccountData = metInPersonAccountData(config))
    val accountExportStorage = buildAccountExportStorage(config)
    val accountLifecycleService =
        AccountLifecycleService(
            repository = accountLifecycleRepository,
            exportStorage = accountExportStorage,
        )
    val accountDataExportWorker =
        if (config.accountDataExportEnabled) {
            AccountDataExportWorker(
                processor =
                    AccountDataExportProcessor(
                        repository = accountLifecycleRepository,
                        archiver =
                            JdbcAccountDataExportArchiver(
                                database.dataSource,
                                metInPersonAccountData = metInPersonAccountData(config),
                            ),
                        storage = accountExportStorage,
                    ),
                intervalMillis = config.accountDataExportIntervalMillis,
            )
        } else {
            null
        }
    val travelLocationRepository = JdbcTravelLocationRepository(database.dataSource)
    val presenceRepository = RedisPresenceRepository(redisCache)
    val presenceBroadcaster = PresenceBroadcaster(relay = redisCache.realtimeRelay)
    val onlinePresenceTracker = OnlinePresenceTracker()
    val presenceService =
        PresenceService(
            repository = presenceRepository,
            onlineTracker = onlinePresenceTracker,
            lastActiveRecorder =
                com.pynapli.server.presence.LastActiveRecorder(
                    com.pynapli.server.presence
                        .JdbcLastActiveRepository(database.dataSource),
                ),
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
    val riskService =
        RiskService(
            JdbcRiskRepository(database.dataSource),
            travelEnforcement =
                com.pynapli.server.safety
                    .TravelRiskEnforcement(travelLocationRepository),
        )
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
    val productMetrics = ProductMetrics()
    val authService =
        AuthService(
            repository = JdbcAuthRepository(database.dataSource),
            rateLimitService = rateLimitService,
            deviceFarmRepository = JdbcDeviceFarmRepository(database.dataSource),
            deviceRiskSignalPublisher = riskService,
            productMetrics = productMetrics,
            revocationRelay = redisCache.realtimeRelay,
        )
    val passwordRecoveryService =
        PasswordRecoveryService(
            repository = JdbcPasswordRecoveryRepository(database.dataSource),
            // The local mailer writes codes to a file on this machine, so it is refused anywhere but local.
            mailer =
                if (config.passwordResetMailer == "log" && config.environment == "local") {
                    LocalFilePasswordResetMailer()
                } else {
                    UnconfiguredPasswordResetMailer
                },
            // A member's lookup, code write and email run off the request path, so a reset request
            // takes the same time whether or not the address has an account.
            launchResetWork = { work -> backgroundScope.launch { work() } },
            rateLimitService = rateLimitService,
            onSessionsRevoked = authService::invalidateCachedSessionsForUser,
        )
    val signedInDevicesService =
        SignedInDevicesService(
            repository = JdbcSignedInDevicesRepository(database.dataSource),
            onSessionsRevoked = authService::invalidateCachedSessionsForUser,
        )
    val reportService =
        ReportService(
            repository = JdbcReportRepository(database.dataSource),
            riskSignalPublisher = riskService,
            rateLimitService = rateLimitService,
            supportEmail = config.supportEmail,
            hiddenProfileRepository = hiddenProfileRepository,
            evidenceLookup = JdbcReportEvidenceLookup(database.dataSource),
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
    // Every replica publishes its deltas to Redis and delivers the others' to its own sockets.
    val activityBroadcaster = ActivityBroadcaster(relay = redisCache.realtimeRelay)
    val psyncBroadcaster = PsyncBroadcaster(relay = redisCache.realtimeRelay)
    val messageBroadcaster = MessageBroadcaster(relay = redisCache.realtimeRelay)
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
        accountDataExportWorker = accountDataExportWorker,
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
        productMetrics = productMetrics,
        authService = authService,
        passwordRecoveryService = passwordRecoveryService,
        signedInDevicesService = signedInDevicesService,
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
