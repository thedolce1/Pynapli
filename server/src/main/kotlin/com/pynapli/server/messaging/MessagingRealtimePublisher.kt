package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.MessageDeltaType
import java.time.Clock
import java.util.UUID

internal class MessagingRealtimePublisher(
    private val broadcaster: MessageBroadcaster?,
    private val clock: Clock,
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    /**
     * [actorUserId] is whoever caused the change: no hint goes to a participant the actor hides from
     * ("Hide from people you know"), since that participant is never shown the change.
     */
    suspend fun publishDelta(
        type: String,
        conversationId: UUID,
        participants: List<ConversationParticipantRecord>,
        messageId: UUID? = null,
        statuses: Set<String>,
        actorUserId: UUID? = null,
        // People the change is about, besides the actor: no hint to a recipient any of them hides from.
        subjectUserIds: Collection<UUID> = emptyList(),
    ) {
        val activeBroadcaster = broadcaster ?: return
        val causes = (listOfNotNull(actorUserId) + subjectUserIds).distinct()
        val recipients =
            participants
                .filter { participant -> participant.status in statuses }
                .distinctBy { participant -> participant.userId }
                .filterNot { participant ->
                    causes.any { cause -> cause != participant.userId && actorHidesFrom(cause, participant.userId) }
                }
        recipients
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

        recipientIds.filterNot { actorHidesFrom(actor.userId, it) }.forEach { recipientId ->
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

    private suspend fun actorHidesFrom(
        actorUserId: UUID,
        recipientUserId: UUID,
    ): Boolean = contactBlockVisibility?.isHiddenFrom(viewerUserId = recipientUserId, otherUserId = actorUserId) == true
}
