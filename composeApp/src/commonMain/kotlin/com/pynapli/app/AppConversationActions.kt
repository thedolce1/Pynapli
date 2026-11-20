package com.pynapli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.conversation.MetInPersonCallbacks
import com.pynapli.app.messages.MessagesStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The conversation action callbacks the open-conversation UI fires, bundled out of the App.kt
 * shell. Behavior-preserving: each lambda does exactly what its inline `val` did. `dismiss`
 * reads the current conversation id from the holder's state at call time, so the bundle stays
 * stable across recompositions (no per-frame relocation).
 */
@Immutable
internal data class ConversationActions(
    val dismiss: () -> Unit,
    val onDraftChanged: (String) -> Unit,
    val onSend: () -> Unit,
    val onLoadOlder: () -> Unit,
    val onInvitePartner: () -> Unit,
    val onAcceptInvite: () -> Unit,
    val onDeclineInvite: () -> Unit,
    val onLeave: () -> Unit,
    val onDeleteMessage: (String) -> Unit,
    val onSetExpiry: (Int?) -> Unit,
    val onRevealMedia: (String) -> Unit,
    val onReportMessage: (messageId: String, category: String) -> Unit = { _, _ -> },
    val onOpenStore: () -> Unit = {},
    val metInPerson: MetInPersonCallbacks = MetInPersonCallbacks(),
)

@Composable
internal fun rememberConversationActions(
    scope: CoroutineScope,
    conversationStateHolder: ConversationStateHolder,
    messagesStateHolder: MessagesStateHolder,
    onOpenStore: () -> Unit = {},
): ConversationActions =
    remember(scope, conversationStateHolder, messagesStateHolder) {
        ConversationActions(
            dismiss = {
                val conversationId =
                    conversationStateHolder.state.value.detail
                        ?.conversation
                        ?.conversationId
                scope.launch { conversationStateHolder.sendTypingStopped(conversationId) }
                conversationStateHolder.dismiss()
            },
            onDraftChanged = { value ->
                conversationStateHolder.updateDraft(value)
                scope.launch { conversationStateHolder.sendTypingSignal(value.isNotBlank()) }
            },
            onSend = { scope.launch { conversationStateHolder.sendDraft() } },
            onLoadOlder = { scope.launch { conversationStateHolder.loadOlderMessages() } },
            onInvitePartner = {
                scope.launch {
                    conversationStateHolder.inviteLinkedPartner()
                    messagesStateHolder.refreshSilently()
                }
            },
            onAcceptInvite = {
                scope.launch {
                    conversationStateHolder.acceptPartnerInvite()
                    messagesStateHolder.refreshSilently()
                }
            },
            onDeclineInvite = {
                scope.launch {
                    conversationStateHolder.declinePartnerInvite()
                    messagesStateHolder.refreshSilently()
                }
            },
            onLeave = {
                scope.launch {
                    conversationStateHolder.leaveConversation()
                    messagesStateHolder.refreshSilently()
                }
            },
            onDeleteMessage = { messageId ->
                scope.launch {
                    conversationStateHolder.deleteMessage(messageId)
                    messagesStateHolder.refreshSilently()
                }
            },
            onOpenStore = {
                conversationStateHolder.dismiss()
                onOpenStore()
            },
            onReportMessage = { messageId, category ->
                scope.launch { conversationStateHolder.reportMessage(messageId, category) }
            },
            onSetExpiry = { expirySeconds ->
                scope.launch {
                    conversationStateHolder.setExpiry(expirySeconds)
                    messagesStateHolder.refreshSilently()
                }
            },
            onRevealMedia = { messageId ->
                scope.launch { conversationStateHolder.revealMedia(messageId) }
            },
            metInPerson =
                MetInPersonCallbacks(
                    onMenuOpened = { scope.launch { conversationStateHolder.loadMetInPersonOffers() } },
                    onOpen = conversationStateHolder::openMetInPerson,
                    onIncludePartnerChange = conversationStateHolder::setMetInPersonIncludePartner,
                    onConfirm = { scope.launch { conversationStateHolder.confirmMetInPerson() } },
                    onTakeBack = { scope.launch { conversationStateHolder.takeBackMetInPerson() } },
                    onDismiss = conversationStateHolder::dismissMetInPerson,
                ),
        )
    }
