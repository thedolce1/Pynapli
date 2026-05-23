package com.pynapli.app.messages

import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.shared.network.MessageStreamClient

class MessagesRealtimeCoordinator(
    private val client: MessageStreamClient,
    private val messagesStateHolder: MessagesStateHolder,
    private val conversationStateHolder: ConversationStateHolder
) {
    suspend fun run() {
        client.connect().collect { delta ->
            messagesStateHolder.refreshSilently()
            conversationStateHolder.refreshOpenConversationIfConversationId(delta.conversationId)
        }
    }
}
