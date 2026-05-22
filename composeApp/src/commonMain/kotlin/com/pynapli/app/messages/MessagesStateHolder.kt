package com.pynapli.app.messages

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.MessageDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class MessagesUiState(
    val conversations: List<ConversationDto> = emptyList(),
    val mutatingPartnerInviteConversationIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val unreadCount: Int
        get() = conversations.sumOf { conversation -> conversation.unreadCount }

    fun partnerChatInvites(viewerUserId: String?): List<ConversationDto> =
        conversations.filter { conversation ->
            conversation.viewerParticipantStatus(viewerUserId) == ConversationParticipantStatus.INVITED
        }
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

    suspend fun acceptPartnerInvite(conversationId: String) {
        mutatePartnerInvite(conversationId) {
            when (val result = repository.acceptPartnerInvite(conversationId)) {
                is AppResult.Success -> {
                    _state.update { current ->
                        current.copy(
                            conversations = current.conversations.upsert(result.value.conversation),
                            mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds - conversationId,
                            errorMessage = null
                        )
                    }
                }

                is AppResult.Failure -> {
                    _state.update { current ->
                        current.copy(
                            mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds - conversationId,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    suspend fun declinePartnerInvite(conversationId: String, viewerUserId: String? = null) {
        val removeConversation = state.value.conversations.firstOrNull {
            it.conversationId == conversationId
        }?.viewerParticipantStatus(viewerUserId) == ConversationParticipantStatus.INVITED
        mutatePartnerInvite(conversationId) {
            when (val result = repository.declinePartnerInvite(conversationId)) {
                is AppResult.Success -> {
                    if (removeConversation) {
                        _state.update { current ->
                            current.copy(
                                conversations = current.conversations.filterNot { it.conversationId == conversationId },
                                mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds - conversationId,
                                errorMessage = null
                            )
                        }
                    } else {
                        _state.update { current ->
                            current.copy(
                                mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds - conversationId,
                                errorMessage = null
                            )
                        }
                        refreshSilently()
                    }
                }

                is AppResult.Failure -> {
                    _state.update { current ->
                        current.copy(
                            mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds - conversationId,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private suspend fun mutatePartnerInvite(
        conversationId: String,
        call: suspend () -> Unit
    ) {
        if (conversationId in state.value.mutatingPartnerInviteConversationIds) return
        _state.update { current ->
            current.copy(
                mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds + conversationId,
                errorMessage = null
            )
        }
        call()
    }
}

interface MessagesRepository {
    suspend fun listConversations(): AppResult<ConversationListResponseDto>

    suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto>

    suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>
}

class ApiMessagesRepository(
    private val apiClient: PynapliApiClient
) : MessagesRepository {
    override suspend fun listConversations(): AppResult<ConversationListResponseDto> =
        apiClient.listConversations()

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> =
        apiClient.acceptConversationPartnerInvite(conversationId)

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        apiClient.declineConversationPartnerInvite(conversationId)
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

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(
                conversation = ConversationDto(
                    conversationId = conversationId,
                    kind = "group",
                    origin = "grid",
                    participants = listOf(
                        ConversationParticipantDto("viewer", "You"),
                        ConversationParticipantDto("maya", "Maya", online = true)
                    ),
                    unreadCount = 0,
                    updatedAt = "2026-04-24T18:00:00Z"
                ),
                messages = emptyList()
            )
        )

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED))
}

private fun ConversationDto.viewerParticipantStatus(viewerUserId: String?): String? =
    viewerUserId?.let { userId ->
        participants.firstOrNull { participant -> participant.userId == userId }?.status
    }

private fun List<ConversationDto>.upsert(conversation: ConversationDto): List<ConversationDto> =
    if (any { it.conversationId == conversation.conversationId }) {
        map { existing ->
            if (existing.conversationId == conversation.conversationId) conversation else existing
        }
    } else {
        listOf(conversation) + this
    }
