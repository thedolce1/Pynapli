package com.pynapli.server.messaging

import com.pynapli.server.safety.BlockRepository
import java.util.UUID

internal class MessagingConversationGuard(
    private val blockRepository: BlockRepository?,
) {
    suspend fun filterBlockedConversations(
        viewerUserId: UUID,
        conversations: List<ConversationRecord>,
    ): List<ConversationRecord> {
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
        if (blockedRelatedIds.isEmpty()) return conversations

        return conversations.filterNot { conversation ->
            conversation.participants.any { participant ->
                participant.userId != viewerUserId && participant.userId in blockedRelatedIds
            }
        }
    }

    suspend fun requireNoDirectBlock(
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) {
            throw MessagingException("MESSAGING_NOT_ALLOWED")
        }
    }

    suspend fun requireVisibleToViewer(
        conversation: ConversationRecord,
        viewerUserId: UUID,
        failureCode: String = "CONVERSATION_NOT_FOUND",
    ) {
        if (conversation.hasBlockedParticipantFor(viewerUserId)) {
            throw MessagingException(failureCode)
        }
    }

    suspend fun requireMessagingAllowed(
        conversation: ConversationRecord,
        actorUserId: UUID,
        failureCode: String = "MESSAGING_NOT_ALLOWED",
    ) {
        if (conversation.hasBlockedParticipantFor(actorUserId)) {
            throw MessagingException(failureCode)
        }
    }

    suspend fun ConversationRecord.hasBlockedParticipantFor(userId: UUID): Boolean {
        val repository = blockRepository ?: return false
        return participants.any { participant ->
            participant.userId != userId &&
                repository.isBlockedEitherWay(userId, participant.userId)
        }
    }
}
