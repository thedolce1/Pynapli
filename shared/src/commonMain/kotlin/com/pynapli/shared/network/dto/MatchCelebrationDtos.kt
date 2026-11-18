package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * A match this person has not seen the full-screen "It's a match" moment for yet
 * (`GET /v1/matches/celebrations`). Server-owned: it is shown once per person per match across
 * devices, and marked seen with `POST /v1/matches/celebrations/seen`.
 */
@Serializable
data class MatchCelebrationDto(
    val matchId: String,
    val matchedUserId: String,
    val matchedDisplayName: String,
    val matchedPhotoUrl: String? = null,
    /** The viewer's own first profile photo, for the two-photo moment. */
    val viewerPhotoUrl: String? = null,
    /**
     * A Psync "together" match: the matched person's linked partner, who matched the viewer in the
     * same session ("Maya & Alex"). Both matches are one moment; mark both ids seen.
     */
    val pairMatchId: String? = null,
    val pairUserId: String? = null,
    val pairDisplayName: String? = null,
    val pairPhotoUrl: String? = null,
    val createdAt: String,
)

@Serializable
data class MatchCelebrationsResponseDto(
    val items: List<MatchCelebrationDto> = emptyList(),
)

/** Idempotent: ids already seen, or of matches the caller is not part of, are ignored. */
@Serializable
data class MarkMatchesCelebratedRequestDto(
    val matchIds: List<String>,
)

@Serializable
data class MarkMatchesCelebratedResponseDto(
    val marked: Int,
)
