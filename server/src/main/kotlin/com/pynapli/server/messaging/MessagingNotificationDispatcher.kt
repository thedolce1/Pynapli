package com.pynapli.server.messaging

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import java.util.UUID

internal class MessagingNotificationDispatcher(
    private val publisher: NotificationPublisher?,
) {
    suspend fun enqueueMessageNotifications(
        actorUserId: UUID,
        conversationId: UUID,
        messageId: String,
        participants: List<ConversationParticipantRecord>,
    ) {
        participants
            .filter { participant ->
                participant.userId != actorUserId && participant.status == ConversationParticipantStatus.ACTIVE
            }.forEach { participant ->
                publisher?.enqueue(
                    NotificationEventDraft(
                        recipientUserId = participant.userId,
                        actorUserId = actorUserId,
                        kind = NotificationKind.MESSAGE,
                        subjectId = conversationId,
                        idempotencyKey = "message:$messageId:${participant.userId}",
                        payload =
                            mapOf(
                                "target" to "messages",
                                "conversationId" to conversationId.toString(),
                                "messageId" to messageId,
                            ),
                    ),
                )
            }
    }

    suspend fun enqueuePartnerChatInvite(
        inviterUserId: UUID,
        inviteeUserId: UUID,
        conversationId: UUID,
    ) {
        publisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = inviteeUserId,
                actorUserId = inviterUserId,
                kind = NotificationKind.PARTNER_CHAT_INVITE,
                subjectId = conversationId,
                idempotencyKey = "partner_chat_invite:$conversationId:$inviteeUserId",
                payload =
                    mapOf(
                        "target" to "activity",
                        "activityTab" to "invites",
                        "activityEventType" to "partner_chat_invite",
                        "conversationId" to conversationId.toString(),
                    ),
            ),
        )
    }
}
