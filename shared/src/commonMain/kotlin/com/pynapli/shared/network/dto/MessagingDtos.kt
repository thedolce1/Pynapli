package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

const val MESSAGE_BODY_MAX_LENGTH = 2_000

/** Max length of a client-supplied message idempotency key (matches the DB CHECK constraint). */
const val MESSAGE_IDEMPOTENCY_KEY_MAX_LENGTH = 128

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

object MessageType {
    const val USER = "user"
    const val SYSTEM = "system"
}

/**
 * Allowed conversation expiry timer values in seconds (MESSAGING_SPEC: 1h / 24h / 7d / 30d).
 * null = disappearing messages off. The server enforces the same set.
 */
object MessageExpiryOption {
    const val OneHour = 3_600
    const val OneDay = 86_400
    const val OneWeek = 604_800
    const val ThirtyDays = 2_592_000

    val ALL = listOf(OneHour, OneDay, OneWeek, ThirtyDays)
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
    // The viewer's own disappearing-messages timer (null = off) and the effective timer for the
    // conversation (strictest of all active participants; what messages actually expire on).
    val messageExpirySeconds: Int? = null,
    val effectiveMessageExpirySeconds: Int? = null,
    // Server-decided: this conversation belongs in the viewer's Messages new-matches rail (a match the
    // viewer has not acted on yet; MESSAGING_SPEC "New-matches rail"). Per viewer. Null only from a
    // server that predates the field, where clients fall back to swipe origin + no last message.
    val newMatch: Boolean? = null,
    // Server-decided "Bring your partner" offer for the viewer (MESSAGING_SPEC "Partner Invite Flow"),
    // sent on the conversation-detail read and the read mark only. Null when there is nothing to offer, and from a server
    // that predates it; clients then render the conversation exactly as before.
    val partnerOffers: ConversationPartnerOffersDto? = null,
)

/**
 * A shortcut over the ordinary partner invite, never a separate consent path: [bringPartner] is the
 * viewer's own linked partner, invited with the existing `POST .../partner-invite` (same accepts);
 * [participantPartners] names another participant's linked partner the viewer is already allowed to
 * see, so the viewer can ask that participant. The viewer can never invite someone else's partner.
 */
@Serializable
data class ConversationPartnerOffersDto(
    val bringPartner: ConversationBringPartnerDto? = null,
    val participantPartners: List<ConversationParticipantPartnerDto> = emptyList(),
)

@Serializable
data class ConversationBringPartnerDto(
    val partnerUserId: String,
    val partnerDisplayName: String,
)

@Serializable
data class ConversationParticipantPartnerDto(
    val participantUserId: String,
    val partnerDisplayName: String,
)

@Serializable
data class ConversationParticipantDto(
    val userId: String,
    val displayName: String,
    val online: Boolean = false,
    val status: String = ConversationParticipantStatus.ACTIVE,
    val invitedByUserId: String? = null,
    // The participant's public primary profile photo (same projection as their grid/profile card);
    // null when they have no approved profile photo. Never a private-album asset.
    val photoUrl: String? = null,
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
    // "user" (default) or "system" (e.g. an expiry-timer change notice). Defaulted so older
    // clients keep treating every message as a normal user message.
    val messageType: String = MessageType.USER,
    // Per-recipient media reveal (MESSAGING_SPEC). When a photo is attached, [mediaAssetId] is set;
    // [mediaUrl] is populated only for the sender or a recipient who has revealed it (otherwise the
    // client shows a locked "tap to reveal" placeholder). [mediaRevealed] reflects the viewer.
    val mediaAssetId: String? = null,
    val mediaContentType: String? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val mediaUrl: String? = null,
    val mediaRevealed: Boolean = false,
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
    // Optional client-generated idempotency key (<=128 chars). When set, a retried send with the
    // same key in the same conversation returns the original message instead of creating a
    // duplicate. Lets the client safely retry a send whose response was lost.
    val clientIdempotencyKey: String? = null,
)

@Serializable
data class SendTypingIndicatorRequestDto(
    val isTyping: Boolean,
)

@Serializable
data class SetConversationExpiryRequestDto(
    // Seconds from the allowed set, or null to turn disappearing messages off.
    val expirySeconds: Int? = null,
)

@Serializable
data class CreateMessageMediaUploadRequestDto(
    val contentType: String,
    val byteSize: Long,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
)

@Serializable
data class MessageMediaUploadSlotDto(
    val mediaAssetId: String,
    val uploadUrl: String,
    val uploadMethod: String,
    val uploadHeaders: Map<String, String>,
    val expiresAt: String,
)

@Serializable
data class SendMediaMessageRequestDto(
    val mediaAssetId: String,
    val body: String = "",
    val clientIdempotencyKey: String? = null,
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
