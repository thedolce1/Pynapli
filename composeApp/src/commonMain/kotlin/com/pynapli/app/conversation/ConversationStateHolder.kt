package com.pynapli.app.conversation

import androidx.compose.runtime.Immutable
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
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
    val isInvitingPartner: Boolean = false,
    val isRespondingToInvite: Boolean = false,
    val isLeaving: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val partnerInviteFeedback: String? = null,
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

    val viewerParticipantStatus: String?
        get() = viewerUserId?.let { userId ->
            detail?.conversation?.participants?.firstOrNull { participant -> participant.userId == userId }?.status
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
        get() = detail != null &&
            !isViewerInvited &&
            draft.trim().isNotEmpty() &&
            !draftOverLimit &&
            !isOpening &&
            !isSending &&
            !isRespondingToInvite &&
            !isLeaving
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
                        partnerInviteFeedback = null,
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

    suspend fun openSwipeMatch(match: MatchDto, viewerUserId: String?) {
        _state.update {
            ConversationUiState(
                titleOverride = match.matchedDisplayName ?: "Match",
                viewerUserId = viewerUserId,
                isOpening = true
            )
        }

        when (val result = repository.createDirectConversation(match.matchedUserId, origin = "swipe")) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation()
                _state.update { current ->
                    current.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(viewerUserId),
                        isOpening = false,
                        partnerInviteFeedback = null,
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
                        partnerInviteFeedback = null,
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

    suspend fun openConversationById(
        conversationId: String,
        viewerUserId: String?,
        title: String = "Conversation"
    ) {
        _state.update {
            ConversationUiState(
                titleOverride = title,
                viewerUserId = viewerUserId,
                isOpening = true
            )
        }

        when (val result = repository.getConversationDetail(conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation()
                _state.update { current ->
                    current.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(viewerUserId),
                        isOpening = false,
                        partnerInviteFeedback = null,
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
                            partnerInviteFeedback = null,
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

    suspend fun inviteLinkedPartner() {
        val current = state.value
        if (current.isInvitingPartner || current.isOpening || current.isViewerInvited) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        _state.update {
            it.copy(
                isInvitingPartner = true,
                partnerInviteFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.inviteLinkedPartner(conversationId)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    val existingDetail = existing.detail
                    existing.copy(
                        detail = existingDetail?.copy(conversation = result.value),
                        titleOverride = result.value.titleFor(existing.viewerUserId),
                        isInvitingPartner = false,
                        partnerInviteFeedback = "Partner invited",
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isInvitingPartner = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun acceptPartnerInvite() {
        val current = state.value
        if (current.isRespondingToInvite || !current.canRespondToPartnerInvite) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        _state.update {
            it.copy(
                isRespondingToInvite = true,
                partnerInviteFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.acceptPartnerInvite(conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation()
                _state.update { existing ->
                    existing.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(existing.viewerUserId),
                        isRespondingToInvite = false,
                        partnerInviteFeedback = current.partnerInviteAcceptedFeedback(detail),
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isRespondingToInvite = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun declinePartnerInvite() {
        val current = state.value
        if (current.isRespondingToInvite || !current.canRespondToPartnerInvite) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        _state.update {
            it.copy(
                isRespondingToInvite = true,
                partnerInviteFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.declinePartnerInvite(conversationId)) {
            is AppResult.Success -> {
                if (current.isViewerInvited) {
                    dismiss()
                } else {
                    refreshOpenConversationAfterInviteDecline(conversationId)
                }
            }
            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isRespondingToInvite = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    private suspend fun refreshOpenConversationAfterInviteDecline(conversationId: String) {
        when (val detailResult = repository.getConversationDetail(conversationId)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    existing.copy(
                        detail = detailResult.value.withReadConversation(),
                        titleOverride = detailResult.value.conversation.titleFor(existing.viewerUserId),
                        isRespondingToInvite = false,
                        partnerInviteFeedback = "Invite declined",
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isRespondingToInvite = false,
                        partnerInviteFeedback = null,
                        errorMessage = detailResult.error.message
                    )
                }
            }
        }
    }

    suspend fun leaveConversation() {
        val current = state.value
        if (current.isLeaving || current.isOpening) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        _state.update {
            it.copy(
                isLeaving = true,
                partnerInviteFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.leaveConversation(conversationId)) {
            is AppResult.Success -> dismiss()
            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isLeaving = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun sendDraft() {
        val current = state.value
        if (current.isSending || current.isViewerInvited) return
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

        _state.update { it.copy(isSending = true, partnerInviteFeedback = null, errorMessage = null) }
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
    suspend fun createDirectConversation(
        targetUserId: String,
        origin: String = "grid"
    ): AppResult<ConversationDetailDto>

    suspend fun getConversationDetail(
        conversationId: String,
        before: String? = null
    ): AppResult<ConversationDetailDto>

    suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto>

    suspend fun sendMessage(conversationId: String, body: String): AppResult<MessageDto>

    suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto>

    suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto>

    suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>

    suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>
}

class ApiConversationRepository(
    private val apiClient: PynapliApiClient
) : ConversationRepository {
    override suspend fun createDirectConversation(
        targetUserId: String,
        origin: String
    ): AppResult<ConversationDetailDto> =
        apiClient.createDirectConversation(
            CreateDirectConversationRequestDto(
                targetUserId = targetUserId,
                origin = origin
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

    override suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto> =
        apiClient.inviteLinkedPartnerToConversation(conversationId)

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> =
        apiClient.acceptConversationPartnerInvite(conversationId)

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        apiClient.declineConversationPartnerInvite(conversationId)

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        apiClient.leaveConversation(conversationId)
}

class SampleConversationRepository : ConversationRepository {
    override suspend fun createDirectConversation(
        targetUserId: String,
        origin: String
    ): AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(
                conversation = com.pynapli.shared.network.dto.ConversationDto(
                    conversationId = "sample-conversation",
                    kind = "direct",
                    origin = origin,
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

    override suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto> =
        AppResult.Success(
            com.pynapli.shared.network.dto.ConversationDto(
                conversationId = conversationId,
                kind = "group",
                origin = "grid",
                participants = listOf(
                    com.pynapli.shared.network.dto.ConversationParticipantDto("viewer", "You"),
                    com.pynapli.shared.network.dto.ConversationParticipantDto("target", "Maya"),
                    com.pynapli.shared.network.dto.ConversationParticipantDto(
                        userId = "partner",
                        displayName = "Partner",
                        status = ConversationParticipantStatus.INVITED,
                        invitedByUserId = "viewer"
                    )
                ),
                unreadCount = 0,
                updatedAt = "2026-04-24T18:00:00Z"
            )
        )

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> =
        getConversationDetail(conversationId, before = null)

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED))

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.LEFT))
}

internal fun ConversationDto.titleFor(viewerUserId: String?): String =
    participants
        .filter { participant -> participant.status in visibleConversationParticipantStatuses }
        .filterNot { participant -> viewerUserId != null && participant.userId == viewerUserId }
        .joinToString(", ") { participant -> participant.displayName }
        .ifBlank { participants.firstOrNull()?.displayName ?: "Conversation" }

private fun ConversationUiState.partnerInviteAcceptedFeedback(detail: ConversationDetailDto): String =
    when {
        isViewerInvited && detail.conversation.partnerInvite == null -> "Joined chat"
        isViewerInvited -> "Invite accepted"
        else -> "Invite approved"
    }

internal val visibleConversationParticipantStatuses = setOf(
    ConversationParticipantStatus.ACTIVE,
    ConversationParticipantStatus.INVITED
)
