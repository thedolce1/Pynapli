package com.pynapli.server.messaging

import com.pynapli.server.safety.MessageReviewRiskSignalPublisher
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.MessageDeltaType
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.MessageMediaUploadSlotDto
import com.pynapli.shared.network.dto.SendMediaMessageRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

internal class MessagingMessageActions(
    private val repository: MessagingRepository,
    private val dtoMapper: MessagingDtoMapper,
    private val conversationGuard: MessagingConversationGuard,
    private val realtimePublisher: MessagingRealtimePublisher,
    private val notificationDispatcher: MessagingNotificationDispatcher,
    private val rateLimitService: RateLimitService?,
    private val messageSafetyPolicy: MessageSafetyPolicy,
    private val messageReviewRiskSignalPublisher: MessageReviewRiskSignalPublisher?,
    private val riskPolicyService: RiskPolicyService?,
    private val clock: Clock,
    private val messageMediaGateway: MessageMediaGateway?,
) {
    suspend fun sendText(
        userId: UUID,
        conversationId: UUID,
        request: SendMessageRequestDto,
    ): MessageDto {
        val body = request.body.trim()
        if (body.isEmpty()) throw MessagingException("MESSAGE_BODY_EMPTY")
        if (body.length > MESSAGE_BODY_MAX_LENGTH) throw MessagingException("MESSAGE_BODY_TOO_LONG")
        val clientIdempotencyKey = request.clientIdempotencyKey.toClientIdempotencyKeyOrThrow()
        requireUnrestrictedSocialAction(userId)

        val detail =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        conversationGuard.requireMessagingAllowed(detail.conversation, userId)

        val isFirstMessage = !repository.hasSentMessageInConversation(userId, conversationId)
        if (isFirstMessage && rateLimitService?.check(userId, RateLimitedAction.FIRST_MESSAGE)?.allowed == false) {
            throw MessagingException("RATE_LIMITED")
        }

        val safetyDecision = evaluateMessageSafety(userId, body, isFirstMessage)
        val messageRecord =
            repository.sendMessage(
                userId = userId,
                conversationId = conversationId,
                body = body,
                moderationStatus = safetyDecision.moderationStatus,
                bodyFingerprint = safetyDecision.bodyFingerprint,
                safetyReason = safetyDecision.safetyReason,
                clientIdempotencyKey = clientIdempotencyKey,
            ) ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        val conversation = repository.getConversationDetail(userId, conversationId, limit = 1)?.conversation
        val message =
            dtoMapper.toDto(
                record = messageRecord,
                viewerUserId = userId,
                conversation = conversation,
                readReceiptCache = mutableMapOf(),
            )

        if (message.status == MessageModerationStatus.HELD) {
            recordHeldMessageRiskSignal(userId, message, safetyDecision)
            return message
        }

        publishCreatedMessage(userId, conversationId, message, conversation?.participants.orEmpty())
        return message
    }

    suspend fun createMediaUploadSlot(
        userId: UUID,
        request: CreateMessageMediaUploadRequestDto,
    ): MessageMediaUploadSlotDto {
        val gateway = messageMediaGateway ?: throw MessagingException("MEDIA_NOT_AVAILABLE")
        requireUnrestrictedSocialAction(userId)
        return gateway.createUploadSlot(userId, request)
    }

    suspend fun sendMedia(
        userId: UUID,
        conversationId: UUID,
        request: SendMediaMessageRequestDto,
    ): MessageDto {
        val gateway = messageMediaGateway ?: throw MessagingException("MEDIA_NOT_AVAILABLE")
        val mediaAssetId = request.mediaAssetId.toMessagingUuidOrThrow()
        val caption = request.body.trim()
        if (caption.length > MESSAGE_BODY_MAX_LENGTH) throw MessagingException("MESSAGE_BODY_TOO_LONG")
        val clientIdempotencyKey = request.clientIdempotencyKey.toClientIdempotencyKeyOrThrow()
        requireUnrestrictedSocialAction(userId)

        val detail =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        conversationGuard.requireMessagingAllowed(detail.conversation, userId)

        val prepared =
            gateway.prepareOutboundMedia(userId, mediaAssetId)
                ?: throw MessagingException("INVALID_MEDIA")

        val messageRecord =
            repository.sendMediaMessage(
                userId = userId,
                conversationId = conversationId,
                mediaAssetId = mediaAssetId,
                contentType = prepared.contentType,
                pixelWidth = prepared.pixelWidth,
                pixelHeight = prepared.pixelHeight,
                body = caption,
                clientIdempotencyKey = clientIdempotencyKey,
            ) ?: throw MessagingException("CONVERSATION_NOT_FOUND")

        val conversation = repository.getConversationDetail(userId, conversationId, limit = 1)?.conversation
        val message = dtoMapper.toDto(record = messageRecord, viewerUserId = userId, conversation = conversation)
        publishCreatedMessage(userId, conversationId, message, conversation?.participants.orEmpty())
        return message
    }

    suspend fun revealMedia(
        userId: UUID,
        messageId: UUID,
    ): MessageDto {
        val revealed =
            repository.recordMediaReveal(userId, messageId)
                ?: throw MessagingException("MESSAGE_NOT_FOUND")
        val conversation = repository.getConversationDetail(userId, revealed.conversationId, limit = 1)?.conversation
        return dtoMapper.toDto(record = revealed, viewerUserId = userId, conversation = conversation)
    }

    suspend fun deleteOwnMessage(
        userId: UUID,
        messageId: UUID,
    ): MessageDto {
        val deleted =
            repository.deleteOwnMessage(userId, messageId)
                ?: throw MessagingException("MESSAGE_NOT_FOUND")
        val participants =
            repository
                .getConversationDetail(userId, deleted.conversationId, limit = 1)
                ?.conversation
                ?.participants
                .orEmpty()
        realtimePublisher.publishDelta(
            type = MessageDeltaType.MESSAGE_DELETED,
            conversationId = deleted.conversationId,
            participants = participants,
            messageId = deleted.messageId,
            statuses = MessagingParticipantStatuses.active,
        )
        return dtoMapper.toDto(deleted, userId)
    }

    private suspend fun publishCreatedMessage(
        userId: UUID,
        conversationId: UUID,
        message: MessageDto,
        participants: List<ConversationParticipantRecord>,
    ) {
        realtimePublisher.publishDelta(
            type = MessageDeltaType.MESSAGE_CREATED,
            conversationId = conversationId,
            participants = participants,
            messageId = UUID.fromString(message.messageId),
            statuses = MessagingParticipantStatuses.active,
        )
        notificationDispatcher.enqueueMessageNotifications(
            actorUserId = userId,
            conversationId = conversationId,
            messageId = message.messageId,
            participants = participants,
        )
    }

    private suspend fun evaluateMessageSafety(
        userId: UUID,
        body: String,
        isFirstMessage: Boolean,
    ): MessageSafetyDecision {
        val bodyFingerprint = messageSafetyPolicy.fingerprint(body)
        if (!isFirstMessage) return MessageSafetyDecision.visible(bodyFingerprint)

        val recentDuplicateCount =
            repository.countRecentMessagesByFingerprint(
                userId = userId,
                bodyFingerprint = bodyFingerprint,
                since = clock.instant().minusSeconds(MessageSafetyPolicy.DUPLICATE_LOOKBACK_SECONDS),
            )
        return messageSafetyPolicy.evaluateFirstMessage(
            body = body,
            recentDuplicateCount = recentDuplicateCount,
            bodyFingerprint = bodyFingerprint,
        )
    }

    private suspend fun requireUnrestrictedSocialAction(userId: UUID) {
        if (riskPolicyService?.isSocialActionRestricted(userId) == true) {
            throw MessagingException("RISK_RESTRICTED")
        }
    }

    private suspend fun recordHeldMessageRiskSignal(
        userId: UUID,
        message: MessageDto,
        safetyDecision: MessageSafetyDecision,
    ) {
        safetyDecision.safetyReason?.let { reason ->
            runCatching {
                messageReviewRiskSignalPublisher?.recordMessageHeld(
                    userId = userId,
                    messageId = UUID.fromString(message.messageId),
                    reason = reason,
                )
            }.onFailure { error ->
                logger.warn("Failed to record held-message risk signal for message {}", message.messageId, error)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MessagingMessageActions::class.java)
    }
}
