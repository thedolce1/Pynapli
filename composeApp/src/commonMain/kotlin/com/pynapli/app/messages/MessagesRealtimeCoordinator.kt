package com.pynapli.app.messages

import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.shared.network.MessageStreamClient
import com.pynapli.shared.network.dto.MessageDeltaType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MessagesRealtimeCoordinator(
    private val client: MessageStreamClient,
    private val messagesStateHolder: MessagesStateHolder,
    private val conversationStateHolder: ConversationStateHolder,
) {
    suspend fun run() {
        coroutineScope {
            launch {
                while (true) {
                    delay(TYPING_EXPIRY_SWEEP_MS)
                    conversationStateHolder.clearExpiredTypingIndicators()
                }
            }
            client.connect().collect { delta ->
                when (delta.type) {
                    MessageDeltaType.TYPING_STARTED ->
                        conversationStateHolder.applyTypingStarted(
                            conversationId = delta.conversationId,
                            actorUserId = delta.actorUserId,
                            actorDisplayName = delta.actorDisplayName,
                            expiresAt = delta.expiresAt,
                        )
                    MessageDeltaType.TYPING_STOPPED ->
                        conversationStateHolder.applyTypingStopped(
                            conversationId = delta.conversationId,
                            actorUserId = delta.actorUserId,
                        )
                    else -> {
                        messagesStateHolder.refreshSilently()
                        conversationStateHolder.refreshOpenConversationIfConversationId(delta.conversationId)
                    }
                }
            }
        }
    }

    private companion object {
        const val TYPING_EXPIRY_SWEEP_MS = 1_000L
    }
}
