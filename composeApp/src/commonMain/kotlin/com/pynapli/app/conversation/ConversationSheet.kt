package com.pynapli.app.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliMaxWidthColumn
import com.pynapli.shared.network.dto.ConversationParticipantDto

@Composable
fun ConversationSheet(
    state: ConversationUiState,
    onDismiss: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    linkedPartnerUserId: String? = null,
    onInvitePartner: () -> Unit = {},
    onAcceptInvite: () -> Unit = {},
    onDeclineInvite: () -> Unit = {},
    onLeaveConversation: () -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onSetExpiry: (Int?) -> Unit = {},
    onRevealMedia: (String) -> Unit = {},
    onPickPhoto: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return

    val title = state.conversationTitle()

    PynapliBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConversationHeader(
                title = title,
                onDismiss = onDismiss,
            )

            ConversationContent(
                state = state,
                linkedPartnerUserId = linkedPartnerUserId,
                onDraftChanged = onDraftChanged,
                onSend = onSend,
                onLoadOlder = onLoadOlder,
                onInvitePartner = onInvitePartner,
                onAcceptInvite = onAcceptInvite,
                onDeclineInvite = onDeclineInvite,
                onLeaveConversation = onLeaveConversation,
                onDeleteMessage = onDeleteMessage,
                onSetExpiry = onSetExpiry,
                onRevealMedia = onRevealMedia,
                onPickPhoto = onPickPhoto,
                fillAvailableHeight = false,
                showLeaveAction = true,
                showPartnerStatusFeedback = true,
            )
        }
    }
}

@Composable
fun ConversationScreen(
    state: ConversationUiState,
    onBack: () -> Unit,
    onProfileIconClick: () -> Unit = {},
    onOpenParticipantProfile: (ConversationParticipantDto) -> Unit = {},
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    linkedPartnerUserId: String? = null,
    onInvitePartner: () -> Unit = {},
    onAcceptInvite: () -> Unit = {},
    onDeclineInvite: () -> Unit = {},
    onLeaveConversation: () -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onSetExpiry: (Int?) -> Unit = {},
    onRevealMedia: (String) -> Unit = {},
    onPickPhoto: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    PynapliMaxWidthColumn(
        maxWidth = PynapliContentWidth.Conversation,
        modifier = modifier,
        columnModifier = Modifier.testTag(ConversationTestTags.fullScreen),
    ) {
        FullScreenConversationHeader(
            state = state,
            onBack = onBack,
            onOpenParticipantProfile = onOpenParticipantProfile,
        )
        ConversationContextStrip(conversation = state.detail?.conversation)
        ConversationContent(
            state = state,
            linkedPartnerUserId = linkedPartnerUserId,
            onDraftChanged = onDraftChanged,
            onSend = onSend,
            onLoadOlder = onLoadOlder,
            onInvitePartner = onInvitePartner,
            onAcceptInvite = onAcceptInvite,
            onDeclineInvite = onDeclineInvite,
            onLeaveConversation = onLeaveConversation,
            onDeleteMessage = onDeleteMessage,
            onSetExpiry = onSetExpiry,
            onRevealMedia = onRevealMedia,
            onPickPhoto = onPickPhoto,
            fillAvailableHeight = true,
            showLeaveAction = false,
            showPartnerStatusFeedback = false,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            onEscape = onBack,
        )
    }
}

@Composable
private fun ColumnScope.ConversationContent(
    state: ConversationUiState,
    linkedPartnerUserId: String?,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    onInvitePartner: () -> Unit,
    onAcceptInvite: () -> Unit,
    onDeclineInvite: () -> Unit,
    onLeaveConversation: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onSetExpiry: (Int?) -> Unit,
    onRevealMedia: (String) -> Unit,
    onPickPhoto: () -> Unit,
    fillAvailableHeight: Boolean,
    showLeaveAction: Boolean,
    showPartnerStatusFeedback: Boolean,
    modifier: Modifier = Modifier,
    onEscape: (() -> Unit)? = null,
) {
    when {
        state.isOpening -> {
            Box(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .then(if (fillAvailableHeight) Modifier.weight(1f) else Modifier.heightIn(min = 180.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        state.errorMessage != null && state.detail == null -> {
            Box(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .then(if (fillAvailableHeight) Modifier.weight(1f) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        else -> {
            Column(
                modifier = modifier.then(if (fillAvailableHeight) Modifier.weight(1f) else Modifier),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConversationPartnerActions(
                    state = state,
                    linkedPartnerUserId = linkedPartnerUserId,
                    onInvitePartner = onInvitePartner,
                    onAcceptInvite = onAcceptInvite,
                    onDeclineInvite = onDeclineInvite,
                    onLeaveConversation = onLeaveConversation,
                    showLeaveAction = showLeaveAction,
                    showPartnerStatusFeedback = showPartnerStatusFeedback,
                )
                if (!state.isViewerInvited) {
                    ConversationExpiryControl(
                        effectiveExpirySeconds = state.effectiveExpirySeconds,
                        viewerExpirySeconds = state.viewerExpirySeconds,
                        isSettingExpiry = state.isSettingExpiry,
                        onSetExpiry = onSetExpiry,
                    )
                }
                MessageList(
                    messages = state.detail?.messages.orEmpty(),
                    viewerUserId = state.viewerUserId,
                    senderDisplayNames =
                        state.detail
                            ?.conversation
                            ?.participants
                            ?.associate { participant -> participant.userId to participant.displayName }
                            .orEmpty(),
                    hasOlderMessages = state.detail?.nextCursor != null,
                    isLoadingOlder = state.isLoadingOlder,
                    isDeletingMessageIds = state.isDeletingMessageIds,
                    revealingMessageIds = state.revealingMessageIds,
                    onLoadOlder = onLoadOlder,
                    onDeleteMessage = onDeleteMessage,
                    onRevealMedia = onRevealMedia,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (fillAvailableHeight) Modifier.weight(1f) else Modifier.heightIn(min = 220.dp, max = 430.dp)),
                )
                TypingIndicator(participants = state.typingParticipants)
                if (!state.isViewerInvited) {
                    Composer(
                        draft = state.draft,
                        canSend = state.canSend,
                        isSending = state.isSending,
                        isSendingPhoto = state.isSendingPhoto,
                        isOverLimit = state.draftOverLimit,
                        remainingCharacters = state.draftRemaining,
                        errorMessage = state.errorMessage,
                        onDraftChanged = onDraftChanged,
                        onSend = onSend,
                        onPickPhoto = onPickPhoto,
                        onEscape = onEscape,
                    )
                }
            }
        }
    }
}

object ConversationTestTags {
    fun deleteMessage(messageId: String): String = "conversation-delete-message-$messageId"

    const val typingIndicator: String = "conversation-typing-indicator"
    const val fullScreen: String = "conversation-full-screen"
    const val fullScreenHeader: String = "conversation-full-screen-header"
}
