package com.pynapli.server.messaging

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.metrics.ProductMetrics
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.MessageReviewRiskSignalPublisher
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MessageDeltaType
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import java.time.Clock
import java.util.UUID

class MessagingService(
    private val repository: MessagingRepository,
    blockRepository: BlockRepository? = null,
    notificationPublisher: NotificationPublisher? = null,
    messageBroadcaster: MessageBroadcaster? = null,
    rateLimitService: RateLimitService? = null,
    messageSafetyPolicy: MessageSafetyPolicy = MessageSafetyPolicy(),
    messageReviewRiskSignalPublisher: MessageReviewRiskSignalPublisher? = null,
    riskPolicyService: RiskPolicyService? = null,
    subscriptionService: SubscriptionService? = null,
    clock: Clock = Clock.systemUTC(),
    activityService: ActivityService? = null,
    messageMediaGateway: MessageMediaGateway? = null,
    private val productMetrics: ProductMetrics? = null,
    // "Hide from people you know" (viewer side only): pair with JdbcMessagingRepository's tokens.
    contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    private val featureAccess = MessagingFeatureAccess(subscriptionService)
    private val dtoMapper = MessagingDtoMapper(featureAccess, messageMediaGateway, contactBlockVisibility)
    private val conversationGuard = MessagingConversationGuard(blockRepository, contactBlockVisibility)
    private val realtimePublisher = MessagingRealtimePublisher(messageBroadcaster, clock, contactBlockVisibility)
    private val notificationDispatcher = MessagingNotificationDispatcher(notificationPublisher)
    private val conversationActions =
        MessagingConversationActions(
            repository = repository,
            dtoMapper = dtoMapper,
            conversationGuard = conversationGuard,
            realtimePublisher = realtimePublisher,
            featureAccess = featureAccess,
            riskPolicyService = riskPolicyService,
        )
    private val messageActions =
        MessagingMessageActions(
            repository = repository,
            dtoMapper = dtoMapper,
            conversationGuard = conversationGuard,
            realtimePublisher = realtimePublisher,
            notificationDispatcher = notificationDispatcher,
            rateLimitService = rateLimitService,
            messageSafetyPolicy = messageSafetyPolicy,
            messageReviewRiskSignalPublisher = messageReviewRiskSignalPublisher,
            riskPolicyService = riskPolicyService,
            clock = clock,
            messageMediaGateway = messageMediaGateway,
        )
    private val partnerActions =
        MessagingPartnerConversationActions(
            repository = repository,
            dtoMapper = dtoMapper,
            conversationGuard = conversationGuard,
            realtimePublisher = realtimePublisher,
            notificationDispatcher = notificationDispatcher,
            activityService = activityService,
            riskPolicyService = riskPolicyService,
        )

    /** [MatchConversationOpener] backed by this service; see [openMatchConversation]. */
    val matchConversationOpener: MatchConversationOpener = MatchConversationOpener { a, b -> openMatchConversation(a, b) }

    /**
     * Get or create the swipe-origin direct conversation for a new match and tell both people's
     * Messages to refetch. A conversation that already exists (they talked from the Grid) is reused
     * and moved up to the match time; it shows in both rails until each person opens it.
     * Blocked pairs get nothing. A failure is thrown: the caller delivers this through the match
     * side-effect outbox, which keeps it for a retry (the match itself already stands).
     */
    suspend fun openMatchConversation(
        userA: UUID,
        userB: UUID,
    ) {
        if (userA == userB) return
        val conversation =
            repository.getOrCreateDirectConversation(
                viewerUserId = userA,
                targetUserId = userB,
                origin = MATCH_CONVERSATION_ORIGIN,
            ) ?: return
        realtimePublisher.publishDelta(
            type = MessageDeltaType.CONVERSATION_UPDATED,
            conversationId = conversation.conversationId,
            participants = conversation.participants,
            statuses = MessagingParticipantStatuses.visible,
        )
    }

    suspend fun listConversations(
        userId: UUID,
        cursorText: String? = null,
    ): ConversationListResponseDto = conversationActions.listConversations(userId, cursorText)

    suspend fun createDirectConversation(
        viewerUserId: UUID,
        request: CreateDirectConversationRequestDto,
    ): ConversationDetailDto = conversationActions.createDirectConversation(viewerUserId, request)

    suspend fun getConversationDetail(
        userId: UUID,
        conversationIdText: String,
        beforeCursorText: String? = null,
        limitText: String? = null,
    ): ConversationDetailDto =
        conversationActions.getConversationDetail(
            userId = userId,
            conversationIdText = conversationIdText,
            beforeCursorText = beforeCursorText,
            limitText = limitText,
        )

    suspend fun markConversationRead(
        userId: UUID,
        conversationIdText: String,
    ): ConversationDto = conversationActions.markConversationRead(userId, conversationIdText)

    suspend fun sendMessage(
        userId: UUID,
        conversationIdText: String,
        request: SendMessageRequestDto,
    ): MessageDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        val message = messageActions.sendText(userId, conversationId, request)
        productMetrics?.record(ProductMetrics.MESSAGE_SENT)
        return message
    }

    suspend fun createMessageMediaUploadSlot(
        userId: UUID,
        request: com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto,
    ): com.pynapli.shared.network.dto.MessageMediaUploadSlotDto = messageActions.createMediaUploadSlot(userId, request)

    suspend fun sendMediaMessage(
        userId: UUID,
        conversationIdText: String,
        request: com.pynapli.shared.network.dto.SendMediaMessageRequestDto,
    ): MessageDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        val message = messageActions.sendMedia(userId, conversationId, request)
        productMetrics?.record(ProductMetrics.MESSAGE_SENT)
        return message
    }

    suspend fun revealMessageMedia(
        userId: UUID,
        messageIdText: String,
    ): MessageDto {
        val messageId = messageIdText.toMessagingUuidOrThrow()
        return messageActions.revealMedia(userId, messageId)
    }

    suspend fun deleteOwnMessage(
        userId: UUID,
        messageIdText: String,
    ): MessageDto {
        val messageId = messageIdText.toMessagingUuidOrThrow()
        return messageActions.deleteOwnMessage(userId, messageId)
    }

    suspend fun inviteLinkedPartnerToConversation(
        userId: UUID,
        conversationIdText: String,
    ): ConversationDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        return partnerActions.inviteLinkedPartnerToConversation(userId, conversationId)
    }

    /**
     * [inviteIdText] (optional `inviteId` query parameter) names the invite a third party approves when
     * several are open; without it the server resolves the invite from who is acting.
     */
    suspend fun acceptPartnerInvite(
        userId: UUID,
        conversationIdText: String,
        inviteIdText: String? = null,
    ): ConversationDetailDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        return partnerActions.acceptPartnerInvite(userId, conversationId, inviteIdText.toInviteIdOrThrow())
    }

    suspend fun declinePartnerInvite(
        userId: UUID,
        conversationIdText: String,
        inviteIdText: String? = null,
    ): ConversationPartnerInviteResponseDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        return partnerActions.declinePartnerInvite(userId, conversationId, inviteIdText.toInviteIdOrThrow())
    }

    private fun String?.toInviteIdOrThrow(): UUID? =
        this?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { UUID.fromString(text) }.getOrElse { throw MessagingException("PARTNER_INVITE_NOT_FOUND") }
        }

    suspend fun leaveConversation(
        userId: UUID,
        conversationIdText: String,
    ): ConversationPartnerInviteResponseDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        return partnerActions.leaveConversation(userId, conversationId)
    }

    suspend fun sendTypingIndicator(
        userId: UUID,
        conversationIdText: String,
        request: SendTypingIndicatorRequestDto,
    ) = conversationActions.sendTypingIndicator(userId, conversationIdText, request)

    suspend fun setConversationExpiry(
        userId: UUID,
        conversationIdText: String,
        expirySeconds: Int?,
    ): ConversationDto = conversationActions.setConversationExpiry(userId, conversationIdText, expirySeconds)
}

class MessagingException(
    val code: String,
) : RuntimeException(code)

// The origin a match opens its conversation with. The new-matches rail itself is decided by the
// server per viewer (ConversationRecord.newMatch), not by origin.
internal const val MATCH_CONVERSATION_ORIGIN = "swipe"
