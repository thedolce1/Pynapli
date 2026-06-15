package com.pynapli.app.conversation

import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto

internal fun ConversationUiState.conversationTitle(): String {
    val otherParticipant =
        detail?.conversation?.participants?.firstOrNull {
            viewerUserId == null || it.userId != viewerUserId
        }
    return otherParticipant?.displayName ?: titleOverride ?: selectedCard?.displayName ?: "Conversation"
}

internal fun ConversationDto.primaryOtherParticipant(viewerUserId: String?): ConversationParticipantDto? =
    participants
        .filter { participant -> participant.status in visibleConversationParticipantStatuses }
        .firstOrNull { participant -> viewerUserId == null || participant.userId != viewerUserId }

internal fun String.conversationOriginLabel(): String =
    when (this) {
        "swipe" -> "MATCH"
        "intro_note" -> "INTRO NOTE"
        else -> "FROM GRID"
    }

internal fun String.conversationUpdatedLabel(): String {
    val time = messageTimeLabel()
    return if (time.isEmpty()) "Thread" else "Updated $time"
}

internal fun String.messageTimeLabel(): String = if (length >= 16 && this[10] == 'T') substring(11, 16) else ""

internal fun String.messageDateKey(): String? = if (length >= 10 && this[4] == '-' && this[7] == '-') substring(0, 10) else null

internal fun String.messageDateLabel(): String {
    val month =
        substringOrNull(5, 7)?.toIntOrNull()?.let { monthIndex ->
            monthNames.getOrNull(monthIndex - 1)
        } ?: return this
    val day = substringOrNull(8, 10)?.trimStart('0')?.ifBlank { "0" } ?: return this
    return "$month $day"
}

private fun String.substringOrNull(
    startIndex: Int,
    endIndex: Int,
): String? = if (length >= endIndex) substring(startIndex, endIndex) else null

private val monthNames =
    listOf(
        "JAN",
        "FEB",
        "MAR",
        "APR",
        "MAY",
        "JUN",
        "JUL",
        "AUG",
        "SEP",
        "OCT",
        "NOV",
        "DEC",
    )

internal fun List<TypingParticipantUi>.typingLabel(): String =
    when (size) {
        1 -> "${first().displayName} is typing"
        2 -> "${this[0].displayName} and ${this[1].displayName} are typing"
        else -> "${first().displayName} and ${size - 1} others are typing"
    }
