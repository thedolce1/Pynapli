package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.ConversationParticipantStatus

internal object MessagingServiceDefaults {
    const val MessagePageLimit = 50
    const val MaxMessagePageLimit = 100
    const val ThreadLimit = 50
    const val TypingIndicatorTtlSeconds = 6L

    val origins = setOf("grid", "swipe", "intro_note")
}

internal object MessagingParticipantStatuses {
    val active = setOf(ConversationParticipantStatus.ACTIVE)
    val visible =
        setOf(
            ConversationParticipantStatus.ACTIVE,
            ConversationParticipantStatus.INVITED,
        )
}
