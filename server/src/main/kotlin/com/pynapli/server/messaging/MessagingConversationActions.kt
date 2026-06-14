package com.pynapli.server.messaging

import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MessageDeltaType
import com.pynapli.shared.network.dto.MessageExpiryOption
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import java.util.UUID

internal class MessagingConversationActions(
    private val repository: MessagingRepository,
    private val dtoMapper: MessagingDtoMapper,
    private val conversationGuard: MessagingConversationGuard,
    private val realtimePublisher: MessagingRealtimePublisher,
    private val featureAccess: MessagingFeatureAccess,
    private val riskPolicyService: RiskPolicyService?,
) {
    suspend fun listConversations(userId: UUID): ConversationListResponseDto {
        val conversations = repository.listConversations(userId, MessagingServiceDefaults.ThreadLimit)
        val visible = conversationGuard.filterBlockedConversations(userId, conversations)
        val readReceiptCache = mutableMapOf<UUID, Boolean>()
        return ConversationListResponseDto(
            items = visible.map { dtoMapper.toDto(it, userId, readReceiptCache) },
            nextCursor = null,
        )
    }

    suspend fun createDirectConversation(
        viewerUserId: UUID,
        request: CreateDirectConversationRequestDto,
    ): ConversationDetailDto {
        val targetUserId = request.targetUserId.toMessagingUuidOrThrow()
        if (targetUserId == viewerUserId) throw MessagingException("INVALID_CONVERSATION_TARGET")
        conversationGuard.requireNoDirectBlock(viewerUserId, targetUserId)

        val origin = request.origin.trim().lowercase()
        if (origin !in MessagingServiceDefaults.origins) throw MessagingException("INVALID_CONVERSATION_ORIGIN")
        if (riskPolicyService?.isSocialActionRestricted(viewerUserId) == true) {
            throw MessagingException("RISK_RESTRICTED")
        }

        val conversation =
            repository.getOrCreateDirectConversation(
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                origin = origin,
            ) ?: throw MessagingException("MESSAGING_NOT_ALLOWED")

        val detail =
            repository.getConversationDetail(
                viewerUserId,
                conversation.conversationId,
                MessagingServiceDefaults.MessagePageLimit,
            )
        return detail?.let { dtoMapper.toDto(it, viewerUserId) }
            ?: ConversationDetailDto(conversation = dtoMapper.toDto(conversation, viewerUserId), messages = emptyList())
    }

    suspend fun getConversationDetail(
        userId: UUID,
        conversationIdText: String,
        beforeCursorText: String? = null,
        limitText: String? = null,
    ): ConversationDetailDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        val before = beforeCursorText?.takeIf { it.isNotBlank() }?.toMessageCursorOrThrow()
        val limit = limitText.toMessageLimitOrThrow()
        val detail =
            repository.getConversationDetail(userId, conversationId, limit, before)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        conversationGuard.requireVisibleToViewer(detail.conversation, userId)
        return dtoMapper.toDto(detail, userId)
    }

    suspend fun markConversationRead(
        userId: UUID,
        conversationIdText: String,
    ): ConversationDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        val conversation =
            repository.markConversationRead(userId, conversationId)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        if (featureAccess.readReceiptsAllowed(userId)) {
            realtimePublisher.publishDelta(
                type = MessageDeltaType.MESSAGE_READ,
                conversationId = conversationId,
                participants = conversation.participants,
                statuses = MessagingParticipantStatuses.active,
            )
        }
        return dtoMapper.toDto(conversation, userId)
    }

    suspend fun sendTypingIndicator(
        userId: UUID,
        conversationIdText: String,
        request: SendTypingIndicatorRequestDto,
    ) {
        if (!featureAccess.typingIndicatorsAllowed(userId)) return
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        val detail =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: return
        runCatching { conversationGuard.requireMessagingAllowed(detail.conversation, userId) }.getOrElse { return }

        val actor =
            detail.conversation.participants.firstOrNull { participant ->
                participant.userId == userId && participant.status == ConversationParticipantStatus.ACTIVE
            } ?: return
        val recipientIds =
            detail.conversation.participants
                .filter { participant ->
                    participant.userId != userId &&
                        participant.status == ConversationParticipantStatus.ACTIVE &&
                        featureAccess.typingIndicatorsAllowed(participant.userId)
                }.map { it.userId }
                .distinct()
        if (recipientIds.isEmpty()) return

        realtimePublisher.publishTyping(
            conversationId = conversationId,
            actor = actor,
            recipientIds = recipientIds,
            isTyping = request.isTyping,
        )
    }

    suspend fun setConversationExpiry(
        userId: UUID,
        conversationIdText: String,
        expirySeconds: Int?,
    ): ConversationDto {
        val conversationId = conversationIdText.toMessagingUuidOrThrow()
        val normalized = expirySeconds?.takeIf { it > 0 }
        if (normalized != null && normalized !in MessageExpiryOption.ALL) {
            throw MessagingException("INVALID_MESSAGE_EXPIRY")
        }
        if (!featureAccess.messageExpiryAllowed(userId)) {
            throw MessagingException("ENTITLEMENT_REQUIRED")
        }

        val result =
            repository.setConversationExpiry(
                userId = userId,
                conversationId = conversationId,
                expirySeconds = normalized,
                systemMessageBody = expirySystemMessageBody(normalized),
            ) ?: throw MessagingException("CONVERSATION_NOT_FOUND")

        realtimePublisher.publishDelta(
            type = MessageDeltaType.MESSAGE_CREATED,
            conversationId = conversationId,
            participants = result.conversation.participants,
            messageId = result.systemMessage.messageId,
            statuses = MessagingParticipantStatuses.active,
        )
        realtimePublisher.publishDelta(
            type = MessageDeltaType.CONVERSATION_UPDATED,
            conversationId = conversationId,
            participants = result.conversation.participants,
            statuses = MessagingParticipantStatuses.active,
        )
        return dtoMapper.toDto(result.conversation, userId)
    }

    private fun expirySystemMessageBody(expirySeconds: Int?): String =
        when (expirySeconds) {
            null -> "Disappearing messages turned off."
            MessageExpiryOption.OneHour -> "Disappearing messages set to 1 hour."
            MessageExpiryOption.OneDay -> "Disappearing messages set to 24 hours."
            MessageExpiryOption.OneWeek -> "Disappearing messages set to 7 days."
            MessageExpiryOption.ThirtyDays -> "Disappearing messages set to 30 days."
            else -> "Disappearing messages updated."
        }
}
