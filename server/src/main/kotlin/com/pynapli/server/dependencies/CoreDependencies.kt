package com.pynapli.server.dependencies

import com.pynapli.server.account.AccountDataExportProcessor
import com.pynapli.server.account.AccountDataExportWorker
import com.pynapli.server.account.AccountLifecycleService
import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.account.JdbcAccountLifecycleRepository
import com.pynapli.server.activity.ActivityBroadcaster
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.BoundedWorkLauncher
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.JdbcDeviceFarmRepository
import com.pynapli.server.auth.JdbcPasswordRecoveryRepository
import com.pynapli.server.auth.JdbcSignedInDevicesRepository
import com.pynapli.server.auth.LocalFilePasswordResetMailer
import com.pynapli.server.auth.PasswordRecoveryService
import com.pynapli.server.auth.SignedInDevicesService
import com.pynapli.server.auth.UnconfiguredPasswordResetMailer
import com.pynapli.server.auth.social.AppleGrantRevoker
import com.pynapli.server.auth.social.AppleTokenClient
import com.pynapli.server.auth.social.SocialTokenSeal
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.contactblocks.ContactBlockResolver
import com.pynapli.server.contactblocks.ContactBlockResolverWorker
import com.pynapli.server.contactblocks.ContactBlockService
import com.pynapli.server.contactblocks.ContactBlockTokens
import com.pynapli.server.contactblocks.ContactBlockVisibility
import com.pynapli.server.contactblocks.JdbcContactBlockRepository
import com.pynapli.server.contactblocks.JdbcContactBlockResolverRepository
import com.pynapli.server.contactblocks.JdbcContactBlockVisibility
import com.pynapli.server.db.PostgresJobLock
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
    val contactBlockTokens: ContactBlockTokens,
    val contactBlockVisibility: ContactBlockVisibility,
    val contactBlockResolver: ContactBlockResolver,
    val contactBlockResolverWorker: ContactBlockResolverWorker,
    val contactBlockService: ContactBlockService,
    val contactBlockSettling: com.pynapli.server.contactblocks.ContactBlockSettling,
    val socialTokenSeal: SocialTokenSeal,
    val appleTokenClient: AppleTokenClient?,
    val appleGrantRevoker: AppleGrantRevoker?,
)

internal fun buildCoreDependencies(
    config: ServerConfig,
    database: Database,
    redisCache: RedisCache,
    // The server's background scope (cancelled on shutdown) for work that runs after a response.
    backgroundScope: CoroutineScope,
): CoreDependencies {
    // "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact blocks"): the configured keys, the
    // viewer-side lookup every surface applies, and the off-request resolver.
    val contactBlockTokens = ContactBlockTokens(config.contactBlockEmailKey, config.contactBlockTargetKey)
    val contactBlockVisibility = JdbcContactBlockVisibility(database.dataSource, contactBlockTokens)
    val contactBlockResolver = ContactBlockResolver(JdbcContactBlockResolverRepository(database.dataSource), contactBlockTokens)
    val contactBlockResolverWorker =
        ContactBlockResolverWorker(
            resolver = contactBlockResolver,
            jobLock = PostgresJobLock(database.dataSource),
        )
    // Verify- and sign-in-time settling that can never fail the member's request (it retries on the worker).
    val contactBlockSettling =
        com.pynapli.server.contactblocks
            .ContactBlockSettling(contactBlockResolver, contactBlockResolverWorker)
    val accountLifecycleRepository =
        JdbcAccountLifecycleRepository(
            database.dataSource,
            contactBlockTokens,
            metInPersonAccountData = metInPersonAccountData(config),
        )
    val accountExportStorage = buildAccountExportStorage(config)
    // Sign in with Apple: revocation on deletion works whenever the Apple key is configured, even if
    // the sign-in flag was switched off later (stored grants must still be revoked).
    val socialTokenSeal = SocialTokenSeal(config.socialTokenKey)
    val appleTokenClient = buildAppleTokenClient(config)
    val appleGrantRevoker =
        appleTokenClient?.let { client ->
            AppleGrantRevoker(client, socialTokenSeal, BoundedWorkLauncher(backgroundScope, label = "apple-revocation")::launch)
        }
    val accountLifecycleService =
        AccountLifecycleService(
            repository = accountLifecycleRepository,
            exportStorage = accountExportStorage,
            revokeAppleGrants = appleGrantRevoker?.let { revoker -> revoker::revokeLater },
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
                                contactBlockTokens = contactBlockTokens,
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
    val lastActiveRepository =
        com.pynapli.server.presence
            .JdbcLastActiveRepository(database.dataSource)
    // Online/offline transitions go out on the presence stream only for people the Grid could show
    // (active, visible, Grid-visible, not incognito, not hidden while travelling, not quarantined).
    val presenceVisibility =
        com.pynapli.server.presence
            .JdbcPresenceVisibility(database.dataSource)
    val presenceBroadcastAllowed: suspend (java.util.UUID) -> Boolean = presenceVisibility::broadcastable
    val presenceService =
        PresenceService(
            repository = presenceRepository,
            onlineTracker = onlinePresenceTracker,
            lastActiveRecorder =
                com.pynapli.server.presence
                    .LastActiveRecorder(lastActiveRepository),
            broadcaster = presenceBroadcaster,
            broadcastAllowed = presenceBroadcastAllowed,
        )
    // Plan G2: every authenticated request (and a socket opening) marks the caller online. The
    // in-memory claim keeps it to one Redis + one DB write per user a minute, and the write runs off
    // the request so a slow Redis never delays an unrelated call.
    val presenceRefreshLauncher =
        com.pynapli.server.auth.BoundedWorkLauncher(
            scope = backgroundScope,
            maxConcurrent = PRESENCE_REFRESH_MAX_CONCURRENT,
            maxPending = PRESENCE_REFRESH_MAX_PENDING,
            label = "presence",
        )
    val markOnlineOnAuthenticated: (java.util.UUID) -> Unit = { userId ->
        // The activity time is taken now, on the request, so a refresh still queued when the member
        // logs out is recognised as older than the logout and writes nothing (privacy review F3).
        presenceService.claimRefreshAt(userId)?.let { activityAt ->
            presenceRefreshLauncher.launch { runCatching { presenceService.refreshOnline(userId, activityAt) } }
        }
    }
    val presenceOfflineScanner =
        PresenceOfflineScanner(
            tracker = onlinePresenceTracker,
            repository = presenceRepository,
            broadcaster = presenceBroadcaster,
            broadcastAllowed = presenceBroadcastAllowed,
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
            onEmailSignIn = contactBlockSettling::atSignIn,
            onAuthenticated = markOnlineOnAuthenticated,
            onPresenceEnded = presenceService::endPresence,
        )
    val contactBlockService =
        ContactBlockService(
            repository = JdbcContactBlockRepository(database.dataSource),
            tokens = contactBlockTokens,
            resolutionTrigger = contactBlockResolverWorker,
            rateLimitService = rateLimitService,
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
    val notificationService = NotificationService(notificationRepository, contactBlockVisibility = contactBlockVisibility)
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
        contactBlockTokens = contactBlockTokens,
        contactBlockVisibility = contactBlockVisibility,
        contactBlockResolver = contactBlockResolver,
        contactBlockResolverWorker = contactBlockResolverWorker,
        contactBlockService = contactBlockService,
        contactBlockSettling = contactBlockSettling,
        socialTokenSeal = socialTokenSeal,
        appleTokenClient = appleTokenClient,
        appleGrantRevoker = appleGrantRevoker,
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

// Presence refresh off the request path: a few concurrent Redis/DB writes, a bounded queue; past it a
// refresh is dropped and the next request a minute later tries again.
private const val PRESENCE_REFRESH_MAX_CONCURRENT = 8
private const val PRESENCE_REFRESH_MAX_PENDING = 2_000
