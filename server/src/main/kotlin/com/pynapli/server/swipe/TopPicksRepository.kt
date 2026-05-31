package com.pynapli.server.swipe

import java.time.Instant
import java.util.UUID

interface TopPicksRepository {
    /**
     * Returns the active session for this viewer (generated_at <= now < expires_at)
     * along with its ordered cards. Null if no active session exists — the service
     * generates a fresh one in that case.
     */
    suspend fun findCurrentSession(
        userId: UUID,
        now: Instant,
    ): TopPickSessionWithCards?

    /**
     * Persists a freshly-generated session + its cards in a single transaction.
     * Cards are written in `cards` order; the caller is responsible for assigning
     * sequential positions. Returns the persisted records (with their assigned ids
     * if the caller passed null).
     */
    suspend fun createSession(
        sessionId: UUID,
        userId: UUID,
        deckSessionId: UUID,
        generatedAt: Instant,
        expiresAt: Instant,
        cards: List<TopPickCardInsert>,
    ): TopPickSessionWithCards
}

data class TopPickSessionRecord(
    val sessionId: UUID,
    val userId: UUID,
    val deckSessionId: UUID,
    val generatedAt: Instant,
    val expiresAt: Instant,
)

data class TopPickCardRecord(
    val cardId: UUID,
    val sessionId: UUID,
    val targetUserId: UUID,
    val position: Int,
    val cardTokenHash: String,
    val dealtAt: Instant,
)

data class TopPickCardInsert(
    val cardId: UUID,
    val targetUserId: UUID,
    val position: Int,
    val cardTokenHash: String,
    val dealtAt: Instant,
)

data class TopPickSessionWithCards(
    val session: TopPickSessionRecord,
    val cards: List<TopPickCardRecord>,
)
