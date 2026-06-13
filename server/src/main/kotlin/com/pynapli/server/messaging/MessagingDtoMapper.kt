package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.PartnerChatInviteDto
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

internal class MessagingDtoMapper(
    private val featureAccess: MessagingFeatureAccess,
) {
    suspend fun toDto(
        detail: ConversationDetailRecord,
        viewerUserId: UUID,
    ): ConversationDetailDto {
        val readReceiptCache = mutableMapOf<UUID, Boolean>()
        return ConversationDetailDto(
            conversation = toDto(detail.conversation, viewerUserId, readReceiptCache),
            messages =
                detail.messages.map { message ->
                    toDto(
                        record = message,
                        viewerUserId = viewerUserId,
                        conversation = detail.conversation,
                        readReceiptCache = readReceiptCache,
                    )
                },
            nextCursor = detail.nextCursor?.toToken(),
        )
    }

    suspend fun toDto(
        record: ConversationRecord,
        viewerUserId: UUID,
        readReceiptCache: MutableMap<UUID, Boolean> = mutableMapOf(),
    ): ConversationDto =
        ConversationDto(
            conversationId = record.conversationId.toString(),
            kind = record.kind,
            origin = record.origin,
            participants = record.participants.map { it.toDto() },
            lastMessage =
                record.lastMessage?.let { message ->
                    toDto(
                        record = message,
                        viewerUserId = viewerUserId,
                        conversation = record,
                        readReceiptCache = readReceiptCache,
                    )
                },
            unreadCount = record.unreadCount,
            updatedAt = record.updatedAt.toString(),
            partnerInvite = record.partnerInvite?.toDto(),
            messageExpirySeconds = record.viewerExpirySeconds,
            effectiveMessageExpirySeconds = record.effectiveExpirySeconds,
        )

    suspend fun toDto(
        record: MessageRecord,
        viewerUserId: UUID,
        conversation: ConversationRecord?,
        readReceiptCache: MutableMap<UUID, Boolean> = mutableMapOf(),
    ): MessageDto =
        MessageDto(
            messageId = record.messageId.toString(),
            conversationId = record.conversationId.toString(),
            senderUserId = record.senderUserId.toString(),
            body = record.body,
            createdAt = record.createdAt.toString(),
            status = record.status,
            deliveryStatus =
                record.deliveryStatus.visibleDeliveryStatus(
                    viewerUserId = viewerUserId,
                    senderUserId = record.senderUserId,
                    conversation = conversation,
                    readReceiptCache = readReceiptCache,
                ),
            deletedAt = record.deletedAt?.toString(),
            messageType = record.messageType,
        )

    suspend fun toDto(
        record: MessageRecord,
        viewerUserId: UUID,
    ): MessageDto = toDto(record = record, viewerUserId = viewerUserId, conversation = null)

    private fun ConversationParticipantRecord.toDto(): ConversationParticipantDto =
        ConversationParticipantDto(
            userId = userId.toString(),
            displayName = displayName,
            online = online,
            status = status,
            invitedByUserId = invitedByUserId?.toString(),
        )

    private suspend fun String.visibleDeliveryStatus(
        viewerUserId: UUID,
        senderUserId: UUID,
        conversation: ConversationRecord?,
        readReceiptCache: MutableMap<UUID, Boolean>,
    ): String {
        if (this != DELIVERY_READ || senderUserId != viewerUserId) return this
        val activeRecipients =
            conversation
                ?.participants
                .orEmpty()
                .filter { participant ->
                    participant.userId != senderUserId &&
                        participant.status == ConversationParticipantStatus.ACTIVE
                }
        if (activeRecipients.isEmpty()) return DELIVERY_SENT
        if (!featureAccess.readReceiptsAllowed(viewerUserId, readReceiptCache)) return DELIVERY_SENT
        val allRecipientsAllowReceipts =
            activeRecipients.all { participant ->
                featureAccess.readReceiptsAllowed(participant.userId, readReceiptCache)
            }
        return if (allRecipientsAllowReceipts) DELIVERY_READ else DELIVERY_SENT
    }

    private fun PartnerChatInviteRecord.toDto(): PartnerChatInviteDto =
        PartnerChatInviteDto(
            inviteId = inviteId.toString(),
            inviterUserId = inviterUserId.toString(),
            invitedPartnerUserId = invitedPartnerUserId.toString(),
            thirdPartyStatus = thirdPartyStatus,
            partnerStatus = partnerStatus,
            createdAt = createdAt.toString(),
        )

    private fun MessagePageCursor.toToken(): String {
        val raw = "$createdAt|$messageId"
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        private const val DELIVERY_READ = "read"
        private const val DELIVERY_SENT = "sent"
    }
}
