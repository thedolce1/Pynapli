package com.pynapli.app.conversation

import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH

internal class ConversationMessageActions(
    private val repository: ConversationRepository,
    private val state: ConversationStateAccess,
    private val idempotencyKeyFactory: () -> String,
    private val sendTypingStopped: suspend (String?) -> Unit,
) {
    // Reused after a failed send so a retry of the same draft collapses to one server-side message.
    private var pendingSendKey: String? = null

    fun updateDraft(value: String) {
        state.update { current ->
            current.copy(
                draft = value,
                errorMessage = null,
            )
        }
    }

    suspend fun sendDraft() {
        val current = state.current
        if (current.isSending || current.isViewerInvited) return
        val conversationId = current.detail?.conversation?.conversationId ?: return
        val body = current.draft.trim()
        when {
            body.isEmpty() -> {
                state.update { existing -> existing.copy(errorMessage = "Enter a message before sending.") }
                return
            }

            current.draftOverLimit -> {
                state.update { existing ->
                    existing.copy(errorMessage = "Message is too long. Keep it under $MESSAGE_BODY_MAX_LENGTH characters.")
                }
                return
            }
        }

        val idempotencyKey = pendingSendKey ?: idempotencyKeyFactory().also { pendingSendKey = it }

        state.update { it.copy(isSending = true, partnerInviteFeedback = null, errorMessage = null) }
        when (val result = repository.sendMessage(conversationId, body, idempotencyKey)) {
            is AppResult.Success -> {
                pendingSendKey = null
                sendTypingStopped(conversationId)
                state.update { existing ->
                    existing.copy(
                        detail = existing.detail?.withAppendedMessage(result.value),
                        draft = "",
                        isSending = false,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(
                        isSending = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun sendPhoto(media: PickedMediaForUpload) {
        val current = state.current
        if (current.isSending || current.isSendingPhoto || current.isViewerInvited) return
        val conversationId = current.detail?.conversation?.conversationId ?: return

        state.update { it.copy(isSendingPhoto = true, partnerInviteFeedback = null, errorMessage = null) }
        when (
            val result =
                repository.sendPhoto(
                    conversationId = conversationId,
                    contentType = media.contentType,
                    byteSize = media.byteSize,
                    pixelWidth = media.pixelWidth,
                    pixelHeight = media.pixelHeight,
                    bytes = media.bytes,
                )
        ) {
            is AppResult.Success -> {
                sendTypingStopped(conversationId)
                state.update { existing ->
                    existing.copy(
                        detail = existing.detail?.withAppendedMessage(result.value),
                        isSendingPhoto = false,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(isSendingPhoto = false, errorMessage = result.error.message)
                }
            }
        }
    }

    suspend fun revealMedia(messageId: String) {
        val current = state.current
        if (messageId in current.revealingMessageIds) return
        state.update { existing ->
            existing.copy(revealingMessageIds = existing.revealingMessageIds + messageId, errorMessage = null)
        }
        when (val result = repository.revealMedia(messageId)) {
            is AppResult.Success -> {
                state.update { existing ->
                    existing.copy(
                        detail = existing.detail?.withUpdatedMessage(result.value),
                        revealingMessageIds = existing.revealingMessageIds - messageId,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(
                        revealingMessageIds = existing.revealingMessageIds - messageId,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun deleteMessage(messageId: String) {
        val current = state.current
        val detail = current.detail ?: return
        val message = detail.messages.firstOrNull { it.messageId == messageId } ?: return
        if (message.senderUserId != current.viewerUserId || message.deletedAt != null) return
        if (messageId in current.isDeletingMessageIds) return

        state.update { existing ->
            existing.copy(
                isDeletingMessageIds = existing.isDeletingMessageIds + messageId,
                partnerInviteFeedback = null,
                errorMessage = null,
            )
        }

        when (val result = repository.deleteMessage(messageId)) {
            is AppResult.Success -> {
                state.update { existing ->
                    existing.copy(
                        detail = existing.detail?.withUpdatedMessage(result.value),
                        isDeletingMessageIds = existing.isDeletingMessageIds - messageId,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update { existing ->
                    existing.copy(
                        isDeletingMessageIds = existing.isDeletingMessageIds - messageId,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    fun reset() {
        pendingSendKey = null
    }
}
