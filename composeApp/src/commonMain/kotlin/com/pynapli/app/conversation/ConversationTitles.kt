package com.pynapli.app.conversation

import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus

internal fun ConversationDto.titleFor(viewerUserId: String?): String =
    participants
        .filter { participant -> participant.status in visibleConversationParticipantStatuses }
        .filterNot { participant -> viewerUserId != null && participant.userId == viewerUserId }
        .joinToString(", ") { participant -> participant.displayName }
        .ifBlank { participants.firstOrNull()?.displayName ?: "Conversation" }

internal val visibleConversationParticipantStatuses =
    setOf(
        ConversationParticipantStatus.ACTIVE,
        ConversationParticipantStatus.INVITED,
    )

internal fun ConversationUiState.partnerInviteAcceptedFeedback(detail: ConversationDetailDto): String =
    when {
        isViewerInvited && detail.conversation.partnerInvite == null -> "Joined chat"
        isViewerInvited -> "Invite accepted"
        else -> "Invite approved"
    }
