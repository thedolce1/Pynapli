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
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.MESSAGE_IDEMPOTENCY_KEY_MAX_LENGTH
import com.pynapli.shared.network.dto.MessageDeltaType
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

class MessagingService(
    private val repository: MessagingRepository,
    private val blockRepository: BlockRepository? = null,
    private val notificationPublisher: NotificationPublisher? = null,
    private val messageBroadcaster: MessageBroadcaster? = null,
    private val rateLimitService: RateLimitService? = null,
    private val messageSafetyPolicy: MessageSafetyPolicy = MessageSafetyPolicy(),
    private val messageReviewRiskSignalPublisher: MessageReviewRiskSignalPublisher? = null,
    private val riskPolicyService: RiskPolicyService? = null,
    private val subscriptionService: SubscriptionService? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val activityService: ActivityService? = null,
) {
    private val featureAccess = MessagingFeatureAccess(subscriptionService)
    private val dtoMapper = MessagingDtoMapper(featureAccess)

    suspend fun listConversations(userId: UUID): ConversationListResponseDto {
        val conversations = repository.listConversations(userId, THREAD_LIMIT)
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(userId).orEmpty()
        val visible =
            if (blockedRelatedIds.isEmpty()) {
                conversations
            } else {
                conversations.filterNot { conversation ->
                    conversation.participants.any { participant ->
                        participant.userId != userId && participant.userId in blockedRelatedIds
                    }
                }
            }
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

        val conversation =
            repository.getOrCreateDirectConversation(
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                origin = origin,
            ) ?: throw MessagingException("MESSAGING_NOT_ALLOWED")

        val detail = repository.getConversationDetail(viewerUserId, conversation.conversationId, MESSAGE_PAGE_LIMIT)
        return detail?.let { dtoMapper.toDto(it, viewerUserId) }
            ?: ConversationDetailDto(conversation = dtoMapper.toDto(conversation, viewerUserId), messages = emptyList())
    }

    suspend fun getConversationDetail(
        userId: UUID,
        conversationIdText: String,
        beforeCursorText: String? = null,
        limitText: String? = null,
    ): ConversationDetailDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val before = beforeCursorText?.takeIf { it.isNotBlank() }?.toMessageCursorOrThrow()
        val limit = limitText.toMessageLimitOrThrow()
        val detail =
            repository.getConversationDetail(userId, conversationId, limit, before)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        if (detail.conversation.hasBlockedOtherParticipant(userId)) {
            throw MessagingException("CONVERSATION_NOT_FOUND")
        }
        return dtoMapper.toDto(detail, userId)
    }

    suspend fun markConversationRead(
        userId: UUID,
        conversationIdText: String,
    ): ConversationDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val conversation =
            repository.markConversationRead(userId, conversationId)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        if (featureAccess.readReceiptsAllowed(userId)) {
            publishMessageDelta(
                type = MessageDeltaType.MESSAGE_READ,
                conversationId = conversationId,
                participants = conversation.participants,
                statuses = activeParticipantStatuses,
            )
        }
        return dtoMapper.toDto(conversation, userId)
    }

    suspend fun sendMessage(
        userId: UUID,
        conversationIdText: String,
        request: SendMessageRequestDto,
    ): MessageDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val body = request.body.trim()
        if (body.isEmpty()) throw MessagingException("MESSAGE_BODY_EMPTY")
        if (body.length > MESSAGE_BODY_MAX_LENGTH) throw MessagingException("MESSAGE_BODY_TOO_LONG")
        val clientIdempotencyKey = request.clientIdempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        if (clientIdempotencyKey != null && clientIdempotencyKey.length > MESSAGE_IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw MessagingException("INVALID_IDEMPOTENCY_KEY")
        }
        if (riskPolicyService?.isSocialActionRestricted(userId) == true) {
            throw MessagingException("RISK_RESTRICTED")
        }

        if (blockRepository != null) {
            val detail =
                repository.getConversationDetail(userId, conversationId, limit = 1)
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
        val safetyDecision =
            if (isFirstMessage) {
                val recentDuplicateCount =
                    repository.countRecentMessagesByFingerprint(
                        userId = userId,
                        bodyFingerprint = bodyFingerprint,
                        since = clock.instant().minusSeconds(MessageSafetyPolicy.DUPLICATE_LOOKBACK_SECONDS),
                    )
                messageSafetyPolicy.evaluateFirstMessage(
                    body = body,
                    recentDuplicateCount = recentDuplicateCount,
                    bodyFingerprint = bodyFingerprint,
                )
            } else {
                MessageSafetyDecision.visible(bodyFingerprint)
            }

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
        val conversation =
            repository
                .getConversationDetail(userId, conversationId, limit = 1)
                ?.conversation
        val message =
            dtoMapper.toDto(
                record = messageRecord,
                viewerUserId = userId,
                conversation = conversation,
                readReceiptCache = mutableMapOf<UUID, Boolean>(),
            )

        if (message.status == MessageModerationStatus.HELD) {
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
            return message
        }

        val participants = conversation?.participants.orEmpty()
        publishMessageDelta(
            type = MessageDeltaType.MESSAGE_CREATED,
            conversationId = conversationId,
            participants = participants,
            messageId = UUID.fromString(message.messageId),
            statuses = activeParticipantStatuses,
        )
        participants
            .filter { participant ->
                participant.userId != userId && participant.status == ConversationParticipantStatus.ACTIVE
            }.forEach { participant ->
                notificationPublisher?.enqueue(
                    NotificationEventDraft(
                        recipientUserId = participant.userId,
                        actorUserId = userId,
                        kind = NotificationKind.MESSAGE,
                        subjectId = conversationId,
                        idempotencyKey = "message:${message.messageId}:${participant.userId}",
                        payload =
                            mapOf(
                                "target" to "messages",
                                "conversationId" to conversationId.toString(),
                                "messageId" to message.messageId,
                            ),
                    ),
                )
            }

        return message
    }

    suspend fun deleteOwnMessage(
        userId: UUID,
        messageIdText: String,
    ): MessageDto {
        val messageId = messageIdText.toUuidOrThrow()
        val deleted =
            repository.deleteOwnMessage(userId, messageId)
                ?: throw MessagingException("MESSAGE_NOT_FOUND")
        val participants =
            repository
                .getConversationDetail(userId, deleted.conversationId, limit = 1)
                ?.conversation
                ?.participants
                .orEmpty()
        publishMessageDelta(
            type = MessageDeltaType.MESSAGE_DELETED,
            conversationId = deleted.conversationId,
            participants = participants,
            messageId = deleted.messageId,
            statuses = activeParticipantStatuses,
        )
        return dtoMapper.toDto(deleted, userId)
    }

    suspend fun inviteLinkedPartnerToConversation(
        userId: UUID,
        conversationIdText: String,
    ): ConversationDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        if (riskPolicyService?.isSocialActionRestricted(userId) == true) {
            throw MessagingException("RISK_RESTRICTED")
        }
        val partnerUserId =
            repository.getActiveLinkedPartnerUserId(userId)
                ?: throw MessagingException("LINKED_PARTNER_NOT_FOUND")

        val detail =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        if (detail.conversation.hasBlockedOtherParticipant(userId)) {
            throw MessagingException("CONVERSATION_NOT_FOUND")
        }
        if (detail.conversation.hasBlockedParticipantFor(partnerUserId)) {
            throw MessagingException("MESSAGING_NOT_ALLOWED")
        }

        return when (
            val result =
                repository.inviteLinkedPartner(
                    inviterUserId = userId,
                    conversationId = conversationId,
                    partnerUserId = partnerUserId,
                )
        ) {
            is PartnerInviteResult.Invited -> {
                publishMessageDelta(
                    type = MessageDeltaType.CONVERSATION_UPDATED,
                    conversationId = conversationId,
                    participants = result.conversation.participants,
                    statuses = visibleParticipantStatuses,
                )
                activityService?.recordPartnerChatInvite(
                    inviterUserId = userId,
                    inviteeUserId = partnerUserId,
                    conversationId = conversationId,
                )
                notificationPublisher?.enqueue(
                    NotificationEventDraft(
                        recipientUserId = partnerUserId,
                        actorUserId = userId,
                        kind = NotificationKind.PARTNER_CHAT_INVITE,
                        subjectId = conversationId,
                        idempotencyKey = "partner_chat_invite:$conversationId:$partnerUserId",
                        payload =
                            mapOf(
                                "target" to "activity",
                                "activityTab" to "invites",
                                "activityEventType" to "partner_chat_invite",
                                "conversationId" to conversationId.toString(),
                            ),
                    ),
                )
                dtoMapper.toDto(result.conversation, userId)
            }
            PartnerInviteResult.ConversationNotFound -> throw MessagingException("CONVERSATION_NOT_FOUND")
            PartnerInviteResult.AlreadyInConversation -> throw MessagingException("PARTNER_ALREADY_IN_CONVERSATION")
            PartnerInviteResult.AlreadyInvited -> throw MessagingException("PARTNER_INVITE_ALREADY_SENT")
            PartnerInviteResult.CooldownActive -> throw MessagingException("PARTNER_INVITE_COOLDOWN")
        }
    }

    suspend fun acceptPartnerInvite(
        userId: UUID,
        conversationIdText: String,
    ): ConversationDetailDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val detail =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        val invite = detail.conversation.partnerInvite ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        val viewerParticipant = detail.conversation.participants.firstOrNull { it.userId == userId }
        val viewerIsInvitedPartner = invite.invitedPartnerUserId == userId
        val viewerCanApproveAsThirdParty =
            viewerParticipant?.status == ConversationParticipantStatus.ACTIVE &&
                userId != invite.inviterUserId &&
                userId != invite.invitedPartnerUserId
        if (!viewerIsInvitedPartner && !viewerCanApproveAsThirdParty) {
            throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        }
        if (detail.conversation.hasBlockedOtherParticipant(userId)) {
            throw MessagingException("MESSAGING_NOT_ALLOWED")
        }

        val accepted =
            repository.acceptPartnerInvite(userId, conversationId)
                ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        publishMessageDelta(
            type = MessageDeltaType.CONVERSATION_UPDATED,
            conversationId = conversationId,
            participants = accepted.conversation.participants,
            statuses = visibleParticipantStatuses,
        )
        return dtoMapper.toDto(accepted, userId)
    }

    suspend fun declinePartnerInvite(
        userId: UUID,
        conversationIdText: String,
    ): ConversationPartnerInviteResponseDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val detailBeforeDecline =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        if (!repository.declinePartnerInvite(userId, conversationId)) {
            throw MessagingException("PARTNER_INVITE_NOT_FOUND")
        }
        publishMessageDelta(
            type = MessageDeltaType.CONVERSATION_UPDATED,
            conversationId = conversationId,
            participants = detailBeforeDecline.conversation.participants,
            statuses = visibleParticipantStatuses,
        )
        return ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED)
    }

    suspend fun leaveConversation(
        userId: UUID,
        conversationIdText: String,
    ): ConversationPartnerInviteResponseDto {
        val conversationId = conversationIdText.toUuidOrThrow()
        val detailBeforeLeave =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
        if (!repository.leaveConversation(userId, conversationId)) {
            throw MessagingException("CONVERSATION_NOT_FOUND")
        }
        publishMessageDelta(
            type = MessageDeltaType.CONVERSATION_UPDATED,
            conversationId = conversationId,
            participants = detailBeforeLeave.conversation.participants,
            statuses = visibleParticipantStatuses,
        )
        return ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.LEFT)
    }

    suspend fun sendTypingIndicator(
        userId: UUID,
        conversationIdText: String,
        request: SendTypingIndicatorRequestDto,
    ) {
        if (!featureAccess.typingIndicatorsAllowed(userId)) return
        val conversationId = conversationIdText.toUuidOrThrow()
        val detail =
            repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: return
        if (detail.conversation.hasBlockedOtherParticipant(userId)) return

        val actor =
            detail.conversation.participants.firstOrNull { participant ->
                participant.userId == userId && participant.status == ConversationParticipantStatus.ACTIVE
            } ?: return
        val broadcaster = messageBroadcaster ?: return
        val recipientIds =
            detail.conversation.participants
                .filter { participant ->
                    participant.userId != userId &&
                        participant.status == ConversationParticipantStatus.ACTIVE &&
                        featureAccess.typingIndicatorsAllowed(participant.userId)
                }.map { it.userId }
                .distinct()
        if (recipientIds.isEmpty()) return

        val occurredAt = clock.instant()
        val type = if (request.isTyping) MessageDeltaType.TYPING_STARTED else MessageDeltaType.TYPING_STOPPED
        val expiresAt = if (request.isTyping) occurredAt.plusSeconds(TYPING_INDICATOR_TTL_SECONDS) else null
        recipientIds.forEach { recipientId ->
            broadcaster.emit(
                MessageRealtimeDelta(
                    recipientUserId = recipientId,
                    type = type,
                    conversationId = conversationId,
                    occurredAt = occurredAt,
                    actorUserId = userId,
                    actorDisplayName = actor.displayName,
                    expiresAt = expiresAt,
                ),
            )
        }
    }

    private suspend fun publishMessageDelta(
        type: String,
        conversationId: UUID,
        participants: List<ConversationParticipantRecord>,
        messageId: UUID? = null,
        statuses: Set<String>,
    ) {
        val broadcaster = messageBroadcaster ?: return
        participants
            .asSequence()
            .filter { participant -> participant.status in statuses }
            .distinctBy { participant -> participant.userId }
            .forEach { participant ->
                broadcaster.emit(
                    MessageRealtimeDelta(
                        recipientUserId = participant.userId,
                        type = type,
                        conversationId = conversationId,
                        messageId = messageId,
                    ),
                )
            }
    }

    private suspend fun ConversationRecord.hasBlockedOtherParticipant(viewerUserId: UUID): Boolean = hasBlockedParticipantFor(viewerUserId)

    private suspend fun ConversationRecord.hasBlockedParticipantFor(userId: UUID): Boolean {
        val repository = blockRepository ?: return false
        return participants.any { participant ->
            participant.userId != userId &&
                repository.isBlockedEitherWay(userId, participant.userId)
        }
    }

    private fun String.toUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw MessagingException("INVALID_ID") }

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
                messageId = UUID.fromString(decoded.substring(separator + 1)),
            )
        }.getOrElse { throw MessagingException("INVALID_MESSAGE_CURSOR") }

    companion object {
        private val logger = LoggerFactory.getLogger(MessagingService::class.java)
        private const val MESSAGE_PAGE_LIMIT = 50
        private const val MAX_MESSAGE_PAGE_LIMIT = 100
        private const val THREAD_LIMIT = 50
        private const val TYPING_INDICATOR_TTL_SECONDS = 6L
        private val ORIGINS = setOf("grid", "swipe", "intro_note")
        private val activeParticipantStatuses = setOf(ConversationParticipantStatus.ACTIVE)
        private val visibleParticipantStatuses =
            setOf(
                ConversationParticipantStatus.ACTIVE,
                ConversationParticipantStatus.INVITED,
            )
    }
}

class MessagingException(
    val code: String,
) : RuntimeException(code)
