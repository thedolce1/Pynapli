package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * Playing Tonight session DTOs (PLAYING_TONIGHT_SPEC). A session is a short-lived
 * availability signal — solo (live immediately) or linked-together (both partners
 * must approve). Location is never carried here; the feed/map derive zone + distance
 * server-side, zone-only.
 */
@Serializable
data class ProposeTonightSessionRequestDto(
    val mode: String = "solo", // "solo" | "together"
    val intent: String = "solo", // "solo" | "together" | "social" | "event"
    val hostMode: String = "public_meet_first", // "hosting" | "traveling" | "public_meet_first"
    val note: String? = null, // <= 100 chars
    val durationHours: Int? = null, // defaults to the 2-hour window
    val mapVisible: Boolean = true,
    val photoMediaAssetId: String? = null,
)

@Serializable
data class TonightSessionDto(
    val id: String,
    val mode: String,
    val intent: String,
    val hostMode: String,
    val note: String? = null,
    val photoMediaAssetId: String? = null,
    val mapVisible: Boolean,
    val status: String, // proposed | active | ended | expired | declined
    val createdAt: String,
    val startedAt: String? = null,
    val expiresAt: String,
    val participants: List<TonightSessionParticipantDto> = emptyList(),
)

@Serializable
data class TonightSessionParticipantDto(
    val userId: String,
    val displayName: String? = null,
    val role: String, // owner | partner
    val approvalStatus: String, // approved | pending | declined
)

/**
 * Nullable wrapper for "my current session" — null payload means the caller has no
 * live or pending session.
 */
@Serializable
data class CurrentTonightSessionDto(
    val session: TonightSessionDto? = null,
)
