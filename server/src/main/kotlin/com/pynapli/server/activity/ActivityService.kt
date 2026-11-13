package com.pynapli.server.activity

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaService
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import java.util.UUID

class ActivityService(
    repository: ActivityRepository,
    mediaService: MediaService? = null,
    mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    blockRepository: BlockRepository? = null,
    notificationPublisher: NotificationPublisher? = null,
    rateLimitService: RateLimitService? = null,
    riskPolicyService: RiskPolicyService? = null,
    subscriptionService: SubscriptionService? = null,
    activityBroadcaster: ActivityBroadcaster? = null,
    cursorCodec: ActivityCursorCodec = ActivityCursorCodec("pynapli-local-dev-activity-cursor-secret"),
) {
    private val signalActions =
        ActivitySignalActions(
            repository = repository,
            mediaService = mediaService,
            blockRepository = blockRepository,
            notificationPublisher = notificationPublisher,
            rateLimitService = rateLimitService,
            riskPolicyService = riskPolicyService,
            activityBroadcaster = activityBroadcaster,
        )
    private val feedActions =
        ActivityFeedActions(
            repository = repository,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            blockRepository = blockRepository,
            riskPolicyService = riskPolicyService,
            subscriptionService = subscriptionService,
            cursorCodec = cursorCodec,
        )
    private val managementActions =
        ActivityManagementActions(
            repository = repository,
            mediaService = mediaService,
        )

    suspend fun recordProfileView(
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        signalActions.recordProfileView(
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
        )
    }

    suspend fun sendFlirt(
        senderUserId: UUID,
        targetUserId: UUID,
        source: String? = null,
    ): SendFlirtResponseDto =
        signalActions.sendFlirt(
            senderUserId = senderUserId,
            targetUserId = targetUserId,
            source = source,
        )

    suspend fun requestAlbumAccess(
        requesterUserId: UUID,
        targetUserId: UUID,
    ): RequestAlbumAccessResponseDto =
        signalActions.requestAlbumAccess(
            requesterUserId = requesterUserId,
            targetUserId = targetUserId,
        )

    /**
     * [pairUserId]/[pairSessionId]: a like the actor made together with their linked partner in a
     * Psync session, where the partner liked the same person; the feed shows the two as one.
     */
    suspend fun recordSwipeInterest(
        actorUserId: UUID,
        targetUserId: UUID,
        eventType: String,
        pairUserId: UUID? = null,
        pairSessionId: UUID? = null,
    ) {
        signalActions.recordSwipeInterest(
            actorUserId = actorUserId,
            targetUserId = targetUserId,
            eventType = eventType,
            metadataJson =
                if (pairUserId != null && pairSessionId != null) {
                    "{\"pairUserId\":\"$pairUserId\",\"pairSessionId\":\"$pairSessionId\"}"
                } else {
                    "{}"
                },
        )
    }

    suspend fun recordMatch(
        userA: UUID,
        userB: UUID,
        matchId: UUID,
    ) {
        signalActions.recordMatch(
            userA = userA,
            userB = userB,
            matchId = matchId,
        )
    }

    suspend fun recordPartnerChatInvite(
        inviterUserId: UUID,
        inviteeUserId: UUID,
        conversationId: UUID,
    ) {
        signalActions.recordPartnerChatInvite(
            inviterUserId = inviterUserId,
            inviteeUserId = inviteeUserId,
            conversationId = conversationId,
        )
    }

    suspend fun recordPsyncSessionEvent(
        recipientUserId: UUID,
        actorUserId: UUID,
        eventType: String,
        sessionId: UUID,
    ) {
        signalActions.recordPsyncSessionEvent(
            recipientUserId = recipientUserId,
            actorUserId = actorUserId,
            eventType = eventType,
            sessionId = sessionId,
        )
    }

    suspend fun respondToAlbumAccessRequest(
        ownerUserId: UUID,
        eventId: UUID,
        approve: Boolean,
    ): RespondAlbumAccessResponseDto =
        managementActions.respondToAlbumAccessRequest(
            ownerUserId = ownerUserId,
            eventId = eventId,
            approve = approve,
            onGranted = { requesterUserId -> signalActions.recordAlbumGranted(ownerUserId, requesterUserId) },
        )

    suspend fun dismissActivityEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): DismissActivityEventResponseDto =
        managementActions.dismissActivityEvent(
            recipientUserId = recipientUserId,
            eventId = eventId,
        )

    suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryDto = managementActions.getActivitySummary(recipientUserId)

    suspend fun markActivityEventsSeen(
        recipientUserId: UUID,
        eventIdTexts: List<String>,
    ): MarkActivitySeenResponseDto =
        managementActions.markActivityEventsSeen(
            recipientUserId = recipientUserId,
            eventIdTexts = eventIdTexts,
        )

    suspend fun listActivity(
        recipientUserId: UUID,
        limit: Int = ACTIVITY_DEFAULT_LIMIT,
        tabText: String? = null,
        cursorText: String? = null,
    ): ActivityFeedResponseDto =
        feedActions.listActivity(
            recipientUserId = recipientUserId,
            limit = limit,
            tabText = tabText,
            cursorText = cursorText,
        )

    companion object {
        const val ACTIVITY_SOURCE_PLAYING_TONIGHT = ACTIVITY_SOURCE_PLAYING_TONIGHT_VALUE
    }
}
