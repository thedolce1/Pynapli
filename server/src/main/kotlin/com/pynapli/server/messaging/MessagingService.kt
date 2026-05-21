package com.pynapli.server.messaging

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.MessageReviewRiskSignalPublisher
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import org.slf4j.LoggerFactory
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

class MessagingService(
    private val repository: MessagingRepository,
    private val blockRepository: BlockRepository? = null,
    private val notificationPublisher: NotificationPublisher? = null,
    private val rateLimitService: RateLimitService? = null,
    private val messageSafetyPolicy: MessageSafetyPolicy = MessageSafetyPolicy(),
    private val messageReviewRiskSignalPublisher: MessageReviewRiskSignalPublisher? = null,
    private val riskPolicyService: RiskPolicyService? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val activityService: ActivityService? = null
) {
    suspend fun listConversations(userId: UUID): ConversationListResponseDto {
        val conversations = repository.listConversations(userId, THREAD_LIMIT)
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(userId).orEmpty()
        val visible = if (blockedRelatedIds.isEmpty()) {
            conversations
        } else {
            conversations.filterNot { conversation ->
                conversation.participants.any { participant ->
                    participant.userId != userId && participant.userId in blockedRelatedIds
                }
            }
        }
        return ConversationListResponseDto(
            items = visible.map { it.toDto() },
            nextCursor = null
        )
    }

    suspend fun createDirectConversation(
        viewerUserId: UUID,
        request: CreateDirectConversationRequestDto
    ): ConversationDetailDto {
        val targetUserId = request.targetUserId.toUuidOrThrow()
        if (targetUserId == viewerUserId) throw MessagingException("INVALID_CONVERSATION_TARGET")

        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) {
            throw MessagingException("MESSAGING_NOT_ALLOWED")
        }

        val origin = request.origin.trim().lowercase()
        if (origin !in ORIGINS) throw MessagingException("INVALID_CONVERSATION_ORIGIN")
        if (riskPolicyService?.isSocialActionRestricted(viewerUserId) == true) {
            throw MessagingException("RISK_RESTRICTED")
        }

        val conversation = repository.getOrCreateDirectConversation(
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
            origin = origin
        ) ?: throw MessagingException("MESSAGING_NOT_ALLOWED")

        val detail = repository.getConversationDetail(viewerUserId, conversation.conversationId, MESSAGE_PAGE_LIMIT)
        return detail?.toDto() ?: ConversationDetailDto(conversation = conversation.toDto(), messages = emptyList())
    }

    suspend fun getConversationDetail(
        userId: UUID,
        conversationIdText: String,
        beforeCursorText: String? = null,
        limitText: String? = null
    ): ConversationDetailDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val before = beforeCursorText?.takeIf { it.isNotBlank() }?.toMessageCursorOrThrow()
        val limit = limitText.toMessageLimitOrThrow()
        val detail = repository.getConversationDetail(userId, conversationId, limit, before)
            ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        if (detail.conversation.hasBlockedOtherParticipant(userId)) {
            throw MessagingException("CONVERSATION_NOT_FOUND")
        }
        return detail.toDto()
    }

    suspend fun markConversationRead(userId: UUID, conversationIdText: String): ConversationDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        return repository.markConversationRead(userId, conversationId)?.toDto()
            ?: throw MessagingException("CONVERSATION_NOT_FOUND")
    }

    suspend fun sendMessage(userId: UUID, conversationIdText: String, request: SendMessageRequestDto): MessageDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val body = request.body.trim()
        if (body.isEmpty()) throw MessagingException("MESSAGE_BODY_EMPTY")
        if (body.length > MESSAGE_BODY_MAX_LENGTH) throw MessagingException("MESSAGE_BODY_TOO_LONG")
        if (riskPolicyService?.isSocialActionRestricted(userId) == true) {
            throw MessagingException("RISK_RESTRICTED")
        }

        if (blockRepository != null) {
            val detail = repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
            if (detail.conversation.hasBlockedOtherParticipant(userId)) {
                throw MessagingException("MESSAGING_NOT_ALLOWED")
            }
        }
        val isFirstMessage = !repository.hasSentMessageInConversation(userId, conversationId)
        if (isFirstMessage) {
            if (rateLimitService?.check(userId, RateLimitedAction.FIRST_MESSAGE)?.allowed == false) {
                throw MessagingException("RATE_LIMITED")
            }
        }
        val bodyFingerprint = messageSafetyPolicy.fingerprint(body)
        val safetyDecision = if (isFirstMessage) {
            val recentDuplicateCount = repository.countRecentMessagesByFingerprint(
                userId = userId,
                bodyFingerprint = bodyFingerprint,
                since = clock.instant().minusSeconds(MessageSafetyPolicy.DUPLICATE_LOOKBACK_SECONDS)
            )
            messageSafetyPolicy.evaluateFirstMessage(
                body = body,
                recentDuplicateCount = recentDuplicateCount,
                bodyFingerprint = bodyFingerprint
            )
        } else {
            MessageSafetyDecision.visible(bodyFingerprint)
        }

        val message = repository.sendMessage(
            userId = userId,
            conversationId = conversationId,
            body = body,
            moderationStatus = safetyDecision.moderationStatus,
            bodyFingerprint = safetyDecision.bodyFingerprint,
            safetyReason = safetyDecision.safetyReason
        )?.toDto() ?: throw MessagingException("CONVERSATION_NOT_FOUND")

        if (message.status == MessageModerationStatus.HELD) {
            safetyDecision.safetyReason?.let { reason ->
                runCatching {
                    messageReviewRiskSignalPublisher?.recordMessageHeld(
                        userId = userId,
                        messageId = UUID.fromString(message.messageId),
                        reason = reason
                    )
                }.onFailure { error ->
                    logger.warn("Failed to record held-message risk signal for message {}", message.messageId, error)
                }
            }
            return message
        }

        repository.getConversationDetail(userId, conversationId, limit = 1)
            ?.conversation
            ?.participants
            ?.filter { participant ->
                participant.userId != userId && participant.status == ConversationParticipantStatus.ACTIVE
            }
            ?.forEach { participant ->
                notificationPublisher?.enqueue(
                    NotificationEventDraft(
                        recipientUserId = participant.userId,
                        actorUserId = userId,
                        kind = NotificationKind.MESSAGE,
                        subjectId = conversationId,
                        idempotencyKey = "message:${message.messageId}:${participant.userId}",
                        payload = mapOf(
                            "target" to "messages",
                            "conversationId" to conversationId.toString(),
                            "messageId" to message.messageId
                        )
                    )
                )
            }

        return message
    }

    suspend fun inviteLinkedPartnerToConversation(userId: UUID, conversationIdText: String): ConversationDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        if (riskPolicyService?.isSocialActionRestricted(userId) == true) {
            throw MessagingException("RISK_RESTRICTED")
        }
        val partnerUserId = repository.getActiveLinkedPartnerUserId(userId)
            ?: throw MessagingException("LINKED_PARTNER_NOT_FOUND")

        val detail = repository.getConversationDetail(userId, conversationId, limit = 1)
            ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        if (detail.conversation.hasBlockedOtherParticipant(userId)) {
            throw MessagingException("CONVERSATION_NOT_FOUND")
        }
        if (detail.conversation.hasBlockedParticipantFor(partnerUserId)) {
            throw MessagingException("MESSAGING_NOT_ALLOWED")
        }

        return when (
            val result = repository.inviteLinkedPartner(
                inviterUserId = userId,
                conversationId = conversationId,
                partnerUserId = partnerUserId
            )
        ) {
            is PartnerInviteResult.Invited -> {
                activityService?.recordPartnerChatInvite(
                    inviterUserId = userId,
                    inviteeUserId = partnerUserId,
                    conversationId = conversationId
                )
                notificationPublisher?.enqueue(
                    NotificationEventDraft(
                        recipientUserId = partnerUserId,
                        actorUserId = userId,
                        kind = NotificationKind.PARTNER_CHAT_INVITE,
                        subjectId = conversationId,
                        idempotencyKey = "partner_chat_invite:$conversationId:$partnerUserId",
                        payload = mapOf(
                            "target" to "activity",
                            "activityTab" to "invites",
                            "activityEventType" to "partner_chat_invite",
                            "conversationId" to conversationId.toString()
                        )
                    )
                )
                result.conversation.toDto()
            }
            PartnerInviteResult.ConversationNotFound -> throw MessagingException("CONVERSATION_NOT_FOUND")
            PartnerInviteResult.AlreadyInConversation -> throw MessagingException("PARTNER_ALREADY_IN_CONVERSATION")
            PartnerInviteResult.AlreadyInvited -> throw MessagingException("PARTNER_INVITE_ALREADY_SENT")
        }
    }

    suspend fun acceptPartnerInvite(userId: UUID, conversationIdText: String): ConversationDetailDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val detail = repository.getConversationDetail(userId, conversationId, limit = 1)
            ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        val viewerParticipant = detail.conversation.participants.firstOrNull { it.userId == userId }
        if (viewerParticipant?.status != ConversationParticipantStatus.INVITED) {
            throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        }
        if (detail.conversation.hasBlockedOtherParticipant(userId)) {
            throw MessagingException("MESSAGING_NOT_ALLOWED")
        }

        return repository.acceptPartnerInvite(userId, conversationId)?.toDto()
            ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
    }

    suspend fun declinePartnerInvite(userId: UUID, conversationIdText: String): ConversationPartnerInviteResponseDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        if (!repository.declinePartnerInvite(userId, conversationId)) {
            throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        }
        return ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED)
    }

    suspend fun leaveConversation(userId: UUID, conversationIdText: String): ConversationPartnerInviteResponseDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        if (!repository.leaveConversation(userId, conversationId)) {
            throw MessagingException("CONVERSATION_NOT_FOUND")
        }
        return ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.LEFT)
    }

    private suspend fun ConversationRecord.hasBlockedOtherParticipant(viewerUserId: UUID): Boolean {
        return hasBlockedParticipantFor(viewerUserId)
    }

    private suspend fun ConversationRecord.hasBlockedParticipantFor(userId: UUID): Boolean {
        val repository = blockRepository ?: return false
        return participants.any { participant ->
            participant.userId != userId &&
                repository.isBlockedEitherWay(userId, participant.userId)
        }
    }

    private fun ConversationDetailRecord.toDto(): ConversationDetailDto = ConversationDetailDto(
        conversation = conversation.toDto(),
        messages = messages.map { it.toDto() },
        nextCursor = nextCursor?.toToken()
    )

    private fun ConversationRecord.toDto(): ConversationDto = ConversationDto(
        conversationId = conversationId.toString(),
        kind = kind,
        origin = origin,
        participants = participants.map { it.toDto() },
        lastMessage = lastMessage?.toDto(),
        unreadCount = unreadCount,
        updatedAt = updatedAt.toString()
    )

    private fun ConversationParticipantRecord.toDto(): ConversationParticipantDto = ConversationParticipantDto(
        userId = userId.toString(),
        displayName = displayName,
        online = online,
        status = status,
        invitedByUserId = invitedByUserId?.toString()
    )

    private fun MessageRecord.toDto(): MessageDto = MessageDto(
        messageId = messageId.toString(),
        conversationId = conversationId.toString(),
        senderUserId = senderUserId.toString(),
        body = body,
        createdAt = createdAt.toString(),
        status = status,
        deliveryStatus = deliveryStatus
    )

    private fun String.toUuidOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw MessagingException("INVALID_ID") }

    private fun String?.toMessageLimitOrThrow(): Int {
        val value = this?.takeIf { it.isNotBlank() } ?: return MESSAGE_PAGE_LIMIT
        return value.toIntOrNull()?.takeIf { it in 1..MAX_MESSAGE_PAGE_LIMIT }
            ?: throw MessagingException("INVALID_MESSAGE_PAGE_LIMIT")
    }

    private fun String.toMessageCursorOrThrow(): MessagePageCursor =
        runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8)
            val separator = decoded.indexOf('|')
            if (separator <= 0 || separator == decoded.lastIndex) throw IllegalArgumentException("Invalid cursor")
            MessagePageCursor(
                createdAt = Instant.parse(decoded.substring(0, separator)),
                messageId = UUID.fromString(decoded.substring(separator + 1))
            )
        }.getOrElse { throw MessagingException("INVALID_MESSAGE_CURSOR") }

    private fun MessagePageCursor.toToken(): String {
        val raw = "$createdAt|$messageId"
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MessagingService::class.java)
        private const val MESSAGE_PAGE_LIMIT = 50
        private const val MAX_MESSAGE_PAGE_LIMIT = 100
        private const val THREAD_LIMIT = 50
        private val ORIGINS = setOf("grid", "swipe", "intro_note")
    }
}

class MessagingException(val code: String) : RuntimeException(code)
