package com.pynapli.app.messages

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class MessagesUiState(
    val conversations: List<ConversationDto> = emptyList(),
    val mutatingPartnerInviteConversationIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    // Paging: the server returns the inbox newest-first in pages; null when everything is loaded.
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
    val pagesLoaded: Int = 0,
    val errorMessage: String? = null,
) {
    val unreadCount: Int
        get() = conversations.sumOf { conversation -> conversation.unreadCount }

    fun partnerChatInvites(viewerUserId: String?): List<ConversationDto> =
        conversations.filter { conversation ->
            conversation.viewerParticipantStatus(viewerUserId) == ConversationParticipantStatus.INVITED
        }
}

class MessagesStateHolder(
    private val repository: MessagesRepository,
) {
    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()
    private var refreshInFlight = false

    suspend fun load() = refresh(showLoading = true, surfaceErrors = true)

    suspend fun refreshSilently() = refresh(showLoading = false, surfaceErrors = false)

    private suspend fun refresh(
        showLoading: Boolean,
        surfaceErrors: Boolean,
    ) {
        if (refreshInFlight) return
        refreshInFlight = true
        if (showLoading) {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
        }

        try {
            when (val result = repository.listConversations()) {
                is AppResult.Success -> {
                    _state.update { current -> current.withFirstPage(result.value) }
                }

                is AppResult.Failure -> {
                    _state.update { current ->
                        current.copy(
                            isLoading = false,
                            errorMessage =
                                if (surfaceErrors || current.conversations.isEmpty()) {
                                    result.error.message
                                } else {
                                    current.errorMessage
                                },
                        )
                    }
                }
            }
        } finally {
            refreshInFlight = false
        }
    }

    /** Loads the next inbox page (the list's footer calls this when it scrolls into view). */
    suspend fun loadMore() {
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.isLoadingMore) return
        _state.update { it.copy(isLoadingMore = true) }
        when (val result = repository.listConversationsAfter(cursor)) {
            is AppResult.Success ->
                _state.update { current ->
                    val known = current.conversations.mapTo(mutableSetOf()) { it.conversationId }
                    current.copy(
                        conversations = current.conversations + result.value.items.filter { it.conversationId !in known },
                        nextCursor = result.value.nextCursor,
                        isLoadingMore = false,
                        pagesLoaded = current.pagesLoaded + 1,
                    )
                }
            // Keep the cursor: scrolling back to the footer tries again.
            is AppResult.Failure -> _state.update { it.copy(isLoadingMore = false) }
        }
    }

    fun markConversationReadLocal(conversationId: String) {
        _state.update { current ->
            current.copy(
                conversations =
                    current.conversations.map { conversation ->
                        if (conversation.conversationId == conversationId) {
                            conversation.copy(unreadCount = 0)
                        } else {
                            conversation
                        }
                    },
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
                            errorMessage = null,
                        )
                    }
                }

                is AppResult.Failure -> {
                    _state.update { current ->
                        current.copy(
                            mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds - conversationId,
                            errorMessage = result.error.message,
                        )
                    }
                }
            }
        }
    }

    suspend fun declinePartnerInvite(
        conversationId: String,
        viewerUserId: String? = null,
    ) {
        val removeConversation =
            state.value.conversations
                .firstOrNull {
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
                                errorMessage = null,
                            )
                        }
                    } else {
                        _state.update { current ->
                            current.copy(
                                mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds - conversationId,
                                errorMessage = null,
                            )
                        }
                        refreshSilently()
                    }
                }

                is AppResult.Failure -> {
                    _state.update { current ->
                        current.copy(
                            mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds - conversationId,
                            errorMessage = result.error.message,
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
        call: suspend () -> Unit,
    ) {
        if (conversationId in state.value.mutatingPartnerInviteConversationIds) return
        _state.update { current ->
            current.copy(
                mutatingPartnerInviteConversationIds = current.mutatingPartnerInviteConversationIds + conversationId,
                errorMessage = null,
            )
        }
        call()
    }
}

interface MessagesRepository {
    suspend fun listConversations(): AppResult<ConversationListResponseDto>

    suspend fun listConversationsAfter(cursor: String): AppResult<ConversationListResponseDto> = AppResult.Success(ConversationListResponseDto(items = emptyList()))

    suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto>

    suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>
}

class ApiMessagesRepository(
    private val apiClient: PynapliApiClient,
) : MessagesRepository {
    override suspend fun listConversations(): AppResult<ConversationListResponseDto> = apiClient.listConversations()

    override suspend fun listConversationsAfter(cursor: String): AppResult<ConversationListResponseDto> = apiClient.listConversationsAfter(cursor)

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> = apiClient.acceptConversationPartnerInvite(conversationId)

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> = apiClient.declineConversationPartnerInvite(conversationId)
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

/**
 * A refresh brings back the first page. Pages the user already scrolled to stay (otherwise every
 * refresh would snap a long inbox back to its top 50): older threads not in the new first page are
 * kept after it, and the cursor continues from where they end.
 */
private fun MessagesUiState.withFirstPage(page: ConversationListResponseDto): MessagesUiState {
    val keepOlderPages = pagesLoaded > 1 && page.nextCursor != null
    val firstIds = page.items.mapTo(mutableSetOf()) { it.conversationId }
    val oldest = page.items.lastOrNull()?.updatedAt
    val older =
        if (keepOlderPages && oldest != null) {
            conversations.filter { it.conversationId !in firstIds && it.updatedAt <= oldest }
        } else {
            emptyList()
        }
    return copy(
        conversations = page.items + older,
        nextCursor = if (older.isNotEmpty()) nextCursor else page.nextCursor,
        pagesLoaded = if (older.isNotEmpty()) pagesLoaded else 1,
        isLoading = false,
        errorMessage = null,
    )
}
