package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

const val MESSAGE_BODY_MAX_LENGTH = 2_000

@Serializable
data class CreateDirectConversationRequestDto(
    val targetUserId: String,
    val origin: String = "grid"
)

@Serializable
data class ConversationDto(
    val conversationId: String,
    val kind: String,
    val origin: String,
    val participants: List<ConversationParticipantDto>,
    val lastMessage: MessageDto? = null,
    val unreadCount: Int,
    val updatedAt: String
)

@Serializable
data class ConversationParticipantDto(
    val userId: String,
    val displayName: String,
    val online: Boolean = false
)

@Serializable
data class ConversationDetailDto(
    val conversation: ConversationDto,
    val messages: List<MessageDto>,
    val nextCursor: String? = null
)

@Serializable
data class ConversationListResponseDto(
    val items: List<ConversationDto>,
    val nextCursor: String? = null
)

@Serializable
data class MessageDto(
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val body: String,
    val createdAt: String,
    val status: String,
    val deliveryStatus: String
)

@Serializable
data class SendMessageRequestDto(
    val body: String
)
