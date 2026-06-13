package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppResult

internal class ConversationTypingCoordinator(
    private val repository: ConversationRepository,
    private val state: ConversationStateAccess,
    private val nowMillis: () -> Long = ::currentTimeMillis,
) {
    private var lastTypingConversationId: String? = null
    private var lastTypingActive: Boolean = false
    private var lastTypingSentAtMillis: Long = 0L

    suspend fun sendTypingSignal(
        isTyping: Boolean,
        force: Boolean = false,
    ) {
        val current = state.current
        val conversationId = current.detail?.conversation?.conversationId ?: return
        if (!current.visible || current.isOpening || current.isViewerInvited) return
        val now = nowMillis()
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
            is AppResult.Success -> reset()
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
        val current = state.current
        if (current.detail?.conversation?.conversationId != conversationId || userId == current.viewerUserId) return
        val expiresAtMillis =
            expiresAt?.let { parseInstantMillis(it) }
                ?: (nowMillis() + TYPING_INDICATOR_TTL_MS)
        val displayName =
            actorDisplayName?.takeIf { it.isNotBlank() }
                ?: current.detail.conversation.participants
                    .firstOrNull { it.userId == userId }
                    ?.displayName
                ?: "Someone"

        state.update { existing ->
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
        if (state.current.detail
                ?.conversation
                ?.conversationId != conversationId
        ) {
            return
        }
        state.update { existing ->
            existing.copy(typingParticipants = existing.typingParticipants.filterNot { it.userId == userId })
        }
    }

    fun clearExpiredTypingIndicators(nowMillis: Long = this.nowMillis()) {
        state.update { existing ->
            val active = existing.typingParticipants.filter { it.expiresAtMillis > nowMillis }
            if (active == existing.typingParticipants) existing else existing.copy(typingParticipants = active)
        }
    }

    fun reset() {
        lastTypingConversationId = null
        lastTypingActive = false
        lastTypingSentAtMillis = 0L
    }
}
