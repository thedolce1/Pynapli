package com.pynapli.server.tonight

import java.time.Instant
import java.util.UUID

/**
 * Server-internal domain model mirroring `tonight_sessions`. Location is NEVER stored
 * on a session; the feed/map derive zone + distance live (zone-only), exactly like the
 * Grid.
 */
data class TonightSession(
    val id: UUID,
    val createdByUserId: UUID,
    val mode: String,
    val intent: String,
    val hostMode: String,
    val note: String?,
    val photoMediaAssetId: UUID?,
    val mapVisible: Boolean,
    val status: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val expiresAt: Instant,
    val endedAt: Instant?,
    val participants: List<TonightSessionParticipant>,
)

/**
 * Server-internal domain model mirroring `tonight_session_participants`, plus the
 * joined display name used when projecting "my current session".
 */
data class TonightSessionParticipant(
    val userId: UUID,
    val role: String,
    val approvalStatus: String,
    val approvedAt: Instant?,
    val displayName: String? = null,
)
