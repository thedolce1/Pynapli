package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

const val MESSAGE_BODY_MAX_LENGTH = 2_000

object ConversationParticipantStatus {
    const val ACTIVE = "active"
    const val INVITED = "invited"
    const val DECLINED = "declined"
    const val LEFT = "left"
}

object PartnerChatInviteStatus {
    const val PENDING = "pending"
    const val ACCEPTED = "accepted"
    const val DECLINED = "declined"
}

object MessageDeltaType {
    const val MESSAGE_CREATED = "message_created"
    const val MESSAGE_DELETED = "message_deleted"
    const val MESSAGE_READ = "message_read"
    const val CONVERSATION_UPDATED = "conversation_updated"
    const val TYPING_STARTED = "typing_started"
    const val TYPING_STOPPED = "typing_stopped"
}

@Serializable
data class CreateDirectConversationRequestDto(
    val targetUserId: String,
    val origin: String = "grid",
)

@Serializable
data class ConversationDto(
    val conversationId: String,
    val kind: String,
    val origin: String,
    val participants: List<ConversationParticipantDto>,
    val lastMessage: MessageDto? = null,
    val unreadCount: Int,
    val updatedAt: String,
    val partnerInvite: PartnerChatInviteDto? = null,
)

@Serializable
data class ConversationParticipantDto(
    val userId: String,
    val displayName: String,
    val online: Boolean = false,
    val status: String = ConversationParticipantStatus.ACTIVE,
    val invitedByUserId: String? = null,
)

@Serializable
data class ConversationDetailDto(
    val conversation: ConversationDto,
    val messages: List<MessageDto>,
    val nextCursor: String? = null,
)

@Serializable
data class ConversationListResponseDto(
    val items: List<ConversationDto>,
    val nextCursor: String? = null,
)

@Serializable
data class MessageDto(
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val body: String,
    val createdAt: String,
    val status: String,
    val deliveryStatus: String,
    val deletedAt: String? = null,
)

@Serializable
data class PartnerChatInviteDto(
    val inviteId: String,
    val inviterUserId: String,
    val invitedPartnerUserId: String,
    val thirdPartyStatus: String,
    val partnerStatus: String,
    val createdAt: String,
)

@Serializable
data class SendMessageRequestDto(
    val body: String,
)

@Serializable
data class SendTypingIndicatorRequestDto(
    val isTyping: Boolean,
)

@Serializable
data class ConversationPartnerInviteResponseDto(
    val status: String,
    val conversation: ConversationDto? = null,
)

@Serializable
data class MessageDeltaDto(
    val type: String,
    val conversationId: String,
    val occurredAt: String,
    val messageId: String? = null,
    val actorUserId: String? = null,
    val actorDisplayName: String? = null,
    val expiresAt: String? = null,
)
