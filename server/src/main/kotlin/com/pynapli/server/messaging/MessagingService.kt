package com.pynapli.server.messaging

import com.pynapli.server.safety.BlockRepository
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

class MessagingService(
    private val repository: MessagingRepository,
    private val blockRepository: BlockRepository? = null
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

        if (blockRepository != null) {
            val detail = repository.getConversationDetail(userId, conversationId, limit = 1)
                ?: throw MessagingException("CONVERSATION_NOT_FOUND")
            if (detail.conversation.hasBlockedOtherParticipant(userId)) {
                throw MessagingException("MESSAGING_NOT_ALLOWED")
            }
        }

        return repository.sendMessage(
            userId = userId,
            conversationId = conversationId,
            body = body
        )?.toDto() ?: throw MessagingException("CONVERSATION_NOT_FOUND")
    }

    private suspend fun ConversationRecord.hasBlockedOtherParticipant(viewerUserId: UUID): Boolean {
        val repository = blockRepository ?: return false
        return participants.any { participant ->
            participant.userId != viewerUserId &&
                repository.isBlockedEitherWay(viewerUserId, participant.userId)
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
        online = online
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
        private const val MESSAGE_PAGE_LIMIT = 50
        private const val MAX_MESSAGE_PAGE_LIMIT = 100
        private const val THREAD_LIMIT = 50
        private val ORIGINS = setOf("grid", "swipe", "intro_note")
    }
}

class MessagingException(val code: String) : RuntimeException(code)
