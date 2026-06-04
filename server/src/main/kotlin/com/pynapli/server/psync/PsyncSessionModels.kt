package com.pynapli.server.psync

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
)
