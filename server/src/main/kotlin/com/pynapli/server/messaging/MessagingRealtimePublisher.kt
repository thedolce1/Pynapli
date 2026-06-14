package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.MessageDeltaType
import java.time.Clock
import java.util.UUID

internal class MessagingRealtimePublisher(
    private val broadcaster: MessageBroadcaster?,
    private val clock: Clock,
) {
    suspend fun publishDelta(
        type: String,
        conversationId: UUID,
        participants: List<ConversationParticipantRecord>,
        messageId: UUID? = null,
        statuses: Set<String>,
    ) {
        val activeBroadcaster = broadcaster ?: return
        participants
            .asSequence()
            .filter { participant -> participant.status in statuses }
            .distinctBy { participant -> participant.userId }
            .forEach { participant ->
                activeBroadcaster.emit(
                    MessageRealtimeDelta(
                        recipientUserId = participant.userId,
                        type = type,
                        conversationId = conversationId,
                        messageId = messageId,
                    ),
                )
            }
    }

    suspend fun publishTyping(
        conversationId: UUID,
        actor: ConversationParticipantRecord,
        recipientIds: List<UUID>,
        isTyping: Boolean,
    ) {
        val activeBroadcaster = broadcaster ?: return
        if (recipientIds.isEmpty()) return

        val occurredAt = clock.instant()
        val type = if (isTyping) MessageDeltaType.TYPING_STARTED else MessageDeltaType.TYPING_STOPPED
        val expiresAt =
            if (isTyping) {
                occurredAt.plusSeconds(MessagingServiceDefaults.TypingIndicatorTtlSeconds)
            } else {
                null
            }

        recipientIds.forEach { recipientId ->
            activeBroadcaster.emit(
                MessageRealtimeDelta(
                    recipientUserId = recipientId,
                    type = type,
                    conversationId = conversationId,
                    occurredAt = occurredAt,
                    actorUserId = actor.userId,
                    actorDisplayName = actor.displayName,
                    expiresAt = expiresAt,
                ),
            )
        }
    }
}
