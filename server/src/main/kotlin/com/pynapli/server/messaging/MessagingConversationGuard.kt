package com.pynapli.server.messaging

import com.pynapli.server.contactblocks.ContactBlockVisibility
import com.pynapli.server.safety.BlockRepository
import java.util.UUID

internal class MessagingConversationGuard(
    private val blockRepository: BlockRepository?,
    // "Hide from people you know" (viewer side only): see [isHiddenConversationFor].
    private val contactBlockVisibility: ContactBlockVisibility? = null,
) {
    suspend fun filterBlockedConversations(
        viewerUserId: UUID,
        conversations: List<ConversationRecord>,
    ): List<ConversationRecord> {
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
        val hiddenFromViewer = hiddenFrom(viewerUserId)
        if (blockedRelatedIds.isEmpty() && hiddenFromViewer.isEmpty()) return conversations

        return conversations.filterNot { conversation ->
            conversation.participants.any { participant ->
                participant.userId != viewerUserId && participant.userId in blockedRelatedIds
            } ||
                conversation.isHiddenConversationFor(viewerUserId, hiddenFromViewer)
        }
    }

    suspend fun requireNoDirectBlock(
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) {
            throw MessagingException("MESSAGING_NOT_ALLOWED")
        }
        // Someone hiding from the viewer can't be messaged by them (the same refusal as a block or a
        // missing account), and a conversation they opened is never handed to the viewer this way.
        if (contactBlockVisibility?.isHiddenFrom(viewerUserId, targetUserId) == true) {
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
        if (conversation.isHiddenConversationFor(viewerUserId, hiddenFrom(viewerUserId))) {
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
        // The viewer can't act in a conversation that doesn't exist for them.
        if (conversation.isHiddenConversationFor(actorUserId, hiddenFrom(actorUserId))) {
            throw MessagingException("CONVERSATION_NOT_FOUND")
        }
    }

    /**
     * For calls that otherwise write before loading anything (read mark, timer, leave): a viewer can't
     * touch a conversation that is hidden from them ([isHiddenConversationFor]), so nothing they do
     * there can reach the person hiding from them (a read mark would show as "read"). Does nothing
     * when the feature isn't wired.
     */
    suspend fun requireNotHiddenConversation(
        repository: MessagingRepository,
        viewerUserId: UUID,
        conversationId: UUID,
        failureCode: String = "CONVERSATION_NOT_FOUND",
    ) {
        if (contactBlockVisibility == null) return
        val hidden = hiddenFrom(viewerUserId)
        if (hidden.isEmpty()) return
        val conversation = repository.getConversationDetail(viewerUserId, conversationId, limit = 1)?.conversation ?: return
        if (conversation.isHiddenConversationFor(viewerUserId, hidden)) throw MessagingException(failureCode)
    }

    suspend fun ConversationRecord.hasBlockedParticipantFor(userId: UUID): Boolean {
        val repository = blockRepository ?: return false
        return participants.any { participant ->
            participant.userId != userId &&
                repository.isBlockedEitherWay(userId, participant.userId)
        }
    }

    suspend fun hiddenFrom(viewerUserId: UUID): Set<UUID> = contactBlockVisibility?.hiddenFrom(viewerUserId).orEmpty()
}

/**
 * A conversation that, for [viewerUserId], holds nobody but people hiding from them: the direct
 * conversation someone hiding from the viewer opened with them (it can't become a group: only the
 * viewer could approve a third person). It is never listed for the viewer and every call on it is
 * CONVERSATION_NOT_FOUND, exactly like a conversation that doesn't exist.
 */
internal fun ConversationRecord.isHiddenConversationFor(
    viewerUserId: UUID,
    hiddenFromViewer: Set<UUID>,
): Boolean {
    if (hiddenFromViewer.isEmpty()) return false
    val others = participants.filter { it.userId != viewerUserId }
    return others.isNotEmpty() && others.all { it.userId in hiddenFromViewer }
}

/**
 * What the viewer sees of a conversation that also holds someone hiding from them (a group joined
 * later): those people are left out of the participants and of every partner invite naming them.
 * Their messages are left out by the SQL (MessagingRepository with contact block tokens).
 */
internal fun ConversationRecord.withoutHiddenPeople(hiddenFromViewer: Set<UUID>): ConversationRecord {
    if (hiddenFromViewer.isEmpty()) return this
    val visibleInvites =
        partnerInvites.filterNot { it.inviterUserId in hiddenFromViewer || it.invitedPartnerUserId in hiddenFromViewer }
    return copy(
        participants =
            participants
                .filterNot { it.userId in hiddenFromViewer }
                .map { if (it.invitedByUserId in hiddenFromViewer) it.copy(invitedByUserId = null) else it },
        partnerInvite =
            partnerInvite?.takeUnless { it.inviterUserId in hiddenFromViewer || it.invitedPartnerUserId in hiddenFromViewer }
                ?: visibleInvites.firstOrNull().takeIf { partnerInvite != null },
        partnerInvites = visibleInvites,
    )
}
