package com.pynapli.app.conversation

internal interface ConversationStateAccess {
    val current: ConversationUiState

    fun update(transform: (ConversationUiState) -> ConversationUiState)

    fun set(value: ConversationUiState)
}
