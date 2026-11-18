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

/**
 * Optional Playing Tonight feed filters, matched against the session owner's own profile with the
 * same rules as Grid and Swipe (orientation keys, lifestyle filter tokens, boundary items).
 */
@Serializable
data class TonightFeedFiltersDto(
    val orientations: List<String> = emptyList(),
    val lifestyleTags: List<String> = emptyList(),
    val boundaryItems: List<String> = emptyList(),
)

/**
 * Body of `POST /v1/tonight/feed/query`: the feed with filters. Filters travel in a body, never the
 * query string, so orientation / lifestyle / boundary values stay out of URLs and access logs.
 */
@Serializable
data class TonightFeedQueryRequestDto(
    val radiusMeters: Int = 50_000,
    val intent: String? = null,
    val limit: Int = 100,
    val filters: TonightFeedFiltersDto = TonightFeedFiltersDto(),
)

/**
 * Public Playing Tonight feed: live sessions near the viewer that opted into
 * discovery. One row per session, anchored on the session owner with the linked
 * partner surfaced as a mini-avatar (PLAYING_TONIGHT_SPEC: "Session row must still
 * make individual accounts clear"). Location is zone-only — never precise.
 */
@Serializable
data class TonightFeedResponseDto(
    val items: List<TonightFeedItemDto> = emptyList(),
)

@Serializable
data class TonightFeedItemDto(
    val sessionId: String,
    val mode: String,
    val intent: String,
    val hostMode: String,
    val note: String? = null,
    val photoUrl: String? = null,
    val person: TonightFeedPersonDto,
    val linkedPartner: LinkedPartnerMiniDto? = null,
)

@Serializable
data class TonightFeedPersonDto(
    val userId: String,
    val displayName: String,
    val thumbnailUrl: String? = null,
    val online: Boolean = false,
    val distanceLabel: String? = null,
    val distanceMeters: Int? = null,
    val zoneKey: String? = null,
)
