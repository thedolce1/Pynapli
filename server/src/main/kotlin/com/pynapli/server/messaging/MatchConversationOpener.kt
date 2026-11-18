package com.pynapli.server.messaging

import java.util.UUID

/**
 * Opens the direct conversation for a new match, so it shows up in both people's Messages
 * (the new-matches rail lists swipe-origin conversations with no messages yet). Without it a match
 * existed only as a row in `matches` and an Activity item, and appeared in Messages only after one
 * of them tapped "Message". Idempotent. May throw: it runs after the match commits, through the
 * match side-effect outbox, which retries a failure (it never undoes the match).
 */
fun interface MatchConversationOpener {
    suspend fun openForMatch(
        userA: UUID,
        userB: UUID,
    )
}
