package com.pynapli.app.profile

import androidx.compose.runtime.Immutable
import com.pynapli.app.conversation.newMessageIdempotencyKey
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The "Say something…" field on a Grid profile, bound to one person at a time. */
@Immutable
data class ProfileComposerUiState(
    val targetUserId: String? = null,
    val draft: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null,
) {
    val canSend: Boolean
        get() = targetUserId != null && !isSending && draft.isNotBlank() && draft.trim().length <= MESSAGE_BODY_MAX_LENGTH
}

/**
 * The first message from a Grid profile (MESSAGING_SPEC "Starting from a profile"). [send] opens or
 * reuses the direct conversation and posts the body through the normal message path, returning the
 * conversation id. The idempotency key is made once per (person, body) and reused when the viewer
 * retries after a failure, so a send whose answer was lost never lands twice; nothing retries on
 * its own. Message bodies are never logged.
 *
 * The draft belongs to the person it was typed for: binding another person (the pager moved on)
 * clears it, and a send that finishes after the pager moved neither clears the new draft nor asks
 * the host to open a conversation with someone else.
 */
class ProfileComposerStateHolder(
    private val send: suspend (targetUserId: String, body: String, idempotencyKey: String) -> AppResult<String>,
    private val idempotencyKeyFactory: () -> String = ::newMessageIdempotencyKey,
) {
    private val _state = MutableStateFlow(ProfileComposerUiState())
    val state: StateFlow<ProfileComposerUiState> = _state.asStateFlow()

    private var pendingKey: String? = null
    private var pendingFor: Pair<String, String>? = null

    /** Points the composer at [targetUserId] (null when no profile is loaded). */
    fun bind(targetUserId: String?) {
        if (_state.value.targetUserId == targetUserId) return
        _state.value = ProfileComposerUiState(targetUserId = targetUserId)
    }

    fun updateDraft(value: String) {
        _state.update { it.copy(draft = value, errorMessage = null) }
    }

    /**
     * Sends the draft. Returns the conversation id when the message went to the person still bound,
     * so the host can open that conversation; null otherwise (nothing to send, failure, or the pager
     * moved on while it was in flight).
     */
    suspend fun sendDraft(): String? {
        val current = _state.value
        val target = current.targetUserId ?: return null
        if (current.isSending) return null
        val body = current.draft.trim()
        if (body.isEmpty()) return null
        if (body.length > MESSAGE_BODY_MAX_LENGTH) {
            _state.update { it.copy(errorMessage = "Message is too long. Keep it under $MESSAGE_BODY_MAX_LENGTH characters.") }
            return null
        }
        val key =
            pendingKey?.takeIf { pendingFor == (target to body) }
                ?: idempotencyKeyFactory().also {
                    pendingKey = it
                    pendingFor = target to body
                }

        _state.update { it.copy(isSending = true, errorMessage = null) }
        val result = send(target, body, key)
        val stillBound = _state.value.targetUserId == target
        return when (result) {
            is AppResult.Success -> {
                pendingKey = null
                pendingFor = null
                if (stillBound) {
                    _state.update { it.copy(draft = "", isSending = false, errorMessage = null) }
                    result.value
                } else {
                    null
                }
            }

            is AppResult.Failure -> {
                if (stillBound) {
                    _state.update { it.copy(isSending = false, errorMessage = result.error.message) }
                }
                null
            }
        }
    }
}
