package com.pynapli.app.conversation

import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
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

    private val stateAccess =
        object : ConversationStateAccess {
            override val current: ConversationUiState
                get() = _state.value

            override fun update(transform: (ConversationUiState) -> ConversationUiState) {
                _state.update(transform)
            }

            override fun set(value: ConversationUiState) {
                _state.value = value
            }
        }

    private val typingCoordinator = ConversationTypingCoordinator(repository, stateAccess)
    private val messageActions =
        ConversationMessageActions(
            repository = repository,
            state = stateAccess,
            idempotencyKeyFactory = idempotencyKeyFactory,
            sendTypingStopped = typingCoordinator::sendTypingStopped,
        )
    private val partnerActions =
        ConversationPartnerActionCoordinator(
            repository = repository,
            state = stateAccess,
            refreshOpenConversation = { refreshOpenConversation() },
            dismissConversation = { dismiss() },
        )

    suspend fun openDirect(
        card: GridProfileCardUi,
        viewerUserId: String?,
    ) {
        stateAccess.set(
            ConversationUiState(
                selectedCard = card,
                viewerUserId = viewerUserId,
                isOpening = true,
            ),
        )
        openWithResult(resultProvider = { repository.createDirectConversation(card.id) })
    }

    /**
     * The Grid profile's "Say something…" send (MESSAGING_SPEC "Starting from a profile"): gets or
     * creates the Grid direct conversation (the same server gate as the Message button, so the
     * recipient's first-message setting still decides) and posts [body] with the caller's
     * [idempotencyKey]. Leaves the open-conversation state alone; the caller opens the returned
     * conversation id afterwards, which refetches it over HTTP.
     */
    suspend fun sendFirstGridMessage(
        targetUserId: String,
        body: String,
        idempotencyKey: String,
    ): AppResult<String> {
        val conversationId =
            when (val opened = repository.createDirectConversation(targetUserId)) {
                is AppResult.Success -> opened.value.conversation.conversationId
                is AppResult.Failure -> return opened
            }
        return when (val sent = repository.sendMessage(conversationId, body, idempotencyKey)) {
            is AppResult.Success -> AppResult.Success(conversationId)
            is AppResult.Failure -> sent
        }
    }

    suspend fun openSwipeMatch(
        match: MatchDto,
        viewerUserId: String?,
    ) {
        stateAccess.set(
            ConversationUiState(
                titleOverride = match.matchedDisplayName ?: "Match",
                viewerUserId = viewerUserId,
                isOpening = true,
            ),
        )
        openWithResult(
            resultProvider = { repository.createDirectConversation(match.matchedUserId, origin = "swipe") },
            titleForDetail = { detail, _ -> detail.conversation.titleFor(viewerUserId) },
        )
    }

    suspend fun openConversation(
        conversation: ConversationDto,
        viewerUserId: String?,
    ) {
        stateAccess.set(
            ConversationUiState(
                titleOverride = conversation.titleFor(viewerUserId),
                viewerUserId = viewerUserId,
                detail =
                    ConversationDetailDto(
                        conversation = conversation,
                        messages = emptyList(),
                    ),
                isOpening = true,
            ),
        )
        openConversationDetail(conversation.conversationId, viewerUserId)
    }

    suspend fun openConversationById(
        conversationId: String,
        viewerUserId: String?,
        title: String = "Conversation",
    ) {
        stateAccess.set(
            ConversationUiState(
                titleOverride = title,
                viewerUserId = viewerUserId,
                isOpening = true,
            ),
        )
        openConversationDetail(conversationId, viewerUserId)
    }

    suspend fun refreshOpenConversation(silent: Boolean = true) {
        val current = state.value
        if (!current.visible || current.isOpening || current.isSending || current.isLoadingOlder) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        when (val result = repository.getConversationDetail(conversationId)) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation(repository)
                stateAccess.update { existing ->
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
                    stateAccess.update { existing -> existing.copy(errorMessage = result.error.message) }
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

        stateAccess.update { it.copy(isLoadingOlder = true, errorMessage = null) }
        when (val result = repository.getConversationDetail(conversationId, before = cursor)) {
            is AppResult.Success -> {
                stateAccess.update { existing ->
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
                stateAccess.update { existing ->
                    existing.copy(
                        isLoadingOlder = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    fun updateDraft(value: String) = messageActions.updateDraft(value)

    suspend fun sendTypingSignal(
        isTyping: Boolean,
        force: Boolean = false,
    ) = typingCoordinator.sendTypingSignal(isTyping, force)

    suspend fun sendTypingStopped(conversationId: String?) = typingCoordinator.sendTypingStopped(conversationId)

    fun applyTypingStarted(
        conversationId: String,
        actorUserId: String?,
        actorDisplayName: String?,
        expiresAt: String?,
    ) = typingCoordinator.applyTypingStarted(conversationId, actorUserId, actorDisplayName, expiresAt)

    fun applyTypingStopped(
        conversationId: String,
        actorUserId: String?,
    ) = typingCoordinator.applyTypingStopped(conversationId, actorUserId)

    fun clearExpiredTypingIndicators(nowMillis: Long = currentTimeMillis()) = typingCoordinator.clearExpiredTypingIndicators(nowMillis)

    suspend fun inviteLinkedPartner() = partnerActions.inviteLinkedPartner()

    suspend fun acceptPartnerInvite() = partnerActions.acceptPartnerInvite()

    suspend fun declinePartnerInvite() = partnerActions.declinePartnerInvite()

    suspend fun leaveConversation() = partnerActions.leaveConversation()

    suspend fun setExpiry(expirySeconds: Int?) = partnerActions.setExpiry(expirySeconds)

    suspend fun sendDraft() = messageActions.sendDraft()

    suspend fun sendPhoto(media: PickedMediaForUpload) = messageActions.sendPhoto(media)

    suspend fun revealMedia(messageId: String) = messageActions.revealMedia(messageId)

    suspend fun deleteMessage(messageId: String) = messageActions.deleteMessage(messageId)

    suspend fun reportMessage(
        messageId: String,
        category: String,
    ) = messageActions.reportMessage(messageId, category)

    fun dismiss() {
        typingCoordinator.reset()
        messageActions.reset()
        stateAccess.set(ConversationUiState())
    }

    private suspend fun openConversationDetail(
        conversationId: String,
        viewerUserId: String?,
    ) {
        openWithResult(
            resultProvider = { repository.getConversationDetail(conversationId) },
            titleForDetail = { detail, _ -> detail.conversation.titleFor(viewerUserId) },
        )
    }

    private suspend fun openWithResult(
        resultProvider: suspend () -> AppResult<ConversationDetailDto>,
        titleForDetail: (ConversationDetailDto, ConversationUiState) -> String? = { _, current -> current.titleOverride },
    ) {
        when (val result = resultProvider()) {
            is AppResult.Success -> {
                val detail = result.value.withReadConversation(repository)
                stateAccess.update { current ->
                    current.copy(
                        detail = detail,
                        titleOverride = titleForDetail(detail, current),
                        isOpening = false,
                        partnerInviteFeedback = null,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                stateAccess.update { current ->
                    current.copy(
                        isOpening = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }
}
