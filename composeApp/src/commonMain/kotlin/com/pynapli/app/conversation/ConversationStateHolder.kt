package com.pynapli.app.conversation

import androidx.compose.runtime.Immutable
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class ConversationUiState(
    val selectedCard: GridProfileCardUi? = null,
    val titleOverride: String? = null,
    val viewerUserId: String? = null,
    val detail: ConversationDetailDto? = null,
    val draft: String = "",
    val isOpening: Boolean = false,
    val isSending: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val errorMessage: String? = null
) {
    val visible: Boolean
        get() = selectedCard != null || titleOverride != null || detail != null || isOpening

    val draftLength: Int
        get() = draft.length

    val draftRemaining: Int
        get() = MESSAGE_BODY_MAX_LENGTH - draftLength

    val draftOverLimit: Boolean
        get() = draftLength > MESSAGE_BODY_MAX_LENGTH

    val canSend: Boolean
        get() = detail != null &&
            draft.trim().isNotEmpty() &&
            !draftOverLimit &&
            !isOpening &&
            !isSending
}

class ConversationStateHolder(
    private val repository: ConversationRepository
) {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    suspend fun openDirect(card: GridProfileCardUi, viewerUserId: String?) {
        _state.update {
            ConversationUiState(
                selectedCard = card,
                viewerUserId = viewerUserId,
                isOpening = true
            )
        }

        when (val result = repository.createDirectConversation(card.id)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation()
                _state.update { current ->
                    current.copy(
                        detail = detail,
                        isOpening = false,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isOpening = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun openConversation(conversation: ConversationDto, viewerUserId: String?) {
        _state.update {
            ConversationUiState(
                titleOverride = conversation.titleFor(viewerUserId),
                viewerUserId = viewerUserId,
                detail = ConversationDetailDto(
                    conversation = conversation,
                    messages = emptyList()
                ),
                isOpening = true
            )
        }

        when (val result = repository.getConversationDetail(conversation.conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation()
                _state.update { current ->
                    current.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(viewerUserId),
                        isOpening = false,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isOpening = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun refreshOpenConversation(silent: Boolean = true) {
        val current = state.value
        if (!current.visible || current.isOpening || current.isSending || current.isLoadingOlder) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        when (val result = repository.getConversationDetail(conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation()
                _state.update { existing ->
                    val existingDetail = existing.detail ?: return@update existing
                    if (existingDetail.conversation.conversationId != conversationId) {
                        existing
                    } else {
                        existing.copy(
                            detail = detail.withMergedMessagesFrom(existingDetail),
                            titleOverride = detail.conversation.titleFor(existing.viewerUserId),
                            errorMessage = null
                        )
                    }
                }
            }

            is AppResult.Failure -> {
                if (!silent) {
                    _state.update { existing ->
                        existing.copy(errorMessage = result.error.message)
                    }
                }
            }
        }
    }

    suspend fun loadOlderMessages() {
        val current = state.value
        if (current.isLoadingOlder || current.isOpening) return
        val detail = current.detail ?: return
        val cursor = detail.nextCursor ?: return
        val conversationId = detail.conversation.conversationId

        _state.update { it.copy(isLoadingOlder = true, errorMessage = null) }
        when (val result = repository.getConversationDetail(conversationId, before = cursor)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    val existingDetail = existing.detail
                    if (existingDetail?.conversation?.conversationId != conversationId) {
                        existing.copy(isLoadingOlder = false)
                    } else {
                        existing.copy(
                            detail = existingDetail.withPrependedPage(result.value),
                            isLoadingOlder = false,
                            errorMessage = null
                        )
                    }
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isLoadingOlder = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun updateDraft(value: String) {
        _state.update { current ->
            current.copy(
                draft = value,
                errorMessage = null
            )
        }
    }

    suspend fun sendDraft() {
        val current = state.value
        if (current.isSending) return
        val conversationId = current.detail?.conversation?.conversationId ?: return
        val body = current.draft.trim()
        when {
            body.isEmpty() -> {
                _state.update { existing ->
                    existing.copy(errorMessage = "Enter a message before sending.")
                }
                return
            }

            current.draftOverLimit -> {
                _state.update { existing ->
                    existing.copy(errorMessage = "Message is too long. Keep it under $MESSAGE_BODY_MAX_LENGTH characters.")
                }
                return
            }
        }

        _state.update { it.copy(isSending = true, errorMessage = null) }
        when (val result = repository.sendMessage(conversationId, body)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    val updatedDetail = existing.detail?.withAppendedMessage(result.value)
                    existing.copy(
                        detail = updatedDetail,
                        draft = "",
                        isSending = false,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isSending = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun dismiss() {
        _state.value = ConversationUiState()
    }

    private fun ConversationDetailDto.withAppendedMessage(message: MessageDto): ConversationDetailDto =
        copy(
            conversation = conversation.copy(
                lastMessage = message,
                unreadCount = 0,
                updatedAt = message.createdAt
            ),
            messages = messages + message
        )

    private suspend fun ConversationDetailDto.withReadConversation(): ConversationDetailDto =
        when (val readResult = repository.markConversationRead(conversation.conversationId)) {
            is AppResult.Success -> copy(conversation = readResult.value)
            is AppResult.Failure -> this
        }

    private fun ConversationDetailDto.withPrependedPage(page: ConversationDetailDto): ConversationDetailDto =
        copy(
            conversation = page.conversation,
            messages = mergeMessages(page.messages + messages),
            nextCursor = page.nextCursor
        )

    private fun ConversationDetailDto.withMergedMessagesFrom(existing: ConversationDetailDto): ConversationDetailDto =
        copy(
            messages = mergeMessages(existing.messages + messages),
            nextCursor = existing.nextCursor ?: nextCursor
        )

    private fun mergeMessages(messages: List<MessageDto>): List<MessageDto> =
        messages
            .associateBy { message -> message.messageId }
            .values
            .sortedWith(compareBy<MessageDto> { message -> message.createdAt }.thenBy { message -> message.messageId })
}

interface ConversationRepository {
    suspend fun createDirectConversation(targetUserId: String): AppResult<ConversationDetailDto>

    suspend fun getConversationDetail(
        conversationId: String,
        before: String? = null
    ): AppResult<ConversationDetailDto>

    suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto>

    suspend fun sendMessage(conversationId: String, body: String): AppResult<MessageDto>
}

class ApiConversationRepository(
    private val apiClient: PynapliApiClient
) : ConversationRepository {
    override suspend fun createDirectConversation(targetUserId: String): AppResult<ConversationDetailDto> =
        apiClient.createDirectConversation(
            CreateDirectConversationRequestDto(
                targetUserId = targetUserId,
                origin = "grid"
            )
        )

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?
    ): AppResult<ConversationDetailDto> =
        apiClient.getConversationDetail(conversationId, before = before)

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
        apiClient.markConversationRead(conversationId)

    override suspend fun sendMessage(conversationId: String, body: String): AppResult<MessageDto> =
        apiClient.sendMessage(conversationId, SendMessageRequestDto(body = body))
}

class SampleConversationRepository : ConversationRepository {
    override suspend fun createDirectConversation(targetUserId: String): AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(
                conversation = com.pynapli.shared.network.dto.ConversationDto(
                    conversationId = "sample-conversation",
                    kind = "direct",
                    origin = "grid",
                    participants = listOf(
                        com.pynapli.shared.network.dto.ConversationParticipantDto("viewer", "You"),
                        com.pynapli.shared.network.dto.ConversationParticipantDto(targetUserId, "Maya")
                    ),
                    unreadCount = 0,
                    updatedAt = "2026-04-24T18:00:00Z"
                ),
                messages = emptyList()
            )
        )

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?
    ): AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(
                conversation = com.pynapli.shared.network.dto.ConversationDto(
                    conversationId = conversationId,
                    kind = "direct",
                    origin = "grid",
                    participants = listOf(
                        com.pynapli.shared.network.dto.ConversationParticipantDto("viewer", "You"),
                        com.pynapli.shared.network.dto.ConversationParticipantDto("target", "Maya")
                    ),
                    lastMessage = MessageDto(
                        messageId = "sample-message-1",
                        conversationId = conversationId,
                        senderUserId = "target",
                        body = "See you tonight",
                        createdAt = "2026-04-24T18:00:00Z",
                        status = "visible",
                        deliveryStatus = "received"
                    ),
                    unreadCount = 1,
                    updatedAt = "2026-04-24T18:00:00Z"
                ),
                messages = listOf(
                    MessageDto(
                        messageId = "sample-message-1",
                        conversationId = conversationId,
                        senderUserId = "target",
                        body = "See you tonight",
                        createdAt = "2026-04-24T18:00:00Z",
                        status = "visible",
                        deliveryStatus = "received"
                    )
                ),
                nextCursor = if (before == null) "sample-older-page" else null
            )
        )

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
        AppResult.Success(
            com.pynapli.shared.network.dto.ConversationDto(
                conversationId = conversationId,
                kind = "direct",
                origin = "grid",
                participants = listOf(
                    com.pynapli.shared.network.dto.ConversationParticipantDto("viewer", "You"),
                    com.pynapli.shared.network.dto.ConversationParticipantDto("target", "Maya")
                ),
                unreadCount = 0,
                updatedAt = "2026-04-24T18:00:00Z"
            )
        )

    override suspend fun sendMessage(conversationId: String, body: String): AppResult<MessageDto> =
        AppResult.Success(
            MessageDto(
                messageId = "sample-message",
                conversationId = conversationId,
                senderUserId = "viewer",
                body = body,
                createdAt = "2026-04-24T18:00:00Z",
                status = "visible",
                deliveryStatus = "sent"
            )
        )
}

internal fun ConversationDto.titleFor(viewerUserId: String?): String =
    participants
        .filterNot { participant -> viewerUserId != null && participant.userId == viewerUserId }
        .joinToString(", ") { participant -> participant.displayName }
        .ifBlank { participants.firstOrNull()?.displayName ?: "Conversation" }
