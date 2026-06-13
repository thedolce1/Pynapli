package com.pynapli.app.conversation

import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.MatchDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ConversationStateHolder(
    private val repository: ConversationRepository,
    private val idempotencyKeyFactory: () -> String = ::newMessageIdempotencyKey,
) {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()
    private var lastTypingConversationId: String? = null
    private var lastTypingActive: Boolean = false
    private var lastTypingSentAtMillis: Long = 0L

    // Idempotency key for the in-flight/failed send. Generated for a fresh draft and reused when the
    // user re-taps Send after a failure (the draft is preserved on failure), so a retry of a send
    // whose response was lost collapses to one server-side message. Cleared on success.
    private var pendingSendKey: String? = null

    suspend fun openDirect(
        card: GridProfileCardUi,
        viewerUserId: String?,
    ) {
        _state.update {
            ConversationUiState(
                selectedCard = card,
                viewerUserId = viewerUserId,
                isOpening = true,
            )
        }

        when (val result = repository.createDirectConversation(card.id)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation(repository)
                _state.update { current ->
                    current.copy(
                        detail = detail,
                        isOpening = false,
                        partnerInviteFeedback = null,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isOpening = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun openSwipeMatch(
        match: MatchDto,
        viewerUserId: String?,
    ) {
        _state.update {
            ConversationUiState(
                titleOverride = match.matchedDisplayName ?: "Match",
                viewerUserId = viewerUserId,
                isOpening = true,
            )
        }

        when (val result = repository.createDirectConversation(match.matchedUserId, origin = "swipe")) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation(repository)
                _state.update { current ->
                    current.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(viewerUserId),
                        isOpening = false,
                        partnerInviteFeedback = null,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isOpening = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun openConversation(
        conversation: ConversationDto,
        viewerUserId: String?,
    ) {
        _state.update {
            ConversationUiState(
                titleOverride = conversation.titleFor(viewerUserId),
                viewerUserId = viewerUserId,
                detail =
                    ConversationDetailDto(
                        conversation = conversation,
                        messages = emptyList(),
                    ),
                isOpening = true,
            )
        }

        when (val result = repository.getConversationDetail(conversation.conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation(repository)
                _state.update { current ->
                    current.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(viewerUserId),
                        isOpening = false,
                        partnerInviteFeedback = null,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isOpening = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun openConversationById(
        conversationId: String,
        viewerUserId: String?,
        title: String = "Conversation",
    ) {
        _state.update {
            ConversationUiState(
                titleOverride = title,
                viewerUserId = viewerUserId,
                isOpening = true,
            )
        }

        when (val result = repository.getConversationDetail(conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation(repository)
                _state.update { current ->
                    current.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(viewerUserId),
                        isOpening = false,
                        partnerInviteFeedback = null,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isOpening = false,
                        errorMessage = result.error.message,
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
                val detail = result.value.withReadConversation(repository)
                _state.update { existing ->
                    val existingDetail = existing.detail ?: return@update existing
                    if (existingDetail.conversation.conversationId != conversationId) {
                        existing
                    } else {
                        existing.copy(
                            detail = detail.withMergedMessagesFrom(existingDetail),
                            titleOverride = detail.conversation.titleFor(existing.viewerUserId),
                            partnerInviteFeedback = if (silent) existing.partnerInviteFeedback else null,
                            errorMessage = null,
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

    suspend fun refreshOpenConversationIfConversationId(conversationId: String) {
        if (state.value.detail
                ?.conversation
                ?.conversationId == conversationId
        ) {
            refreshOpenConversation()
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
                            errorMessage = null,
                        )
                    }
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isLoadingOlder = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    fun updateDraft(value: String) {
        _state.update { current ->
            current.copy(
                draft = value,
                errorMessage = null,
            )
        }
    }

    suspend fun sendTypingSignal(
        isTyping: Boolean,
        force: Boolean = false,
    ) {
        val current = state.value
        val conversationId = current.detail?.conversation?.conversationId ?: return
        if (!current.visible || current.isOpening || current.isViewerInvited) return
        val now = currentTimeMillis()
        val sameState = lastTypingConversationId == conversationId && lastTypingActive == isTyping
        if (!force && sameState && now - lastTypingSentAtMillis < TYPING_SIGNAL_THROTTLE_MS) return

        when (repository.sendTypingIndicator(conversationId, isTyping)) {
            is AppResult.Success -> {
                lastTypingConversationId = conversationId
                lastTypingActive = isTyping
                lastTypingSentAtMillis = now
            }
            is AppResult.Failure -> Unit
        }
    }

    suspend fun sendTypingStopped(conversationId: String?) {
        val threadId = conversationId ?: return
        if (lastTypingConversationId != threadId || !lastTypingActive) return
        when (repository.sendTypingIndicator(threadId, isTyping = false)) {
            is AppResult.Success -> {
                lastTypingConversationId = null
                lastTypingActive = false
                lastTypingSentAtMillis = 0L
            }
            is AppResult.Failure -> Unit
        }
    }

    fun applyTypingStarted(
        conversationId: String,
        actorUserId: String?,
        actorDisplayName: String?,
        expiresAt: String?,
    ) {
        val userId = actorUserId ?: return
        val current = state.value
        if (current.detail?.conversation?.conversationId != conversationId || userId == current.viewerUserId) return
        val expiresAtMillis =
            expiresAt?.let { parseInstantMillis(it) }
                ?: (currentTimeMillis() + TYPING_INDICATOR_TTL_MS)
        val displayName =
            actorDisplayName?.takeIf { it.isNotBlank() }
                ?: current.detail.conversation.participants
                    .firstOrNull { it.userId == userId }
                    ?.displayName
                ?: "Someone"

        _state.update { existing ->
            existing.copy(
                typingParticipants =
                    (
                        existing.typingParticipants.filterNot { it.userId == userId } +
                            TypingParticipantUi(userId, displayName, expiresAtMillis)
                    ).sortedBy { it.displayName },
            )
        }
    }

    fun applyTypingStopped(
        conversationId: String,
        actorUserId: String?,
    ) {
        val userId = actorUserId ?: return
        if (state.value.detail
                ?.conversation
                ?.conversationId != conversationId
        ) {
            return
        }
        _state.update { existing ->
            existing.copy(typingParticipants = existing.typingParticipants.filterNot { it.userId == userId })
        }
    }

    fun clearExpiredTypingIndicators(nowMillis: Long = currentTimeMillis()) {
        _state.update { existing ->
            val active = existing.typingParticipants.filter { it.expiresAtMillis > nowMillis }
            if (active == existing.typingParticipants) existing else existing.copy(typingParticipants = active)
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
                errorMessage = null,
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
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isInvitingPartner = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message,
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
                errorMessage = null,
            )
        }

        when (val result = repository.acceptPartnerInvite(conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation(repository)
                _state.update { existing ->
                    existing.copy(
                        detail = detail,
                        titleOverride = detail.conversation.titleFor(existing.viewerUserId),
                        isRespondingToInvite = false,
                        partnerInviteFeedback = current.partnerInviteAcceptedFeedback(detail),
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isRespondingToInvite = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message,
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
                errorMessage = null,
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
                        errorMessage = result.error.message,
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
                        detail = detailResult.value.withReadConversation(repository),
                        titleOverride = detailResult.value.conversation.titleFor(existing.viewerUserId),
                        isRespondingToInvite = false,
                        partnerInviteFeedback = "Invite declined",
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isRespondingToInvite = false,
                        partnerInviteFeedback = null,
                        errorMessage = detailResult.error.message,
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
                errorMessage = null,
            )
        }

        when (val result = repository.leaveConversation(conversationId)) {
            is AppResult.Success -> dismiss()
            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isLeaving = false,
                        partnerInviteFeedback = null,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun setExpiry(expirySeconds: Int?) {
        val current = state.value
        if (current.isSettingExpiry || current.isOpening) return
        if (current.viewerExpirySeconds == expirySeconds) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        _state.update { it.copy(isSettingExpiry = true, partnerInviteFeedback = null, errorMessage = null) }

        when (val result = repository.setConversationExpiry(conversationId, expirySeconds)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    existing.copy(
                        detail = existing.detail?.copy(conversation = result.value),
                        isSettingExpiry = false,
                        errorMessage = null,
                    )
                }
                // Pull the server-authored system message into the thread.
                refreshOpenConversation()
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isSettingExpiry = false,
                        errorMessage = result.error.message,
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

        // Reuse the key from a prior failed attempt of this draft; otherwise mint a fresh one.
        val idempotencyKey = pendingSendKey ?: idempotencyKeyFactory().also { pendingSendKey = it }

        _state.update { it.copy(isSending = true, partnerInviteFeedback = null, errorMessage = null) }
        when (val result = repository.sendMessage(conversationId, body, idempotencyKey)) {
            is AppResult.Success -> {
                pendingSendKey = null
                sendTypingStopped(conversationId)
                _state.update { existing ->
                    val updatedDetail = existing.detail?.withAppendedMessage(result.value)
                    existing.copy(
                        detail = updatedDetail,
                        draft = "",
                        isSending = false,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                // Keep pendingSendKey so a re-tap of the same draft retries idempotently.
                _state.update { existing ->
                    existing.copy(
                        isSending = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun deleteMessage(messageId: String) {
        val current = state.value
        val detail = current.detail ?: return
        val message = detail.messages.firstOrNull { it.messageId == messageId } ?: return
        if (message.senderUserId != current.viewerUserId || message.deletedAt != null) return
        if (messageId in current.isDeletingMessageIds) return

        _state.update { existing ->
            existing.copy(
                isDeletingMessageIds = existing.isDeletingMessageIds + messageId,
                partnerInviteFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.deleteMessage(messageId)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    existing.copy(
                        detail = existing.detail?.withUpdatedMessage(result.value),
                        isDeletingMessageIds = existing.isDeletingMessageIds - messageId,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isDeletingMessageIds = existing.isDeletingMessageIds - messageId,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    fun dismiss() {
        lastTypingConversationId = null
        lastTypingActive = false
        lastTypingSentAtMillis = 0L
        _state.value = ConversationUiState()
    }
}
