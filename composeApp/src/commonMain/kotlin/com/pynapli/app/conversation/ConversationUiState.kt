package com.pynapli.app.conversation

import androidx.compose.runtime.Immutable
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.PartnerChatInviteStatus

@Immutable
data class TypingParticipantUi(
    val userId: String,
    val displayName: String,
    val expiresAtMillis: Long,
)

@Immutable
data class ConversationUiState(
    val selectedCard: GridProfileCardUi? = null,
    val titleOverride: String? = null,
    val viewerUserId: String? = null,
    val detail: ConversationDetailDto? = null,
    val draft: String = "",
    val isOpening: Boolean = false,
    val isSending: Boolean = false,
    val isInvitingPartner: Boolean = false,
    val isRespondingToInvite: Boolean = false,
    val isLeaving: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isDeletingMessageIds: Set<String> = emptySet(),
    val typingParticipants: List<TypingParticipantUi> = emptyList(),
    val partnerInviteFeedback: String? = null,
    val errorMessage: String? = null,
) {
    val visible: Boolean
        get() = selectedCard != null || titleOverride != null || detail != null || isOpening

    val draftLength: Int
        get() = draft.length

    val draftRemaining: Int
        get() = MESSAGE_BODY_MAX_LENGTH - draftLength

    val draftOverLimit: Boolean
        get() = draftLength > MESSAGE_BODY_MAX_LENGTH

    val viewerParticipantStatus: String?
        get() =
            viewerUserId?.let { userId ->
                detail
                    ?.conversation
                    ?.participants
                    ?.firstOrNull { participant -> participant.userId == userId }
                    ?.status
            }

    val isViewerInvited: Boolean
        get() = viewerParticipantStatus == ConversationParticipantStatus.INVITED

    val canRespondToPartnerInvite: Boolean
        get() {
            val invite = detail?.conversation?.partnerInvite ?: return false
            val viewer = viewerUserId ?: return false
            if (viewer == invite.invitedPartnerUserId &&
                invite.partnerStatus == PartnerChatInviteStatus.PENDING
            ) {
                return true
            }
            return viewerParticipantStatus == ConversationParticipantStatus.ACTIVE &&
                viewer != invite.inviterUserId &&
                viewer != invite.invitedPartnerUserId &&
                invite.thirdPartyStatus == PartnerChatInviteStatus.PENDING
        }

    val canSend: Boolean
        get() =
            detail != null &&
                !isViewerInvited &&
                draft.trim().isNotEmpty() &&
                !draftOverLimit &&
                !isOpening &&
                !isSending &&
                !isRespondingToInvite &&
                !isLeaving &&
                isDeletingMessageIds.isEmpty()
}
