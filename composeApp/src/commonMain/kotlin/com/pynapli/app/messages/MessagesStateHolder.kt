package com.pynapli.app.messages

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.MessageDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class MessagesUiState(
    val conversations: List<ConversationDto> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val unreadCount: Int
        get() = conversations.sumOf { conversation -> conversation.unreadCount }
}

class MessagesStateHolder(
    private val repository: MessagesRepository
) {
    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()
    private var refreshInFlight = false

    suspend fun load() = refresh(showLoading = true, surfaceErrors = true)

    suspend fun refreshSilently() = refresh(showLoading = false, surfaceErrors = false)

    private suspend fun refresh(showLoading: Boolean, surfaceErrors: Boolean) {
        if (refreshInFlight) return
        refreshInFlight = true
        if (showLoading) {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
        }

        try {
            when (val result = repository.listConversations()) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            conversations = result.value.items,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }

                is AppResult.Failure -> {
                    _state.update { current ->
                        current.copy(
                            isLoading = false,
                            errorMessage = if (surfaceErrors || current.conversations.isEmpty()) {
                                result.error.message
                            } else {
                                current.errorMessage
                            }
                        )
                    }
                }
            }
        } finally {
            refreshInFlight = false
        }
    }

    fun markConversationReadLocal(conversationId: String) {
        _state.update { current ->
            current.copy(
                conversations = current.conversations.map { conversation ->
                    if (conversation.conversationId == conversationId) {
                        conversation.copy(unreadCount = 0)
                    } else {
                        conversation
                    }
                }
            )
        }
    }
}

interface MessagesRepository {
    suspend fun listConversations(): AppResult<ConversationListResponseDto>
}

class ApiMessagesRepository(
    private val apiClient: PynapliApiClient
) : MessagesRepository {
    override suspend fun listConversations(): AppResult<ConversationListResponseDto> =
        apiClient.listConversations()
}

class SampleMessagesRepository : MessagesRepository {
    override suspend fun listConversations(): AppResult<ConversationListResponseDto> =
        AppResult.Success(
            ConversationListResponseDto(
                items = listOf(
                    ConversationDto(
                        conversationId = "sample-conversation",
                        kind = "direct",
                        origin = "grid",
                        participants = listOf(
                            ConversationParticipantDto("viewer", "You"),
                            ConversationParticipantDto("maya", "Maya", online = true)
                        ),
                        lastMessage = MessageDto(
                            messageId = "sample-message",
                            conversationId = "sample-conversation",
                            senderUserId = "maya",
                            body = "See you tonight",
                            createdAt = "2026-04-24T18:00:00Z",
                            status = "visible",
                            deliveryStatus = "received"
                        ),
                        unreadCount = 1,
                        updatedAt = "2026-04-24T18:00:00Z"
                    )
                )
            )
        )
}
