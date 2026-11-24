package com.pynapli.server.dependencies

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.messaging.JdbcMessagingRepository
import com.pynapli.server.messaging.MessageBroadcaster
import com.pynapli.server.messaging.MessagingService
import com.pynapli.server.moderation.AdminReportService
import com.pynapli.server.moderation.AdminRiskService
import com.pynapli.server.moderation.JdbcAdminReportRepository
import com.pynapli.server.moderation.JdbcAdminRiskRepository
import com.pynapli.server.moderation.JdbcMessageModerationRepository
import com.pynapli.server.moderation.MessageModerationService
import com.pynapli.server.notifications.NotificationService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.safety.RiskService
import com.pynapli.server.subscriptions.SubscriptionService
import javax.sql.DataSource

/**
 * Messaging + moderation subsystem wiring extracted from `ServerDependencies.fromEnvironment` to keep
 * the DI root a thin orchestrator. Builds the conversation/messaging service and the admin
 * moderation/report/risk services; all are consumed only by the `ServerDependencies` constructor, so
 * the shared upstream dependencies are threaded in as parameters. Behavior matches the original
 * inline construction.
 */
internal class MessagingDependencies(
    val messagingService: MessagingService,
    val messageModerationService: MessageModerationService,
    val adminReportService: AdminReportService,
    val adminRiskService: AdminRiskService,
)

internal fun buildMessagingDependencies(
    dataSource: DataSource,
    blockRepository: BlockRepository,
    notificationService: NotificationService,
    messageBroadcaster: MessageBroadcaster,
    rateLimitService: RateLimitService,
    riskService: RiskService,
    riskPolicyService: RiskPolicyService,
    subscriptionService: SubscriptionService,
    activityService: ActivityService,
    messageMediaGateway: com.pynapli.server.messaging.MessageMediaGateway,
    productMetrics: com.pynapli.server.metrics.ProductMetrics,
    travelLocationRepository: com.pynapli.server.location.TravelLocationRepository? = null,
    contactBlockTokens: com.pynapli.server.contactblocks.ContactBlockTokens,
    contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
): MessagingDependencies {
    val messagingService =
        MessagingService(
            // Moderation and admin repositories below never get the contact block rule.
            repository = JdbcMessagingRepository(dataSource, contactBlockTokens = contactBlockTokens),
            blockRepository = blockRepository,
            notificationPublisher = notificationService,
            messageBroadcaster = messageBroadcaster,
            rateLimitService = rateLimitService,
            messageReviewRiskSignalPublisher = riskService,
            riskPolicyService = riskPolicyService,
            subscriptionService = subscriptionService,
            activityService = activityService,
            messageMediaGateway = messageMediaGateway,
            productMetrics = productMetrics,
            contactBlockVisibility = contactBlockVisibility,
        )
    val messageModerationService =
        MessageModerationService(
            JdbcMessageModerationRepository(dataSource),
        )
    val adminReportService =
        AdminReportService(
            JdbcAdminReportRepository(dataSource),
        )
    val adminRiskService =
        AdminRiskService(
            JdbcAdminRiskRepository(dataSource),
            travelEnforcement =
                com.pynapli.server.safety
                    .TravelRiskEnforcement(travelLocationRepository),
        )
    return MessagingDependencies(
        messagingService = messagingService,
        messageModerationService = messageModerationService,
        adminReportService = adminReportService,
        adminRiskService = adminRiskService,
    )
}
