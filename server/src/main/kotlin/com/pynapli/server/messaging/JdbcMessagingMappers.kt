package com.pynapli.server.messaging

import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.toMessageRecord(deliveryStatus: String): MessageRecord =
    MessageRecord(
        messageId = getObject("id", UUID::class.java),
        conversationId = getObject("conversation_id", UUID::class.java),
        senderUserId = getObject("sender_user_id", UUID::class.java),
        body = getString("body"),
        createdAt = getTimestamp("created_at").toInstant(),
        status = getString("moderation_status"),
        deliveryStatus = deliveryStatus,
        deletedAt = getTimestamp("deleted_at")?.toInstant(),
    )

internal fun MessageRecord.toCursor(): MessagePageCursor =
    MessagePageCursor(
        createdAt = createdAt,
        messageId = messageId,
    )

internal fun ResultSet.toPartnerChatInviteRecord(): PartnerChatInviteRecord =
    PartnerChatInviteRecord(
        inviteId = getObject("id", UUID::class.java),
        threadId = getObject("thread_id", UUID::class.java),
        inviterUserId = getObject("inviter_user_id", UUID::class.java),
        invitedPartnerUserId = getObject("invited_partner_user_id", UUID::class.java),
        thirdPartyStatus = getString("third_party_status"),
        partnerStatus = getString("partner_status"),
        createdAt = getTimestamp("created_at").toInstant(),
    )
