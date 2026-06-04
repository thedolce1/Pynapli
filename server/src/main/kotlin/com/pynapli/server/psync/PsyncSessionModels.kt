package com.pynapli.server.psync

import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import java.time.Instant
import java.util.UUID

/**
 * Server-internal domain model mirroring `psync_sessions`. Filters are the Swipe
 * [SwipeDeckRequestDto] persisted as JSONB. PS-1 only takes a session up to 'ready';
 * deckSessionId/startedAt are reserved for later phases and stay null here.
 */
data class PsyncSession(
    val id: UUID,
    val partnerLinkId: UUID,
    val initiatorUserId: UUID,
    val status: String,
    val filters: SwipeDeckRequestDto,
    val deckSize: Int,
    val deckSessionId: UUID?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val expiresAt: Instant,
    val endedAt: Instant?,
    val participants: List<PsyncSessionParticipant>,
)

/**
 * Server-internal domain model mirroring `psync_session_participants`, plus the joined
 * display name used when projecting "my current session".
 */
data class PsyncSessionParticipant(
    val userId: UUID,
    val role: String,
    val consentStatus: String,
    val approved: Boolean,
    val displayName: String? = null,
    // PS-2: the both-tap-Start gate. The deck is generated + the session flips to
    // 'active' only when every participant has started.
    val started: Boolean = false,
)

/**
 * PS-2 shared deck card (mirrors `psync_session_cards`). One row per card; both partners
 * act on the same ordered list. The [snapshot] is the SwipeCardDto captured at generation
 * time (bounded, short-lived deck — staleness is acceptable). The [cardTokenHash] is the
 * hash of the HMAC card token used to validate submitted actions against this deck.
 */
data class PsyncSessionCard(
    val id: UUID,
    val sessionId: UUID,
    val position: Int,
    val targetUserId: UUID,
    val cardTokenHash: String,
    val snapshot: SwipeCardDto,
    val dealtAt: Instant,
)

/**
 * PS-2 private per-partner swipe (mirrors `psync_session_actions`). One decision per card
 * per partner; the actor's own decisions are never exposed to the other partner.
 */
data class PsyncSessionAction(
    val actorUserId: UUID,
    val targetUserId: UUID,
    val action: String,
    val createdAt: Instant,
)
